package com.example.androidmorsetrainer.ui.screens.train

import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import com.example.androidmorsetrainer.morse.KochMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.random.Random

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

class FakeMorseAudioGenerator : MorseAudioGenerator() {
    val playedCharacters = mutableListOf<String>()

    override suspend fun playCharacter(character: String) {
        playedCharacters.add(character)
    }

    override fun stop() {}
    override fun release() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeProfileRepository
    private lateinit var kochMethodManager: KochMethodManager
    private lateinit var fakeAudioGenerator: FakeMorseAudioGenerator
    private lateinit var viewModel: TrainViewModel
    private lateinit var testProfile: UserProfile

    @Before
    fun setUp() = runTest(testDispatcher) {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeProfileRepository()
        kochMethodManager = KochMethodManager(random = Random(12345))
        fakeAudioGenerator = FakeMorseAudioGenerator()
        viewModel = TrainViewModel(
            profileRepository = fakeRepository,
            kochMethodManager = kochMethodManager,
            audioGenerator = fakeAudioGenerator,
            ioDispatcher = testDispatcher,
            defaultDrillLength = 20
        )

        val profileId = fakeRepository.createProfile("Test Trainee")
        testProfile = fakeRepository.getProfileById(profileId)!!
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_emptyBeforeActiveProfileSet() {
        val state = viewModel.uiState.value
        assertNull(state.activeProfile)
        assertEquals(1, state.activeKochLevel)
        assertTrue(state.availableCharacters.isEmpty())
        assertEquals("", state.targetCharacter)
        assertEquals(0, state.sessionTotalAttempts)
        assertEquals(0, state.sessionCorrectAttempts)
        assertEquals(0.0f, state.sessionAccuracy, 0.001f)
        assertFalse(state.isSessionActive)
        assertFalse(state.isSessionFinished)
        assertEquals(20, state.selectedDrillLength)
    }

    @Test
    fun setActiveProfile_loadsLevelAndEntersSetupMode() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.activeProfile)
        assertEquals(testProfile.id, state.activeProfile?.id)
        assertEquals(1, state.activeKochLevel)
        assertEquals(listOf("K", "M"), state.availableCharacters)
        assertFalse(state.isSessionActive)
        assertFalse(state.isSessionFinished)
        assertEquals("", state.targetCharacter)
        assertEquals(0, state.sessionTotalAttempts)

        // Starting drill enters active session and selects first challenge
        viewModel.startLesson(20)
        advanceUntilIdle()

        val startedState = viewModel.uiState.value
        assertTrue(startedState.isSessionActive)
        assertFalse(startedState.isSessionFinished)
        assertEquals(1, startedState.currentChallengeIndex)
        assertEquals(20, startedState.sessionBatchSize)
        assertTrue(startedState.targetCharacter in listOf("K", "M"))
        assertFalse(startedState.isReplayTone)
    }

    @Test
    fun preDrillSetup_preventsTargetAndAudioPlaybackUntilStarted() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Prior to starting: no target character, session not active
        val state = viewModel.uiState.value
        assertFalse(state.isSessionActive)
        assertEquals("", state.targetCharacter)
        assertFalse(state.hasTarget)

        // Attempting to play tone before starting does NOT engage audio generator
        viewModel.playTone()
        advanceUntilIdle()
        assertTrue(fakeAudioGenerator.playedCharacters.isEmpty())

        // User starts drill
        viewModel.startLesson(50)
        advanceUntilIdle()

        val confirmedState = viewModel.uiState.value
        assertTrue(confirmedState.isSessionActive)
        assertEquals(50, confirmedState.sessionBatchSize)
        assertTrue(confirmedState.targetCharacter.isNotEmpty())
        assertTrue(confirmedState.hasTarget)

        // Now audio generator can be engaged
        viewModel.playTone()
        advanceUntilIdle()
        assertEquals(1, fakeAudioGenerator.playedCharacters.size)
        assertEquals(confirmedState.targetCharacter, fakeAudioGenerator.playedCharacters.first())
    }

    @Test
    fun setDrillLength_updatesSelectedLengthAndBatchSize() = runTest(testDispatcher) {
        viewModel.setDrillLength(100)
        assertEquals(100, viewModel.uiState.value.selectedDrillLength)
        assertEquals(100, viewModel.uiState.value.sessionBatchSize)

        viewModel.setDrillLength(50)
        assertEquals(50, viewModel.uiState.value.selectedDrillLength)
        assertEquals(50, viewModel.uiState.value.sessionBatchSize)
    }

    @Test
    fun playReplayButtonState_togglesOnPlayAndResetsOnCorrectGuess() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        // New challenge starts with "Play Tone" state (isReplayTone = false)
        assertFalse(viewModel.uiState.value.isReplayTone)

        // Playing tone switches state to "Replay Tone" (isReplayTone = true)
        viewModel.playTone()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReplayTone)

        // Correct guess generates a new challenge and explicitly resets button state back to "Play Tone"
        val currentTarget = viewModel.uiState.value.targetCharacter
        viewModel.submitGuess(currentTarget)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isReplayTone)
    }

    @Test
    fun playTone_triggersAudioGeneratorOnBackgroundThread() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        val currentTarget = viewModel.uiState.value.targetCharacter
        assertTrue(currentTarget.isNotEmpty())

        viewModel.playTone()
        advanceUntilIdle()

        assertEquals(1, fakeAudioGenerator.playedCharacters.size)
        assertEquals(currentTarget, fakeAudioGenerator.playedCharacters.first())
    }

    @Test
    fun submitGuess_correctGuessUpdatesRoomStatsAndAccuracy() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        val target = viewModel.uiState.value.targetCharacter
        viewModel.submitGuess(target)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.sessionTotalAttempts)
        assertEquals(1, state.sessionCorrectAttempts)
        assertEquals(100.0f, state.sessionAccuracy, 0.001f)
        assertEquals(true, state.lastGuessWasCorrect)
        assertEquals(target, state.lastGuessedCharacter)

        val stat = fakeRepository.getStatForCharacter(testProfile.id, target)
        assertNotNull(stat)
        assertEquals(1, stat?.correctCount)
        assertEquals(0, stat?.incorrectCount)
        assertTrue(stat!!.priorityWeight <= 1.0f)
    }

    @Test
    fun submitGuess_incorrectGuessUpdatesRoomStatsAndAccuracy() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        val target = viewModel.uiState.value.targetCharacter
        val wrongGuess = if (target == "K") "M" else "K"

        viewModel.submitGuess(wrongGuess)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.sessionTotalAttempts)
        assertEquals(0, state.sessionCorrectAttempts)
        assertEquals(0.0f, state.sessionAccuracy, 0.001f)
        assertEquals(false, state.lastGuessWasCorrect)
        assertEquals(wrongGuess, state.lastGuessedCharacter)

        val stat = fakeRepository.getStatForCharacter(testProfile.id, target)
        assertNotNull(stat)
        assertEquals(0, stat?.correctCount)
        assertEquals(1, stat?.incorrectCount)
        assertTrue(stat!!.priorityWeight > 1.0f)
    }

    @Test
    fun levelProgression_latestCharacterMeetsV17Threshold_advancesKochLevel() = runTest(testDispatcher) {
        // v1.7 logic: Level 1 characters are "K" and "M". Latest introduced is "M".
        // Promotion criteria: attempts >= 5 and accuracy >= 70.0% on latest character "M".
        fakeRepository.saveCharacterStat(
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
        viewModel.startLesson(20)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.activeKochLevel)

        // Answer challenges correctly until latest character "M" is evaluated
        while (viewModel.uiState.value.activeKochLevel == 1 && !viewModel.uiState.value.isSessionFinished) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.submitGuess(target)
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        // Latest character "M" now reached >= 5 attempts, >= 70% accuracy -> advances to Level 2!
        assertEquals(2, state.activeKochLevel)
        assertTrue(state.availableCharacters.contains("R"))
        assertNotNull(state.levelUpMessage)
        assertTrue(state.levelUpMessage!!.contains("Level 2"))

        // Profile in database is updated
        val updatedProfile = fakeRepository.getProfileById(testProfile.id)
        assertEquals(2, updatedProfile?.currentKochLevel)
    }

    @Test
    fun levelProgression_belowV17ThresholdDoesNotAdvance() = runTest(testDispatcher) {
        // Latest character "M" has 5 attempts, but only 2 correct (40% accuracy < 70%)
        fakeRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "M",
                correctCount = 2,
                incorrectCount = 3,
                priorityWeight = 1.0f
            )
        )
        // Earlier character "K" has 100% accuracy, but advancement only checks latest "M"
        fakeRepository.saveCharacterStat(
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
        viewModel.startLesson(20)
        advanceUntilIdle()

        val target = viewModel.uiState.value.targetCharacter
        viewModel.submitGuess(target)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.activeKochLevel)
        assertNull(state.levelUpMessage)
    }

    @Test
    fun drillLifecycle_completesBatchAndHaltsAtSummary() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        // Start drill with small batch of 3
        viewModel.startLesson(3)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentChallengeIndex)
        assertEquals(3, viewModel.uiState.value.sessionBatchSize)

        // Guess 1
        viewModel.submitGuess(viewModel.uiState.value.targetCharacter)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSessionFinished)

        // Guess 2
        viewModel.submitGuess(viewModel.uiState.value.targetCharacter)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSessionFinished)

        // Guess 3 (completes batch)
        viewModel.submitGuess(viewModel.uiState.value.targetCharacter)
        advanceUntilIdle()

        val finishedState = viewModel.uiState.value
        assertTrue(finishedState.isSessionFinished)
        assertFalse(finishedState.isSessionActive)
        assertEquals(3, finishedState.sessionTotalAttempts)
        assertEquals(3, finishedState.sessionCorrectAttempts)
    }

    @Test
    fun returnToSetup_resetsSessionAndReturnsToSetupMode() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(2)
        advanceUntilIdle()

        repeat(2) {
            viewModel.submitGuess(viewModel.uiState.value.targetCharacter)
            advanceUntilIdle()
        }

        assertTrue(viewModel.uiState.value.isSessionFinished)

        viewModel.returnToSetup()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSessionActive)
        assertFalse(state.isSessionFinished)
        assertEquals(0, state.currentChallengeIndex)
        assertEquals("", state.targetCharacter)
    }

    @Test
    fun resetSession_clearsSessionScore() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        viewModel.submitGuess(viewModel.uiState.value.targetCharacter)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.sessionTotalAttempts)

        viewModel.resetSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.sessionTotalAttempts)
        assertEquals(0, state.sessionCorrectAttempts)
        assertEquals(0.0f, state.sessionAccuracy, 0.001f)
        assertNull(state.lastGuessedCharacter)
        assertNull(state.lastGuessWasCorrect)
        assertTrue(state.targetCharacter.isNotEmpty())
    }

    @Test
    fun adaptiveVisualHints_characterMastered_addedToMasteredSet() = runTest(testDispatcher) {
        // v1.7 proficiency: attempts >= 5 and accuracy >= 70.0%
        fakeRepository.saveCharacterStat(
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
    }
}
