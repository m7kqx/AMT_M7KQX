package com.example.androidmorsetrainer.ui.screens.decode

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.audio.FFTAnalyzer
import com.example.androidmorsetrainer.audio.MorseDSPManager
import com.example.androidmorsetrainer.morse.CwDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ViewModel managing real-time microphone DSP audio processing, Goertzel frequency tone detection,
 * raw amplitude visualization stream, and CW live Morse decoding.
 */
class DecodeViewModel(
    val dspManager: MorseDSPManager,
    private val fftAnalyzer: FFTAnalyzer = FFTAnalyzer(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    companion object {
        private const val TAG = "DecodeViewModel"
        const val MAX_AMPLITUDE_POINTS = 120

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MorseTrainerApplication)
                DecodeViewModel(
                    dspManager = application.container.morseDSPManager
                )
            }
        }
    }

    val activeDspManager: MorseDSPManager
        get() = dspManager

    private val decoder = CwDecoder(initialWpm = 20, scope = viewModelScope)

    private val _isDecoding = MutableStateFlow(dspManager.dspState.value.isListening)
    val isDecoding: StateFlow<Boolean> = _isDecoding.asStateFlow()

    private val _uiState = MutableStateFlow(
        DecodeUiState(
            isListening = dspManager.dspState.value.isListening,
            isDecoding = dspManager.dspState.value.isListening,
            hasRecordPermission = dspManager.hasRecordPermission(),
            targetFrequencyHz = dspManager.targetFrequencyHz,
            detectionThreshold = dspManager.squelchThreshold,
            squelchLevel = MorseDSPManager.magnitudeToSquelchLevel(dspManager.squelchThreshold),
            rawAmplitudes = List(MAX_AMPLITUDE_POINTS) { 0.0f },
            amplitudePoints = List(MAX_AMPLITUDE_POINTS) { AmplitudePoint(0.0f, false) }
        )
    )
    val uiState: StateFlow<DecodeUiState> = _uiState.asStateFlow()

    private val _squelchLevel = MutableStateFlow(
        MorseDSPManager.magnitudeToSquelchLevel(dspManager.squelchThreshold)
    )
    val squelchLevel: StateFlow<Float> = _squelchLevel.asStateFlow()

    private val _rawAmplitudes = MutableStateFlow(List(MAX_AMPLITUDE_POINTS) { 0.0f })
    val rawAmplitudes: StateFlow<List<Float>> = _rawAmplitudes.asStateFlow()

    private val _isToneDetected = MutableStateFlow(false)
    val isToneDetected: StateFlow<Boolean> = _isToneDetected.asStateFlow()

    private val rollingPoints = ArrayDeque<AmplitudePoint>(
        List(MAX_AMPLITUDE_POINTS) { AmplitudePoint(0.0f, false) }
    )

    init {
        observeDspState()
        observeToneEvents()
        observeRawAudio()
        observeDecoder()
    }

    private fun observeDspState() {
        viewModelScope.launch {
            dspManager.dspState.collect { dsp ->
                val level = MorseDSPManager.magnitudeToSquelchLevel(dsp.detectionThreshold)
                _isToneDetected.value = dsp.isTonePresent
                _squelchLevel.value = level
                _isDecoding.value = dsp.isListening
                _uiState.update {
                    it.copy(
                        isListening = dsp.isListening,
                        isDecoding = dsp.isListening,
                        isCalibrating = dsp.isCalibrating,
                        isTonePresent = dsp.isTonePresent,
                        currentMagnitude = dsp.currentMagnitude,
                        spectralPurity = dsp.spectralPurity,
                        noiseFloor = dsp.noiseFloor,
                        detectionThreshold = dsp.detectionThreshold,
                        squelchLevel = level
                    )
                }
            }
        }
    }

    /**
     * Updates the Goertzel detection squelch threshold from a normalized UI slider level (0.0 to 1.0).
     */
    fun setSquelchLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _squelchLevel.value = clamped
        val magnitude = MorseDSPManager.squelchLevelToMagnitude(clamped)
        dspManager.setSquelchThreshold(magnitude)
    }

    private fun observeToneEvents() {
        viewModelScope.launch {
            dspManager.toneEvents.collect { event ->
                if (event.isTonePresent) {
                    decoder.onToneStarted()
                } else {
                    decoder.onToneCompleted(event.durationMs)
                }
            }
        }
    }

    private fun observeRawAudio() {
        viewModelScope.launch(defaultDispatcher) {
            dspManager.rawAudioFlow.collect { pcmBuffer ->
                // Calculate RMS amplitude normalized to 0.0 .. 1.0
                var sumSq = 0.0
                for (sample in pcmBuffer) {
                    val norm = sample.toDouble() / Short.MAX_VALUE
                    sumSq += norm * norm
                }
                val rms = sqrt(sumSq / pcmBuffer.size).toFloat().coerceIn(0.0f, 1.0f)
                val isTone = _isToneDetected.value

                // Update rolling buffer
                val newPoint = AmplitudePoint(amplitude = rms, isTone = isTone)
                synchronized(rollingPoints) {
                    if (rollingPoints.size >= MAX_AMPLITUDE_POINTS) {
                        rollingPoints.removeFirst()
                    }
                    rollingPoints.addLast(newPoint)
                }

                val pointsSnapshot = synchronized(rollingPoints) { rollingPoints.toList() }
                val amplitudesSnapshot = pointsSnapshot.map { it.amplitude }

                _rawAmplitudes.value = amplitudesSnapshot
                _uiState.update {
                    it.copy(
                        rawAmplitudes = amplitudesSnapshot,
                        amplitudePoints = pointsSnapshot
                    )
                }
            }
        }
    }

    private fun observeDecoder() {
        viewModelScope.launch {
            decoder.decodedText.collect { text ->
                _uiState.update { it.copy(decodedText = text) }
            }
        }
        viewModelScope.launch {
            decoder.currentSymbol.collect { symbol ->
                _uiState.update { it.copy(currentMorseSymbol = symbol) }
            }
        }
        viewModelScope.launch {
            decoder.estimatedWpm.collect { wpm ->
                _uiState.update { it.copy(estimatedWpm = wpm) }
            }
        }
    }

    /**
     * Starts microphone audio capture and Goertzel DSP analysis.
     */
    fun startListening() {
        if (!dspManager.hasRecordPermission()) {
            _uiState.update {
                it.copy(
                    hasRecordPermission = false,
                    userMessage = "Microphone permission is required to decode audio."
                )
            }
            return
        }
        _uiState.update { it.copy(hasRecordPermission = true, userMessage = null) }
        dspManager.startListening()
    }

    /**
     * Stops microphone audio recording and analysis.
     */
    fun stopListening() {
        dspManager.stopListening()
    }

    /**
     * Toggles between listening and paused states.
     */
    fun toggleListening() {
        if (_uiState.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    /**
     * Updates the target detection frequency (e.g. 600Hz, 700Hz, 800Hz).
     */
    fun setTargetFrequency(frequencyHz: Double) {
        dspManager.setTargetFrequency(frequencyHz)
        _uiState.update { it.copy(targetFrequencyHz = frequencyHz) }
    }

    /**
     * Calibrates the ambient noise floor for 1 second.
     */
    fun calibrateNoiseFloor() {
        viewModelScope.launch {
            try {
                dspManager.calibrateNoiseFloor()
            } catch (e: Exception) {
                Log.e(TAG, "Calibration error: ${e.message}")
            }
        }
    }

    /**
     * Updates state when runtime microphone permission changes.
     */
    fun onPermissionResult(isGranted: Boolean) {
        _uiState.update { it.copy(hasRecordPermission = isGranted) }
        if (isGranted) {
            startListening()
        }
    }

    /**
     * Clears the live decoded text and in-progress symbol.
     */
    fun clearDecodedText() {
        decoder.clear()
    }

    /**
     * Auto-detects the target CW frequency by capturing a short buffer of microphone audio,
     * running FFT spectral peak detection within 400Hz - 1000Hz on Dispatchers.Default,
     * dynamically retuning MorseDSPManager's Goertzel filter to the peak, and updating the UI.
     */
    fun autoDetectPitch() {
        if (!dspManager.hasRecordPermission()) {
            _uiState.update {
                it.copy(
                    hasRecordPermission = false,
                    userMessage = "Microphone permission is required to auto-detect pitch."
                )
            }
            return
        }

        if (_uiState.value.isAutoTuning) return

        _uiState.update {
            it.copy(
                isAutoTuning = true,
                userMessage = "Sampling microphone audio for CW pitch..."
            )
        }

        viewModelScope.launch(defaultDispatcher) {
            try {
                val wasListening = dspManager.dspState.value.isListening
                if (!wasListening) {
                    dspManager.startListening()
                }

                // Capture ~280ms of audio (approx 24 blocks of 512 samples)
                val chunks = mutableListOf<ShortArray>()
                withTimeoutOrNull(800) {
                    dspManager.rawAudioFlow.take(24).collect { buffer ->
                        chunks.add(buffer)
                    }
                }

                if (chunks.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isAutoTuning = false,
                            userMessage = "No audio received from microphone."
                        )
                    }
                    return@launch
                }

                // Flatten captured chunks into a contiguous buffer
                val totalSamples = chunks.sumOf { it.size }
                val audioBuffer = ShortArray(totalSamples)
                var offset = 0
                for (chunk in chunks) {
                    System.arraycopy(chunk, 0, audioBuffer, offset, chunk.size)
                    offset += chunk.size
                }

                val targetWindowSize = 4096
                val sampleWindow = if (audioBuffer.size > targetWindowSize) {
                    extractHighestEnergyWindow(audioBuffer, targetWindowSize)
                } else {
                    audioBuffer
                }

                val result = fftAnalyzer.analyze(
                    samples = sampleWindow,
                    sampleRate = dspManager.sampleRate,
                    minFreqHz = 400.0,
                    maxFreqHz = 1000.0,
                    targetFftSize = 4096
                )

                if (result.isToneDetected) {
                    val lockedFreq = result.peakFrequencyHz.roundToInt().toDouble()
                    dspManager.setTargetFrequency(lockedFreq)
                    _uiState.update {
                        it.copy(
                            isAutoTuning = false,
                            targetFrequencyHz = lockedFreq,
                            autoTunedFrequencyHz = lockedFreq,
                            userMessage = "Locked CW pitch to ${lockedFreq.toInt()} Hz (SNR: ${String.format("%.1f", result.snr)}x)"
                        )
                    }
                    Log.d(TAG, "Auto-detected CW pitch: ${lockedFreq}Hz (SNR: ${result.snr})")
                } else {
                    _uiState.update {
                        it.copy(
                            isAutoTuning = false,
                            userMessage = "No distinct CW tone found (400–1000 Hz). Please key or play a tone."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-tune failed: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isAutoTuning = false,
                        userMessage = "Pitch auto-detection failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun extractHighestEnergyWindow(audio: ShortArray, windowSize: Int): ShortArray {
        if (audio.size <= windowSize) return audio
        val step = 512
        var bestStart = 0
        var maxEnergy = -1.0

        var start = 0
        while (start + windowSize <= audio.size) {
            var energy = 0.0
            for (i in start until start + windowSize step 8) {
                val s = audio[i].toDouble()
                energy += s * s
            }
            if (energy > maxEnergy) {
                maxEnergy = energy
                bestStart = start
            }
            start += step
        }

        return audio.copyOfRange(bestStart, bestStart + windowSize)
    }

    /**
     * Clears any active user notifications.
     */
    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    public override fun onCleared() {
        super.onCleared()
        dspManager.stopListening()
    }
}
