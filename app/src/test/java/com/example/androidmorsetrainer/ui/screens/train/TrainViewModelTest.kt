package com.example.androidmorsetrainer.ui.screens.train

import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import com.example.androidmorsetrainer.morse.KochMethodManager
import com.example.androidmorsetrainer.morse.MorseConstants
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
        assertTrue(startedState.isReplayTone)
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

        // User starts drill (which automatically sounds the first challenge tone)
        viewModel.startLesson(50)
        advanceUntilIdle()

        val confirmedState = viewModel.uiState.value
        assertTrue(confirmedState.isSessionActive)
        assertEquals(50, confirmedState.sessionBatchSize)
        assertTrue(confirmedState.targetCharacter.isNotEmpty())
        assertTrue(confirmedState.hasTarget)
        assertEquals(1, fakeAudioGenerator.playedCharacters.size)
        assertEquals(confirmedState.targetCharacter, fakeAudioGenerator.playedCharacters.first())

        // Now audio generator can be engaged for replaying tone
        viewModel.playTone()
        advanceUntilIdle()
        assertEquals(2, fakeAudioGenerator.playedCharacters.size)
        assertEquals(confirmedState.targetCharacter, fakeAudioGenerator.playedCharacters[1])
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

        // Starting drill auto-plays tone and sets button state to "Replay Tone" (isReplayTone = true)
        assertTrue(viewModel.uiState.value.isReplayTone)

        // Replaying tone maintains state as "Replay Tone"
        viewModel.playTone()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReplayTone)
    }

    @Test
    fun playTone_triggersAudioGeneratorOnBackgroundThread() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        val currentTarget = viewModel.uiState.value.targetCharacter
        assertTrue(currentTarget.isNotEmpty())
        assertEquals(1, fakeAudioGenerator.playedCharacters.size)

        viewModel.playTone()
        advanceUntilIdle()

        assertEquals(2, fakeAudioGenerator.playedCharacters.size)
        assertEquals(currentTarget, fakeAudioGenerator.playedCharacters[1])
    }

    @Test
    fun submitGuess_correctGuessUpdatesRoomStatsAndAccuracy() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        val target = viewModel.uiState.value.targetCharacter
        viewModel.submitGuess(target)
        testDispatcher.scheduler.advanceTimeBy(500)

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
        testDispatcher.scheduler.advanceTimeBy(500)

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
    fun setWpm_updatesUiStateAndAudioGenerator() = runTest(testDispatcher) {
        assertEquals(20, viewModel.uiState.value.currentWpm)
        viewModel.setWpm(15)
        assertEquals(15, viewModel.uiState.value.currentWpm)
        assertEquals(15, fakeAudioGenerator.wpm)
    }

    @Test
    fun setWpm_clampsBetween10And25() = runTest(testDispatcher) {
        viewModel.setWpm(5)
        assertEquals(10, viewModel.uiState.value.currentWpm)
        assertEquals(10, fakeAudioGenerator.wpm)

        viewModel.setWpm(30)
        assertEquals(25, viewModel.uiState.value.currentWpm)
        assertEquals(25, fakeAudioGenerator.wpm)
    }

    @Test
    fun playTone_routesCurrentWpmToAudioGenerator() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        viewModel.setWpm(18)
        viewModel.playTone()
        advanceUntilIdle()

        assertEquals(18, fakeAudioGenerator.wpm)
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

        // Active drill is preserved and continues running seamlessly
        assertTrue(state.isSessionActive)
        assertEquals(DrillState.DrillActive, state.drillState)
        assertFalse(state.isSessionFinished)

        // Profile in database is updated
        val updatedProfile = fakeRepository.getProfileById(testProfile.id)
        assertEquals(2, updatedProfile?.currentKochLevel)

        // Simulating the UI lifecycle / observer re-submitting the updated profile:
        // Must NOT reset the active drill back to DrillSetup!
        viewModel.setActiveProfile(updatedProfile)
        advanceUntilIdle()

        val stateAfterProfileUpdate = viewModel.uiState.value
        assertTrue(stateAfterProfileUpdate.isSessionActive)
        assertEquals(DrillState.DrillActive, stateAfterProfileUpdate.drillState)
        assertEquals(2, stateAfterProfileUpdate.activeKochLevel)
    }

    @Test
    fun levelAdvancement_doesNotInterruptActiveDrillUntilBatchCompletes() = runTest(testDispatcher) {
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

        viewModel.startLesson(5)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.currentChallengeIndex)
        assertEquals(DrillState.DrillActive, viewModel.uiState.value.drillState)

        // Advance to Level 2
        while (viewModel.uiState.value.activeKochLevel == 1) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.submitGuess(target)
            advanceUntilIdle()
        }

        assertEquals(2, viewModel.uiState.value.activeKochLevel)
        assertNotNull(viewModel.uiState.value.levelUpMessage)

        // The drill must NOT halt or complete early; it must continue running
        assertTrue(viewModel.uiState.value.isSessionActive)
        assertEquals(DrillState.DrillActive, viewModel.uiState.value.drillState)

        // Complete remaining challenges up to batch size 5
        while (!viewModel.uiState.value.isSessionFinished) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.submitGuess(target)
            advanceUntilIdle()
        }

        assertEquals(DrillState.Finished, viewModel.uiState.value.drillState)
        assertEquals(5, viewModel.uiState.value.sessionTotalAttempts)
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

    @Test
    fun quitDrill_terminatesActiveDrillAndUpdatesMetrics() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()
        viewModel.startLesson(20)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSessionActive)

        // Make 1 correct guess
        viewModel.submitGuess(viewModel.uiState.value.targetCharacter)
        testDispatcher.scheduler.advanceTimeBy(500)

        // Trainee decides to quit the drill
        viewModel.quitDrill()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DrillState.DrillSetup, state.drillState)
        assertFalse(state.isSessionActive)
        assertEquals(0, state.currentChallengeIndex)
        assertEquals("", state.targetCharacter)
        assertEquals(100, state.lastDrillAccuracy)
        assertEquals(100, state.overallAccuracy)
    }

    @Test
    fun endOfSession_calculatesOverallAndLastDrillAccuracy() = runTest(testDispatcher) {
        // Pre-populate historical stats: K has 3 correct, 1 incorrect (75%)
        fakeRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "K",
                correctCount = 3,
                incorrectCount = 1,
                priorityWeight = 1.0f
            )
        )

        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Initial career overall accuracy is 75% (3/4)
        assertEquals(75, viewModel.uiState.value.overallAccuracy)

        // Start small drill of 2 challenges
        viewModel.startLesson(2)
        advanceUntilIdle()

        // 1 correct, 1 incorrect in this drill -> drill accuracy = 50%
        val firstTarget = viewModel.uiState.value.targetCharacter
        viewModel.submitGuess(firstTarget) // Correct
        advanceUntilIdle()

        val secondTarget = viewModel.uiState.value.targetCharacter
        val wrongGuess = if (secondTarget == "K") "M" else "K"
        viewModel.submitGuess(wrongGuess) // Incorrect
        advanceUntilIdle()

        val finished = viewModel.uiState.value
        assertTrue(finished.isSessionFinished)
        assertEquals(50, finished.lastDrillAccuracy)
        // Career overall: previously 3/4 + new 1/2 = 4 correct / 6 total = 67%
        assertEquals(67, finished.overallAccuracy)
    }

    @Test
    fun initialProfile_setsActiveNewCharacterAndVisualAid() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("K", state.newlyIntroducedCharacter)
        assertEquals("-.-", state.newCharacterDotRepresentation)
        assertEquals("K -.-", state.newCharacterVisualAid)
        assertTrue(state.showNewCharacterVisualAid)
    }

    @Test
    fun rollingAccuracyThresholdMet_togglesVisualAidState() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.startLesson(50)
        advanceUntilIdle()

        // Submit correct guesses until active character "K" meets threshold
        while (viewModel.uiState.value.newlyIntroducedCharacter == "K" && !viewModel.uiState.value.isSessionFinished) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.submitGuess(target)
            advanceUntilIdle()
        }

        val stateAfterK = viewModel.uiState.value
        // "K" met threshold, so active new character at Level 1 progresses to "M"
        if (stateAfterK.activeKochLevel == 1) {
            assertEquals("M", stateAfterK.newlyIntroducedCharacter)
            assertEquals("--", stateAfterK.newCharacterDotRepresentation)
            assertEquals("M --", stateAfterK.newCharacterVisualAid)
            assertTrue(stateAfterK.showNewCharacterVisualAid)
        } else {
            // If Level 1 already completed and advanced to Level 2
            assertEquals(2, stateAfterK.activeKochLevel)
            assertEquals("R", stateAfterK.newlyIntroducedCharacter)
            assertEquals(".-.", stateAfterK.newCharacterDotRepresentation)
            assertEquals("R .-.", stateAfterK.newCharacterVisualAid)
            assertTrue(stateAfterK.showNewCharacterVisualAid)
        }
    }

    @Test
    fun profileWithAllLevelCharactersMastered_togglesVisualAidOff() = runTest(testDispatcher) {
        // Pre-master both K and M at level 1
        fakeRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "K",
                correctCount = 5,
                incorrectCount = 0,
                priorityWeight = 1.0f
            )
        )
        fakeRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "M",
                correctCount = 5,
                incorrectCount = 0,
                priorityWeight = 1.0f
            )
        )

        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.newlyIntroducedCharacter)
        assertNull(state.newCharacterDotRepresentation)
        assertNull(state.newCharacterVisualAid)
        assertFalse(state.showNewCharacterVisualAid)
    }

    @Test
    fun activeChallenge_mapsActiveHintCharacterAndDotRepToCurrentTarget() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.startLesson(20)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val currentTarget = state.targetCharacter
        assertTrue(currentTarget.isNotEmpty())
        assertEquals(currentTarget, state.activeHintCharacter)
        assertEquals(MorseConstants.MORSE_MAP[currentTarget.uppercase()], state.activeHintDotRep)

        // Submit guess and verify next challenge updates activeHintCharacter and activeHintDotRep to match
        viewModel.submitGuess(currentTarget)
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        val nextState = viewModel.uiState.value
        if (!nextState.isSessionFinished) {
            val nextTarget = nextState.targetCharacter
            assertTrue(nextTarget.isNotEmpty())
            assertEquals(nextTarget, nextState.activeHintCharacter)
            assertEquals(MorseConstants.MORSE_MAP[nextTarget.uppercase()], nextState.activeHintDotRep)
        }
    }

    @Test
    fun sixSuccessfulChallenges_hidesVisualAid() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.startLesson(50)
        advanceUntilIdle()

        val activeNewChar = viewModel.uiState.value.newlyIntroducedCharacter
        assertNotNull(activeNewChar)
        assertTrue(viewModel.uiState.value.isVisualAidActive)
        assertEquals(0, viewModel.uiState.value.activeNewCharacterSuccessCount)

        var successfulChallenges = 0
        while (successfulChallenges < 6 && !viewModel.uiState.value.isSessionFinished) {
            val target = viewModel.uiState.value.targetCharacter
            val isTargetActiveChar = target.equals(activeNewChar, ignoreCase = true)

            // Submit correct guess for active character, or incorrect for other characters to prevent premature level advancement
            val guess = if (isTargetActiveChar) target else "X"
            viewModel.submitGuess(guess)
            testScheduler.advanceTimeBy(1600)
            advanceUntilIdle()

            if (isTargetActiveChar) {
                successfulChallenges++
                if (successfulChallenges < 6) {
                    assertEquals(successfulChallenges, viewModel.uiState.value.activeNewCharacterSuccessCount)
                    assertTrue(viewModel.uiState.value.isVisualAidActive)
                }
            }
        }

        // Once exactly 6 successful challenges are completed for the active character:
        assertEquals(6, viewModel.uiState.value.activeNewCharacterSuccessCount)
        assertFalse(viewModel.uiState.value.isVisualAidActive)
        assertFalse(viewModel.uiState.value.showNewCharacterVisualAid)
        assertNull(viewModel.uiState.value.visualAidText)
        assertNull(viewModel.uiState.value.newCharacterVisualAid)
        assertNull(viewModel.uiState.value.activeHintCharacter)
        assertNull(viewModel.uiState.value.activeHintDotRep)
    }

    @Test
    fun incorrectGuess_doesNotIncrementSuccessCount() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.startLesson(20)
        advanceUntilIdle()

        val initialCount = viewModel.uiState.value.activeNewCharacterSuccessCount
        assertEquals(0, initialCount)

        val target = viewModel.uiState.value.targetCharacter
        val wrongGuess = if (target == "K") "M" else "K"

        viewModel.submitGuess(wrongGuess)
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.activeNewCharacterSuccessCount)
        assertTrue(viewModel.uiState.value.isVisualAidActive)
    }

    @Test
    fun historicalSuccessThreshold_persistsAndHidesVisualAidOnProfileLoad() = runTest(testDispatcher) {
        fakeRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "K",
                correctCount = 6,
                incorrectCount = 0,
                priorityWeight = 1.0f
            )
        )

        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // "K" already completed 6 successful challenges, so next active new char at Level 1 is "M"
        assertEquals("M", state.newlyIntroducedCharacter)
        assertEquals("M --", state.newCharacterVisualAid)
        assertTrue(state.showNewCharacterVisualAid)
    }

    @Test
    fun perCharacterHintEvaluation_showsAndHidesIndependentlyForKAndM() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.startLesson(50)
        advanceUntilIdle()

        // Submit 6 correct guesses for "K" while keeping "M" with 0 successes
        var kSuccesses = 0
        while (kSuccesses < 6 && !viewModel.uiState.value.isSessionFinished) {
            val target = viewModel.uiState.value.targetCharacter
            if (target == "K") {
                viewModel.submitGuess("K")
                kSuccesses++
            } else {
                viewModel.submitGuess("X") // incorrect for M
            }
            testScheduler.advanceTimeBy(1600)
            advanceUntilIdle()
        }

        // Now "K" has 6 successful challenges, "M" has 0
        assertEquals(6, viewModel.uiState.value.characterSuccessCounts["K"])
        assertEquals(0, viewModel.uiState.value.characterSuccessCounts["M"])

        // In active challenges:
        // When target is "K", visual aid must be hidden
        // When target is "M", visual aid must still be active
        for (i in 0 until 5) {
            if (viewModel.uiState.value.isSessionFinished) break
            val currentTarget = viewModel.uiState.value.targetCharacter
            if (currentTarget == "K") {
                assertFalse("K reached 6 successes, hint should be hidden", viewModel.uiState.value.isVisualAidActive)
                assertNull(viewModel.uiState.value.visualAidText)
            } else if (currentTarget == "M") {
                assertTrue("M has < 6 successes, hint should be visible", viewModel.uiState.value.isVisualAidActive)
                assertEquals("M", viewModel.uiState.value.activeHintCharacter)
                assertEquals("--", viewModel.uiState.value.activeHintDotRep)
                assertEquals("M --", viewModel.uiState.value.visualAidText)
            }
            viewModel.submitGuess(if (currentTarget == "M") "M" else "K")
            testScheduler.advanceTimeBy(1600)
            advanceUntilIdle()
        }
    }

    @Test
    fun levelIncrease_newCharacterShowsHintsUntilThreshold_masteredDoNotReTrigger() = runTest(testDispatcher) {
        // Pre-populate K and M as completed / mastered at Level 1 (6 successes each)
        fakeRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "K",
                correctCount = 6,
                incorrectCount = 0,
                priorityWeight = 1.0f,
                successfulChallenges = 6
            )
        )
        fakeRepository.saveCharacterStat(
            CharacterStats(
                profileId = testProfile.id,
                character = "M",
                correctCount = 6,
                incorrectCount = 0,
                priorityWeight = 1.0f,
                successfulChallenges = 6
            )
        )

        // Advance profile to Level 2
        fakeRepository.updateProfileKochLevel(testProfile.id, 2)
        val level2Profile = fakeRepository.getProfileById(testProfile.id)!!

        viewModel.setActiveProfile(level2Profile)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.activeKochLevel)
        assertEquals("R", viewModel.uiState.value.newlyIntroducedCharacter)
        assertEquals("R .-.", viewModel.uiState.value.newCharacterVisualAid)

        viewModel.startLesson(50)
        advanceUntilIdle()

        // Test hints for challenges at Level 2
        var rAttempts = 0
        for (i in 0 until 12) {
            if (viewModel.uiState.value.isSessionFinished) break
            val target = viewModel.uiState.value.targetCharacter
            val rSuccesses = viewModel.uiState.value.characterSuccessCounts["R"] ?: 0

            if (target == "R") {
                if (rSuccesses < 6) {
                    assertTrue("R with < 6 successes should show hint", viewModel.uiState.value.isVisualAidActive)
                    assertEquals("R", viewModel.uiState.value.activeHintCharacter)
                    assertEquals(".-.", viewModel.uiState.value.activeHintDotRep)
                } else {
                    assertFalse("R with >= 6 successes should hide hint", viewModel.uiState.value.isVisualAidActive)
                }
                rAttempts++
            } else if (target in listOf("K", "M")) {
                // K and M are previously mastered characters, so they must NEVER show hints
                assertFalse("Mastered character $target must not re-trigger hint", viewModel.uiState.value.isVisualAidActive)
                assertNull(viewModel.uiState.value.visualAidText)
            }

            // Answer incorrectly for R on specific attempts to keep accuracy < 70% and prevent premature promotion to Level 3
            val guess = if (target == "R" && (rAttempts == 1 || rAttempts == 3 || rAttempts == 5)) "X" else target
            viewModel.submitGuess(guess)
            testScheduler.advanceTimeBy(1600)
            advanceUntilIdle()
        }
    }

    @Test
    fun characterSuccessCountsAndAccuracies_trackedIndependentlyInStateAndRepository() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.startLesson(10)
        advanceUntilIdle()

        val firstTarget = viewModel.uiState.value.targetCharacter
        // Make 1 correct guess
        viewModel.submitGuess(firstTarget)
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        val stateAfterFirst = viewModel.uiState.value
        assertEquals(1, stateAfterFirst.characterSuccessCounts[firstTarget.uppercase()])
        assertEquals(100.0f, stateAfterFirst.characterAccuracies[firstTarget.uppercase()] ?: 0f, 0.01f)

        // Verify Room persistence of successfulChallenges
        val persistedStat = fakeRepository.getStatForCharacter(testProfile.id, firstTarget)
        assertNotNull(persistedStat)
        assertEquals(1, persistedStat?.successfulChallenges)
        assertEquals(1, persistedStat?.correctCount)
        assertEquals(1, persistedStat?.totalAttempts)
    }
}
