package com.example.androidmorsetrainer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    audioManager: AudioManager? = null
) {
    open val audioManager: AudioManager? = audioManager ?: (context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)

    @Volatile
    var squelchThreshold: Double = squelchThreshold
        private set

    @Volatile
    open var isDecodingActive: Boolean = false
        set(value) {
            field = value
            _dspState.update { it.copy(isDecodingActive = value) }
            if (!value) {
                _dspState.update {
                    it.copy(
                        isTonePresent = false,
                        currentMagnitude = 0.0
                    )
                }
            }
        }

    companion object {
        private const val TAG = "MorseDSP"
        const val DEFAULT_NOISE_FLOOR = 0.02
        const val DEFAULT_THRESHOLD = 0.05
        const val MIN_SQUELCH_MAGNITUDE = 0.005
        const val MAX_SQUELCH_MAGNITUDE = 0.500
        private const val MIN_SPECTRAL_PURITY = 0.20 // 20% spectral concentration around target tone
        const val DEFAULT_LOCK_TIMEOUT_MS = 2500L
        const val MIN_CW_FREQ = 400.0
        const val MAX_CW_FREQ = 1000.0

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

    enum class PitchLockState {
        SEARCHING,
        LOCKED
    }

    @Volatile
    var isAutoDetectPitchEnabled: Boolean = true

    var lockTimeoutMs: Long = DEFAULT_LOCK_TIMEOUT_MS

    val isPitchLocked: Boolean
        get() = _dspState.value.isPitchLocked

    val lockedFrequencyHz: Double?
        get() = _dspState.value.lockedFrequencyHz

    val fftAnalyzer = FFTAnalyzer()

    @Volatile
    var pitchLockState: PitchLockState = PitchLockState.SEARCHING
        private set

    private val goertzelDetector = GoertzelDetector(
        sampleRate = sampleRate,
        targetFrequencyHz = targetFrequencyHz,
        blockSize = blockSize,
        squelchThreshold = squelchThreshold
    )

    data class DSPState(
        val isListening: Boolean = false,
        val isDecodingActive: Boolean = false,
        val isTonePresent: Boolean = false,
        val isCalibrating: Boolean = false,
        val currentMagnitude: Double = 0.0,
        val spectralPurity: Double = 0.0,
        val noiseFloor: Double = DEFAULT_NOISE_FLOOR,
        val detectionThreshold: Double = DEFAULT_THRESHOLD,
        val lastToneDurationMs: Long = 0,
        val lastSilenceDurationMs: Long = 0,
        val rawRms: Double = 0.0,
        val isPitchLocked: Boolean = false,
        val lockedFrequencyHz: Double? = null,
        val targetFrequencyHz: Double = 700.0
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

    private val fftBufferSize = 2048
    private val fftAudioBuffer = ShortArray(fftBufferSize)
    private var fftBufferSamples = 0
    private var lastToneSeenTimeMs = 0L
    private var lastFftScanTimeMs = 0L
    private val FFT_SCAN_INTERVAL_MS = 60L
    private var toneActive = false
    private var toneStartTime = 0L
    private var silenceStartTime = 0L

    /**
     * Updates the target detection frequency (e.g. 600Hz - 800Hz).
     */
    open fun setTargetFrequency(frequencyHz: Double) {
        targetFrequencyHz = frequencyHz
        goertzelDetector.setTargetFrequency(frequencyHz)
        _dspState.update { it.copy(targetFrequencyHz = frequencyHz) }
        Log.d(TAG, "Target frequency updated to ${frequencyHz}Hz")
    }

    /**
     * Locks onto the given frequency explicitly.
     */
    open fun lockOnFrequency(frequencyHz: Double) {
        targetFrequencyHz = frequencyHz
        goertzelDetector.setTargetFrequency(frequencyHz)
        pitchLockState = PitchLockState.LOCKED
        _dspState.update {
            it.copy(
                isPitchLocked = true,
                lockedFrequencyHz = frequencyHz,
                targetFrequencyHz = frequencyHz
            )
        }
        Log.d(TAG, "Pitch locked on ${frequencyHz}Hz")
    }

    /**
     * Releases the active frequency lock and returns to searching mode.
     */
    open fun releaseFrequencyLock() {
        pitchLockState = PitchLockState.SEARCHING
        _dspState.update {
            it.copy(
                isPitchLocked = false,
                lockedFrequencyHz = null
            )
        }
        Log.d(TAG, "Pitch lock released. Resuming search.")
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
     * Checks if the given [AudioDeviceInfo] is a Bluetooth input microphone.
     */
    open fun isBluetoothDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /**
     * Checks if the given [AudioDeviceInfo] is a USB input microphone.
     */
    open fun isUsbDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
               device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
               (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && device.type == AudioDeviceInfo.TYPE_USB_HEADSET)
    }

    /**
     * Discovers external input audio devices (Bluetooth or USB)
     * using [AudioManager.getDevices] with [AudioManager.GET_DEVICES_INPUTS].
     */
    open fun discoverExternalAudioDevices(): List<AudioDeviceInfo> {
        val am = audioManager ?: return emptyList()
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.filter { isBluetoothDevice(it) || isUsbDevice(it) }
    }

    open fun getExternalAudioDevices(): List<AudioDeviceInfo> = discoverExternalAudioDevices()

    open fun checkExternalAudioDevices(): List<AudioDeviceInfo> = discoverExternalAudioDevices()

    /**
     * Routes audio capture to external devices (Bluetooth or USB).
     * - If a Bluetooth microphone is detected, invokes [AudioManager.startBluetoothSco] and [AudioManager.setBluetoothScoOn].
     * - If a USB microphone is detected, applies [AudioRecord.setPreferredDevice] targeting the USB peripheral.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    open fun routeAudio(record: AudioRecord?): AudioDeviceInfo? {
        val am = audioManager ?: return null
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val bluetoothDevice = devices.firstOrNull { isBluetoothDevice(it) }
        val usbDevice = devices.firstOrNull { isUsbDevice(it) }

        if (bluetoothDevice != null) {
            Log.d(TAG, "Bluetooth microphone detected: ${bluetoothDevice.productName}. Routing audio via Bluetooth SCO.")
            am.startBluetoothSco()
            am.setBluetoothScoOn(true)
            return bluetoothDevice
        } else if (usbDevice != null) {
            Log.d(TAG, "USB microphone detected: ${usbDevice.productName}. Setting preferred device on AudioRecord.")
            record?.setPreferredDevice(usbDevice)
            return usbDevice
        }

        return null
    }

    open fun configureAudioRouting(record: AudioRecord?): AudioDeviceInfo? = routeAudio(record)

    /**
     * Processes an incoming 16-bit PCM audio block through the auto-detection, Goertzel filter,
     * hysteresis lock-on delay, and tone transition pipeline.
     */
    open fun processPcmBlock(buffer: ShortArray, now: Long = System.currentTimeMillis()): GoertzelDetector.DetectionResult {
        _rawAudioFlow.tryEmit(buffer)

        // 1. Accumulate audio samples into the rolling FFT buffer
        if (fftBufferSize > buffer.size) {
            System.arraycopy(fftAudioBuffer, buffer.size, fftAudioBuffer, 0, fftBufferSize - buffer.size)
            System.arraycopy(buffer, 0, fftAudioBuffer, fftBufferSize - buffer.size, buffer.size)
        } else {
            System.arraycopy(buffer, 0, fftAudioBuffer, 0, fftBufferSize)
        }
        if (fftBufferSamples < fftBufferSize) {
            fftBufferSamples += buffer.size
        }

        // 2. Measure raw RMS of current buffer
        var sumSq = 0.0
        for (s in buffer) {
            val norm = s.toDouble() / Short.MAX_VALUE
            sumSq += norm * norm
        }
        val rawRms = sqrt(sumSq / buffer.size)

        // 3. Initial Goertzel detection on current buffer at current target frequency
        var result = goertzelDetector.process(buffer)
        val threshold = squelchThreshold
        var isToneDetected = result.targetMagnitude >= threshold &&
                result.spectralPurity >= MIN_SPECTRAL_PURITY

        // 4. Automatic pitch scanning & frequency lock mechanism
        if (isAutoDetectPitchEnabled) {
            when (pitchLockState) {
                PitchLockState.SEARCHING -> {
                    // If we have enough samples and audio energy is present, scan for CW peak frequency
                    if (fftBufferSamples >= 1024 &&
                        (now - lastFftScanTimeMs >= FFT_SCAN_INTERVAL_MS) &&
                        (rawRms >= 0.008)
                    ) {
                        lastFftScanTimeMs = now
                        val fftResult = fftAnalyzer.analyze(
                            samples = fftAudioBuffer,
                            sampleRate = sampleRate,
                            minFreqHz = MIN_CW_FREQ,
                            maxFreqHz = MAX_CW_FREQ,
                            targetFftSize = 2048
                        )

                        if (fftResult.isToneDetected) {
                            val detectedFreq = fftResult.peakFrequencyHz.roundToInt().toDouble()
                            setTargetFrequency(detectedFreq)
                            pitchLockState = PitchLockState.LOCKED
                            lastToneSeenTimeMs = now
                            result = goertzelDetector.process(buffer)
                            isToneDetected = result.targetMagnitude >= threshold &&
                                    result.spectralPurity >= MIN_SPECTRAL_PURITY
                            Log.d(TAG, "CW Pitch Auto-Locked onto ${detectedFreq.toInt()}Hz (SNR: ${String.format("%.1f", fftResult.snr)})")
                        }
                    }
                }

                PitchLockState.LOCKED -> {
                    if (isToneDetected) {
                        lastToneSeenTimeMs = now
                    } else {
                        // If signal has been absent at the locked pitch for more than lockTimeoutMs, release lock
                        if (lastToneSeenTimeMs > 0L && (now - lastToneSeenTimeMs >= lockTimeoutMs)) {
                            pitchLockState = PitchLockState.SEARCHING
                            lastToneSeenTimeMs = 0L
                            Log.d(TAG, "CW Pitch Lock Released after ${lockTimeoutMs}ms signal loss. Resuming frequency search.")
                        } else if (fftBufferSamples >= 1024 && rawRms >= 0.015 && (now - lastFftScanTimeMs >= FFT_SCAN_INTERVAL_MS)) {
                            // Check if sender retuned to a different CW frequency
                            lastFftScanTimeMs = now
                            val fftResult = fftAnalyzer.analyze(
                                samples = fftAudioBuffer,
                                sampleRate = sampleRate,
                                minFreqHz = MIN_CW_FREQ,
                                maxFreqHz = MAX_CW_FREQ,
                                targetFftSize = 2048
                            )
                            if (fftResult.isToneDetected && abs(fftResult.peakFrequencyHz - targetFrequencyHz) >= 25.0) {
                                val newFreq = fftResult.peakFrequencyHz.roundToInt().toDouble()
                                setTargetFrequency(newFreq)
                                lastToneSeenTimeMs = now
                                result = goertzelDetector.process(buffer)
                                isToneDetected = result.targetMagnitude >= threshold &&
                                        result.spectralPurity >= MIN_SPECTRAL_PURITY
                                Log.d(TAG, "CW Pitch Retuned to ${newFreq.toInt()}Hz (SNR: ${String.format("%.1f", fftResult.snr)})")
                            }
                        }
                    }
                }
            }
        } else {
            if (isToneDetected && pitchLockState == PitchLockState.LOCKED) {
                lastToneSeenTimeMs = now
            }
        }

        // 5. Evaluate tone transitions
        if (isToneDetected != toneActive) {
            if (isToneDetected) {
                val silenceDuration = if (silenceStartTime > 0L) now - silenceStartTime else 0L
                toneStartTime = now
                toneActive = true
                lastToneSeenTimeMs = now

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
                        lastSilenceDurationMs = silenceDuration,
                        isPitchLocked = (pitchLockState == PitchLockState.LOCKED),
                        lockedFrequencyHz = if (pitchLockState == PitchLockState.LOCKED) targetFrequencyHz else null,
                        targetFrequencyHz = targetFrequencyHz
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
                        lastToneDurationMs = toneDuration,
                        isPitchLocked = (pitchLockState == PitchLockState.LOCKED),
                        lockedFrequencyHz = if (pitchLockState == PitchLockState.LOCKED) targetFrequencyHz else null,
                        targetFrequencyHz = targetFrequencyHz
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
            _dspState.update {
                it.copy(
                    currentMagnitude = result.targetMagnitude,
                    spectralPurity = result.spectralPurity,
                    rawRms = result.totalRms,
                    isPitchLocked = (pitchLockState == PitchLockState.LOCKED),
                    lockedFrequencyHz = if (pitchLockState == PitchLockState.LOCKED) targetFrequencyHz else null,
                    targetFrequencyHz = targetFrequencyHz
                )
            }
        }

        return result
    }

    /**
     * Starts the audio capture and Goertzel DSP analysis pipeline.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    open fun startListening() {
        isDecodingActive = true
        silenceStartTime = System.currentTimeMillis()
        toneActive = false
        pitchLockState = PitchLockState.SEARCHING
        lastToneSeenTimeMs = 0L
        lastFftScanTimeMs = 0L
        fftBufferSamples = 0

        if (_dspState.value.isListening) {
            _dspState.update { it.copy(isListening = true, isDecodingActive = true) }
            return
        }

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

        routeAudio(audioRecord)

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

            try {
                for (buffer in audioChannel) {
                    if (!isActive) break

                    if (!isDecodingActive) {
                        if (toneActive) {
                            toneActive = false
                            _dspState.update {
                                it.copy(
                                    isTonePresent = false,
                                    currentMagnitude = 0.0
                                )
                            }
                        }
                        continue
                    }

                    processPcmBlock(buffer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in DSP processing loop: ${e.message}")
            }
        }

        _dspState.update {
            it.copy(
                isListening = true,
                isDecodingActive = true,
                isPitchLocked = (pitchLockState == PitchLockState.LOCKED),
                lockedFrequencyHz = if (pitchLockState == PitchLockState.LOCKED) targetFrequencyHz else null,
                targetFrequencyHz = targetFrequencyHz
            )
        }
        Log.d(TAG, "MorseDSPManager listening started")
    }

    /**
     * Stops the audio recording and DSP analysis loops.
     */
    @Synchronized
    @Suppress("DEPRECATION")
    open fun stopListening() {
        isDecodingActive = false
        pitchLockState = PitchLockState.SEARCHING
        lastToneSeenTimeMs = 0L
        lastFftScanTimeMs = 0L
        fftBufferSamples = 0
        toneActive = false

        if (!_dspState.value.isListening) {
            _dspState.update {
                it.copy(
                    isListening = false,
                    isDecodingActive = false,
                    isTonePresent = false,
                    currentMagnitude = 0.0,
                    isPitchLocked = false,
                    lockedFrequencyHz = null
                )
            }
            return
        }

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

            try {
                audioManager?.let { am ->
                    if (am.isBluetoothScoOn) {
                        am.setBluetoothScoOn(false)
                        am.stopBluetoothSco()
                        Log.d(TAG, "Bluetooth SCO stopped")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Bluetooth SCO: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            _dspState.update {
                it.copy(
                    isListening = false,
                    isDecodingActive = false,
                    isTonePresent = false,
                    currentMagnitude = 0.0,
                    isPitchLocked = false,
                    lockedFrequencyHz = null
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
