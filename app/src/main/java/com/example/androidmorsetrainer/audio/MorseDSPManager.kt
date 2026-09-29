package com.example.androidmorsetrainer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Real-time Audio DSP pipeline using AudioRecord and the Goertzel algorithm.
 * Concurrency:
 *  - Audio capture runs strictly on Dispatchers.IO.
 *  - Goertzel signal processing runs strictly on Dispatchers.Default.
 */
open class MorseDSPManager(
    private val context: Context? = null,
    val sampleRate: Int = 44100,
    var targetFrequencyHz: Double = 700.0,
    val blockSize: Int = 512,
    squelchThreshold: Double = DEFAULT_THRESHOLD,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    @Volatile
    var squelchThreshold: Double = squelchThreshold
        private set

    companion object {
        private const val TAG = "MorseDSP"
        const val DEFAULT_NOISE_FLOOR = 0.02
        const val DEFAULT_THRESHOLD = 0.05
        const val MIN_SQUELCH_MAGNITUDE = 0.005
        const val MAX_SQUELCH_MAGNITUDE = 0.500
        private const val MIN_SPECTRAL_PURITY = 0.20 // 20% spectral concentration around target tone

        /**
         * Maps a normalized UI slider value (0.0 to 1.0) to underlying Goertzel magnitude scale.
         */
        fun squelchLevelToMagnitude(level: Float): Double {
            val clamped = level.coerceIn(0f, 1f)
            return MIN_SQUELCH_MAGNITUDE + clamped * (MAX_SQUELCH_MAGNITUDE - MIN_SQUELCH_MAGNITUDE)
        }

        /**
         * Maps an underlying Goertzel magnitude scale to normalized UI slider value (0.0 to 1.0).
         */
        fun magnitudeToSquelchLevel(magnitude: Double): Float {
            val clamped = magnitude.coerceIn(MIN_SQUELCH_MAGNITUDE, MAX_SQUELCH_MAGNITUDE)
            return ((clamped - MIN_SQUELCH_MAGNITUDE) / (MAX_SQUELCH_MAGNITUDE - MIN_SQUELCH_MAGNITUDE)).toFloat()
        }
    }

    private val goertzelDetector = GoertzelDetector(
        sampleRate = sampleRate,
        targetFrequencyHz = targetFrequencyHz,
        blockSize = blockSize,
        squelchThreshold = squelchThreshold
    )

    data class DSPState(
        val isListening: Boolean = false,
        val isTonePresent: Boolean = false,
        val isCalibrating: Boolean = false,
        val currentMagnitude: Double = 0.0,
        val spectralPurity: Double = 0.0,
        val noiseFloor: Double = DEFAULT_NOISE_FLOOR,
        val detectionThreshold: Double = DEFAULT_THRESHOLD,
        val lastToneDurationMs: Long = 0,
        val lastSilenceDurationMs: Long = 0,
        val rawRms: Double = 0.0
    )

    data class ToneEvent(
        val isTonePresent: Boolean,
        val durationMs: Long,
        val magnitude: Double,
        val timestampMs: Long
    )

    private val _dspState = MutableStateFlow(DSPState())
    open val dspState: StateFlow<DSPState> = _dspState.asStateFlow()

    private val _toneEvents = MutableSharedFlow<ToneEvent>(extraBufferCapacity = 64)
    open val toneEvents: SharedFlow<ToneEvent> = _toneEvents.asSharedFlow()

    private val _rawAudioFlow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
    open val rawAudioFlow: SharedFlow<ShortArray> = _rawAudioFlow.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var processingJob: Job? = null
    private var scope: CoroutineScope? = null

    private var audioChannel = Channel<ShortArray>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Updates the target detection frequency (e.g. 600Hz - 800Hz).
     */
    open fun setTargetFrequency(frequencyHz: Double) {
        targetFrequencyHz = frequencyHz
        goertzelDetector.setTargetFrequency(frequencyHz)
        Log.d(TAG, "Target frequency updated to ${frequencyHz}Hz")
    }

    /**
     * Thread-safely updates the Goertzel detection squelch threshold.
     * Updates mutable volatile threshold without blocking active audio recording.
     */
    open fun setSquelchThreshold(threshold: Double) {
        val clamped = threshold.coerceIn(MIN_SQUELCH_MAGNITUDE, MAX_SQUELCH_MAGNITUDE)
        squelchThreshold = clamped
        goertzelDetector.setSquelchThreshold(clamped)
        _dspState.update { it.copy(detectionThreshold = clamped) }
        Log.d(TAG, "Squelch threshold updated to ${String.format("%.3f", clamped)}")
    }

    /**
     * Checks if RECORD_AUDIO permission has been granted.
     */
    open fun hasRecordPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Calibrates ambient noise floor for microphone input.
     * Records audio for [durationMs] and sets the dynamic threshold above the ambient floor.
     */
    open suspend fun calibrateNoiseFloor(durationMs: Long = 1000): Double = withContext(defaultDispatcher) {
        if (!hasRecordPermission()) {
            Log.w(TAG, "Cannot calibrate: RECORD_AUDIO permission missing")
            return@withContext DEFAULT_NOISE_FLOOR
        }

        _dspState.update { it.copy(isCalibrating = true) }
        Log.d(TAG, "Starting noise floor calibration for ${durationMs}ms...")

        val wasListeningBefore = _dspState.value.isListening
        if (!wasListeningBefore) {
            startListening()
        }

        val calibrationSamples = mutableListOf<Double>()
        val startCalTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startCalTime < durationMs) {
            val mag = _dspState.value.currentMagnitude
            if (mag > 0.0) {
                calibrationSamples.add(mag)
            }
            kotlinx.coroutines.delay(20)
        }

        val avgNoise = if (calibrationSamples.isNotEmpty()) {
            calibrationSamples.average()
        } else {
            DEFAULT_NOISE_FLOOR
        }

        val newThreshold = max(DEFAULT_THRESHOLD, avgNoise * 2.8)
        setSquelchThreshold(newThreshold)
        _dspState.update {
            it.copy(
                isCalibrating = false,
                noiseFloor = avgNoise,
                detectionThreshold = newThreshold
            )
        }

        Log.d(TAG, "Calibration completed. Ambient Noise Floor: $avgNoise, Detection Threshold: $newThreshold")

        if (!wasListeningBefore) {
            stopListening()
        }

        avgNoise
    }

    /**
     * Starts the audio capture and Goertzel DSP analysis pipeline.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    open fun startListening() {
        if (_dspState.value.isListening) return

        if (!hasRecordPermission()) {
            Log.e(TAG, "Failed to start listening: RECORD_AUDIO permission not granted")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = max(minBufferSize, blockSize * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        audioChannel = Channel(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        scope = CoroutineScope(Dispatchers.Default)

        // Audio Capture Coroutine (Strictly Dispatchers.IO)
        captureJob = scope?.launch(ioDispatcher) {
            val readBuffer = ShortArray(blockSize)
            val record = audioRecord ?: return@launch

            Log.d(TAG, "Audio capture loop started on Dispatchers.IO (SampleRate: $sampleRate, BlockSize: $blockSize)")

            try {
                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val readCount = record.read(readBuffer, 0, blockSize)
                    if (readCount > 0) {
                        val bufferCopy = readBuffer.copyOf(readCount)
                        audioChannel.send(bufferCopy)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop: ${e.message}")
            }
        }

        // DSP Analysis Coroutine (Strictly Dispatchers.Default)
        processingJob = scope?.launch(defaultDispatcher) {
            Log.d(TAG, "Goertzel DSP processing loop started on Dispatchers.Default (Target: ${targetFrequencyHz}Hz)")

            var toneActive = false
            var toneStartTime = 0L
            var silenceStartTime = System.currentTimeMillis()

            try {
                for (buffer in audioChannel) {
                    if (!isActive) break

                    _rawAudioFlow.tryEmit(buffer)

                    val result = goertzelDetector.process(buffer)
                    val now = System.currentTimeMillis()
                    val threshold = squelchThreshold

                    // Evaluates tone presence based on magnitude and spectral purity
                    val isToneDetected = result.targetMagnitude >= threshold &&
                            result.spectralPurity >= MIN_SPECTRAL_PURITY

                    if (isToneDetected != toneActive) {
                        if (isToneDetected) {
                            // Tone onset
                            val silenceDuration = now - silenceStartTime
                            toneStartTime = now
                            toneActive = true

                            Log.d(
                                TAG,
                                "Tone Present: True, Silence Ended: ${silenceDuration}ms, Magnitude: ${result.targetMagnitude}, Threshold: $threshold"
                            )

                            _dspState.update {
                                it.copy(
                                    isTonePresent = true,
                                    currentMagnitude = result.targetMagnitude,
                                    spectralPurity = result.spectralPurity,
                                    rawRms = result.totalRms,
                                    lastSilenceDurationMs = silenceDuration
                                )
                            }

                            _toneEvents.tryEmit(
                                ToneEvent(
                                    isTonePresent = true,
                                    durationMs = silenceDuration,
                                    magnitude = result.targetMagnitude,
                                    timestampMs = now
                                )
                            )
                        } else {
                            // Tone offset
                            val toneDuration = now - toneStartTime
                            silenceStartTime = now
                            toneActive = false

                            Log.d(
                                TAG,
                                "Tone Present: False, Duration: ${toneDuration}ms, Magnitude: ${result.targetMagnitude}, Threshold: $threshold"
                            )

                            _dspState.update {
                                it.copy(
                                    isTonePresent = false,
                                    currentMagnitude = result.targetMagnitude,
                                    spectralPurity = result.spectralPurity,
                                    rawRms = result.totalRms,
                                    lastToneDurationMs = toneDuration
                                )
                            }

                            _toneEvents.tryEmit(
                                ToneEvent(
                                    isTonePresent = false,
                                    durationMs = toneDuration,
                                    magnitude = result.targetMagnitude,
                                    timestampMs = now
                                )
                            )
                        }
                    } else {
                        // Periodic state update for amplitude monitoring
                        _dspState.update {
                            it.copy(
                                currentMagnitude = result.targetMagnitude,
                                spectralPurity = result.spectralPurity,
                                rawRms = result.totalRms
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in DSP processing loop: ${e.message}")
            }
        }

        _dspState.update { it.copy(isListening = true) }
        Log.d(TAG, "MorseDSPManager listening started")
    }

    /**
     * Stops the audio recording and DSP analysis loops.
     */
    @Synchronized
    open fun stopListening() {
        if (!_dspState.value.isListening) return

        try {
            captureJob?.cancel()
            processingJob?.cancel()
            audioChannel.close()

            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            _dspState.update {
                it.copy(
                    isListening = false,
                    isTonePresent = false,
                    currentMagnitude = 0.0
                )
            }
            Log.d(TAG, "MorseDSPManager listening stopped")
        }
    }

    /**
     * Releases all DSP audio resources.
     */
    open fun release() {
        stopListening()
    }
}
