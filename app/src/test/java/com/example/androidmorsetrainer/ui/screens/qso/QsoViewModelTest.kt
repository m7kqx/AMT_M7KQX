package com.example.androidmorsetrainer.ui.screens.qso

import com.example.androidmorsetrainer.morse.QsoBot
import com.example.androidmorsetrainer.morse.QsoManager
import com.example.androidmorsetrainer.morse.QsoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class QsoViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var qsoManager: QsoManager
    private lateinit var qsoBot: QsoBot
    private lateinit var viewModel: QsoViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        qsoManager = QsoManager()
        qsoBot = QsoBot(userCallsign = "M7KQX", random = Random(1234))
        val dsp = StubDspCharacterStream()
        val audio = StubMorseAudioPlayer()
        viewModel = QsoViewModel(
            qsoManager = qsoManager,
            qsoBot = qsoBot,
            dspStream = dsp,
            audioPlayer = audio
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitializationStartsSessionWithValidProfile() = runTest {
        advanceUntilIdle()

        // 1. Initial State should be State 1: ListenCQ
        assertEquals(QsoState.ListenCQ, viewModel.qsoState.value)

        // 2. Active profile is populated
        val profile = viewModel.activeProfile.value
        assertNotNull("Active operator profile should be loaded", profile)
        assertTrue(profile!!.wpm in 12..22)

        // 3. Transcript has initialization entries
        assertTrue(viewModel.transcript.value.isNotEmpty())
        assertTrue(viewModel.transcript.value.first().contains("New QSO with"))
    }

    @Test
    fun testStartNewQsoGeneratesNewProfileAndResetsFsm() = runTest {
        advanceUntilIdle()

        // Advance FSM into InitialResponse
        viewModel.simulateInput("CQ CQ DE M7KQX K")
        advanceUntilIdle()
        assertEquals(QsoState.InitialResponse, viewModel.qsoState.value)

        // Trigger new QSO
        viewModel.startNewQso()
        advanceUntilIdle()

        // State must reset to ListenCQ
        assertEquals(QsoState.ListenCQ, viewModel.qsoState.value)
        assertNotNull(viewModel.activeProfile.value)
    }

    @Test
    fun testSequentialTransitionsViaSimulateInputAndTranscript() = runTest {
        advanceUntilIdle()

        // Send CQ
        viewModel.simulateInput("CQ CQ DE M7KQX K")
        advanceUntilIdle()
        assertEquals(QsoState.InitialResponse, viewModel.qsoState.value)
        assertTrue(viewModel.transcript.value.any { it.contains("TX (You): CQ CQ DE M7KQX K") })
        // Bot should transmit initial response
        assertTrue(viewModel.transcript.value.any { it.startsWith("RX") })

        // Send signal report
        viewModel.simulateInput("599")
        advanceUntilIdle()
        assertEquals(QsoState.InfoExchange, viewModel.qsoState.value)

        // Send info ACK
        viewModel.simulateInput("QSL FB")
        advanceUntilIdle()
        assertEquals(QsoState.SignOff, viewModel.qsoState.value)
    }

    @Test
    fun testClearTranscript() = runTest {
        advanceUntilIdle()
        assertTrue(viewModel.transcript.value.isNotEmpty())

        viewModel.clearTranscript()
        assertEquals(0, viewModel.transcript.value.size)
    }
}
