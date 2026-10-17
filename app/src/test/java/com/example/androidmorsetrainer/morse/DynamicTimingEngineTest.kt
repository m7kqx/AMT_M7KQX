package com.example.androidmorsetrainer.morse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DynamicTimingEngineTest {

    private lateinit var engine: DynamicTimingEngine

    @Before
    fun setUp() {
        engine = DynamicTimingEngine(initialWpm = 20)
    }

    @Test
    fun testInitialThresholdsAt20Wpm() {
        val thresholds = engine.getThresholds()
        // 20 WPM standard PARIS dit = 1200 / 20 = 60ms
        assertEquals(60.0, thresholds.ditDurationMs, 0.01)

        // ±35% tolerances
        assertEquals(39.0, thresholds.ditMinMs, 0.01)   // 60 * 0.65
        assertEquals(81.0, thresholds.ditMaxMs, 0.01)   // 60 * 1.35
        assertEquals(117.0, thresholds.dahMinMs, 0.01)  // 60 * 1.95
        assertEquals(243.0, thresholds.dahMaxMs, 0.01)  // 60 * 4.05
        assertEquals(108.0, thresholds.charSpaceThresholdMs, 0.01) // 60 * 1.8
        assertEquals(270.0, thresholds.wordSpaceThresholdMs, 0.01) // 60 * 4.5
    }

    @Test
    fun testDitAndDahClassification() {
        val thresholds = engine.getThresholds()

        // 60ms should be a dit
        assertTrue(thresholds.isDit(60L))
        assertFalse(thresholds.isDah(60L))

        // 180ms should be a dah
        assertTrue(thresholds.isDah(180L))
        assertFalse(thresholds.isDit(180L))
    }

    @Test
    fun testSpaceBoundaryClassification() {
        val thresholds = engine.getThresholds()

        // 60ms silence -> intra-character element space (not char or word space)
        assertFalse(thresholds.isCharacterSpace(60L))
        assertFalse(thresholds.isWordSpace(60L))

        // 180ms silence -> character space
        assertTrue(thresholds.isCharacterSpace(180L))
        assertFalse(thresholds.isWordSpace(180L))

        // 350ms silence -> word space
        assertFalse(thresholds.isCharacterSpace(350L))
        assertTrue(thresholds.isWordSpace(350L))
    }

    @Test
    fun testOutlierFilteringRejectsLongDahs() {
        val initialDit = engine.rollingDitMs
        assertEquals(60.0, initialDit, 0.01)

        // Inject an abnormally long dah of 800ms (> dahMax of 243ms)
        engine.recordElement(800L)

        // Rolling dit duration must NOT be skewed by the outlier
        assertEquals("Rolling dit average must not be skewed by long dah outlier", initialDit, engine.rollingDitMs, 0.01)
    }

    @Test
    fun testOutlierFilteringRejectsTransientNoiseClicks() {
        val initialDit = engine.rollingDitMs

        // Inject transient click of 8ms (< 15ms)
        engine.recordElement(8L)

        assertEquals("Rolling dit average must not be affected by transient glitches", initialDit, engine.rollingDitMs, 0.01)
    }

    @Test
    fun testAdaptationWithValidFasterDits() {
        // Record several valid faster dits (50ms ~ 24 WPM)
        repeat(10) {
            engine.recordElement(50L)
        }

        // Rolling dit should adapt towards 50ms
        assertTrue("Rolling dit should adapt downwards from 60ms", engine.rollingDitMs < 58.0)
        assertTrue("Rolling dit should stay above 49ms", engine.rollingDitMs >= 50.0)
    }

    @Test
    fun testAdaptationWithValidDahs() {
        val initialDit = engine.rollingDitMs

        // Record standard dahs (180ms -> normalized dit = 60ms)
        repeat(5) {
            engine.recordElement(180L)
        }

        // Average should remain around 60ms
        assertEquals(initialDit, engine.rollingDitMs, 0.1)
    }
}
