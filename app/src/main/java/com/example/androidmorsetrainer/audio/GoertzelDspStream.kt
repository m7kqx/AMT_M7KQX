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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hardware integration implementation of [DspCharacterStream].
 *
 * Wraps [MorseDSPManager] (handling microphone capture via AudioRecord and real-time Goertzel FFT)
 * and [DynamicTimingEngine] to decode Morse elements and spaces into text tokens and words.
 *
 * Emits real-time [DspTelemetry] throttled to 10-20 FPS to prevent Compose recomposition thrashing.
 *
 * Concurrency:
 * - Audio capture runs strictly on [Dispatchers.IO].
 * - Goertzel signal processing, telemetry polling, and element classification run strictly on [Dispatchers.Default].
 */
open class GoertzelDspStream(
    val dspManager: MorseDSPManager = MorseDSPManager(),
    val timingEngine: DynamicTimingEngine = DynamicTimingEngine(initialWpm = 20),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : DspCharacterStream {

    companion object {
        private const val TAG = "GoertzelDspStream"
        const val TELEMETRY_THROTTLE_MS = 60L // ~16.6 FPS (safely within 10-20 FPS limit)
    }

    private val _characterFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val characterFlow: Flow<String> = _characterFlow.asSharedFlow()

    private val _toneActivityFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val toneActivityFlow: Flow<Unit> = _toneActivityFlow.asSharedFlow()

    private val _isListening = MutableStateFlow(true)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    @Volatile
    var isListeningPaused: Boolean = false
        private set

    private val _telemetry = MutableStateFlow(
        DspTelemetry(
            noiseFloorThreshold = dspManager.squelchThreshold.toFloat(),
            currentDitAverageMs = timingEngine.rollingDitMs.toFloat()
        )
    )
    val telemetry: StateFlow<DspTelemetry> = _telemetry.asStateFlow()

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
        synchronized(currentSymbol) {
            currentSymbol.clear()
        }
        currentWord.clear()
        updateTelemetry()
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        lastToneEndTimeMs = 0L
    }

    /**
     * Resumes DSP decoding of microphone audio after simulated bot transmission finishes.
     */
    override fun resumeListening() {
        isListeningPaused = false
        _isListening.value = true
        dspManager.isDecodingActive = true
        lastToneEndTimeMs = 0L
    }

    /**
     * Starts microphone audio capture, Goertzel tone processing, and throttled telemetry updates.
     */
    @Synchronized
    open fun start() {
        if (listeningJob?.isActive == true) return

        dspManager.startListening()

        // 1. Listen for real-time tone events from Goertzel DSP
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

        // 2. Throttled telemetry updates based on incoming DSP state (throttled to <= 16.6 FPS)
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
    open fun updateNoiseFloor(threshold: Float) {
        val clamped = threshold.coerceIn(MorseDSPManager.MIN_SQUELCH_MAGNITUDE.toFloat(), MorseDSPManager.MAX_SQUELCH_MAGNITUDE.toFloat())
        dspManager.setSquelchThreshold(clamped.toDouble())
        updateTelemetry()
    }

    /**
     * Key-down handler: Triggered when a tone starts.
     * Evaluates preceding silence duration using [DynamicTimingEngine.getThresholds]
     * to detect character or word boundaries.
     */
    open fun onKeyToneStarted(timestampMs: Long = System.currentTimeMillis()) {
        silenceWatchdogJob?.cancel()

        if (lastToneEndTimeMs > 0L) {
            val silenceDuration = (timestampMs - lastToneEndTimeMs).coerceAtLeast(0L)
            lastSilenceDurationMs = silenceDuration
            updateTelemetry()
            val thresholds = timingEngine.getThresholds()

            if (thresholds.isWordSpace(silenceDuration)) {
                // Word space crossed
                commitCharacter()
                commitWord()
            } else if (thresholds.isCharacterSpace(silenceDuration)) {
                // Character space crossed
                commitCharacter()
            }
        }
    }

    /**
     * Key-up handler: Triggered when a tone ends.
     * Passes tone duration to [DynamicTimingEngine.recordElement], classifies dit/dah,
     * and sets up a watchdog timer for trailing silence.
     */
    open fun onKeyToneEnded(durationMs: Long, timestampMs: Long = System.currentTimeMillis()) {
        if (durationMs < DynamicTimingEngine.MIN_VALID_PULSE_MS) return

        lastToneDurationMs = durationMs
        lastToneEndTimeMs = timestampMs

        // 1. Pass duration to DynamicTimingEngine (outlier-filtered rolling dit update)
        timingEngine.recordElement(durationMs)

        // 2. Classify tone as dit or dah using thresholds
        val thresholds = timingEngine.getThresholds()
        val symbol = if (thresholds.isDit(durationMs)) '.' else '-'
        synchronized(currentSymbol) {
            currentSymbol.append(symbol)
        }
        updateTelemetry()

        // 3. Start silence watchdog for trailing character or word completion
        startSilenceWatchdog()
    }

    private fun startSilenceWatchdog() {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = scope.launch(defaultDispatcher) {
            val thresholds = timingEngine.getThresholds()

            // Wait until character boundary threshold
            delay(thresholds.charSpaceThresholdMs.toLong())
            commitCharacter()

            // Wait for word boundary threshold
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
    open fun stop() {
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
        lastToneEndTimeMs = 0L
        updateTelemetry()
        Log.d(TAG, "GoertzelDspStream stopped and microphone capture released")
    }

    /**
     * Allows manual injection of Morse tokens into the stream for testing or simulation.
     */
    open fun emitManual(input: String) {
        if (isListeningPaused) return
        val trimmed = input.trim()
        if (trimmed.isNotEmpty()) {
            _toneActivityFlow.tryEmit(Unit)
            _characterFlow.tryEmit(trimmed)
        }
    }

    /**
     * Manually updates telemetry values (convenient for testing).
     */
    fun setTelemetryForTest(telemetryData: DspTelemetry) {
        _telemetry.value = telemetryData
    }

    fun getCurrentSymbol(): String = synchronized(currentSymbol) { currentSymbol.toString() }
    fun getCurrentWord(): String = currentWord.toString()
}
