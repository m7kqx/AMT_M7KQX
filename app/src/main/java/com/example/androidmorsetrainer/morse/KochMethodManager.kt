package com.example.androidmorsetrainer.morse

import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Result data class when Koch advancement is evaluated.
 */
data class AdvancementResult(
    val newLevel: Int,
    val newlyUnlockedCharacter: String,
    val message: String
)

/**
 * Manager handling the Koch method progression sequence, Morse conversions,
 * dynamic priority challenge weighting, and advancement evaluation ported from v1.7.
 */
class KochMethodManager(
    private val random: Random = Random.Default
) {

    companion object {
        const val PROMOTION_MIN_ATTEMPTS = 5
        const val PROMOTION_ACCURACY_PERCENT = 70.0f
        const val MASTERY_MIN_ATTEMPTS = 5
        const val MASTERY_ACCURACY_PERCENT = 70.0f

        const val BASE_WEIGHT = 1.0f
        const val DEPRIVATION_WEIGHT_ZERO = 20.0f
        const val DEPRIVATION_WEIGHT_FEW = 10.0f
        const val NEW_LETTER_WEIGHT = 30.0f
        const val INACCURACY_MULTIPLIER = 0.5f
    }

    /**
     * Complete Koch sequence of 43 characters:
     * K, M, R, S, U, A, P, T, L, O, W, I, . , N, J, E, F, 0, Y, ,, V, G, 5, /, Q, 9, Z, H, 3, 8, B, ?, 4, 2, 7, C, 1, D, 6, X, <BT>, <SK>, <AR>
     */
    val sequence: List<String> = MorseConstants.KOCH_SEQUENCE

    /**
     * Koch method starts at Level 1 with 2 characters ("K", "M").
     * Each subsequent level introduces 1 new character.
     * Maximum level is sequence.size - 1 (Level 40, having all 41 characters).
     */
    val minLevel: Int = 1
    val maxLevel: Int = sequence.size - 1

    /**
     * Returns the list of active characters for the given Koch level.
     * Level 1 -> ["K", "M"]
     * Level 2 -> ["K", "M", "R"]
     * Level L -> sequence[0 .. min(L + 1, sequence.size) - 1]
     */
    fun getCharactersForLevel(level: Int): List<String> {
        val clampedLevel = level.coerceIn(minLevel, maxLevel)
        val characterCount = min(clampedLevel + 1, sequence.size)
        return sequence.subList(0, characterCount)
    }

    /**
     * Returns the newest character introduced at the given Koch level.
     * Level 1 -> "M"
     * Level 2 -> "R"
     * Level L -> sequence[L]
     */
    fun getLatestCharacterForLevel(level: Int): String {
        return getCharactersForLevel(level).last()
    }

    /**
     * Converts a single character or prosign string into its Morse representation (dots and dashes).
     */
    fun getMorseCode(character: String): String? {
        val upper = character.uppercase()
        if (MorseConstants.MORSE_MAP.containsKey(upper)) {
            return MorseConstants.MORSE_MAP[upper]
        }
        // Check for multi-character sequences (e.g. CQ)
        val parts = upper.mapNotNull { MorseConstants.MORSE_MAP[it.toString()] }
        if (parts.size == upper.length) {
            return parts.joinToString(" ")
        }
        return null
    }

    /**
     * Reverse lookup: converts a Morse pattern (e.g. "-.-") into its alphanumeric character or prosign.
     */
    fun getCharacterForMorse(morse: String): String? {
        return MorseConstants.REVERSE_MORSE_MAP[morse]
    }

    /**
     * Calculates the dynamic priority weight for a character exactly as ported from v1.7:
     * 1. Base Weight (1.0): Ensures highly accurate characters never hit 0% probability.
     * 2. Session Deprivation Weighting: +20.0 if 0 session attempts; +10.0 if < 5 session attempts.
     * 3. New Letter Priority Weighting: +30.0 if < 10 global attempts.
     * 4. Inaccuracy Weighting: + (100.0 - accuracy) * 0.5.
     */
    fun calculatePriorityWeight(
        globalAttempts: Int,
        globalCorrect: Int,
        sessionAttempts: Int
    ): Float {
        var weight = BASE_WEIGHT

        // 2. Session Deprivation Weighting
        if (sessionAttempts == 0) {
            weight += DEPRIVATION_WEIGHT_ZERO
        } else if (sessionAttempts < 5) {
            weight += DEPRIVATION_WEIGHT_FEW
        }

        // 3. New Letter Priority Weighting
        if (globalAttempts < 10) {
            weight += NEW_LETTER_WEIGHT
        }

        // 4. Inaccuracy Weighting
        val acc = if (globalAttempts > 0) {
            (globalCorrect.toFloat() / globalAttempts) * 100.0f
        } else {
            0.0f
        }
        weight += (100.0f - acc) * INACCURACY_MULTIPLIER

        return weight
    }

    /**
     * Roulette wheel selection based on dynamic priority weights ported from v1.7.
     * Enforces targeted practice on weak, newly unlocked, and session-deprived characters.
     */
    fun getNextChallenge(
        pool: List<String>,
        globalStats: Map<String, CharacterStats>,
        sessionAttempts: Map<String, Int>
    ): String {
        require(pool.isNotEmpty()) { "Character pool must not be empty" }
        if (pool.size == 1) return pool.first()

        val weights = pool.map { char ->
            val stat = globalStats[char.uppercase()]
            val gAttempts = (stat?.correctCount ?: 0) + (stat?.incorrectCount ?: 0)
            val gCorrect = stat?.correctCount ?: 0
            val sAttempts = sessionAttempts[char.uppercase()] ?: 0
            calculatePriorityWeight(
                globalAttempts = gAttempts,
                globalCorrect = gCorrect,
                sessionAttempts = sAttempts
            )
        }

        val totalWeight = weights.sum()
        val randomThreshold = random.nextFloat() * totalWeight

        var cumulative = 0.0f
        for (i in pool.indices) {
            cumulative += weights[i]
            if (cumulative >= randomThreshold) {
                return pool[i]
            }
        }
        return pool.last()
    }

    /**
     * Selects a single character from the provided pool using fitness/roulette-wheel weighted random sampling.
     * Maintained for backwards compatibility.
     */
    fun getWeightedRandomCharacter(
        pool: List<String>,
        weights: Map<String, Float>
    ): String {
        require(pool.isNotEmpty()) { "Character pool must not be empty" }
        if (pool.size == 1) return pool.first()

        val normalizedWeights = pool.map { char ->
            max(0.1f, weights[char] ?: 1.0f)
        }
        val totalWeight = normalizedWeights.sum()
        val randomThreshold = random.nextFloat() * totalWeight

        var cumulative = 0.0f
        for (i in pool.indices) {
            cumulative += normalizedWeights[i]
            if (cumulative >= randomThreshold) {
                return pool[i]
            }
        }
        return pool.last()
    }

    /**
     * Generates a random challenge group of characters for a given level and priority weights.
     */
    fun generateChallenge(
        level: Int,
        weights: Map<String, Float>,
        count: Int = 5
    ): List<String> {
        require(count > 0) { "Count must be greater than 0" }
        val activeCharacters = getCharactersForLevel(level)
        return List(count) {
            getWeightedRandomCharacter(activeCharacters, weights)
        }
    }

    /**
     * Calculates the updated priority weight following a challenge result.
     */
    fun calculateUpdatedWeight(
        currentWeight: Float,
        wasCorrect: Boolean,
        minWeight: Float = 1.0f,
        maxWeight: Float = 5.0f
    ): Float {
        return if (wasCorrect) {
            max(minWeight, currentWeight * 0.85f)
        } else {
            min(maxWeight, currentWeight * 1.4f + 0.3f)
        }
    }

    /**
     * Checks whether a character has reached proficiency ported from v1.7:
     * attempts >= 5 and accuracy >= 70.0%
     */
    fun isProficient(correctCount: Int, incorrectCount: Int): Boolean {
        val attempts = correctCount + incorrectCount
        if (attempts < MASTERY_MIN_ATTEMPTS) return false
        val accuracy = (correctCount.toFloat() / attempts) * 100.0f
        return accuracy >= MASTERY_ACCURACY_PERCENT
    }

    /**
     * Evaluates Koch level advancement ported from v1.7:
     * Targets the most recently introduced letter in the sequence.
     * Advances as soon as that letter is learned (attempts >= 5 and accuracy >= 70.0%).
     */
    fun evaluateAdvancement(
        currentLevel: Int,
        latestCharStats: CharacterStats?
    ): AdvancementResult? {
        if (currentLevel >= maxLevel) return null
        if (latestCharStats == null) return null

        val attempts = latestCharStats.correctCount + latestCharStats.incorrectCount
        if (attempts < PROMOTION_MIN_ATTEMPTS) return null

        val accuracy = (latestCharStats.correctCount.toFloat() / attempts) * 100.0f
        if (accuracy >= PROMOTION_ACCURACY_PERCENT) {
            val nextLevel = min(currentLevel + 1, maxLevel)
            val newLetter = getLatestCharacterForLevel(nextLevel)
            return AdvancementResult(
                newLevel = nextLevel,
                newlyUnlockedCharacter = newLetter,
                message = "Koch Level $nextLevel (+ '$newLetter')"
            )
        }
        return null
    }
}
