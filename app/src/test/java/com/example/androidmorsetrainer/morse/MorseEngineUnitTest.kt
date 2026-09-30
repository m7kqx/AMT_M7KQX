package com.example.androidmorsetrainer.morse

import com.example.androidmorsetrainer.audio.GoertzelDetector
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun v17PriorityWeighting_calculatesDeprivationAndAccuracyBonuses() {
        val manager = KochMethodManager()

        // Deprived character (0 session attempts, 0 global attempts):
        // Base (1.0) + Deprived (+20) + New letter (+30) + Inaccuracy ((100 - 0) * 0.5 = +50) = 101.0
        val weightNewDeprived = manager.calculatePriorityWeight(
            globalAttempts = 0,
            globalCorrect = 0,
            sessionAttempts = 0
        )
        assertEquals(101.0f, weightNewDeprived, 0.001f)

        // Practiced character with high accuracy (10 session attempts, 100 global attempts, 100% accuracy):
        // Base (1.0) + Deprived (0) + New letter (0) + Inaccuracy (0) = 1.0
        val weightMastered = manager.calculatePriorityWeight(
            globalAttempts = 100,
            globalCorrect = 100,
            sessionAttempts = 10
        )
        assertEquals(1.0f, weightMastered, 0.001f)

        // Struggling character (10 session attempts, 20 global attempts, 50% accuracy):
        // Base (1.0) + Inaccuracy ((100 - 50) * 0.5 = 25.0) = 26.0
        val weightStruggling = manager.calculatePriorityWeight(
            globalAttempts = 20,
            globalCorrect = 10,
            sessionAttempts = 10
        )
        assertEquals(26.0f, weightStruggling, 0.001f)
    }

    @Test
    fun v17Advancement_requires5AttemptsAnd70PercentOnLatestCharacter() {
        val manager = KochMethodManager()

        // Level 1: sequence[0] = "K", sequence[1] = "M". Latest character is "M".
        assertEquals("M", manager.getLatestCharacterForLevel(1))
        // Level 2: sequence[2] = "R". Latest character is "R".
        assertEquals("R", manager.getLatestCharacterForLevel(2))

        // Case 1: 4 attempts, 100% accuracy -> Not advanced (< 5 attempts)
        val stat4Attempts = CharacterStats(profileId = 1L, character = "M", correctCount = 4, incorrectCount = 0)
        assertNull(manager.evaluateAdvancement(1, stat4Attempts))

        // Case 2: 5 attempts, 3 correct (60% accuracy) -> Not advanced (< 70%)
        val statLowAccuracy = CharacterStats(profileId = 1L, character = "M", correctCount = 3, incorrectCount = 2)
        assertNull(manager.evaluateAdvancement(1, statLowAccuracy))

        // Case 3: 5 attempts, 4 correct (80% accuracy) -> Advances to Level 2!
        val statPass = CharacterStats(profileId = 1L, character = "M", correctCount = 4, incorrectCount = 1)
        val adv = manager.evaluateAdvancement(1, statPass)
        assertNotNull(adv)
        assertEquals(2, adv?.newLevel)
        assertEquals("R", adv?.newlyUnlockedCharacter)
    }

    @Test
    fun v17Proficiency_requires5AttemptsAnd70Percent() {
        val manager = KochMethodManager()
        assertFalse(manager.isProficient(correctCount = 4, incorrectCount = 0)) // 4 attempts < 5
        assertFalse(manager.isProficient(correctCount = 3, incorrectCount = 2)) // 60% < 70%
        assertTrue(manager.isProficient(correctCount = 4, incorrectCount = 1))  // 5 attempts, 80% >= 70%
        assertTrue(manager.isProficient(correctCount = 10, incorrectCount = 1)) // 11 attempts, 90.9% >= 70%
    }
}
