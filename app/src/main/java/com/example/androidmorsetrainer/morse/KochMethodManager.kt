package com.example.androidmorsetrainer.morse

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Manager handling the Koch method progression sequence, Morse conversions,
 * and adaptive priority-weighted challenge generation.
 */
class KochMethodManager(
    private val random: Random = Random.Default
) {

    /**
     * Complete Koch sequence of 43 characters:
     * K, M, R, S, U, A, P, T, L, O, W, I, . , N, J, E, F, 0, Y, ,, V, G, 5, /, Q, 9, Z, H, 3, 8, B, ?, 4, 2, 7, C, 1, D, 6, X, <BT>, <SK>, <AR>
     */
    val sequence: List<String> = MorseConstants.KOCH_SEQUENCE

    /**
     * Koch method starts at Level 1 with 2 characters ("K", "M").
     * Each subsequent level introduces 1 new character.
     * Maximum level is sequence.size - 1 (Level 42, having all 43 characters).
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
     * Converts a single character or prosign string into its Morse representation (dots and dashes).
     */
    fun getMorseCode(character: String): String? {
        return MorseConstants.MORSE_MAP[character.uppercase()]
    }

    /**
     * Reverse lookup: converts a Morse pattern (e.g. "-.-") into its alphanumeric character or prosign.
     */
    fun getCharacterForMorse(morse: String): String? {
        return MorseConstants.REVERSE_MORSE_MAP[morse]
    }

    /**
     * Selects a single character from the provided pool using fitness/roulette-wheel weighted random sampling.
     * Characters with higher priority weights are sampled more frequently.
     * Default baseline weight for unrecorded characters is 1.0f.
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
     * Standard ham radio training group size is typically 5 characters.
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
     * Replicates the adaptive Koch logic:
     * - Incorrect response: increases weight sharply so the user is challenged on it more often.
     * - Correct response: relaxes weight towards baseline (1.0f).
     */
    fun calculateUpdatedWeight(
        currentWeight: Float,
        wasCorrect: Boolean,
        minWeight: Float = 1.0f,
        maxWeight: Float = 5.0f
    ): Float {
        return if (wasCorrect) {
            // Decay weight towards 1.0f
            max(minWeight, currentWeight * 0.85f)
        } else {
            // Boost weight to force frequent appearance
            min(maxWeight, currentWeight * 1.4f + 0.3f)
        }
    }
}
