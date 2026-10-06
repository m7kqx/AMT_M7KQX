package com.example.androidmorsetrainer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity tracking accuracy stats and adaptive priority weighting for each Morse character per profile.
 */
@Entity(
    tableName = "character_stats",
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
        Index(value = ["profile_id", "character"], unique = true)
    ]
)
data class CharacterStats(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "profile_id")
    val profileId: Long,

    @ColumnInfo(name = "character")
    val character: String,

    @ColumnInfo(name = "correct_count")
    val correctCount: Int = 0,

    @ColumnInfo(name = "incorrect_count")
    val incorrectCount: Int = 0,

    @ColumnInfo(name = "priority_weight")
    val priorityWeight: Float = 1.0f,

    @ColumnInfo(name = "successful_challenges", defaultValue = "0")
    val successfulChallenges: Int = correctCount
) {
    val totalAttempts: Int
        get() = correctCount + incorrectCount

    val accuracyPercentage: Float
        get() = if (totalAttempts > 0) (correctCount.toFloat() / totalAttempts) * 100.0f else 0.0f

    val isHintThresholdMet: Boolean
        get() = successfulChallenges >= 6
}
