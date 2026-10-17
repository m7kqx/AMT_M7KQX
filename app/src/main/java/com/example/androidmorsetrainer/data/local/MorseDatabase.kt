package com.example.androidmorsetrainer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.androidmorsetrainer.data.local.dao.CharacterStatsDao
import com.example.androidmorsetrainer.data.local.dao.UserProfileDao
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.TimeAttackReport
import com.example.androidmorsetrainer.data.local.entity.UserProfile

@Database(
    entities = [
        UserProfile::class,
        CharacterStats::class,
        TimeAttackReport::class
    ],
    version = 6,
    exportSchema = false
)
abstract class MorseDatabase : RoomDatabase() {

    abstract fun userProfileDao(): UserProfileDao
    abstract fun characterStatsDao(): CharacterStatsDao

    companion object {
        @Volatile
        private var INSTANCE: MorseDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_1_5: Migration = object : Migration(1, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_2_5: Migration = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_3_5: Migration = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_1_6: Migration = object : Migration(1, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_2_6: Migration = object : Migration(2, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_3_6: Migration = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val MIGRATION_4_6: Migration = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSchema(db)
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_1_5,
            MIGRATION_2_5,
            MIGRATION_3_5,
            MIGRATION_1_6,
            MIGRATION_2_6,
            MIGRATION_3_6,
            MIGRATION_4_6
        )

        private fun migrateSchema(db: SupportSQLiteDatabase) {
            // Preserve user_profiles table data, ensuring table and all columns exist
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_profiles` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `current_koch_level` INTEGER NOT NULL,
                    `current_prosign_level` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `preferred_wpm` INTEGER NOT NULL,
                    `is_progression_paused` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            addColumnIfNotExists(db, "user_profiles", "current_koch_level", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfNotExists(db, "user_profiles", "current_prosign_level", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfNotExists(db, "user_profiles", "created_at", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "user_profiles", "preferred_wpm", "INTEGER NOT NULL DEFAULT 20")
            addColumnIfNotExists(db, "user_profiles", "is_progression_paused", "INTEGER NOT NULL DEFAULT 0")

            // Ensure character_stats table and columns exist
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `character_stats` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `profile_id` INTEGER NOT NULL,
                    `character` TEXT NOT NULL,
                    `correct_count` INTEGER NOT NULL,
                    `incorrect_count` INTEGER NOT NULL,
                    `priority_weight` REAL NOT NULL,
                    `successful_challenges` INTEGER NOT NULL DEFAULT 0,
                    `is_prosign` INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(`profile_id`) REFERENCES `user_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            addColumnIfNotExists(db, "character_stats", "successful_challenges", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfNotExists(db, "character_stats", "is_prosign", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_stats_profile_id` ON `character_stats` (`profile_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_stats_profile_id_character` ON `character_stats` (`profile_id`, `character`)")

            // Ensure time_attack_reports table and indices exist
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `time_attack_reports` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `profile_id` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `response_window_sec` REAL NOT NULL,
                    `inter_challenge_delay_ms` INTEGER NOT NULL,
                    `challenge_count` INTEGER NOT NULL,
                    `included_characters` TEXT NOT NULL,
                    `correct_count` INTEGER NOT NULL,
                    `incorrect_count` INTEGER NOT NULL,
                    `missed_count` INTEGER NOT NULL,
                    `details_encoded` TEXT NOT NULL,
                    FOREIGN KEY(`profile_id`) REFERENCES `user_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_time_attack_reports_profile_id` ON `time_attack_reports` (`profile_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_time_attack_reports_timestamp` ON `time_attack_reports` (`timestamp`)")
        }

        private fun addColumnIfNotExists(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDef: String
        ) {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                        return
                    }
                }
            }
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDef")
        }

        fun getDatabase(context: Context): MorseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MorseDatabase::class.java,
                    "morse_trainer_database"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
