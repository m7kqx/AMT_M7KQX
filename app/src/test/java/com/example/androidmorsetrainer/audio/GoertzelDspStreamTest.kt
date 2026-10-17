package com.example.androidmorsetrainer.audio

import com.example.androidmorsetrainer.morse.DynamicTimingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoertzelDspStreamTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dspManager: MorseDSPManager
    private lateinit var timingEngine: DynamicTimingEngine
    private lateinit var dspStream: GoertzelDspStream

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dspManager = MorseDSPManager()
        timingEngine = DynamicTimingEngine(initialWpm = 20)
        dspStream = GoertzelDspStream(
            dspManager = dspManager,
            timingEngine = timingEngine,
            scope = CoroutineScope(testDispatcher),
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        dspStream.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun testEmitManualProducesValueInCharacterFlow() = runTest(testDispatcher) {
        val collected = mutableListOf<String>()
        val job = launch {
            dspStream.characterFlow.collect { collected.add(it) }
        }
        advanceUntilIdle()

        dspStream.emitManual("CQ")
        advanceUntilIdle()

        assertEquals(1, collected.size)
        assertEquals("CQ", collected.first())

        job.cancel()
    }

    @Test
    fun testKeyToneEndedClassifiesDitAndDahUsingTimingEngine() {
        // At 20 WPM, dit = 60ms, dah = 180ms
        dspStream.onKeyToneEnded(durationMs = 60L, timestampMs = 1000L)
        assertEquals(".", dspStream.getCurrentSymbol())

        dspStream.onKeyToneEnded(durationMs = 180L, timestampMs = 1200L)
        assertEquals(".-", dspStream.getCurrentSymbol())
    }

    @Test
    fun testKeyToneStartedDetectsCharacterAndWordBoundaries() {
        // Form 'E' (.)
        dspStream.onKeyToneEnded(durationMs = 60L, timestampMs = 1000L)
        assertEquals(".", dspStream.getCurrentSymbol())

        // Silence of 200ms (character space at 20 WPM > 108ms)
        dspStream.onKeyToneStarted(timestampMs = 1200L)

        // Previous symbol should be committed as character 'E'
        assertEquals("", dspStream.getCurrentSymbol())
        assertEquals("E", dspStream.getCurrentWord())

        // Form 'T' (-)
        dspStream.onKeyToneEnded(durationMs = 180L, timestampMs = 1380L)

        // Silence of 500ms (word space > 270ms)
        dspStream.onKeyToneStarted(timestampMs = 1880L)

        // Word should be committed (ET)
        assertEquals("", dspStream.getCurrentSymbol())
        assertEquals("", dspStream.getCurrentWord())
    }

    @Test
    fun testWatchdogEmitsDecodedWordOnKeyingCompletion() = runTest(testDispatcher) {
        val collected = mutableListOf<String>()
        val job = launch {
            dspStream.characterFlow.collect { collected.add(it) }
        }
        advanceUntilIdle()

        // Send 'K' (- . -)
        // Dah
        dspStream.onKeyToneStarted(1000L)
        dspStream.onKeyToneEnded(180L, 1180L)

        // Element space 60ms
        dspStream.onKeyToneStarted(1240L)
        dspStream.onKeyToneEnded(60L, 1300L)

        // Element space 60ms
        dspStream.onKeyToneStarted(1360L)
        dspStream.onKeyToneEnded(180L, 1540L)

        assertEquals("-.-", dspStream.getCurrentSymbol())

        // Advance virtual clock past character & word space thresholds (~300ms)
        advanceTimeBy(350L)
        advanceUntilIdle()

        // Watchdog should have committed 'K' and emitted it
        assertEquals(1, collected.size)
        assertEquals("K", collected.first())

        job.cancel()
    }

    @Test
    fun testTelemetryReflectsToneDurationsAndTimingEngineAverage() {
        dspStream.onKeyToneStarted(1000L)
        dspStream.onKeyToneEnded(durationMs = 80L, timestampMs = 1080L)
        dspStream.onKeyToneStarted(1180L)

        val telemetry = dspStream.telemetry.value
        assertEquals(80L, telemetry.lastToneDurationMs)
        assertEquals(100L, telemetry.lastSilenceDurationMs)
        assertTrue(telemetry.currentDitAverageMs > 0f)
    }

    @Test
    fun testUpdateNoiseFloorUpdatesSquelchThresholdInTelemetry() {
        dspStream.updateNoiseFloor(0.125f)
        assertEquals(0.125f, dspStream.telemetry.value.noiseFloorThreshold, 0.001f)
    }

    @Test
    fun testStopHaltsProcessingAndClearsState() {
        dspStream.onKeyToneEnded(60L)
        dspStream.stop()
        assertEquals("", dspStream.getCurrentSymbol())
        assertEquals("", dspStream.getCurrentWord())
        assertEquals("", dspStream.telemetry.value.currentElementBuffer)
    }

    @Test
    fun testCurrentElementBufferInTelemetryUpdatesOnElementsAndClearsOnCharacterBoundary() = runTest(testDispatcher) {
        // Initially empty
        assertEquals("", dspStream.telemetry.value.currentElementBuffer)

        // 1. Dit (60ms)
        dspStream.onKeyToneStarted(1000L)
        dspStream.onKeyToneEnded(durationMs = 60L, timestampMs = 1060L)
        assertEquals(".", dspStream.telemetry.value.currentElementBuffer)

        // 2. Element space (60ms), then Dah (180ms)
        dspStream.onKeyToneStarted(1120L)
        dspStream.onKeyToneEnded(durationMs = 180L, timestampMs = 1300L)
        assertEquals(".-", dspStream.telemetry.value.currentElementBuffer)

        // 3. Advance virtual clock past character space threshold (~110ms at 20 WPM)
        advanceTimeBy(150L)
        runCurrent()

        // Character 'A' committed, element buffer must be cleared back to empty
        assertEquals("", dspStream.telemetry.value.currentElementBuffer)
        assertEquals("A", dspStream.getCurrentWord())
    }

    @Test
    fun testPauseListeningClearsElementBufferInTelemetry() {
        dspStream.onKeyToneEnded(60L)
        assertEquals(".", dspStream.telemetry.value.currentElementBuffer)

        dspStream.pauseListening()
        assertEquals("", dspStream.telemetry.value.currentElementBuffer)
    }
}
