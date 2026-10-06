package com.example.androidmorsetrainer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.androidmorsetrainer.data.local.dao.CharacterStatsDao
import com.example.androidmorsetrainer.data.local.dao.UserProfileDao
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile

@Database(
    entities = [
        UserProfile::class,
        CharacterStats::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MorseDatabase : RoomDatabase() {

    abstract fun userProfileDao(): UserProfileDao
    abstract fun characterStatsDao(): CharacterStatsDao

    companion object {
        @Volatile
        private var INSTANCE: MorseDatabase? = null

        fun getDatabase(context: Context): MorseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MorseDatabase::class.java,
                    "morse_trainer_database"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
