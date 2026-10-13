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
    fun kochSequence_containsExact40Characters() {
        val manager = KochMethodManager()
        assertEquals(40, manager.sequence.size)
        assertEquals("K", manager.sequence[0])
        assertEquals("M", manager.sequence[1])
        assertEquals("R", manager.sequence[2])
        assertEquals("X", manager.sequence[39])
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

        // Max level is 39, containing all 40 characters
        assertEquals(39, manager.maxLevel)
        assertEquals(39, MorseConstants.MAX_KOCH_LEVEL)
        assertEquals(39, MorseConstants.TOTAL_KOCH_LEVELS)
        val maxLevel = manager.getCharactersForLevel(manager.maxLevel)
        assertEquals(40, maxLevel.size)
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

    @Test
    fun prosignsAndAbbreviations_mappedAndParsedCorrectly() {
        val manager = KochMethodManager()
        val prosigns = listOf("DE", "PSE", "K", "UR", "RST", "CQ")
        val expected = mapOf(
            "DE" to "-.. .",
            "PSE" to ".--. ... .",
            "K" to "-.-",
            "UR" to "..- .-.",
            "RST" to ".-. ... -",
            "CQ" to "-.-. --.-"
        )

        for (prosign in prosigns) {
            val code = manager.getMorseCode(prosign)
            assertNotNull("Morse code for $prosign must not be null", code)
            assertEquals("Morse code for $prosign should match expected pattern", expected[prosign], code)
            assertEquals("MorseConstants.MORSE_MAP[$prosign] should match expected pattern", expected[prosign], MorseConstants.MORSE_MAP[prosign])
        }
    }

    @Test
    fun standardTimingMultipliers_explicitlyDefinedAndApplied() {
        assertEquals(1, MorseConstants.DIT_MULTIPLIER)
        assertEquals(3, MorseConstants.DAH_MULTIPLIER)
        assertEquals(1, MorseConstants.INTRA_CHARACTER_SPACE_MULTIPLIER)
        assertEquals(3, MorseConstants.INTER_CHARACTER_SPACE_MULTIPLIER)
        assertEquals(7, MorseConstants.WORD_SPACE_MULTIPLIER)

        val wpm = 20
        val unit = MorseConstants.calculateUnitDurationMs(wpm) // 60ms
        assertEquals(60L, unit)
        assertEquals(unit * MorseConstants.DAH_MULTIPLIER, MorseConstants.calculateDahDurationMs(wpm))
        assertEquals(unit * MorseConstants.INTRA_CHARACTER_SPACE_MULTIPLIER, MorseConstants.calculateElementSpaceMs(wpm))
        assertEquals(unit * MorseConstants.INTER_CHARACTER_SPACE_MULTIPLIER, MorseConstants.calculateCharacterSpaceMs(wpm))
        assertEquals(unit * MorseConstants.WORD_SPACE_MULTIPLIER, MorseConstants.calculateWordSpaceMs(wpm))
    }

    @Test
    fun morseAudioGenerator_bracketedProsigns_concatenatedIntoContinuousSequence() {
        val generator = com.example.androidmorsetrainer.audio.MorseAudioGenerator()

        val skPattern = generator.getProsignMorsePattern("<SK>")
        assertEquals("...-.-", skPattern)
        assertFalse(skPattern!!.contains(" "))

        val arPattern = generator.getProsignMorsePattern("<AR>")
        assertEquals(".-.-.", arPattern)
        assertFalse(arPattern!!.contains(" "))

        val btPattern = generator.getProsignMorsePattern("<BT>")
        assertEquals("-...-", btPattern)
        assertFalse(btPattern!!.contains(" "))

        val knPattern = generator.getProsignMorsePattern("<KN>")
        assertEquals("-.--.", knPattern)
        assertFalse(knPattern!!.contains(" "))

        val asPattern = generator.getProsignMorsePattern("<AS>")
        assertEquals(".-...", asPattern)
        assertFalse(asPattern!!.contains(" "))
    }

    @Test
    fun morseAudioGenerator_tokenizeInputString_handlesSpacesAndProsigns() {
        val generator = com.example.androidmorsetrainer.audio.MorseAudioGenerator()
        val tokens = generator.tokenizeInputString("CQ DE <SK>")
        assertEquals(listOf("C", "Q", " ", "D", "E", " ", "<SK>"), tokens)

        val tokensProsignsOnly = generator.tokenizeInputString("<AR> <SK>")
        assertEquals(listOf("<AR>", " ", "<SK>"), tokensProsignsOnly)
    }

    @Test
    fun morseAudioGenerator_timingTracking_enforcesOneThreeSevenTiming() = kotlinx.coroutines.test.runTest {
        class TrackingAudioGenerator(wpm: Int = 20) : com.example.androidmorsetrainer.audio.MorseAudioGenerator(wpm = wpm) {
            val tones = mutableListOf<Long>()
            val silences = mutableListOf<Long>()

            override suspend fun playTone(durationMs: Long) {
                tones.add(durationMs)
            }

            override suspend fun playSilence(durationMs: Long) {
                silences.add(durationMs)
            }
        }

        val tracker = TrackingAudioGenerator(wpm = 20) // unit = 60ms

        // 1. Test Dit (1 unit) and Dah (3 units)
        tracker.playDit()
        tracker.playDah()
        assertEquals(listOf(60L, 180L), tracker.tones)

        // 2. Test bracketed prosign <SK>: must be continuous 6 elements (...-.-) with only 1-unit silences (60ms)
        // and 0 inter-character silences (180ms) when useContinuousProsign = true
        tracker.tones.clear()
        tracker.silences.clear()
        tracker.playCharacter("<SK>", useContinuousProsign = true)
        assertEquals(listOf(60L, 60L, 60L, 180L, 60L, 180L), tracker.tones)
        // The silences between elements must only be 60ms (1-unit intra-character pause), bypassing 3-unit (180ms)
        val interElementSilences = tracker.silences.filter { it != 400L }
        assertEquals(5, interElementSilences.size)
        assertTrue("All pauses between <SK> elements must be 1 unit (60ms)", interElementSilences.all { it == 60L })
        assertFalse("3-unit pauses (180ms) must be bypassed in <SK>", tracker.silences.contains(180L))

        // Also test <SK> with useContinuousProsign = false (standard 1:3:7 timing with 3-unit pause between letters)
        tracker.tones.clear()
        tracker.silences.clear()
        tracker.playCharacter("<SK>", useContinuousProsign = false)
        assertTrue("3-unit pauses (180ms) must be present between letters in standard 1:3:7 timing", tracker.silences.contains(180L))

        // 3. Test bracketed prosign <AR>: continuous 5 elements (.-.-.) with only 1-unit silences (60ms) when useContinuousProsign = true
        tracker.tones.clear()
        tracker.silences.clear()
        tracker.playCharacter("<AR>", useContinuousProsign = true)
        assertEquals(listOf(60L, 180L, 60L, 180L, 60L), tracker.tones)
        val arSilences = tracker.silences.filter { it != 400L }
        assertEquals(4, arSilences.size)
        assertTrue("All pauses between <AR> elements must be 1 unit (60ms)", arSilences.all { it == 60L })
        assertFalse("3-unit pauses (180ms) must be bypassed in <AR>", tracker.silences.contains(180L))

        // 4. Test spaces trigger 7-unit silence delay (420ms at 20 WPM)
        tracker.tones.clear()
        tracker.silences.clear()
        tracker.playCharacter(" ")
        assertTrue("Space must trigger 7-unit silence (420ms)", tracker.silences.contains(420L))

        // 5. Test input string with space "K M" triggers 7-unit silence between K and M
        tracker.tones.clear()
        tracker.silences.clear()
        tracker.playCharacter("K M")
        assertTrue("Space in input string must trigger 7-unit silence (420ms)", tracker.silences.contains(420L))
    }
}
