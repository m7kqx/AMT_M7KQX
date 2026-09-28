package com.example.androidmorsetrainer.morse

import com.example.androidmorsetrainer.audio.GoertzelDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MorseEngineUnitTest {

    @Test
    fun kochSequence_containsExact43Characters() {
        val manager = KochMethodManager()
        assertEquals(43, manager.sequence.size)
        assertEquals("K", manager.sequence[0])
        assertEquals("M", manager.sequence[1])
        assertEquals("R", manager.sequence[2])
        assertEquals("<BT>", manager.sequence[40])
        assertEquals("<SK>", manager.sequence[41])
        assertEquals("<AR>", manager.sequence[42])
    }

    @Test
    fun kochSequence_levelProgression() {
        val manager = KochMethodManager()
        // Level 1 starts with 2 characters (K, M)
        val level1 = manager.getCharactersForLevel(1)
        assertEquals(listOf("K", "M"), level1)

        // Level 2 has 3 characters (K, M, R)
        val level2 = manager.getCharactersForLevel(2)
        assertEquals(listOf("K", "M", "R"), level2)

        // Max level has all 43 characters
        val maxLevel = manager.getCharactersForLevel(manager.maxLevel)
        assertEquals(43, maxLevel.size)
    }

    @Test
    fun morseDictionary_lookupMatchesExpectations() {
        val manager = KochMethodManager()
        assertEquals("-.-", manager.getMorseCode("K"))
        assertEquals("--", manager.getMorseCode("M"))
        assertEquals(".-", manager.getMorseCode("A"))
        assertEquals("-...-", manager.getMorseCode("<BT>"))
        assertEquals("...-.-", manager.getMorseCode("<SK>"))
        assertEquals(".-.-.", manager.getMorseCode("<AR>"))

        assertEquals("K", manager.getCharacterForMorse("-.-"))
        assertEquals("<BT>", manager.getCharacterForMorse("-...-"))
    }

    @Test
    fun adaptiveWeighting_updatesCorrectly() {
        val manager = KochMethodManager()

        // Correct response decreases weight towards baseline (1.0f)
        val decreased = manager.calculateUpdatedWeight(2.0f, wasCorrect = true)
        assertTrue(decreased < 2.0f)
        assertTrue(decreased >= 1.0f)

        // Incorrect response increases weight
        val increased = manager.calculateUpdatedWeight(1.0f, wasCorrect = false)
        assertTrue(increased > 1.0f)
    }

    @Test
    fun parisTiming_twentyWpmCalculations() {
        // At 20 WPM: 1200 / 20 = 60ms
        assertEquals(60L, MorseConstants.calculateUnitDurationMs(20))
        assertEquals(180L, MorseConstants.calculateDahDurationMs(20))
        assertEquals(60L, MorseConstants.calculateElementSpaceMs(20))
        assertEquals(180L, MorseConstants.calculateCharacterSpaceMs(20))
        assertEquals(420L, MorseConstants.calculateWordSpaceMs(20))
    }

    @Test
    fun goertzelDetector_detectsTargetFrequencyAccurately() {
        val sampleRate = 44100
        val targetFreq = 700.0
        val blockSize = 512
        val detector = GoertzelDetector(sampleRate, targetFreq, blockSize)

        // Generate pure 700Hz sine wave block
        val toneSamples = ShortArray(blockSize)
        for (i in 0 until blockSize) {
            val t = i.toDouble() / sampleRate
            val sample = (Short.MAX_VALUE * 0.8 * sin(2.0 * Math.PI * targetFreq * t)).toInt()
            toneSamples[i] = sample.toShort()
        }

        val toneResult = detector.process(toneSamples)
        assertTrue("Target magnitude should be high for 700Hz", toneResult.targetMagnitude > 0.5)
        assertTrue("Spectral purity should be high for 700Hz", toneResult.spectralPurity > 0.8)

        // Generate 1500Hz off-target sine wave block
        val offTargetSamples = ShortArray(blockSize)
        for (i in 0 until blockSize) {
            val t = i.toDouble() / sampleRate
            val sample = (Short.MAX_VALUE * 0.8 * sin(2.0 * Math.PI * 1500.0 * t)).toInt()
            offTargetSamples[i] = sample.toShort()
        }

        val offTargetResult = detector.process(offTargetSamples)
        assertTrue("Target magnitude should be near zero for 1500Hz", offTargetResult.targetMagnitude < 0.05)
        assertTrue("Spectral purity should be near zero for 1500Hz", offTargetResult.spectralPurity < 0.05)
    }
}
