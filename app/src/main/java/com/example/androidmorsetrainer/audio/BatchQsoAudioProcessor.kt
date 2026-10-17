package com.example.androidmorsetrainer.audio

import android.util.Log
import com.example.androidmorsetrainer.morse.DspCharacterStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dedicated batch audio processor for the offline Morse QSO Simulator.
 * Completely decouples QSO batch processing from the real-time live decoding streams.
 *
 * Strict 2-Phase Operation:
 * 1. Record Phase:
 *    - Captures raw PCM audio bytes continuously into a [ByteArrayOutputStream].
 *    - Monitors audio magnitude strictly for Voice Activity Detection (VAD) to emit
 *      [toneActivityFlow] and reset the 5-second silence timer.
 *    - DO NOT decode dits, dahs, or characters on the fly during this phase.
 *
 * 2. Process Phase:
 *    - Triggered on 5-second silence timeout ([onSilenceTimeout]).
 *    - Stops recording and executes full Goertzel/FFT DSP math over the completed PCM byte buffer
 *      as fast as CPU allows on [Dispatchers.Default].
 *    - Contextually calculates definitive noise floor and locked WPM average across the entire recording.
 *    - Returns the entire decoded message as a single String and emits it to [characterFlow].
 */
open class BatchQsoAudioProcessor(
    val dspManager: MorseDSPManager = MorseDSPManager(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : DspCharacterStream {

    companion object {
        private const val TAG = "BatchQsoAudioProcessor"
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

    private val _statusText = MutableStateFlow("Listening to your transmission...")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    @Volatile
    var isListeningPaused: Boolean = false
        private set

    private val pcmOutputStream = ByteArrayOutputStream()

    private val _telemetry = MutableStateFlow(
        DspTelemetry(
            noiseFloorThreshold = dspManager.squelchThreshold.toFloat()
        )
    )
    override val telemetry: StateFlow<DspTelemetry> = _telemetry.asStateFlow()

    private var rawAudioJob: Job? = null
    private var telemetryJob: Job? = null

    init {
        start()
    }

    /**
     * Pauses microphone audio capture for strict half-duplex operation during simulated bot TX.
     */
    override fun pauseListening() {
        isListeningPaused = true
        _isListening.value = false
        dspManager.isDecodingActive = false
        synchronized(pcmOutputStream) {
            pcmOutputStream.reset()
        }
        _statusText.value = "Microphone Muted (Bot Transmitting)"
        updateTelemetry()
    }

    /**
     * Resumes microphone audio capture after bot transmission completes.
     */
    override fun resumeListening() {
        isListeningPaused = false
        _isListening.value = true
        dspManager.isDecodingActive = true
        synchronized(pcmOutputStream) {
            pcmOutputStream.reset()
        }
        _statusText.value = "Listening to your transmission..."
        dspManager.startListening()
        updateTelemetry()
    }

    /**
     * Starts audio capture, continuous PCM recording, VAD monitoring, and throttled telemetry updates.
     */
    @Synchronized
    override fun start() {
        if (rawAudioJob?.isActive == true) return

        dspManager.startListening()

        // 1. Record Phase: Streams raw PCM audio continuously into ByteArrayOutputStream
        rawAudioJob?.cancel()
        rawAudioJob = scope.launch(defaultDispatcher) {
            val byteBuf = ByteBuffer.allocate(dspManager.blockSize * 2).order(ByteOrder.LITTLE_ENDIAN)
            dspManager.rawAudioFlow.collect { buffer ->
                if (isListeningPaused || _isProcessing.value) return@collect
                byteBuf.clear()
                byteBuf.asShortBuffer().put(buffer)
                synchronized(pcmOutputStream) {
                    pcmOutputStream.write(byteBuf.array(), 0, buffer.size * 2)
                }
            }
        }

        // 2. VAD & Telemetry: ONLY monitors audio magnitude for VAD tone activity (resets 5s timer)
        telemetryJob?.cancel()
        telemetryJob = scope.launch(defaultDispatcher) {
            var lastEmittedTime = 0L
            dspManager.dspState.collect { dspState ->
                if (isListeningPaused || _isProcessing.value) return@collect
                val now = System.currentTimeMillis()

                if (dspState.isTonePresent) {
                    // Voice/Key activity detected -> re-arm 5-second silence watchdog
                    _toneActivityFlow.tryEmit(Unit)
                }

                if (now - lastEmittedTime >= TELEMETRY_THROTTLE_MS || !dspState.isListening) {
                    lastEmittedTime = now
                    _telemetry.update {
                        it.copy(
                            currentMagnitude = dspState.currentMagnitude.toFloat(),
                            noiseFloorThreshold = dspManager.squelchThreshold.toFloat()
                        )
                    }
                }
            }
        }
    }

    /**
     * Process Phase: Triggered on 5-second silence timeout.
     * Takes the completely recorded PCM byte buffer and runs batch Goertzel DSP analysis
     * as fast as CPU allows on [Dispatchers.Default] without blocking the Main UI thread.
     * Returns the entire decoded message as a single String and emits it to [characterFlow].
     */
    override suspend fun onSilenceTimeout(): String = withContext(defaultDispatcher) {
        val audioBytes = synchronized(pcmOutputStream) {
            val bytes = pcmOutputStream.toByteArray()
            pcmOutputStream.reset()
            bytes
        }

        if (audioBytes.size < dspManager.blockSize * 2) {
            return@withContext ""
        }

        _isProcessing.value = true
        _statusText.value = "Processing audio..."
        try {
            // Stop live AudioRecord capture while performing batch decoding
            dspManager.stopListening()

            val processor = BatchAudioProcessor(
                sampleRate = dspManager.sampleRate,
                targetFrequencyHz = dspManager.targetFrequencyHz,
                blockSize = dspManager.blockSize,
                manualNoiseFloorThreshold = dspManager.squelchThreshold.toFloat()
            )

            val decodedText = processor.process(audioBytes)
            if (decodedText.isNotBlank()) {
                Log.d(TAG, "Batch decoding finished: '$decodedText'")
                _characterFlow.tryEmit(decodedText)
            }
            decodedText
        } finally {
            _isProcessing.value = false
            _statusText.value = "Listening to your transmission..."
            if (!isListeningPaused) {
                dspManager.startListening()
            }
        }
    }

    /**
     * Adjusts Goertzel squelch threshold in real-time.
     */
    override fun updateNoiseFloor(threshold: Float) {
        val clamped = threshold.coerceIn(
            MorseDSPManager.MIN_SQUELCH_MAGNITUDE.toFloat(),
            MorseDSPManager.MAX_SQUELCH_MAGNITUDE.toFloat()
        )
        dspManager.setSquelchThreshold(clamped.toDouble())
        updateTelemetry()
    }

    private fun updateTelemetry() {
        _telemetry.update {
            it.copy(
                currentMagnitude = dspManager.dspState.value.currentMagnitude.toFloat(),
                noiseFloorThreshold = dspManager.squelchThreshold.toFloat()
            )
        }
    }

    /**
     * Injects raw PCM audio bytes for unit tests.
     */
    fun recordAudioForTest(bytes: ByteArray) {
        synchronized(pcmOutputStream) {
            pcmOutputStream.write(bytes)
        }
    }

    /**
     * Stops audio capture and cleans up all background jobs.
     */
    @Synchronized
    override fun stop() {
        rawAudioJob?.cancel()
        rawAudioJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        dspManager.stopListening()
        synchronized(pcmOutputStream) {
            pcmOutputStream.reset()
        }
    }
}
