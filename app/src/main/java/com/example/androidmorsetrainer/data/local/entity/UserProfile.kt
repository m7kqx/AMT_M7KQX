package com.example.androidmorsetrainer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a user's training profile.
 * Tracks current Koch progression level and creation timestamp.
 */
@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "current_koch_level")
    val currentKochLevel: Int = 1,

    @ColumnInfo(name = "current_prosign_level")
    val currentProsignLevel: Int = 1,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
