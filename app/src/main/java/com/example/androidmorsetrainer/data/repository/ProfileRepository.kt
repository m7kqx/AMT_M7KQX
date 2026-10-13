package com.example.androidmorsetrainer.data.repository

import com.example.androidmorsetrainer.data.local.dao.CharacterStatsDao
import com.example.androidmorsetrainer.data.local.dao.UserProfileDao
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

interface ProfileRepository {
    val allProfiles: Flow<List<UserProfile>>
    suspend fun getAllProfiles(): List<UserProfile>
    suspend fun getProfileById(id: Long): UserProfile?
    suspend fun createProfile(name: String): Long
    suspend fun updateProfile(profile: UserProfile)
    suspend fun updateProfileKochLevel(profileId: Long, kochLevel: Int)
    suspend fun updateKochLevel(profileId: Long, level: Int) = updateProfileKochLevel(profileId, level)
    suspend fun updatePreferredWpm(profileId: Long, wpm: Int)
    suspend fun deleteProfile(profile: UserProfile)
    suspend fun deleteProfileById(id: Long)
    suspend fun getStatsForProfile(profileId: Long): List<CharacterStats>
    suspend fun getWeightedStatsForProfile(profileId: Long): List<CharacterStats>
    suspend fun getStatForCharacter(profileId: Long, character: String): CharacterStats?
    suspend fun saveCharacterStat(stat: CharacterStats)
    suspend fun saveTimeAttackReport(report: com.example.androidmorsetrainer.data.local.entity.TimeAttackReport): Long
    suspend fun getTimeAttackReports(profileId: Long): List<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport>
    fun getTimeAttackReportsFlow(profileId: Long): Flow<List<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport>>
}

class ProfileRepositoryImpl(
    private val userProfileDao: UserProfileDao,
    private val characterStatsDao: CharacterStatsDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProfileRepository {

    override val allProfiles: Flow<List<UserProfile>> = userProfileDao.getAllProfilesFlow()

    override suspend fun getAllProfiles(): List<UserProfile> = withContext(ioDispatcher) {
        userProfileDao.getAllProfiles()
    }

    override suspend fun getProfileById(id: Long): UserProfile? = withContext(ioDispatcher) {
        userProfileDao.getProfileById(id)
    }

    override suspend fun createProfile(name: String): Long = withContext(ioDispatcher) {
        val newProfile = UserProfile(
            name = name,
            currentKochLevel = 1,
            createdAt = System.currentTimeMillis()
        )
        userProfileDao.insertProfile(newProfile)
    }

    override suspend fun updateProfile(profile: UserProfile) = withContext(ioDispatcher) {
        userProfileDao.updateProfile(profile)
    }

    override suspend fun updateProfileKochLevel(profileId: Long, kochLevel: Int): Unit = withContext(ioDispatcher) {
        val profile = userProfileDao.getProfileById(profileId)
        if (profile != null) {
            userProfileDao.updateProfile(profile.copy(currentKochLevel = kochLevel))
        }
    }

    override suspend fun updatePreferredWpm(profileId: Long, wpm: Int) = withContext(ioDispatcher) {
        userProfileDao.updatePreferredWpm(profileId, wpm)
    }

    override suspend fun deleteProfile(profile: UserProfile) = withContext(ioDispatcher) {
        userProfileDao.deleteProfile(profile)
    }

    override suspend fun deleteProfileById(id: Long) = withContext(ioDispatcher) {
        userProfileDao.deleteProfileById(id)
    }

    override suspend fun getStatsForProfile(profileId: Long): List<CharacterStats> = withContext(ioDispatcher) {
        characterStatsDao.getCharacterStats(profileId)
    }

    override suspend fun getWeightedStatsForProfile(profileId: Long): List<CharacterStats> = withContext(ioDispatcher) {
        characterStatsDao.getWeightedCharacterList(profileId)
    }

    override suspend fun getStatForCharacter(profileId: Long, character: String): CharacterStats? = withContext(ioDispatcher) {
        characterStatsDao.getStatForCharacter(profileId, character)
    }

    override suspend fun saveCharacterStat(stat: CharacterStats) = withContext(ioDispatcher) {
        characterStatsDao.insertOrUpdate(stat)
    }

    override suspend fun saveTimeAttackReport(report: com.example.androidmorsetrainer.data.local.entity.TimeAttackReport): Long = withContext(ioDispatcher) {
        characterStatsDao.insertTimeAttackReport(report)
    }

    override suspend fun getTimeAttackReports(profileId: Long): List<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport> = withContext(ioDispatcher) {
        characterStatsDao.getTimeAttackReportsForProfile(profileId)
    }

    override fun getTimeAttackReportsFlow(profileId: Long): Flow<List<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport>> {
        return characterStatsDao.getTimeAttackReportsFlow(profileId)
    }
}
