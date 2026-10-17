package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Interface representing the real-time stream of characters/words decoded by Goertzel DSP.
 */
interface DspCharacterStream {
    val characterFlow: Flow<String>
    val stream: Flow<String> get() = characterFlow
    val toneActivityFlow: Flow<Unit> get() = emptyFlow()
    val isListening: StateFlow<Boolean> get() = MutableStateFlow(true)
    fun pauseListening() {}
    fun resumeListening() {}
}

/**
 * Interface for synthesizing and playing Morse code audio transmissions.
 */
fun interface MorseAudioPlayer {
    suspend fun playTransmission(text: String, wpm: Int)
}

/**
 * Central integration layer bridging [QsoManager], [QsoBot], [DspCharacterStream],
 * and [MorseAudioPlayer] using Kotlin Coroutines.
 *
 * Enforces strict half-duplex operation by muting microphone listening during simulated
 * bot transmissions to prevent full-duplex acoustic feedback and self-decoding.
 * Manages 5-second silence watchdogs, error recovery transmissions, and Dit-Dit finales.
 */
class QsoSessionController(
    val qsoManager: QsoManager,
    val qsoBot: QsoBot,
    val dspStream: DspCharacterStream,
    val audioPlayer: MorseAudioPlayer
) {
    /**
     * Currently active operator profile in the practice session.
     */
    var activeBotProfile: QsoBotProfile? = null
        private set

    private var activeSessionJob: Job? = null

    private val _isMicMuted = MutableStateFlow(false)

    /**
     * Observable state indicating whether microphone audio input is muted for half-duplex Bot TX.
     */
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    /**
     * Transmits CW audio as the simulated bot while strictly enforcing half-duplex operation.
     * Pauses DSP listening immediately before synthesis and unpauses immediately after completion.
     */
    suspend fun transmitAsBot(text: String, wpm: Int) {
        _isMicMuted.value = true
        dspStream.pauseListening()
        try {
            audioPlayer.playTransmission(text, wpm)
        } finally {
            dspStream.resumeListening()
            _isMicMuted.value = false
        }
    }

    /**
     * Coroutine function that runs the practice session lifecycle:
     * - Configures the bot with the provided [botProfile].
     * - Concurrently collects decoded text from [DspCharacterStream] and feeds it into [QsoManager].
     * - Concurrently monitors tone activity to reset 5s silence watchdogs in State 1 and State 5.
     * - Concurrently collects global error recovery events and transmits "AGN AGN?".
     * - Concurrently collects Dit-Dit finale events, responds with "E E", and completes the QSO.
     * - Concurrently collects FSM state updates and triggers half-duplex bot transmissions.
     */
    suspend fun startSession(botProfile: QsoBotProfile) = coroutineScope {
        activeBotProfile = botProfile
        qsoBot.selectProfile(botProfile)
        qsoManager.activeBotProfile = botProfile

        // 1. Concurrently collect incoming DSP characters and inject into QsoManager
        launch {
            dspStream.characterFlow.collect { input ->
                qsoManager.processInput(input)
            }
        }

        // 2. Silence watchdog coroutine for State 1 and State 5
        var silenceJob: Job? = null

        fun resetSilenceTimer(delayMs: Long = 5000L) {
            silenceJob?.cancel()
            silenceJob = launch {
                delay(delayMs)
                qsoManager.onSilenceTimeout()
            }
        }

        // Reset silence timer on every detected CW tone activity
        launch {
            dspStream.toneActivityFlow.collect {
                val state = qsoManager.state.value
                if (state is QsoState.ListenCQ || state is QsoState.DitDitFinale) {
                    resetSilenceTimer(5000L)
                }
            }
        }

        // 3. Concurrently collect global error recovery triggers and transmit "AGN AGN?"
        launch {
            qsoManager.errorRecoveryFlow.collect { errorMessage ->
                transmitAsBot(errorMessage, botProfile.wpm)
            }
        }

        // 4. Concurrently collect Dit-Dit finale event
        launch {
            qsoManager.ditDitReceivedFlow.collect {
                silenceJob?.cancel()
                transmitAsBot("E E", botProfile.wpm)
                delay(5000L)
                qsoManager.completeQso()
            }
        }

        // 5. Concurrently collect FSM state updates and trigger bot transmissions on transitions
        launch {
            var previousState: QsoState? = null

            qsoManager.stateFlow.collect { currentState ->
                val isTransition = previousState != null && previousState != currentState
                previousState = currentState

                if (currentState is QsoState.DitDitFinale) {
                    resetSilenceTimer(5000L)
                } else if (currentState !is QsoState.ListenCQ) {
                    silenceJob?.cancel()
                }

                if (isTransition && shouldBotRespond(currentState)) {
                    val userCall = qsoManager.remoteCallsign ?: qsoManager.userCallsign
                    val responseText = qsoBot.constructResponse(currentState, userCall = userCall)
                    if (responseText.isNotEmpty()) {
                        transmitAsBot(responseText, botProfile.wpm)
                    }
                }
            }
        }
    }

    /**
     * Starts the session asynchronously within an external [scope] and retains a reference
     * to the running [Job] for explicit cancellation.
     */
    fun startSession(scope: CoroutineScope, botProfile: QsoBotProfile): Job {
        stopSession()
        val job = scope.launch {
            startSession(botProfile)
        }
        activeSessionJob = job
        return job
    }

    /**
     * Cleanly cancels the running session coroutines and restores listening state.
     */
    fun stopSession() {
        activeSessionJob?.cancel()
        activeSessionJob = null
        _isMicMuted.value = false
        dspStream.resumeListening()
    }

    /**
     * Determines whether the given [state] requires the bot to synthesize and transmit a response.
     */
    fun shouldBotRespond(state: QsoState): Boolean {
        return when (state) {
            is QsoState.InitialResponse -> true
            is QsoState.InfoExchange -> true
            is QsoState.SignOff -> true
            is QsoState.ListeningForCaller -> true
            is QsoState.Exchanging -> true
            is QsoState.ListenCQ -> false
            is QsoState.DitDitFinale -> false
            is QsoState.Summary -> false
            is QsoState.LoggingContact -> false
            is QsoState.TransmittingCQ -> false
            is QsoState.Idle -> false
        }
    }
}
