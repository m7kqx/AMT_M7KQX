package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Interface representing the real-time stream of characters/words decoded by Goertzel DSP.
 */
interface DspCharacterStream {
    val characterFlow: Flow<String>
    val stream: Flow<String> get() = characterFlow
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
 * Scoped purely with kotlinx.coroutines to ensure testability on the JVM and clean
 * cancellation when exiting the practice screen.
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

    /**
     * Coroutine function that runs the practice session lifecycle:
     * - Configures the bot with the provided [botProfile].
     * - Concurrently collects decoded text from [DspCharacterStream] and feeds it into [QsoManager].
     * - Concurrently collects FSM state updates from [QsoManager.stateFlow].
     * - Routes state transitions requiring bot action to [QsoBot.constructResponse] and [MorseAudioPlayer.playTransmission].
     */
    suspend fun startSession(botProfile: QsoBotProfile) = coroutineScope {
        activeBotProfile = botProfile
        qsoBot.selectProfile(botProfile)

        // 1. Concurrently collect incoming DSP characters and inject into QsoManager
        launch {
            dspStream.characterFlow.collect { input ->
                qsoManager.processInput(input)
            }
        }

        // 2. Concurrently collect FSM state updates and trigger bot responses on transitions
        launch {
            var previousState: QsoState? = null

            qsoManager.stateFlow.collect { currentState ->
                val isTransition = previousState != null && previousState != currentState
                previousState = currentState

                if (isTransition && shouldBotRespond(currentState)) {
                    val userCall = qsoManager.remoteCallsign ?: qsoBot.userCallsign
                    val responseText = qsoBot.constructResponse(currentState, userCall = userCall)
                    audioPlayer.playTransmission(responseText, botProfile.wpm)
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
     * Cleanly cancels the running session coroutines.
     */
    fun stopSession() {
        activeSessionJob?.cancel()
        activeSessionJob = null
    }

    /**
     * Determines whether the given [state] requires the bot to synthesize and transmit a response.
     */
    private fun shouldBotRespond(state: QsoState): Boolean {
        return when (state) {
            is QsoState.ListeningForCaller -> true
            is QsoState.Exchanging -> true
            is QsoState.LoggingContact -> false
            is QsoState.TransmittingCQ -> false
            is QsoState.Idle -> false
        }
    }
}
