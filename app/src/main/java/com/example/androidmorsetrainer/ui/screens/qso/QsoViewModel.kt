package com.example.androidmorsetrainer.ui.screens.qso

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.audio.DspTelemetry
import com.example.androidmorsetrainer.audio.GoertzelDspStream
import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import com.example.androidmorsetrainer.audio.MorseDSPManager
import com.example.androidmorsetrainer.audio.SineWaveMorsePlayer
import com.example.androidmorsetrainer.morse.DspCharacterStream
import com.example.androidmorsetrainer.morse.ExchangeStage
import com.example.androidmorsetrainer.morse.MorseAudioPlayer
import com.example.androidmorsetrainer.morse.QsoBot
import com.example.androidmorsetrainer.morse.QsoBotProfile
import com.example.androidmorsetrainer.morse.QsoManager
import com.example.androidmorsetrainer.morse.QsoSessionController
import com.example.androidmorsetrainer.morse.QsoState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stubbed implementation of [DspCharacterStream] for unit testing.
 */
class StubDspCharacterStream : DspCharacterStream {
    private val _characterFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val characterFlow: Flow<String> = _characterFlow.asSharedFlow()

    private val _toneActivityFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val toneActivityFlow: Flow<Unit> = _toneActivityFlow.asSharedFlow()

    private val _isListening = MutableStateFlow(true)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    override fun pauseListening() {
        _isListening.value = false
    }

    override fun resumeListening() {
        _isListening.value = true
    }

    fun emitInput(text: String) {
        _toneActivityFlow.tryEmit(Unit)
        _characterFlow.tryEmit(text)
    }

    fun emitTone() {
        _toneActivityFlow.tryEmit(Unit)
    }
}

/**
 * Stubbed implementation of [MorseAudioPlayer] for unit testing.
 */
class StubMorseAudioPlayer(
    private val onPlayback: (text: String, wpm: Int) -> Unit = { _, _ -> }
) : MorseAudioPlayer {
    override suspend fun playTransmission(text: String, wpm: Int) {
        onPlayback(text, wpm)
    }
}

/**
 * Android ViewModel managing the offline Morse code QSO simulator session.
 *
 * Integrates [GoertzelDspStream] for real-time microphone Goertzel audio decoding
 * and [SineWaveMorsePlayer] for strict 1:3:7 ITU AudioTrack sine wave audio synthesis.
 */
class QsoViewModel(
    val qsoManager: QsoManager = QsoManager(),
    val qsoBot: QsoBot = QsoBot(userCallsign = "M7KQX"),
    val dspStream: DspCharacterStream = GoertzelDspStream(MorseDSPManager()),
    val audioPlayer: MorseAudioPlayer = SineWaveMorsePlayer(MorseAudioGenerator())
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MorseTrainerApplication)
                val dspStream = GoertzelDspStream(app.container.morseDSPManager)
                val audioPlayer = SineWaveMorsePlayer(app.container.morseAudioGenerator)
                QsoViewModel(
                    dspStream = dspStream,
                    audioPlayer = audioPlayer
                )
            }
        }

        fun formatStateDescription(state: QsoState): String {
            return when (state) {
                is QsoState.Idle -> "Idle"
                is QsoState.ListenCQ -> "State 1: Listening for CQ"
                is QsoState.InitialResponse -> "State 2: Initial Response (Awaiting RST)"
                is QsoState.InfoExchange -> "State 3: Info Exchange (Awaiting Info/ACK)"
                is QsoState.SignOff -> "State 4: Sign Off (Awaiting 73/TU)"
                is QsoState.DitDitFinale -> "State 5: Dit Dit Finale (Awaiting E E)"
                is QsoState.Summary -> "State 6: QSO Summary"
                // Legacy states
                is QsoState.TransmittingCQ -> "Transmitting CQ"
                is QsoState.ListeningForCaller -> "Listening for Caller"
                is QsoState.Exchanging -> when (state.stage) {
                    ExchangeStage.AWAITING_CALLSIGN -> "Exchanging (Awaiting Callsign)"
                    ExchangeStage.AWAITING_REPORT -> "Exchanging (Awaiting Report)"
                    ExchangeStage.COMPLETED -> "Exchanging (Completed)"
                }
                is QsoState.LoggingContact -> "Logging Contact"
            }
        }
    }

    // 1. Expose FSM state directly to the UI
    val qsoState: StateFlow<QsoState> = qsoManager.stateFlow
    val state: StateFlow<QsoState> get() = qsoState

    // 2. Expose the currently active simulated operator profile
    private val _activeProfile = MutableStateFlow<QsoBotProfile?>(null)
    val activeProfile: StateFlow<QsoBotProfile?> = _activeProfile.asStateFlow()

    // 3. Expose transcription log for the waterfall/console UI
    private val _transcript = MutableStateFlow<List<String>>(emptyList())
    val transcript: StateFlow<List<String>> = _transcript.asStateFlow()

    // 4. Expose real-time DSP telemetry to the UI
    val telemetry: StateFlow<DspTelemetry> = if (dspStream is GoertzelDspStream) {
        dspStream.telemetry
    } else {
        MutableStateFlow(DspTelemetry())
    }

    val sessionController: QsoSessionController
    private var sessionJob: Job? = null

    // 5. Expose microphone half-duplex mute status to the UI
    val isMicMuted: StateFlow<Boolean>

    /**
     * Passes the user's manual noise floor / squelch threshold adjustment down to GoertzelDspStream.
     */
    fun updateNoiseFloor(threshold: Float) {
        if (dspStream is GoertzelDspStream) {
            dspStream.updateNoiseFloor(threshold)
        }
    }

    init {
        // Decorate audio player to log bot transmissions to console and play hardware AudioTrack audio
        val compositeAudioPlayer = MorseAudioPlayer { text, wpm ->
            val senderCall = _activeProfile.value?.callsign ?: "BOT"
            addTranscriptEntry("RX ($senderCall @ ${wpm}WPM): $text")
            audioPlayer.playTransmission(text, wpm)
        }

        sessionController = QsoSessionController(
            qsoManager = qsoManager,
            qsoBot = qsoBot,
            dspStream = dspStream,
            audioPlayer = compositeAudioPlayer
        )

        isMicMuted = sessionController.isMicMuted

        // Monitor FSM state transitions for console transcription
        viewModelScope.launch {
            qsoManager.stateFlow.collect { state ->
                val desc = formatStateDescription(state)
                addTranscriptEntry("SYS: State -> $desc")
            }
        }

        // Monitor real-time decoded words from DSP stream to log to console
        viewModelScope.launch {
            dspStream.characterFlow.collect { decodedText ->
                addTranscriptEntry("DSP Decoded: $decodedText")
            }
        }

        // Monitor error recovery events
        viewModelScope.launch {
            qsoManager.errorRecoveryFlow.collect { errorMsg ->
                addTranscriptEntry("SYS: Error Recovery -> Bot transmitting '$errorMsg'")
            }
        }

        // Start initial practice session
        startNewQso()
    }

    /**
     * Generates a new bot profile, resets the state machine, and starts a fresh session
     * scoped strictly within [viewModelScope].
     */
    fun startNewQso() {
        sessionJob?.cancel()
        if (audioPlayer is SineWaveMorsePlayer) {
            audioPlayer.stop()
        }
        qsoManager.reset(QsoState.ListenCQ)

        val newProfile = qsoBot.generateProfile()
        _activeProfile.value = newProfile
        addTranscriptEntry("=== New QSO with ${newProfile.callsign} (${newProfile.name}, ${newProfile.qth}) ===")

        if (dspStream is GoertzelDspStream) {
            dspStream.start()
        }

        sessionJob = viewModelScope.launch {
            sessionController.startSession(newProfile)
        }
    }

    /**
     * Injects simulated text into the DSP stream (e.g. from tests or programmatic driving).
     */
    fun simulateInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        addTranscriptEntry("TX (You): $trimmed")

        if (dspStream is GoertzelDspStream) {
            dspStream.emitManual(trimmed)
        } else if (dspStream is StubDspCharacterStream) {
            dspStream.emitInput(trimmed)
        } else {
            qsoManager.processInput(trimmed)
        }
    }

    /**
     * Clears the current session transcription log.
     */
    fun clearTranscript() {
        _transcript.value = emptyList()
    }

    private fun addTranscriptEntry(entry: String) {
        _transcript.value = _transcript.value + entry
    }

    override fun onCleared() {
        super.onCleared()
        sessionJob?.cancel()
        sessionJob = null

        // Explicitly release hardware resources
        if (dspStream is GoertzelDspStream) {
            dspStream.stop()
        }
        if (audioPlayer is SineWaveMorsePlayer) {
            audioPlayer.release()
        }
    }
}
