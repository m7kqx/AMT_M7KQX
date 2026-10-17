package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mock implementation of [DspCharacterStream] for testing.
 */
class MockDspCharacterStream : DspCharacterStream {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val characterFlow: Flow<String> = flow

    suspend fun emitInput(text: String) {
        flow.emit(text)
    }
}

/**
 * Mock implementation of [MorseAudioPlayer] for capturing played transmissions.
 */
class MockMorseAudioPlayer : MorseAudioPlayer {
    data class PlayRecord(val text: String, val wpm: Int)

    val history = mutableListOf<PlayRecord>()

    override suspend fun playTransmission(text: String, wpm: Int) {
        history.add(PlayRecord(text, wpm))
    }

    fun clear() {
        history.clear()
    }
}

/**
 * JUnit 4 tests for [QsoSessionController].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QsoSessionControllerTest {

    private lateinit var qsoManager: QsoManager
    private lateinit var qsoBot: QsoBot
    private lateinit var dspStream: MockDspCharacterStream
    private lateinit var audioPlayer: MockMorseAudioPlayer
    private lateinit var controller: QsoSessionController

    private val testBotProfile = QsoBotProfile(
        callsign = "LZ2BB",
        name = "Boyan",
        qth = "Sofia",
        wpm = 18
    )

    @Before
    fun setUp() {
        qsoManager = QsoManager()
        qsoBot = QsoBot(userCallsign = "M7KQX")
        dspStream = MockDspCharacterStream()
        audioPlayer = MockMorseAudioPlayer()

        controller = QsoSessionController(
            qsoManager = qsoManager,
            qsoBot = qsoBot,
            dspStream = dspStream,
            audioPlayer = audioPlayer
        )
    }

    @Test
    fun testInjectingValidCallsignTriggersFsmStateChangeAndBotResponse() = runTest {
        // Setup FSM in ListeningForCaller state
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)

        // Clear any prior records before starting session test
        audioPlayer.clear()

        // Start session in a background coroutine
        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        advanceUntilIdle()

        // Verify initial state before injection
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)
        assertEquals(0, audioPlayer.history.size)

        // Inject valid callsign "M7KQX" into mock DSP stream
        dspStream.emitInput("M7KQX")
        advanceUntilIdle()

        // 1. Verify FSM transitioned to Exchanging(AWAITING_REPORT)
        assertEquals(QsoState.Exchanging(ExchangeStage.AWAITING_REPORT), qsoManager.state.value)
        assertEquals("M7KQX", qsoManager.remoteCallsign)

        // 2. Verify mock Audio player played the expected bot response
        assertEquals("Audio player should have received 1 transmission", 1, audioPlayer.history.size)
        val record = audioPlayer.history.first()
        assertEquals("M7KQX DE LZ2BB UR 599 599 BK", record.text)
        assertEquals(18, record.wpm)

        sessionJob.cancel()
    }

    @Test
    fun testFullQsoSequenceViaDspStream() = runTest {
        // Start in Idle
        assertEquals(QsoState.Idle, qsoManager.state.value)

        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        advanceUntilIdle()

        // 1. User sends CQ -> TransmittingCQ (bot should not play)
        dspStream.emitInput("CQ")
        advanceUntilIdle()
        assertEquals(QsoState.TransmittingCQ, qsoManager.state.value)
        assertEquals(0, audioPlayer.history.size)

        // 2. User finishes CQ with 'K' -> ListeningForCaller (bot replies to CQ)
        dspStream.emitInput("K")
        advanceUntilIdle()
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)
        assertEquals(1, audioPlayer.history.size)
        assertEquals("M7KQX DE LZ2BB K", audioPlayer.history.last().text)

        // 3. Caller callsign received -> Exchanging(AWAITING_REPORT) (bot sends report)
        dspStream.emitInput("M7KQX")
        advanceUntilIdle()
        assertEquals(QsoState.Exchanging(ExchangeStage.AWAITING_REPORT), qsoManager.state.value)
        assertEquals(2, audioPlayer.history.size)
        assertEquals("M7KQX DE LZ2BB UR 599 599 BK", audioPlayer.history.last().text)

        // 4. Signal report received -> Exchanging(COMPLETED) (bot sends final sign-off)
        dspStream.emitInput("599")
        advanceUntilIdle()
        assertEquals(QsoState.Exchanging(ExchangeStage.COMPLETED), qsoManager.state.value)
        assertEquals(3, audioPlayer.history.size)
        assertEquals("TU 73 DE LZ2BB SK", audioPlayer.history.last().text)

        sessionJob.cancel()
    }

    @Test
    fun testNoiseDoesNotTriggerBotTransmission() = runTest {
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)
        audioPlayer.clear()

        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        advanceUntilIdle()

        // Inject random noise
        dspStream.emitInput("ASDFGHJK")
        advanceUntilIdle()

        // State remains unchanged and no audio played
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)
        assertEquals(0, audioPlayer.history.size)

        sessionJob.cancel()
    }

    @Test
    fun testSessionCancellationStopsProcessing() = runTest {
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        audioPlayer.clear()

        val job = controller.startSession(this, testBotProfile)
        advanceUntilIdle()
        assertTrue(job.isActive)

        // Stop session cleanly
        controller.stopSession()
        advanceUntilIdle()
        assertFalse(job.isActive)

        // Emitting after cancellation must have no effect
        dspStream.emitInput("M7KQX")
        advanceUntilIdle()

        assertEquals(0, audioPlayer.history.size)
    }
}
