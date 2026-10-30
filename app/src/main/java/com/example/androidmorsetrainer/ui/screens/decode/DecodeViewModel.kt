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
            isPitchLocked = dspManager.dspState.value.isPitchLocked,
            lockedFrequencyHz = dspManager.dspState.value.lockedFrequencyHz,
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
                        squelchLevel = level,
                        isPitchLocked = dsp.isPitchLocked,
                        lockedFrequencyHz = dsp.lockedFrequencyHz,
                        targetFrequencyHz = dsp.targetFrequencyHz
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
