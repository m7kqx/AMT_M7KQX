package com.example.androidmorsetrainer.ui.screens.send

import com.example.androidmorsetrainer.audio.MorseDSPManager
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import com.example.androidmorsetrainer.morse.KochMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeMorseDSPManager : MorseDSPManager(context = null) {
    var hasPermission: Boolean = true
    var isListeningStarted: Boolean = false
    var isListeningStopped: Boolean = false
    var lastSetFrequency: Double = 700.0
    var lastSetSquelch: Double = MorseDSPManager.DEFAULT_THRESHOLD

    val fakeDspState = MutableStateFlow(DSPState())
    val fakeToneEvents = MutableSharedFlow<ToneEvent>(extraBufferCapacity = 64)
    val fakeRawAudio = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)

    override val dspState: StateFlow<DSPState> = fakeDspState.asStateFlow()
    override val toneEvents: SharedFlow<ToneEvent> = fakeToneEvents.asSharedFlow()
    override val rawAudioFlow: SharedFlow<ShortArray> = fakeRawAudio.asSharedFlow()

    override fun hasRecordPermission(): Boolean = hasPermission

    override fun setTargetFrequency(frequencyHz: Double) {
        lastSetFrequency = frequencyHz
        targetFrequencyHz = frequencyHz
        fakeDspState.update { it.copy() }
    }

    override fun setSquelchThreshold(threshold: Double) {
        lastSetSquelch = threshold
        fakeDspState.update { it.copy(detectionThreshold = threshold) }
    }

    override fun startListening() {
        isListeningStarted = true
        fakeDspState.update { it.copy(isListening = true) }
    }

    override fun stopListening() {
        isListeningStopped = true
        fakeDspState.update { it.copy(isListening = false, isTonePresent = false) }
    }
}

class FakeProfileRepository : ProfileRepository {
    private val profilesFlow = MutableStateFlow<List<UserProfile>>(emptyList())
    private val characterStatsMap = mutableMapOf<Pair<Long, String>, CharacterStats>()
    private var nextProfileId = 1L

    override val allProfiles: Flow<List<UserProfile>> = profilesFlow.asStateFlow()

    override suspend fun getAllProfiles(): List<UserProfile> = profilesFlow.value

    override suspend fun getProfileById(id: Long): UserProfile? = profilesFlow.value.find { it.id == id }

    override suspend fun createProfile(name: String): Long {
        val newProfile = UserProfile(
            id = nextProfileId++,
            name = name,
            currentKochLevel = 1,
            createdAt = System.currentTimeMillis()
        )
        profilesFlow.update { listOf(newProfile) + it }
        return newProfile.id
    }

    override suspend fun updateProfile(profile: UserProfile) {
        profilesFlow.update { list -> list.map { if (it.id == profile.id) profile else it } }
    }

    override suspend fun deleteProfile(profile: UserProfile) {
        profilesFlow.update { list -> list.filterNot { it.id == profile.id } }
        characterStatsMap.keys.removeAll { it.first == profile.id }
    }

    override suspend fun deleteProfileById(id: Long) {
        profilesFlow.update { list -> list.filterNot { it.id == id } }
        characterStatsMap.keys.removeAll { it.first == id }
    }

    override suspend fun getStatsForProfile(profileId: Long): List<CharacterStats> {
        return characterStatsMap.filter { it.key.first == profileId }.values.toList()
    }

    override suspend fun getWeightedStatsForProfile(profileId: Long): List<CharacterStats> {
        return getStatsForProfile(profileId)
    }

    override suspend fun getStatForCharacter(profileId: Long, character: String): CharacterStats? {
        return characterStatsMap[Pair(profileId, character)]
    }

    override suspend fun saveCharacterStat(stat: CharacterStats) {
        characterStatsMap[Pair(stat.profileId, stat.character)] = stat
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SendViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var profileRepository: FakeProfileRepository
    private lateinit var kochMethodManager: KochMethodManager
    private lateinit var dspManager: FakeMorseDSPManager
    private lateinit var viewModel: SendViewModel

    private val testProfile = UserProfile(
        id = 1L,
        name = "Operator",
        currentKochLevel = 1
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        profileRepository = FakeProfileRepository()
        kochMethodManager = KochMethodManager()
        dspManager = FakeMorseDSPManager()
        viewModel = SendViewModel(
            profileRepository = profileRepository,
            kochMethodManager = kochMethodManager,
            dspManager = dspManager,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            minAttemptsForLevelUp = 5
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_hasRecordPermissionAndSquelchSync() {
        val state = viewModel.uiState.value
        assertTrue(state.hasRecordPermission)
        assertEquals(700.0, state.targetFrequencyHz, 0.001)
        assertNotNull(viewModel.squelchLevel.value)
    }

    @Test
    fun setActiveProfile_initializesChallengeAndCharacterPool() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(testProfile, state.activeProfile)
        assertEquals(1, state.activeKochLevel)
        assertEquals(listOf("K", "M"), state.availableCharacters)
        assertTrue(state.targetCharacter in listOf("K", "M"))
        assertTrue(state.targetMorsePattern.isNotEmpty())
        assertEquals(0, state.sessionTotalAttempts)
    }

    @Test
    fun setSquelchLevel_updatesStateFlowAndDspManager() = runTest {
        viewModel.setSquelchLevel(0.5f)
        advanceUntilIdle()

        assertEquals(0.5f, viewModel.squelchLevel.value, 0.001f)
        val expectedMag = MorseDSPManager.squelchLevelToMagnitude(0.5f)
        assertEquals(expectedMag, dspManager.lastSetSquelch, 0.001)
    }

    @Test
    fun setTargetFrequency_updatesStateAndDspManager() = runTest {
        viewModel.setTargetFrequency(600.0)
        advanceUntilIdle()

        assertEquals(600.0, viewModel.uiState.value.targetFrequencyHz, 0.001)
        assertEquals(600.0, dspManager.lastSetFrequency, 0.001)
    }

    @Test
    fun startListening_triggersDspManager() = runTest {
        viewModel.startListening()
        advanceUntilIdle()

        assertTrue(dspManager.isListeningStarted)
        assertTrue(viewModel.uiState.value.hasRecordPermission)
    }

    @Test
    fun stopListening_stopsDspManager() = runTest {
        viewModel.startListening()
        advanceUntilIdle()

        viewModel.stopListening()
        advanceUntilIdle()

        assertTrue(dspManager.isListeningStopped)
    }

    @Test
    fun verifyKeyedCharacter_correctMatch_advancesAttemptsAndAccuracy() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val currentTarget = viewModel.uiState.value.targetCharacter
        assertTrue(currentTarget.isNotEmpty())

        viewModel.verifyKeyedCharacter(currentTarget)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.sessionTotalAttempts)
        assertEquals(1, state.sessionCorrectAttempts)
        assertEquals(100.0f, state.sessionAccuracy, 0.001f)
        assertEquals(true, state.lastKeyWasCorrect)
        assertEquals(currentTarget, state.lastKeyedCharacter)
        assertTrue(state.liveDecodedText.isEmpty())

        // Stat must be saved in database
        val stat = profileRepository.getStatForCharacter(testProfile.id, currentTarget)
        assertNotNull(stat)
        assertEquals(1, stat?.correctCount)
    }

    @Test
    fun verifyKeyedCharacter_incorrectMatch_retainsTargetAndDecreasesAccuracy() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val currentTarget = viewModel.uiState.value.targetCharacter
        val wrongChar = if (currentTarget == "K") "M" else "K"

        viewModel.verifyKeyedCharacter(wrongChar)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.sessionTotalAttempts)
        assertEquals(0, state.sessionCorrectAttempts)
        assertEquals(0.0f, state.sessionAccuracy, 0.001f)
        assertEquals(false, state.lastKeyWasCorrect)
        assertEquals(wrongChar, state.lastKeyedCharacter)
        assertEquals(currentTarget, state.targetCharacter) // Target preserved for retry!

        val stat = profileRepository.getStatForCharacter(testProfile.id, currentTarget)
        assertNotNull(stat)
        assertEquals(1, stat?.incorrectCount)
    }

    @Test
    fun verifyKeyedCharacter_promotesKochLevelAt90PercentAccuracy() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // minAttemptsForLevelUp = 5. Send 5 correct answers.
        repeat(5) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.verifyKeyedCharacter(target)
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(2, state.activeKochLevel)
        assertTrue(state.availableCharacters.contains("R"))
        assertNotNull(state.levelUpMessage)
        assertTrue(state.levelUpMessage?.contains("Level 2") == true)
    }

    @Test
    fun skipChallenge_loadsNewTargetFromPool() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.skipChallenge()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.targetCharacter in listOf("K", "M"))
        assertTrue(state.targetMorsePattern.isNotEmpty())
    }
}
