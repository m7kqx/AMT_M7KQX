package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mock implementation of [DspCharacterStream] for testing half-duplex and tone flows.
 */
class MockDspCharacterStream : DspCharacterStream {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val characterFlow: Flow<String> = flow

    private val _toneActivity = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val toneActivityFlow: Flow<Unit> = _toneActivity.asSharedFlow()

    private val _isListening = MutableStateFlow(true)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    var isPaused = false
        private set

    override fun pauseListening() {
        isPaused = true
        _isListening.value = false
    }

    override fun resumeListening() {
        isPaused = false
        _isListening.value = true
    }

    suspend fun emitInput(text: String) {
        _toneActivity.emit(Unit)
        flow.emit(text)
    }

    fun emitToneActivity() {
        _toneActivity.tryEmit(Unit)
    }
}

/**
 * Mock implementation of [MorseAudioPlayer] for capturing played transmissions
 * and verifying half-duplex state during playback.
 */
class MockMorseAudioPlayer(
    var onPlayback: (suspend (String, Int) -> Unit)? = null
) : MorseAudioPlayer {
    data class PlayRecord(val text: String, val wpm: Int)

    val history = mutableListOf<PlayRecord>()

    override suspend fun playTransmission(text: String, wpm: Int) {
        history.add(PlayRecord(text, wpm))
        onPlayback?.invoke(text, wpm)
    }

    fun clear() {
        history.clear()
    }
}

/**
 * JUnit 4 tests for [QsoSessionController] verifying half-duplex muting,
 * conversational loop, error recovery, and silence watchdogs.
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
    fun testHalfDuplexOperationMutesMicrophoneStrictlyDuringTransmission() = runTest {
        var micWasPausedDuringTx = false
        var isMicMutedObservedDuringTx = false

        audioPlayer.onPlayback = { _, _ ->
            micWasPausedDuringTx = dspStream.isPaused
            isMicMutedObservedDuringTx = controller.isMicMuted.value
        }

        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        runCurrent()

        try {
            assertFalse("Mic should not be paused initially", dspStream.isPaused)
            assertFalse("Controller micMuted should be false initially", controller.isMicMuted.value)

            // Trigger transmission by completing CQ in State 1
            dspStream.emitInput("CQ CQ DE M7KQX K")
            runCurrent()

            // Verify half-duplex behavior during transmission
            assertTrue("DSP stream must be paused during bot audio synthesis", micWasPausedDuringTx)
            assertTrue("Controller isMicMuted must be true during bot audio synthesis", isMicMutedObservedDuringTx)

            // Verify microphone is unmuted immediately after playback finishes
            assertFalse("DSP stream must resume listening after synthesis ends", dspStream.isPaused)
            assertFalse("Controller isMicMuted must return to false after synthesis ends", controller.isMicMuted.value)
        } finally {
            sessionJob.cancel()
        }
    }

    @Test
    fun testFullSequentialLoopViaDspStream() = runTest {
        assertEquals(QsoState.ListenCQ, qsoManager.state.value)

        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        runCurrent()

        try {
            // 1. User transmits CQ -> Bot transmits Initial Response
            dspStream.emitInput("CQ CQ DE M7KQX K")
            runCurrent()
            assertEquals(QsoState.InitialResponse, qsoManager.state.value)
            assertEquals(1, audioPlayer.history.size)
            assertEquals("M7KQX DE LZ2BB UR RST IS 599 5NN BK", audioPlayer.history.last().text)

            // 2. User transmits signal report -> Bot transmits Info Exchange
            dspStream.emitInput("599")
            runCurrent()
            assertEquals(QsoState.InfoExchange, qsoManager.state.value)
            assertEquals(2, audioPlayer.history.size)
            assertEquals("M7KQX DE LZ2BB QSL TNX NAME Boyan QTH Sofia BK", audioPlayer.history.last().text)

            // 3. User transmits info ack -> Bot transmits Sign-Off
            dspStream.emitInput("QSL FB")
            runCurrent()
            assertEquals(QsoState.SignOff, qsoManager.state.value)
            assertEquals(3, audioPlayer.history.size)
            assertEquals("TU FER QSO 73 DE LZ2BB SK", audioPlayer.history.last().text)

            // 4. User transmits 73 -> State moves to DitDitFinale
            dspStream.emitInput("73")
            runCurrent()
            assertEquals(QsoState.DitDitFinale, qsoManager.state.value)

            // 5. User transmits final "E E" -> Bot responds "E E"
            dspStream.emitInput("E E")
            runCurrent()
            assertEquals(4, audioPlayer.history.size)
            assertEquals("E E", audioPlayer.history.last().text)

            // Advance virtual time by 5s delay -> Transitions to State 6 (Summary)
            advanceTimeBy(5000L)
            runCurrent()
            assertTrue(qsoManager.state.value is QsoState.Summary)
            val summary = qsoManager.state.value as QsoState.Summary
            assertEquals("M7KQX", summary.userCallsign)
            assertEquals("599", summary.userReport)
        } finally {
            sessionJob.cancel()
        }
    }

    @Test
    fun testSilenceTimeoutInState1TriggersCqEvaluationAndBotResponse() = runTest {
        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        runCurrent()

        try {
            // User transmits CQ without K
            dspStream.emitInput("CQ CQ DE M7KQX")
            runCurrent()
            assertEquals(QsoState.ListenCQ, qsoManager.state.value)
            assertEquals(0, audioPlayer.history.size)

            // Advance time by 5000ms silence
            advanceTimeBy(5000L)
            runCurrent()

            // State advances to InitialResponse and Bot transmits initial response
            assertEquals(QsoState.InitialResponse, qsoManager.state.value)
            assertEquals(1, audioPlayer.history.size)
            assertEquals("M7KQX DE LZ2BB UR RST IS 599 5NN BK", audioPlayer.history.first().text)
        } finally {
            sessionJob.cancel()
        }
    }

    @Test
    fun testGlobalErrorRecoveryEmitsAgnAgnAndMaintainsState() = runTest {
        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        runCurrent()

        try {
            // Start in State 1, transmit valid CQ
            dspStream.emitInput("CQ CQ DE M7KQX K")
            runCurrent()
            assertEquals(QsoState.InitialResponse, qsoManager.state.value)
            audioPlayer.clear()

            // Send incomprehensible input ending in K in State 2 (no report)
            dspStream.emitInput("UNKNOWN GIBBERISH K")
            runCurrent()

            // Bot should transmit error recovery message "AGN AGN?"
            assertEquals(1, audioPlayer.history.size)
            assertEquals("AGN AGN?", audioPlayer.history.first().text)
            // State must remain in InitialResponse
            assertEquals(QsoState.InitialResponse, qsoManager.state.value)

            // Then send valid report -> advances to InfoExchange
            dspStream.emitInput("599")
            runCurrent()
            assertEquals(QsoState.InfoExchange, qsoManager.state.value)
        } finally {
            sessionJob.cancel()
        }
    }

    @Test
    fun testSessionCancellationStopsProcessingAndRestoresListening() = runTest {
        val job = controller.startSession(this, testBotProfile)
        runCurrent()
        assertTrue(job.isActive)

        // Stop session cleanly
        controller.stopSession()
        runCurrent()
        assertFalse(job.isActive)
        assertFalse(controller.isMicMuted.value)
        assertFalse(dspStream.isPaused)

        // Emitting after cancellation must have no effect
        dspStream.emitInput("CQ CQ DE M7KQX K")
        runCurrent()

        assertEquals(0, audioPlayer.history.size)
    }

    @Test
    fun testEmptyBufferSilenceTimeoutDoesNotTriggerErrorRecoveryAndKeepsListening() = runTest {
        val sessionJob = launch {
            controller.startSession(testBotProfile)
        }
        runCurrent()

        try {
            assertEquals(QsoState.ListenCQ, qsoManager.state.value)
            assertTrue(qsoManager.getCqBufferContent().isEmpty())

            // Advance time across multiple silence watchdog cycles (15s = 3x 5s)
            advanceTimeBy(15000L)
            runCurrent()

            // State must remain ListenCQ, no audio played, no error triggered
            assertEquals(QsoState.ListenCQ, qsoManager.state.value)
            assertEquals(0, audioPlayer.history.size)
        } finally {
            sessionJob.cancel()
        }
    }
}
