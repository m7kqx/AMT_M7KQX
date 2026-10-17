package com.example.androidmorsetrainer.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class BatchQsoAudioProcessorTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dspManager: MorseDSPManager
    private lateinit var processor: BatchQsoAudioProcessor
    private lateinit var audioGen: MorseAudioGenerator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dspManager = MorseDSPManager()
        processor = BatchQsoAudioProcessor(
            dspManager = dspManager,
            scope = CoroutineScope(testDispatcher),
            defaultDispatcher = testDispatcher
        )
        audioGen = MorseAudioGenerator(sampleRate = 44100, frequencyHz = 700, wpm = 20)
    }

    @After
    fun tearDown() {
        processor.stop()
        Dispatchers.resetMain()
    }

    private fun generatePcmBytes(pattern: String): ByteArray {
        val samples = audioGen.generateMorsePatternBuffer(
            pattern = pattern,
            speedWpm = 20,
            initialSilenceMs = 50L
        )
        val trailingSilence = ShortArray(8820)
        val combined = ShortArray(samples.size + trailingSilence.size)
        System.arraycopy(samples, 0, combined, 0, samples.size)
        System.arraycopy(trailingSilence, 0, combined, samples.size, trailingSilence.size)

        val bytes = ByteArray(combined.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(combined)
        return bytes
    }

    @Test
    fun testRecordPhaseDoesNotEmitCharacters() = runTest(testDispatcher) {
        val collected = mutableListOf<String>()
        val job = launch {
            processor.characterFlow.collect { collected.add(it) }
        }
        advanceUntilIdle()

        // Ingest audio during record phase
        val bytes = generatePcmBytes("-.-")
        processor.recordAudioForTest(bytes)
        advanceUntilIdle()

        // Must NOT emit anything during recording phase
        assertTrue(collected.isEmpty())
        assertEquals("Listening to your transmission...", processor.statusText.value)

        job.cancel()
    }

    @Test
    fun testProcessPhaseDecodesCompleteStringOnSilenceTimeout() = runTest(testDispatcher) {
        val collected = mutableListOf<String>()
        val job = launch {
            processor.characterFlow.collect { collected.add(it) }
        }
        advanceUntilIdle()

        val bytes = generatePcmBytes("-.-. --.-") // "CQ"
        processor.recordAudioForTest(bytes)
        advanceUntilIdle()

        // 5-second silence timeout triggers Process Phase
        val result = processor.onSilenceTimeout()
        advanceUntilIdle()

        assertEquals("CQ", result)
        assertEquals(1, collected.size)
        assertEquals("CQ", collected.first())
        assertFalse(processor.isProcessing.value)
        assertEquals("Listening to your transmission...", processor.statusText.value)

        job.cancel()
    }

    @Test
    fun testProcessPhaseWithEmptyBufferReturnsEmptyAndDoesNotEmit() = runTest(testDispatcher) {
        val collected = mutableListOf<String>()
        val job = launch {
            processor.characterFlow.collect { collected.add(it) }
        }
        advanceUntilIdle()

        val result = processor.onSilenceTimeout()
        advanceUntilIdle()

        assertEquals("", result)
        assertTrue(collected.isEmpty())
        assertFalse(processor.isProcessing.value)

        job.cancel()
    }

    @Test
    fun testHalfDuplexPauseAndResume() {
        assertTrue(processor.isListening.value)

        processor.pauseListening()
        assertFalse(processor.isListening.value)
        assertTrue(processor.isListeningPaused)
        assertEquals("Microphone Muted (Bot Transmitting)", processor.statusText.value)

        processor.resumeListening()
        assertTrue(processor.isListening.value)
        assertFalse(processor.isListeningPaused)
        assertEquals("Listening to your transmission...", processor.statusText.value)
    }

    @Test
    fun testUpdateNoiseFloorUpdatesSquelchThreshold() {
        processor.updateNoiseFloor(0.120f)
        assertEquals(0.120f, processor.telemetry.value.noiseFloorThreshold, 0.001f)
    }
}
