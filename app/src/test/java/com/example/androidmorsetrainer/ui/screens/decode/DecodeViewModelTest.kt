package com.example.androidmorsetrainer.ui.screens.decode

import com.example.androidmorsetrainer.audio.MorseDSPManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeMorseDSPManager : MorseDSPManager(context = null) {
    var hasPermission: Boolean = true
    var isListeningStarted: Boolean = false
    var isListeningStopped: Boolean = false
    var lastSetFrequency: Double = 700.0

    val fakeDspState = MutableStateFlow(DSPState())
    val fakeToneEvents = MutableSharedFlow<ToneEvent>(extraBufferCapacity = 64)
    val fakeRawAudio = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)

    override val dspState: StateFlow<DSPState> = fakeDspState.asStateFlow()
    override val toneEvents: SharedFlow<ToneEvent> = fakeToneEvents.asSharedFlow()
    override val rawAudioFlow: SharedFlow<ShortArray> = fakeRawAudio.asSharedFlow()

    override fun hasRecordPermission(): Boolean = hasPermission

    override fun setTargetFrequency(frequencyHz: Double) {
        lastSetFrequency = frequencyHz
        targetFrequencyHz = frequencyHz
        fakeDspState.update { it.copy(targetFrequencyHz = frequencyHz) }
    }

    override fun startListening() {
        isListeningStarted = true
        fakeDspState.update { it.copy(isListening = true) }
    }

    override fun stopListening() {
        isListeningStopped = true
        fakeDspState.update { it.copy(isListening = false, isTonePresent = false) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DecodeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDspManager: FakeMorseDSPManager
    private lateinit var viewModel: DecodeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeDspManager = FakeMorseDSPManager()
        viewModel = DecodeViewModel(
            dspManager = fakeDspManager,
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_reflectsManagerDefaults() {
        val state = viewModel.uiState.value
        assertEquals(700.0, state.targetFrequencyHz, 0.01)
        assertFalse(state.isListening)
        assertFalse(state.isTonePresent)
        assertEquals("", state.decodedText)
        assertEquals("", state.currentMorseSymbol)
        assertEquals(DecodeViewModel.MAX_AMPLITUDE_POINTS, state.rawAmplitudes.size)
        assertEquals(DecodeViewModel.MAX_AMPLITUDE_POINTS, viewModel.rawAmplitudes.value.size)
    }

    @Test
    fun startListening_whenPermissionGranted_triggersDspManager() = runTest(testDispatcher) {
        fakeDspManager.hasPermission = true
        viewModel.startListening()
        advanceUntilIdle()

        assertTrue(fakeDspManager.isListeningStarted)
        assertTrue(viewModel.uiState.value.isListening)
    }

    @Test
    fun startListening_whenPermissionDenied_showsUserMessage() = runTest(testDispatcher) {
        fakeDspManager.hasPermission = false
        viewModel.startListening()
        advanceUntilIdle()

        assertFalse(fakeDspManager.isListeningStarted)
        assertFalse(viewModel.uiState.value.isListening)
        assertNotNull(viewModel.uiState.value.userMessage)
    }

    @Test
    fun dspStateUpdates_flowToUiStateAndStateFlows() = runTest(testDispatcher) {
        fakeDspManager.fakeDspState.update {
            it.copy(
                isTonePresent = true,
                currentMagnitude = 0.42,
                spectralPurity = 0.88,
                detectionThreshold = 0.08
            )
        }
        advanceUntilIdle()

        assertTrue(viewModel.isToneDetected.value)
        val state = viewModel.uiState.value
        assertTrue(state.isTonePresent)
        assertEquals(0.42, state.currentMagnitude, 0.001)
        assertEquals(0.88, state.spectralPurity, 0.001)
        assertEquals(0.08, state.detectionThreshold, 0.001)
    }

    @Test
    fun rawAudio_updatesRawAmplitudesStateFlow() = runTest(testDispatcher) {
        // Emit simulated loud sine/square buffer (amplitude ~0.5)
        val pcm = ShortArray(512) { (Short.MAX_VALUE / 2).toShort() }
        fakeDspManager.fakeRawAudio.emit(pcm)
        advanceUntilIdle()

        val amplitudes = viewModel.rawAmplitudes.value
        assertTrue("Recent amplitude should be non-zero", amplitudes.last() > 0.1f)
        assertEquals(DecodeViewModel.MAX_AMPLITUDE_POINTS, amplitudes.size)
    }

    @Test
    fun toneEvents_feedDecoderAndDecodesText() = runTest(testDispatcher) {
        // Send a Dit (60ms) for character "E" (.)
        fakeDspManager.fakeToneEvents.emit(
            MorseDSPManager.ToneEvent(
                isTonePresent = true,
                durationMs = 100,
                magnitude = 0.5,
                timestampMs = System.currentTimeMillis()
            )
        )
        advanceUntilIdle()

        fakeDspManager.fakeToneEvents.emit(
            MorseDSPManager.ToneEvent(
                isTonePresent = false,
                durationMs = 60,
                magnitude = 0.5,
                timestampMs = System.currentTimeMillis()
            )
        )
        advanceUntilIdle()

        // Wait for character space debounce delay (~150ms)
        advanceTimeBy(180)
        advanceUntilIdle()

        assertEquals("E", viewModel.uiState.value.decodedText.trim())
    }

    @Test
    fun clearDecodedText_clearsText() = runTest(testDispatcher) {
        fakeDspManager.fakeToneEvents.emit(
            MorseDSPManager.ToneEvent(
                isTonePresent = false,
                durationMs = 60,
                magnitude = 0.5,
                timestampMs = System.currentTimeMillis()
            )
        )
        advanceTimeBy(180)
        advanceUntilIdle()

        assertEquals("E", viewModel.uiState.value.decodedText.trim())

        viewModel.clearDecodedText()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.decodedText)
    }

    @Test
    fun setTargetFrequency_updatesFrequencyInDspManager() = runTest(testDispatcher) {
        viewModel.setTargetFrequency(800.0)
        advanceUntilIdle()

        assertEquals(800.0, fakeDspManager.lastSetFrequency, 0.01)
        assertEquals(800.0, viewModel.uiState.value.targetFrequencyHz, 0.01)
    }

    @Test
    fun dspState_propagatesAutomaticPitchLockAndFrequency() = runTest(testDispatcher) {
        // Initially not locked
        assertFalse(viewModel.uiState.value.isPitchLocked)
        assertNull(viewModel.uiState.value.lockedFrequencyHz)

        // When DSP detects and locks onto CW tone
        fakeDspManager.fakeDspState.update {
            it.copy(
                isPitchLocked = true,
                lockedFrequencyHz = 640.0,
                targetFrequencyHz = 640.0
            )
        }
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPitchLocked)
        assertEquals(640.0, viewModel.uiState.value.lockedFrequencyHz!!, 0.01)
        assertEquals(640.0, viewModel.uiState.value.targetFrequencyHz, 0.01)
    }

    @Test
    fun dspState_propagatesPitchLockReleaseAfterTimeout() = runTest(testDispatcher) {
        // Lock on first
        fakeDspManager.fakeDspState.update {
            it.copy(
                isPitchLocked = true,
                lockedFrequencyHz = 550.0,
                targetFrequencyHz = 550.0
            )
        }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isPitchLocked)

        // Lock released after signal loss timeout
        fakeDspManager.fakeDspState.update {
            it.copy(
                isPitchLocked = false,
                lockedFrequencyHz = null,
                targetFrequencyHz = 550.0
            )
        }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPitchLocked)
        assertNull(viewModel.uiState.value.lockedFrequencyHz)
        assertEquals(550.0, viewModel.uiState.value.targetFrequencyHz, 0.01)
    }

    @Test
    fun isDecoding_tracksListeningState() = runTest(testDispatcher) {
        assertFalse(viewModel.isDecoding.value)
        assertFalse(viewModel.uiState.value.isDecoding)

        viewModel.startListening()
        advanceUntilIdle()

        assertTrue(viewModel.isDecoding.value)
        assertTrue(viewModel.uiState.value.isDecoding)
        assertTrue(viewModel.uiState.value.isListening)

        viewModel.stopListening()
        advanceUntilIdle()

        assertFalse(viewModel.isDecoding.value)
        assertFalse(viewModel.uiState.value.isDecoding)
        assertFalse(viewModel.uiState.value.isListening)
    }

    @Test
    fun activeDspManager_preservedAndAccessible() {
        assertEquals(fakeDspManager, viewModel.activeDspManager)
        assertEquals(fakeDspManager, viewModel.dspManager)
    }

    @Test
    fun initialization_preservesActiveDspManagerState() = runTest(testDispatcher) {
        // Given an active DSP manager already capturing audio
        fakeDspManager.startListening()
        assertTrue(fakeDspManager.dspState.value.isListening)

        // When a new ViewModel is created with the active manager (e.g. Activity recreation)
        val newViewModel = DecodeViewModel(
            dspManager = fakeDspManager,
            defaultDispatcher = testDispatcher
        )

        // Then isDecoding and isListening are preserved as active
        assertTrue(newViewModel.isDecoding.value)
        assertTrue(newViewModel.uiState.value.isDecoding)
        assertTrue(newViewModel.uiState.value.isListening)
        assertEquals(fakeDspManager, newViewModel.activeDspManager)
    }

    @Test
    fun onCleared_releasesDspAudioResources() = runTest(testDispatcher) {
        viewModel.startListening()
        advanceUntilIdle()
        assertTrue(fakeDspManager.isListeningStarted)

        // Simulating ViewModel clearance when user definitively leaves/exits screen
        viewModel.onCleared()
        advanceUntilIdle()

        assertTrue(fakeDspManager.isListeningStopped)
        assertFalse(viewModel.isDecoding.value)
        assertFalse(viewModel.uiState.value.isListening)
    }
}

