package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JUnit 4 tests for [QsoManager] and [QsoState] transitions
 * following the 6-state sequential conversational loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QsoStateMachineTest {

    private lateinit var qsoManager: QsoManager

    @Before
    fun setUp() {
        qsoManager = QsoManager()
    }

    @Test
    fun testGoldenPath_sequentialTransitionsThroughAll6States() {
        // 1. Initial State: State 1 (Listen CQ)
        assertEquals(QsoState.ListenCQ, qsoManager.state.value)
        assertEquals("M7KQX", qsoManager.userCallsign)
        assertNull(qsoManager.remoteReport)

        // 2. User transmits CQ ending in 'K' -> State 2 (Initial Response)
        qsoManager.processInput("CQ CQ DE M7KQX K")
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)
        assertEquals("M7KQX", qsoManager.userCallsign)

        // 3. User transmits RST report "599" -> State 3 (Info Exchange)
        qsoManager.processInput("599")
        assertEquals(QsoState.InfoExchange, qsoManager.state.value)
        assertEquals("599", qsoManager.remoteReport)

        // 4. User transmits exchange acknowledgement "QSL FB" -> State 4 (Sign Off)
        qsoManager.processInput("QSL FB")
        assertEquals(QsoState.SignOff, qsoManager.state.value)

        // 5. User transmits sign-off acknowledgement "73" -> State 5 (Dit Dit Finale)
        qsoManager.processInput("73")
        assertEquals(QsoState.DitDitFinale, qsoManager.state.value)

        // 6. User transmits final "E E" or silence timeout completes QSO -> State 6 (Summary)
        qsoManager.completeQso()
        assertTrue(qsoManager.state.value is QsoState.Summary)
        val summary = qsoManager.state.value as QsoState.Summary
        assertEquals("M7KQX", summary.userCallsign)
        assertEquals("599", summary.userReport)
        assertTrue(summary.isCompleted)
    }

    @Test
    fun testSilenceTimeoutInState1AdvancesOnValidCqWithoutK() {
        assertEquals(QsoState.ListenCQ, qsoManager.state.value)

        // User transmits CQ without 'K'
        qsoManager.processInput("CQ CQ DE G4ABC")
        assertEquals(QsoState.ListenCQ, qsoManager.state.value)

        // 5 seconds of silence pass without CW -> triggers evaluateCq()
        qsoManager.onSilenceTimeout()
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)
        assertEquals("G4ABC", qsoManager.userCallsign)
    }

    @Test
    fun testGlobalErrorRecoveryInState1OnIncomprehensibleCq() = runTest {
        val emittedErrors = mutableListOf<String>()
        val job = launch {
            qsoManager.errorRecoveryFlow.collect { emittedErrors.add(it) }
        }
        runCurrent()

        // Noise/gibberish ending with K
        qsoManager.processInput("ZZZZZZ K")
        runCurrent()

        // Bot triggers "AGN AGN?" and returns/stays in State 1
        assertEquals(QsoState.ListenCQ, qsoManager.state.value)
        assertEquals(1, emittedErrors.size)
        assertEquals("AGN AGN?", emittedErrors.first())

        job.cancel()
    }

    @Test
    fun testGlobalErrorRecoveryInState2OnIncomprehensibleReport() = runTest {
        qsoManager.processInput("CQ CQ DE M7KQX K")
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)

        val emittedErrors = mutableListOf<String>()
        val job = launch {
            qsoManager.errorRecoveryFlow.collect { emittedErrors.add(it) }
        }
        runCurrent()

        // Send non-report input ending in K (or trigger via silence timeout)
        qsoManager.processInput("HELLO THERE K")
        runCurrent()

        // Stays in State 2 and emits AGN AGN?
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)
        assertEquals(1, emittedErrors.size)
        assertEquals("AGN AGN?", emittedErrors.first())

        // Now send valid report -> advances to State 3
        qsoManager.processInput("599")
        assertEquals(QsoState.InfoExchange, qsoManager.state.value)

        job.cancel()
    }

    @Test
    fun testGlobalErrorRecoveryInState3OnIncomprehensibleAck() = runTest {
        qsoManager.processInput("CQ CQ DE M7KQX K")
        qsoManager.processInput("599")
        assertEquals(QsoState.InfoExchange, qsoManager.state.value)

        val emittedErrors = mutableListOf<String>()
        val job = launch {
            qsoManager.errorRecoveryFlow.collect { emittedErrors.add(it) }
        }
        runCurrent()

        // Send invalid ack ending in K
        qsoManager.processInput("RANDOM NOISE K")
        runCurrent()

        assertEquals(QsoState.InfoExchange, qsoManager.state.value)
        assertEquals(1, emittedErrors.size)

        // Now send valid ack with keyword FB
        qsoManager.processInput("FB")
        assertEquals(QsoState.SignOff, qsoManager.state.value)

        job.cancel()
    }

    @Test
    fun testSignalReportWithCutNumbers() {
        qsoManager.processInput("CQ CQ DE W1AW K")
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)

        // 5NN is standard Morse cut number for 599
        qsoManager.processInput("5NN")
        assertEquals(QsoState.InfoExchange, qsoManager.state.value)
        assertEquals("5NN", qsoManager.remoteReport)
    }

    @Test
    fun testDitDitFinaleTriggersDitDitFlow() = runTest {
        qsoManager.processInput("CQ CQ DE M7KQX K")
        qsoManager.processInput("599")
        qsoManager.processInput("QSL FB")
        qsoManager.processInput("73")
        assertEquals(QsoState.DitDitFinale, qsoManager.state.value)

        var ditDitReceived = false
        val job = launch {
            qsoManager.ditDitReceivedFlow.collect { ditDitReceived = true }
        }
        runCurrent()

        // Send "E E"
        qsoManager.processInput("E E")
        runCurrent()

        assertTrue("Dit dit flow should have emitted", ditDitReceived)
        job.cancel()
    }

    @Test
    fun testSilenceTimeoutInState5CompletesQso() {
        qsoManager.processInput("CQ CQ DE M7KQX K")
        qsoManager.processInput("599")
        qsoManager.processInput("QSL FB")
        qsoManager.processInput("73")
        assertEquals(QsoState.DitDitFinale, qsoManager.state.value)

        // 5s of silence with no "E E"
        qsoManager.onSilenceTimeout()
        assertTrue(qsoManager.state.value is QsoState.Summary)
    }

    @Test
    fun testResetClearsStateAndBuffers() {
        qsoManager.processInput("CQ CQ DE M7KQX K")
        qsoManager.processInput("599")
        qsoManager.processInput("QSL FB")
        qsoManager.processInput("73")
        qsoManager.completeQso()
        assertTrue(qsoManager.state.value is QsoState.Summary)

        qsoManager.reset()
        assertEquals(QsoState.ListenCQ, qsoManager.state.value)
        assertEquals("M7KQX", qsoManager.userCallsign)
        assertNull(qsoManager.remoteReport)
        assertEquals("", qsoManager.getCqBufferContent())
    }

    @Test
    fun testNoiseFloorFailureTriggeringAgnAgnInState1() = runTest {
        val emittedErrors = mutableListOf<String>()
        val job = launch {
            qsoManager.errorRecoveryFlow.collect { emittedErrors.add(it) }
        }
        runCurrent()

        // Noise floor failure (5+ repeating dits/dahs)
        qsoManager.processInput("EEEEE")
        qsoManager.onSilenceTimeout()
        runCurrent()

        assertEquals(1, emittedErrors.size)
        assertEquals("AGN AGN?", emittedErrors.first())
        assertEquals(QsoState.ListenCQ, qsoManager.state.value)

        job.cancel()
    }

    @Test
    fun testSilenceTimeoutInState2WithGibberishTriggersErrorRecovery() = runTest {
        qsoManager.processInput("CQ CQ DE M7KQX K")
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)

        val emittedErrors = mutableListOf<String>()
        val job = launch {
            qsoManager.errorRecoveryFlow.collect { emittedErrors.add(it) }
        }
        runCurrent()

        // User transmits non-report input without K
        qsoManager.processInput("NO REPORT HERE")
        runCurrent()
        assertEquals(0, emittedErrors.size)

        // 5s silence timeout fires
        qsoManager.onSilenceTimeout()
        runCurrent()

        assertEquals(1, emittedErrors.size)
        assertEquals("AGN AGN?", emittedErrors.first())
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)

        job.cancel()
    }

    @Test
    fun testKeywordsAcrossStates() {
        // State 2: RST keyword
        qsoManager.processInput("CQ CQ DE M7KQX K")
        assertEquals(QsoState.InitialResponse, qsoManager.state.value)
        qsoManager.processInput("UR RST 579")
        assertEquals(QsoState.InfoExchange, qsoManager.state.value)

        // State 3: NAME keyword
        qsoManager.processInput("NAME BOYAN")
        assertEquals(QsoState.SignOff, qsoManager.state.value)

        // State 4: GB keyword
        qsoManager.processInput("GB OM")
        assertEquals(QsoState.DitDitFinale, qsoManager.state.value)
    }
}
