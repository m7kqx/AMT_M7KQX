package com.example.androidmorsetrainer.morse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JUnit 4 tests for [QsoManager] and [QsoState] transitions.
 */
class QsoStateMachineTest {

    private lateinit var qsoManager: QsoManager

    @Before
    fun setUp() {
        qsoManager = QsoManager()
    }

    @Test
    fun testGoldenPath_sequentialTransitionsFromIdleToLoggingContact() {
        // 1. Initial State: Idle
        assertEquals(QsoState.Idle, qsoManager.state.value)
        assertNull(qsoManager.remoteCallsign)
        assertNull(qsoManager.remoteReport)

        // 2. Inject "CQ" -> TransmittingCQ
        qsoManager.processInput("CQ")
        assertEquals(QsoState.TransmittingCQ, qsoManager.state.value)

        // 3. Inject "K" (invitation to transmit) -> ListeningForCaller
        qsoManager.processInput("K")
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)

        // 4. Inject caller callsign "M7KQX" -> Exchanging(AWAITING_REPORT)
        qsoManager.processInput("M7KQX")
        assertEquals(QsoState.Exchanging(ExchangeStage.AWAITING_REPORT), qsoManager.state.value)
        assertEquals("M7KQX", qsoManager.remoteCallsign)

        // 5. Inject signal report "599" -> Exchanging(COMPLETED)
        qsoManager.processInput("599")
        assertEquals(QsoState.Exchanging(ExchangeStage.COMPLETED), qsoManager.state.value)
        assertEquals("599", qsoManager.remoteReport)

        // 6. Inject sign-off "73" -> LoggingContact
        qsoManager.processInput("73")
        assertEquals(QsoState.LoggingContact, qsoManager.state.value)
    }

    @Test
    fun testFullCqTransmissionDirectlyToListeningForCaller() {
        // A full transmission ending in 'K' switches from Idle straight to ListeningForCaller
        qsoManager.processInput("CQ CQ DE M7KQX K")
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)
    }

    @Test
    fun testCallerWithDePrefix() {
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)

        // Caller sends "DE G4ABC K"
        qsoManager.processInput("DE G4ABC K")
        assertEquals(QsoState.Exchanging(ExchangeStage.AWAITING_REPORT), qsoManager.state.value)
        assertEquals("G4ABC", qsoManager.remoteCallsign)
    }

    @Test
    fun testExchangeFromAwaitingCallsignStage() {
        val manager = QsoManager(QsoState.Exchanging(ExchangeStage.AWAITING_CALLSIGN))
        assertEquals(QsoState.Exchanging(ExchangeStage.AWAITING_CALLSIGN), manager.state.value)

        manager.processInput("W1AW")
        assertEquals(QsoState.Exchanging(ExchangeStage.AWAITING_REPORT), manager.state.value)
        assertEquals("W1AW", manager.remoteCallsign)
    }

    @Test
    fun testSignalReportWithCutNumbers() {
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        qsoManager.processInput("M7KQX")
        assertEquals(QsoState.Exchanging(ExchangeStage.AWAITING_REPORT), qsoManager.state.value)

        // 5NN is Morse contest shorthand for 599
        qsoManager.processInput("5NN")
        assertEquals(QsoState.Exchanging(ExchangeStage.COMPLETED), qsoManager.state.value)
        assertEquals("5NN", qsoManager.remoteReport)
    }

    @Test
    fun testCombinedReportAndSignOffJumpsToLoggingContact() {
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        qsoManager.processInput("M7KQX")

        // Report and TU in same buffer
        qsoManager.processInput("UR 599 TU")
        assertEquals(QsoState.LoggingContact, qsoManager.state.value)
        assertEquals("599", qsoManager.remoteReport)
    }

    @Test
    fun testInvalidInputDoesNotTriggerUnexpectedTransition() {
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)

        // Non-callsign noise shouldn't advance to Exchanging
        qsoManager.processInput("HELLO")
        assertEquals(QsoState.ListeningForCaller, qsoManager.state.value)
        assertNull(qsoManager.remoteCallsign)
    }

    @Test
    fun testSignOffVariants() {
        for (signOff in listOf("TU", "SK", "QSL", "73")) {
            val manager = QsoManager(QsoState.Exchanging(ExchangeStage.COMPLETED))
            manager.processInput(signOff)
            assertEquals("Failed for sign-off: $signOff", QsoState.LoggingContact, manager.state.value)
        }
    }

    @Test
    fun testResetClearsStateAndBuffers() {
        qsoManager.processInput("CQ")
        qsoManager.processInput("K")
        qsoManager.processInput("M7KQX")
        qsoManager.processInput("599")
        qsoManager.processInput("73")
        assertEquals(QsoState.LoggingContact, qsoManager.state.value)

        qsoManager.processInput("LOG")
        assertEquals(QsoState.Idle, qsoManager.state.value)
        assertNull(qsoManager.remoteCallsign)
        assertNull(qsoManager.remoteReport)
    }
}
