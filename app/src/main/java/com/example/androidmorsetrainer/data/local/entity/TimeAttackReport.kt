package com.example.androidmorsetrainer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Outcome status for an individual challenge within a Time Attack drill.
 */
enum class TimeAttackOutcome {
    CORRECT,
    INCORRECT,
    MISSED
}

/**
 * Result data class for an individual Time Attack challenge.
 */
data class TimeAttackChallengeResult(
    val target: String,
    val guess: String?,
    val outcome: TimeAttackOutcome,
    val responseTimeMs: Long = 0L
)

/**
 * User configuration data class for a Time Attack drill.
 * Enforces a strict 1.0s to 3.5s response window.
 */
data class TimeAttackConfig(
    val responseWindowSeconds: Float = 2.0f,
    val interChallengeDelayMs: Long = 1000L,
    val challengeCount: Int = 20,
    val includedCharacters: Set<Char> = setOf('K', 'M', 'R')
) {
    init {
        require(responseWindowSeconds in 1.0f..3.5f) {
            "Response window must be between 1.0 and 3.5 seconds."
        }
    }
}

/**
 * Room database entity storing a completed Time Attack session report.
 */
@Entity(
    tableName = "time_attack_reports",
    foreignKeys = [
        ForeignKey(
            entity = UserProfile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["profile_id"]),
        Index(value = ["timestamp"])
    ]
)
data class TimeAttackReport(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "profile_id")
    val profileId: Long,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "response_window_sec")
    val responseWindowSeconds: Float = 1.5f,

    @ColumnInfo(name = "inter_challenge_delay_ms")
    val interChallengeDelayMs: Long = 1000L,

    @ColumnInfo(name = "challenge_count")
    val challengeCount: Int = 20,

    @ColumnInfo(name = "included_characters")
    val includedCharacters: String = "",

    @ColumnInfo(name = "correct_count")
    val correctCount: Int = 0,

    @ColumnInfo(name = "incorrect_count")
    val incorrectCount: Int = 0,

    @ColumnInfo(name = "missed_count")
    val missedCount: Int = 0,

    @ColumnInfo(name = "details_encoded")
    val detailsEncoded: String = ""
) {
    val totalChallenges: Int
        get() = correctCount + incorrectCount + missedCount

    val accuracyPercentage: Float
        get() = if (totalChallenges > 0) (correctCount.toFloat() / totalChallenges) * 100.0f else 0.0f

    /**
     * Decodes the serialized challenge details into a list of [TimeAttackChallengeResult].
     */
    fun toChallengeResults(): List<TimeAttackChallengeResult> {
        return decodeResults(detailsEncoded)
    }

    companion object {
        /**
         * Serializes a list of [TimeAttackChallengeResult] into a compact string format:
         * "TARGET|GUESS|OUTCOME|TIME_MS;..."
         */
        fun encodeResults(results: List<TimeAttackChallengeResult>): String {
            return results.joinToString(";") {
                val cleanGuess = it.guess?.ifEmpty { "-" } ?: "-"
                "${it.target}|$cleanGuess|${it.outcome.name}|${it.responseTimeMs}"
            }
        }

        /**
         * Parses a serialized string into a list of [TimeAttackChallengeResult].
         */
        fun decodeResults(encoded: String): List<TimeAttackChallengeResult> {
            if (encoded.isBlank()) return emptyList()
            return encoded.split(";").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size >= 4) {
                    val target = parts[0]
                    val guess = if (parts[1] == "-" || parts[1].isEmpty()) null else parts[1]
                    val outcome = try {
                        TimeAttackOutcome.valueOf(parts[2])
                    } catch (e: Exception) {
                        TimeAttackOutcome.MISSED
                    }
                    val time = parts[3].toLongOrNull() ?: 0L
                    TimeAttackChallengeResult(target, guess, outcome, time)
                } else null
            }
        }
    }
}
