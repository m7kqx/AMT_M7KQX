package com.example.androidmorsetrainer.audio

import android.util.Log
import com.example.androidmorsetrainer.morse.DspCharacterStream
import com.example.androidmorsetrainer.morse.DynamicTimingEngine
import com.example.androidmorsetrainer.morse.MorseConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real-time Goertzel DSP stream implementing [DspCharacterStream].
 * Used for live on-the-fly Morse decoding (e.g. in live Decode mode).
 * Continuously parses dits, dahs, character spaces, and word boundaries in real-time.
 */
open class GoertzelDspStream(
    val dspManager: MorseDSPManager = MorseDSPManager(),
    val timingEngine: DynamicTimingEngine = DynamicTimingEngine(initialWpm = 20),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : DspCharacterStream {

    companion object {
        private const val TAG = "GoertzelDspStream"
        const val TELEMETRY_THROTTLE_MS = 60L // ~16.6 FPS
    }

    private val _characterFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val characterFlow: Flow<String> = _characterFlow.asSharedFlow()

    private val _toneActivityFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val toneActivityFlow: Flow<Unit> = _toneActivityFlow.asSharedFlow()

    private val _isListening = MutableStateFlow(true)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    override val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    @Volatile
    var isListeningPaused: Boolean = false
        private set

    private val _telemetry = MutableStateFlow(
        DspTelemetry(
            noiseFloorThreshold = dspManager.squelchThreshold.toFloat(),
            currentDitAverageMs = timingEngine.rollingDitMs.toFloat()
        )
    )
    override val telemetry: StateFlow<DspTelemetry> = _telemetry.asStateFlow()

    private val currentSymbol = StringBuilder()
    private val currentWord = StringBuilder()

    @Volatile
    var lastToneDurationMs: Long = 0L
        private set

    @Volatile
    var lastSilenceDurationMs: Long = 0L
        private set

    private var lastToneEndTimeMs: Long = 0L
    private var listeningJob: Job? = null
    private var telemetryJob: Job? = null
    private var silenceWatchdogJob: Job? = null

    init {
        start()
    }

    /**
     * Pauses DSP decoding and drops incoming microphone audio for half-duplex operation.
     */
    override fun pauseListening() {
        isListeningPaused = true
        _isListening.value = false
        dspManager.isDecodingActive = false
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        lastToneEndTimeMs = 0L
        synchronized(currentSymbol) {
            currentSymbol.clear()
        }
        currentWord.clear()
        updateTelemetry()
    }

    /**
     * Resumes DSP decoding of microphone audio after simulated bot transmission finishes.
     */
    override fun resumeListening() {
        isListeningPaused = false
        _isListening.value = true
        dspManager.isDecodingActive = true
        lastToneEndTimeMs = 0L
        dspManager.startListening()
    }

    /**
     * Starts microphone audio capture, VAD, and throttled telemetry updates.
     */
    @Synchronized
    override fun start() {
        if (listeningJob?.isActive == true) return

        dspManager.startListening()

        // 1. Listen for tone events from Goertzel DSP in real-time
        listeningJob = scope.launch(defaultDispatcher) {
            dspManager.toneEvents.collect { event ->
                if (isListeningPaused) return@collect
                if (event.isTonePresent) {
                    _toneActivityFlow.tryEmit(Unit)
                    onKeyToneStarted(event.timestampMs)
                } else {
                    onKeyToneEnded(event.durationMs, event.timestampMs)
                }
            }
        }

        // 2. Throttled telemetry updates for live magnitude meter and threshold adjustment (<= 16.6 FPS)
        telemetryJob?.cancel()
        telemetryJob = scope.launch(defaultDispatcher) {
            var lastEmittedTime = 0L
            dspManager.dspState.collect { dspState ->
                val now = System.currentTimeMillis()
                if (now - lastEmittedTime >= TELEMETRY_THROTTLE_MS || !dspState.isListening) {
                    lastEmittedTime = now
                    updateTelemetry(dspState.currentMagnitude.toFloat())
                }
            }
        }
    }

    override suspend fun onSilenceTimeout(): String {
        return ""
    }

    /**
     * Updates [telemetry] StateFlow with current DSP and timing measurements.
     */
    private fun updateTelemetry(magnitude: Float = dspManager.dspState.value.currentMagnitude.toFloat()) {
        val elementBuffer = synchronized(currentSymbol) { currentSymbol.toString() }
        _telemetry.update {
            it.copy(
                currentMagnitude = magnitude,
                lastToneDurationMs = lastToneDurationMs,
                lastSilenceDurationMs = lastSilenceDurationMs,
                currentDitAverageMs = timingEngine.rollingDitMs.toFloat(),
                noiseFloorThreshold = dspManager.squelchThreshold.toFloat(),
                currentElementBuffer = elementBuffer
            )
        }
    }

    /**
     * Updates the Goertzel squelch threshold in real-time.
     */
    override fun updateNoiseFloor(threshold: Float) {
        val clamped = threshold.coerceIn(
            MorseDSPManager.MIN_SQUELCH_MAGNITUDE.toFloat(),
            MorseDSPManager.MAX_SQUELCH_MAGNITUDE.toFloat()
        )
        dspManager.setSquelchThreshold(clamped.toDouble())
        updateTelemetry()
    }

    /**
     * Key-down handler for real-time element tracking.
     */
    open fun onKeyToneStarted(timestampMs: Long = System.currentTimeMillis()) {
        silenceWatchdogJob?.cancel()

        if (lastToneEndTimeMs > 0L) {
            val silenceDuration = (timestampMs - lastToneEndTimeMs).coerceAtLeast(0L)
            lastSilenceDurationMs = silenceDuration
            updateTelemetry()
            val thresholds = timingEngine.getThresholds()

            if (thresholds.isWordSpace(silenceDuration)) {
                commitCharacter()
                commitWord()
            } else if (thresholds.isCharacterSpace(silenceDuration)) {
                commitCharacter()
            }
        }
    }

    /**
     * Key-up handler for real-time element tracking.
     */
    open fun onKeyToneEnded(durationMs: Long, timestampMs: Long = System.currentTimeMillis()) {
        if (durationMs < DynamicTimingEngine.MIN_VALID_PULSE_MS) return

        lastToneDurationMs = durationMs
        lastToneEndTimeMs = timestampMs

        timingEngine.recordElement(durationMs)

        val thresholds = timingEngine.getThresholds()
        val symbol = if (thresholds.isDit(durationMs)) '.' else '-'
        synchronized(currentSymbol) {
            currentSymbol.append(symbol)
        }
        updateTelemetry()

        startSilenceWatchdog()
    }

    private fun startSilenceWatchdog() {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = scope.launch(defaultDispatcher) {
            val thresholds = timingEngine.getThresholds()

            delay(thresholds.charSpaceThresholdMs.toLong())
            commitCharacter()

            val remainingWordDelay = (thresholds.wordSpaceThresholdMs - thresholds.charSpaceThresholdMs).toLong()
            if (remainingWordDelay > 0) {
                delay(remainingWordDelay)
                commitWord()
            }
        }
    }

    private fun commitCharacter() {
        val pattern = synchronized(currentSymbol) {
            if (currentSymbol.isEmpty()) return
            val pat = currentSymbol.toString()
            currentSymbol.clear()
            pat
        }
        updateTelemetry()

        val char = MorseConstants.REVERSE_MORSE_MAP[pattern] ?: "?"
        currentWord.append(char)
        Log.d(TAG, "Decoded character '$char' from pattern '$pattern'")
    }

    private fun commitWord() {
        commitCharacter()
        if (currentWord.isEmpty()) return
        val word = currentWord.toString().trim()
        currentWord.clear()

        if (word.isNotEmpty()) {
            Log.d(TAG, "Decoded word committed: '$word'")
            _characterFlow.tryEmit(word)
        }
    }

    /**
     * Stops DSP tone processing and releases AudioRecord hardware resources.
     */
    @Synchronized
    override fun stop() {
        telemetryJob?.cancel()
        telemetryJob = null
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        listeningJob?.cancel()
        listeningJob = null
        dspManager.stopListening()
        synchronized(currentSymbol) {
            currentSymbol.clear()
        }
        currentWord.clear()
        updateTelemetry()
        Log.d(TAG, "GoertzelDspStream stopped and microphone capture released")
    }

    /**
     * Manually emits a decoded text element (for testing or debugging).
     */
    fun emitManual(text: String) {
        _characterFlow.tryEmit(text)
    }

    fun getCurrentSymbol(): String = synchronized(currentSymbol) { currentSymbol.toString() }
    fun getCurrentWord(): String = currentWord.toString()
}
