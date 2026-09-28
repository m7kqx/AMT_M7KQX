package com.example.androidmorsetrainer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterStatsDao {

    @Query("SELECT * FROM character_stats WHERE profile_id = :profileId ORDER BY character ASC")
    fun getCharacterStatsFlow(profileId: Long): Flow<List<CharacterStats>>

    @Query("SELECT * FROM character_stats WHERE profile_id = :profileId ORDER BY character ASC")
    suspend fun getCharacterStats(profileId: Long): List<CharacterStats>

    @Query("SELECT * FROM character_stats WHERE profile_id = :profileId ORDER BY priority_weight DESC")
    suspend fun getWeightedCharacterList(profileId: Long): List<CharacterStats>

    @Query("SELECT * FROM character_stats WHERE profile_id = :profileId AND character IN (:characters) ORDER BY priority_weight DESC")
    suspend fun getWeightedCharacterListForCharacters(
        profileId: Long,
        characters: List<String>
    ): List<CharacterStats>

    @Query("SELECT * FROM character_stats WHERE profile_id = :profileId AND character = :character LIMIT 1")
    suspend fun getStatForCharacter(profileId: Long, character: String): CharacterStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stats: List<CharacterStats>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stat: CharacterStats)

    @Update
    suspend fun update(stat: CharacterStats)

    @Delete
    suspend fun delete(stat: CharacterStats)

    @Query("DELETE FROM character_stats WHERE profile_id = :profileId")
    suspend fun deleteStatsForProfile(profileId: Long)
}
