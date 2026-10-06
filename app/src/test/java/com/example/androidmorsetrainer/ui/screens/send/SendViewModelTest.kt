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
import org.junit.Assert.assertNull
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

    override suspend fun updateProfileKochLevel(profileId: Long, kochLevel: Int) {
        profilesFlow.update { list ->
            list.map { if (it.id == profileId) it.copy(currentKochLevel = kochLevel) else it }
        }
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

    private lateinit var testProfile: UserProfile

    @Before
    fun setUp() = runTest(testDispatcher) {
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
            sessionBatchSize = 5
        )

        val profileId = profileRepository.createProfile("Operator")
        testProfile = profileRepository.getProfileById(profileId)!!
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
        assertFalse(state.isSessionActive)
        assertFalse(state.isSessionFinished)
        assertEquals(5, state.sessionBatchSize)
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
        assertFalse(state.isSessionActive)
        assertFalse(state.isSessionFinished)
    }

    @Test
    fun setDrillLength_updatesSelectedLengthAndBatchSize() {
        viewModel.setDrillLength(50)
        assertEquals(50, viewModel.uiState.value.selectedDrillLength)
        assertEquals(50, viewModel.uiState.value.sessionBatchSize)

        viewModel.setDrillLength(100)
        assertEquals(100, viewModel.uiState.value.selectedDrillLength)
        assertEquals(100, viewModel.uiState.value.sessionBatchSize)
    }

    @Test
    fun startLesson_activatesSessionAndStartsListening() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.startLesson(5)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSessionActive)
        assertFalse(state.isSessionFinished)
        assertEquals(1, state.currentChallengeIndex)
        assertEquals(5, state.sessionBatchSize)
        assertTrue(state.targetCharacter in listOf("K", "M"))
        assertTrue(dspManager.isListeningStarted)
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
        viewModel.startLesson(5)
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
        assertEquals(VerificationStatus.IDLE, state.verificationStatus)

        // Stat must be saved in database
        val stat = profileRepository.getStatForCharacter(testProfile.id, currentTarget)
        assertNotNull(stat)
        assertEquals(1, stat?.correctCount)
    }

    @Test
    fun verifyKeyedCharacter_incorrectMatch_recordsIncorrectAttemptAndDecreasesAccuracy() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(5)
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

        val stat = profileRepository.getStatForCharacter(testProfile.id, currentTarget)
        assertNotNull(stat)
        assertEquals(1, stat?.incorrectCount)
    }

    @Test
    fun verifyKeyedCharacter_completesBatchAndPromotesKochLevelOnLatestCharacterMastery() = runTest {
        // v1.7 logic: Level 1 characters are "K" and "M". Latest introduced is "M".
        // Promotion criteria: attempts >= 5 and accuracy >= 70.0% on latest character "M".
        profileRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "M",
                correctCount = 4,
                incorrectCount = 0,
                priorityWeight = 1.0f
            )
        )

        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(5)
        advanceUntilIdle()

        // Key challenges correctly until latest character "M" reaches >= 5 attempts
        while (viewModel.uiState.value.activeKochLevel == 1 && !viewModel.uiState.value.isSessionFinished) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.verifyKeyedCharacter(target)
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(2, state.activeKochLevel)
        assertTrue(state.availableCharacters.contains("R"))
        assertNotNull(state.levelUpMessage)
        assertTrue(state.levelUpMessage?.contains("Level 2") == true)

        val updatedProfile = profileRepository.getProfileById(testProfile.id)
        assertEquals(2, updatedProfile?.currentKochLevel)
    }

    @Test
    fun returnToSetup_resetsSessionAndStartsListeningForCalibration() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(1)
        advanceUntilIdle()

        viewModel.verifyKeyedCharacter(viewModel.uiState.value.targetCharacter)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSessionFinished)

        viewModel.returnToSetup()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSessionActive)
        assertFalse(state.isSessionFinished)
        assertEquals(0, state.currentChallengeIndex)
        assertTrue(dspManager.isListeningStarted)
    }

    @Test
    fun adaptiveVisualHints_hidesHintWhenCharacterMastered() = runTest {
        // v1.7 proficiency: attempts >= 5 and accuracy >= 70.0%
        profileRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "K",
                correctCount = 5,
                incorrectCount = 0,
                priorityWeight = 1.0f
            )
        )

        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.masteredCharacters.contains("K"))
        assertFalse(state.masteredCharacters.contains("M"))

        val target = state.targetCharacter
        if (target == "K") {
            assertFalse(state.showTargetHint)
        } else {
            assertTrue(state.showTargetHint)
        }
    }

    @Test
    fun skipChallenge_advancesChallengeInBatch() = runTest {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(5)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentChallengeIndex)

        viewModel.skipChallenge()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.currentChallengeIndex)
        assertTrue(state.targetCharacter in listOf("K", "M"))
        assertTrue(state.targetMorsePattern.isNotEmpty())
    }
}
