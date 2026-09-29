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
            minAttemptsForLevelUp = 10
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
    }

    @Test
    fun setActiveProfile_loadsLevelAndAvailableCharacters() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.activeProfile)
        assertEquals(testProfile.id, state.activeProfile?.id)
        assertEquals(1, state.activeKochLevel)
        assertEquals(listOf("K", "M"), state.availableCharacters)
        assertTrue(state.showStartLessonDialog)
        assertEquals("", state.targetCharacter)
        assertEquals(0, state.sessionTotalAttempts)

        // Starting lesson confirms and picks first target
        viewModel.startLesson()
        advanceUntilIdle()

        val startedState = viewModel.uiState.value
        assertFalse(startedState.showStartLessonDialog)
        assertTrue(startedState.targetCharacter in listOf("K", "M"))
        assertFalse(startedState.isReplayTone)
    }

    @Test
    fun startLessonDialog_preventsTargetAndAudioPlaybackUntilConfirmed() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Prior to confirmation: no target character and dialog is showing
        val state = viewModel.uiState.value
        assertTrue(state.showStartLessonDialog)
        assertEquals("", state.targetCharacter)
        assertFalse(state.hasTarget)

        // Attempting to play tone before confirmation does NOT engage the audio engine
        viewModel.playTone()
        advanceUntilIdle()
        assertTrue(fakeAudioGenerator.playedCharacters.isEmpty())

        // User confirms Start Lesson
        viewModel.startLesson()
        advanceUntilIdle()

        val confirmedState = viewModel.uiState.value
        assertFalse(confirmedState.showStartLessonDialog)
        assertTrue(confirmedState.targetCharacter.isNotEmpty())
        assertTrue(confirmedState.hasTarget)

        // Now audio engine can be engaged
        viewModel.playTone()
        advanceUntilIdle()
        assertEquals(1, fakeAudioGenerator.playedCharacters.size)
        assertEquals(confirmedState.targetCharacter, fakeAudioGenerator.playedCharacters.first())
    }

    @Test
    fun playReplayButtonState_togglesOnPlayAndResetsOnCorrectGuess() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        viewModel.startLesson()
        advanceUntilIdle()

        // New challenge starts with "Play Tone" state (isReplayTone = false)
        assertFalse(viewModel.uiState.value.isReplayTone)

        // Playing tone switches state to "Replay Tone" (isReplayTone = true)
        viewModel.playTone()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReplayTone)

        // Incorrect guess keeps or sets state to "Replay Tone"
        val target = viewModel.uiState.value.targetCharacter
        val wrongGuess = if (target == "K") "M" else "K"
        viewModel.submitGuess(wrongGuess)
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
        viewModel.startLesson()
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
        viewModel.startLesson()
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
        // Correct guess reduces priority weight (or stays at minWeight 1.0f)
        assertTrue(stat!!.priorityWeight <= 1.0f)
    }

    @Test
    fun submitGuess_incorrectGuessUpdatesRoomStatsAndAccuracy() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        viewModel.startLesson()
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
        // Incorrect guess increases priority weight > 1.0f
        assertTrue(stat!!.priorityWeight > 1.0f)
    }

    @Test
    fun levelProgression_reaches90PercentAccuracyThreshold_advancesKochLevelAndUpdatesProfileInRoom() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        viewModel.startLesson()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.activeKochLevel)

        // Make 9 correct guesses and 1 incorrect guess -> 9 / 10 = 90.0% accuracy
        for (i in 1..9) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.submitGuess(target)
            advanceUntilIdle()
        }

        // 9 attempts, all correct: total = 9, correct = 9 (100%), not yet at 10 min attempts
        assertEquals(9, viewModel.uiState.value.sessionTotalAttempts)
        assertEquals(1, viewModel.uiState.value.activeKochLevel)

        // 10th attempt: incorrect guess -> total = 10, correct = 9 -> exactly 90.0% accuracy!
        val target10 = viewModel.uiState.value.targetCharacter
        val wrongGuess = if (target10 == "K") "M" else "K"
        viewModel.submitGuess(wrongGuess)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Should have seamlessly leveled up to Koch Level 2!
        assertEquals(2, state.activeKochLevel)
        assertEquals(listOf("K", "M", "R"), state.availableCharacters)
        assertNotNull(state.levelUpMessage)
        assertTrue(state.levelUpMessage!!.contains("Level 2"))
        assertTrue(state.levelUpMessage!!.contains("R"))

        // Session score should reset for the new level
        assertEquals(0, state.sessionTotalAttempts)
        assertEquals(0, state.sessionCorrectAttempts)
        assertEquals(0.0f, state.sessionAccuracy, 0.001f)

        // Profile in database should be updated to Level 2
        val updatedProfile = fakeRepository.getProfileById(testProfile.id)
        assertNotNull(updatedProfile)
        assertEquals(2, updatedProfile?.currentKochLevel)
    }

    @Test
    fun levelProgression_belowThresholdDoesNotAdvance() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        viewModel.startLesson()
        advanceUntilIdle()

        // Make 8 correct guesses and 2 incorrect guesses -> 8 / 10 = 80.0% accuracy (< 90%)
        for (i in 1..8) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.submitGuess(target)
            advanceUntilIdle()
        }

        for (i in 1..2) {
            val target = viewModel.uiState.value.targetCharacter
            val wrongGuess = if (target == "K") "M" else "K"
            viewModel.submitGuess(wrongGuess)
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(10, state.sessionTotalAttempts)
        assertEquals(8, state.sessionCorrectAttempts)
        assertEquals(80.0f, state.sessionAccuracy, 0.001f)
        // Level remains 1
        assertEquals(1, state.activeKochLevel)
        assertNull(state.levelUpMessage)

        val profileInRepo = fakeRepository.getProfileById(testProfile.id)
        assertEquals(1, profileInRepo?.currentKochLevel)
    }

    @Test
    fun resetSession_clearsSessionScore() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        viewModel.startLesson()
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
}
