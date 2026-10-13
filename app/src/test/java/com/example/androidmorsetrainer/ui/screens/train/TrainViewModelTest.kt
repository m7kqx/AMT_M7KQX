package com.example.androidmorsetrainer.ui.screens.train

import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import com.example.androidmorsetrainer.morse.KochMethodManager
import com.example.androidmorsetrainer.morse.MorseConstants
import com.example.androidmorsetrainer.data.local.entity.TimeAttackChallengeResult
import com.example.androidmorsetrainer.data.local.entity.TimeAttackOutcome
import com.example.androidmorsetrainer.data.local.entity.TimeAttackReport
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

    override suspend fun updatePreferredWpm(profileId: Long, wpm: Int) {
        profilesFlow.update { list -> list.map { if (it.id == profileId) it.copy(preferredWpm = wpm) else it } }
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

    private val timeAttackReportsList = mutableListOf<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport>()
    private var nextReportId = 1L

    override suspend fun saveCharacterStat(stat: CharacterStats) {
        characterStatsMap[Pair(stat.profileId, stat.character)] = stat
    }

    override suspend fun saveTimeAttackReport(report: com.example.androidmorsetrainer.data.local.entity.TimeAttackReport): Long {
        val saved = report.copy(id = nextReportId++)
        timeAttackReportsList.add(0, saved)
        return saved.id
    }

    override suspend fun getTimeAttackReports(profileId: Long): List<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport> {
        return timeAttackReportsList.filter { it.profileId == profileId }
    }

    override fun getTimeAttackReportsFlow(profileId: Long): Flow<List<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport>> {
        return MutableStateFlow(timeAttackReportsList.filter { it.profileId == profileId })
    }
}

class FakeMorseAudioGenerator : MorseAudioGenerator() {
    val playedCharacters = mutableListOf<String>()
    val playedContinuousProsigns = mutableListOf<Boolean>()

    override suspend fun playCharacter(character: String, speedWpm: Int?, useContinuousProsign: Boolean) {
        playedCharacters.add(character)
        playedContinuousProsigns.add(useContinuousProsign)
    }

    override suspend fun playCharacter(character: String, useContinuousProsign: Boolean) {
        playedCharacters.add(character)
        playedContinuousProsigns.add(useContinuousProsign)
    }

    override suspend fun playCharacter(character: String) {
        playedCharacters.add(character)
        playedContinuousProsigns.add(false)
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

    @Test
    fun setTrainingMode_prosigns_updatesAvailableCharactersAndProgression() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Default mode is Koch
        assertEquals(TrainingMode.Koch, viewModel.uiState.value.trainingMode)
        assertEquals(listOf("K", "M"), viewModel.uiState.value.availableCharacters)
        assertEquals(1, viewModel.uiState.value.currentLevel)

        // Switch to Prosigns mode
        viewModel.setTrainingMode(TrainingMode.Prosigns)
        advanceUntilIdle()

        val prosignState = viewModel.uiState.value
        assertEquals(TrainingMode.Prosigns, prosignState.trainingMode)
        assertEquals(testProfile.currentProsignLevel, prosignState.activeProsignLevel)
        assertEquals(1, prosignState.currentLevel)
        assertEquals(listOf(MorseConstants.PROSIGNS_SEQUENCE[0]), prosignState.availableCharacters)
    }

    @Test
    fun submitGuess_prosignReceiveTraining_evaluatesImmediatelyWithoutLengthRestriction() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.setTrainingMode(TrainingMode.Prosigns)
        advanceUntilIdle()

        viewModel.startLesson(10)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSessionActive)
        val target = state.targetCharacter
        assertTrue(target.isNotEmpty())

        // Target at Level 1 is PROSIGNS_SEQUENCE[0] which is "<AR>"
        assertEquals("<AR>", target)
        assertEquals(0, viewModel.uiState.value.sessionTotalAttempts)

        // Submitting just "A" (short partial input, 1 char vs 2) should evaluate immediately rather than waiting
        viewModel.submitGuess("A")
        testScheduler.runCurrent()

        val stateShowingResult = viewModel.uiState.value
        assertEquals(DrillState.ShowingResult, stateShowingResult.drillState)
        assertEquals(1, stateShowingResult.sessionTotalAttempts)
        assertEquals(false, stateShowingResult.lastGuessWasCorrect)

        // After the 1.5s result display, it advances to the next challenge
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.currentChallengeIndex)
    }

    @Test
    fun prosignMode_initialDotrepHintVisibleOnFirstAttempt() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.setTrainingMode(TrainingMode.Prosigns)
        advanceUntilIdle()

        // Setup state should have <AR> visual aid active
        val setupState = viewModel.uiState.value
        assertEquals("<AR>", setupState.newlyIntroducedCharacter)
        assertEquals(".-.-.", setupState.newCharacterDotRepresentation)
        assertEquals("<AR> .-.-.", setupState.newCharacterVisualAid)
        assertTrue("Setup state should have visual aid active for new prosign", setupState.isVisualAidActive)

        viewModel.startLesson(20)
        advanceUntilIdle()

        val activeState = viewModel.uiState.value
        assertEquals("<AR>", activeState.targetCharacter)
        assertTrue("Prosign hint must be visible on the first challenge", activeState.isVisualAidActive)
        assertEquals("<AR>", activeState.activeHintCharacter)
        assertEquals(".-.-.", activeState.activeHintDotRep)
        assertEquals("<AR> .-.-.", activeState.visualAidText)
    }

    @Test
    fun prosignMode_dotrepHintDynamicallyHidesAfterThreeSuccessfulInputs() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.setTrainingMode(TrainingMode.Prosigns)
        advanceUntilIdle()

        viewModel.startLesson(20)
        advanceUntilIdle()

        // Attempt 1: 0 successes, hint must be visible
        assertEquals(0, viewModel.uiState.value.characterSuccessCounts["<AR>"] ?: 0)
        assertTrue("Hint must be visible at 0 successes", viewModel.uiState.value.isVisualAidActive)
        assertEquals("<AR> .-.-.", viewModel.uiState.value.visualAidText)

        // Make an incorrect attempt first so accuracy stays < 90% (preventing promotion to level 2)
        viewModel.submitGuess("X")
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.characterSuccessCounts["<AR>"] ?: 0)
        assertTrue("Hint must remain visible after incorrect guess", viewModel.uiState.value.isVisualAidActive)

        // Success 1
        viewModel.submitGuess("AR")
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.characterSuccessCounts["<AR>"])
        assertTrue("Hint must remain visible after 1 success", viewModel.uiState.value.isVisualAidActive)
        assertEquals("<AR> .-.-.", viewModel.uiState.value.visualAidText)

        // Success 2
        viewModel.submitGuess("AR")
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.characterSuccessCounts["<AR>"])
        assertTrue("Hint must remain visible after 2 successes", viewModel.uiState.value.isVisualAidActive)
        assertEquals("<AR> .-.-.", viewModel.uiState.value.visualAidText)

        // Success 3 (Threshold of 3 reached)
        viewModel.submitGuess("AR")
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.characterSuccessCounts["<AR>"])
        assertEquals("<AR>", viewModel.uiState.value.targetCharacter)
        assertFalse("Hint must automatically hide after 3 successful inputs", viewModel.uiState.value.isVisualAidActive)
        assertNull("Visual aid text must be null after 3 successes", viewModel.uiState.value.visualAidText)
        assertFalse("shouldShowHintForCharacter must return false after 3 successes",
            viewModel.shouldShowHintForCharacter("<AR>", emptySet(), 1))
    }

    @Test
    fun getVisualAidSuccessThreshold_returns3ForProsignsAnd6ForKoch() {
        assertEquals(3, viewModel.getVisualAidSuccessThreshold(TrainingMode.Prosigns))
        assertEquals(6, viewModel.getVisualAidSuccessThreshold(TrainingMode.Koch))
    }

    @Test
    fun shouldShowHintForCharacter_prosignsVsKochThresholdBehavior() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Koch mode: default
        assertTrue(viewModel.shouldShowHintForCharacter("K", emptySet(), 1))
        assertTrue(viewModel.shouldShowHintForCharacter("M", emptySet(), 1))

        // Prosigns mode
        viewModel.setTrainingMode(TrainingMode.Prosigns)
        advanceUntilIdle()

        // Prosign at level 1 is <AR> (or AR)
        assertTrue(viewModel.shouldShowHintForCharacter("<AR>", emptySet(), 1))
        assertTrue(viewModel.shouldShowHintForCharacter("AR", emptySet(), 1))
        // Prosigns at higher level should not show hint at level 1
        assertFalse(viewModel.shouldShowHintForCharacter("<BT>", emptySet(), 1))

        // When mastered, never show hint
        assertFalse(viewModel.shouldShowHintForCharacter("<AR>", setOf("<AR>"), 1))
        assertFalse(viewModel.shouldShowHintForCharacter("AR", setOf("AR"), 1))
    }

    @Test
    fun prosignAudio_transitionsToContinuousSpacingAtProficiencyThresholdOf6Successes() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.setTrainingMode(TrainingMode.Prosigns)
        advanceUntilIdle()

        viewModel.startLesson(20)
        advanceUntilIdle()

        assertEquals("<AR>", viewModel.uiState.value.targetCharacter)
        assertEquals(0, viewModel.uiState.value.characterSuccessCounts["<AR>"] ?: 0)
        assertFalse(
            "At 0 successes, prosign audio must use standard 3-unit spacing (useContinuous = false)",
            fakeAudioGenerator.playedContinuousProsigns.last()
        )

        // Make two incorrect guesses first so accuracy stays < 90%, keeping drill at level 1 (<AR>)
        viewModel.submitGuess("X")
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        viewModel.submitGuess("X")
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        // Submit 5 successful guesses (successes 1 through 5)
        for (i in 1..5) {
            viewModel.submitGuess("AR")
            testScheduler.advanceTimeBy(1600)
            advanceUntilIdle()
            assertEquals(i, viewModel.uiState.value.characterSuccessCounts["<AR>"])
            assertFalse(
                "At $i successes (< 6), prosign audio must use standard 3-unit spacing (useContinuous = false)",
                fakeAudioGenerator.playedContinuousProsigns.last()
            )
        }

        // Submit 6th successful guess (reaches proficiency threshold of 6)
        viewModel.submitGuess("AR")
        testScheduler.advanceTimeBy(1600)
        advanceUntilIdle()

        assertEquals(6, viewModel.uiState.value.characterSuccessCounts["<AR>"])
        assertTrue(
            "At 6 successes (threshold reached), prosign audio must transition to continuous spacing (useContinuous = true)",
            fakeAudioGenerator.playedContinuousProsigns.last()
        )

        // Replay tone also plays with continuous spacing
        fakeAudioGenerator.playedContinuousProsigns.clear()
        viewModel.playTone()
        advanceUntilIdle()
        assertTrue(
            "Replaying tone after reaching proficiency threshold must use continuous spacing",
            fakeAudioGenerator.playedContinuousProsigns.last()
        )
    }

    @Test
    fun levelAdvancementDuringDrill_smoothlyTransitionsWithoutStoppingOrRestartingSession() = runTest(testDispatcher) {
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

        viewModel.startLesson(10)
        advanceUntilIdle()

        assertTrue("Session must be active", viewModel.uiState.value.isSessionActive)
        assertEquals(1, viewModel.uiState.value.activeKochLevel)
        assertEquals(1, viewModel.uiState.value.currentChallengeIndex)

        while (viewModel.uiState.value.activeKochLevel == 1 && viewModel.uiState.value.currentChallengeIndex < 10) {
            val target = viewModel.uiState.value.targetCharacter
            viewModel.submitGuess(target)
            testScheduler.advanceTimeBy(1600)
            advanceUntilIdle()
        }

        val state = viewModel.uiState.value
        assertEquals(2, state.activeKochLevel)
        assertTrue("Level 2 character pool must contain R", state.availableCharacters.contains("R"))
        assertNotNull(state.levelUpMessage)

        assertTrue("Session must remain active across level change", state.isSessionActive)
        assertFalse("Session must not be finished prematurely", state.isSessionFinished)
        assertTrue("Challenge index must have progressed without resetting", state.currentChallengeIndex > 1)

        val updatedProfile = testProfile.copy(currentKochLevel = 2)
        viewModel.setActiveProfile(updatedProfile)
        advanceUntilIdle()

        val preservedState = viewModel.uiState.value
        assertTrue("Session must remain active after profile update", preservedState.isSessionActive)
        assertEquals(2, preservedState.activeKochLevel)
    }

    @Test
    fun selectTrainingMode_updatesModeAndTransitionsToDrillSetup() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Initially in ModeSelection
        assertEquals(DrillState.ModeSelection, viewModel.uiState.value.drillState)
        assertEquals(SelectedTrainingMode.None, viewModel.uiState.value.selectedTrainingMode)

        // Select Koch mode
        viewModel.selectTrainingMode(SelectedTrainingMode.Koch)
        advanceUntilIdle()

        val kochState = viewModel.uiState.value
        assertEquals(DrillState.DrillSetup, kochState.drillState)
        assertEquals(SelectedTrainingMode.Koch, kochState.selectedTrainingMode)
        assertEquals(TrainingMode.Koch, kochState.trainingMode)

        // Return to Mode Selection
        viewModel.returnToModeSelection()
        advanceUntilIdle()

        val returnState = viewModel.uiState.value
        assertEquals(DrillState.ModeSelection, returnState.drillState)
        assertEquals(SelectedTrainingMode.None, returnState.selectedTrainingMode)

        // Select Prosigns mode
        viewModel.selectTrainingMode(SelectedTrainingMode.Prosign)
        advanceUntilIdle()

        val prosignState = viewModel.uiState.value
        assertEquals(DrillState.DrillSetup, prosignState.drillState)
        assertEquals(SelectedTrainingMode.Prosign, prosignState.selectedTrainingMode)
        assertEquals(TrainingMode.Prosigns, prosignState.trainingMode)
    }

    @Test
    fun overrideKochLevel_capsAt39InsteadOf41() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Valid level 15
        viewModel.overrideKochLevel(15)
        advanceUntilIdle()
        assertEquals(15, viewModel.uiState.value.activeKochLevel)

        // Attempting to set 41 must be capped strictly at 39
        viewModel.overrideKochLevel(41)
        advanceUntilIdle()
        assertEquals(39, viewModel.uiState.value.activeKochLevel)
        assertEquals(40, viewModel.uiState.value.availableCharacters.size)

        // Attempting to set 100 must also be capped at 39
        viewModel.overrideKochLevel(100)
        advanceUntilIdle()
        assertEquals(39, viewModel.uiState.value.activeKochLevel)

        // Lower bound clamp (level 0 or negative clamped to 1)
        viewModel.overrideKochLevel(0)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.activeKochLevel)
    }

    @Test
    fun setActiveProfile_withLevelOver39_capsAt39() = runTest(testDispatcher) {
        val highLevelProfile = testProfile.copy(currentKochLevel = 41)
        viewModel.setActiveProfile(highLevelProfile)
        advanceUntilIdle()

        assertEquals(39, viewModel.uiState.value.activeKochLevel)
        assertEquals(40, viewModel.uiState.value.availableCharacters.size)
    }

    @Test
    fun timeAttack_configurationClampingAndStateUpdates() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.selectTrainingMode(SelectedTrainingMode.TIME_ATTACK)
        advanceUntilIdle()

        // 1. Response window clamping: 1.0s to 3.5s
        viewModel.setTimeAttackResponseWindow(0.2f)
        assertEquals(1.0f, viewModel.uiState.value.timeAttackResponseWindowSeconds, 0.001f)

        viewModel.setTimeAttackResponseWindow(5.0f)
        assertEquals(3.5f, viewModel.uiState.value.timeAttackResponseWindowSeconds, 0.001f)

        viewModel.setTimeAttackResponseWindow(2.2f)
        assertEquals(2.2f, viewModel.uiState.value.timeAttackResponseWindowSeconds, 0.001f)

        // 2. Inter-challenge delay clamping: 200ms to 5000ms
        viewModel.setTimeAttackInterChallengeDelay(50L)
        assertEquals(200L, viewModel.uiState.value.timeAttackInterChallengeDelayMs)

        viewModel.setTimeAttackInterChallengeDelay(8000L)
        assertEquals(5000L, viewModel.uiState.value.timeAttackInterChallengeDelayMs)

        viewModel.setTimeAttackInterChallengeDelay(1200L)
        assertEquals(1200L, viewModel.uiState.value.timeAttackInterChallengeDelayMs)

        // 3. Challenge count clamping: 5 to 100
        viewModel.setTimeAttackChallengeCount(2)
        assertEquals(5, viewModel.uiState.value.timeAttackChallengeCount)

        viewModel.setTimeAttackChallengeCount(600)
        assertEquals(100, viewModel.uiState.value.timeAttackChallengeCount)

        viewModel.setTimeAttackChallengeCount(30)
        assertEquals(30, viewModel.uiState.value.timeAttackChallengeCount)

        // 4. Character toggling
        viewModel.setTimeAttackCharacters(setOf('K', 'M', 'R'))
        assertEquals(setOf('K', 'M', 'R'), viewModel.uiState.value.timeAttackSelectedCharacters)

        viewModel.toggleTimeAttackCharacter('M')
        assertEquals(setOf('K', 'R'), viewModel.uiState.value.timeAttackSelectedCharacters)

        viewModel.toggleTimeAttackCharacter('A')
        assertEquals(setOf('K', 'R', 'A'), viewModel.uiState.value.timeAttackSelectedCharacters)

        // Prevent toggling down to empty set
        viewModel.toggleTimeAttackCharacter('K')
        viewModel.toggleTimeAttackCharacter('R')
        viewModel.toggleTimeAttackCharacter('A')
        assertTrue(viewModel.uiState.value.timeAttackSelectedCharacters.isNotEmpty())
    }

    @Test
    fun timeAttack_drillExecution_timeoutRegistersMissedAndPersistsReport() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.selectTrainingMode(SelectedTrainingMode.TIME_ATTACK)
        viewModel.setTimeAttackCharacters(setOf('K'))
        viewModel.setTimeAttackResponseWindow(1.0f)
        viewModel.setTimeAttackInterChallengeDelay(300L)
        viewModel.setTimeAttackChallengeCount(2)
        advanceUntilIdle()

        // Start Time Attack drill for 2 challenges
        viewModel.startLesson(2)
        testScheduler.runCurrent()
        assertEquals(DrillState.DrillActive, viewModel.uiState.value.drillState)
        assertEquals(SelectedTrainingMode.TIME_ATTACK, viewModel.uiState.value.selectedTrainingMode)

        // Advance through the entire drill (timeout without input)
        advanceUntilIdle()

        // Verify drill finished
        val finishedState = viewModel.uiState.value
        assertEquals(DrillState.Finished, finishedState.drillState)
        assertEquals(2, finishedState.timeAttackMissedCount)
        assertEquals(0, finishedState.timeAttackCorrectCount)
        assertEquals(0, finishedState.timeAttackIncorrectCount)
        assertEquals(0.0f, finishedState.sessionAccuracy, 0.001f)

        // Verify comprehensive report in state and repository
        val report = finishedState.latestTimeAttackReport
        assertNotNull(report)
        assertEquals(2, report?.challengeCount)
        assertEquals(2, report?.missedCount)
        assertEquals(0, report?.correctCount)
        assertEquals(0, report?.incorrectCount)
        assertEquals(0.0f, report?.accuracyPercentage ?: 100f, 0.001f)

        val savedReports = fakeRepository.getTimeAttackReports(testProfile.id)
        assertEquals(1, savedReports.size)
        assertEquals(report?.id, savedReports[0].id)
        val decodedResults = savedReports[0].toChallengeResults()
        assertEquals(2, decodedResults.size)
        assertTrue(decodedResults.all { it.outcome == TimeAttackOutcome.MISSED })
    }

    @Test
    fun timeAttack_drillExecution_evaluatesCorrectAndIncorrectGuesses() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.selectTrainingMode(SelectedTrainingMode.TIME_ATTACK)
        viewModel.setTimeAttackCharacters(setOf('K'))
        viewModel.setTimeAttackResponseWindow(2.0f)
        viewModel.setTimeAttackInterChallengeDelay(200L)
        viewModel.setTimeAttackChallengeCount(2)
        advanceUntilIdle()

        // Start drill with 2 challenges
        viewModel.startLesson(2)

        // Run until first challenge is waiting for input
        testScheduler.advanceTimeBy(100)
        assertTrue(viewModel.uiState.value.isTimeAttackAwaitingInput)
        assertEquals("K", viewModel.uiState.value.targetCharacter)

        // Challenge 1: Submit correct guess
        viewModel.submitGuess("K")
        testScheduler.advanceTimeBy(100)

        // Result shown, delay passes
        testScheduler.advanceTimeBy(300)

        // Challenge 2: Next target waiting for input
        assertTrue(viewModel.uiState.value.isTimeAttackAwaitingInput)
        assertEquals("K", viewModel.uiState.value.targetCharacter)

        // Challenge 2: Submit incorrect guess
        viewModel.submitGuess("M")

        // Finish remaining execution
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(DrillState.Finished, state.drillState)
        assertEquals(1, state.timeAttackCorrectCount)
        assertEquals(1, state.timeAttackIncorrectCount)
        assertEquals(0, state.timeAttackMissedCount)
        assertEquals(50.0f, state.sessionAccuracy, 0.001f)

        val report = state.latestTimeAttackReport
        assertNotNull(report)
        assertEquals(1, report?.correctCount)
        assertEquals(1, report?.incorrectCount)
        assertEquals(0, report?.missedCount)
        assertEquals(50.0f, report?.accuracyPercentage ?: 0f, 0.001f)
    }

    @Test
    fun timeAttack_returnToModeSelection_cancelsJobAndResetsState() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        viewModel.selectTrainingMode(SelectedTrainingMode.TIME_ATTACK)
        viewModel.setTimeAttackCharacters(setOf('K'))
        viewModel.startLesson(5)
        testScheduler.advanceTimeBy(50)

        assertEquals(DrillState.DrillActive, viewModel.uiState.value.drillState)

        // User quits / returns to mode selection mid-drill
        viewModel.returnToModeSelection()
        advanceUntilIdle()

        val resetState = viewModel.uiState.value
        assertEquals(DrillState.ModeSelection, resetState.drillState)
        assertEquals(SelectedTrainingMode.None, resetState.selectedTrainingMode)
        assertFalse(resetState.isTimeAttackAwaitingInput)
        assertEquals(1.0f, resetState.timeAttackTimerFraction, 0.001f)
        assertEquals(0.0f, resetState.timeAttackRemainingSeconds, 0.001f)
    }

    @Test
    fun timeAttack_dotRepVisualAid_hiddenDuringTimeAttack() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // In Time Attack mode, visual aid dotrep hints should strictly be null
        viewModel.selectTrainingMode(SelectedTrainingMode.TIME_ATTACK)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isTimeAttackMode)
        assertNull(viewModel.uiState.value.visualAidText)
    }

    @Test
    fun timeAttack_history_loadsAndTogglesVisibility() = runTest(testDispatcher) {
        viewModel.setActiveProfile(testProfile)
        advanceUntilIdle()

        // Initially history is not showing
        assertFalse(viewModel.uiState.value.isShowingTimeAttackHistory)

        // Save a test report directly into repository
        val report = TimeAttackReport(
            profileId = testProfile.id,
            responseWindowSeconds = 2.0f,
            interChallengeDelayMs = 1000L,
            challengeCount = 10,
            includedCharacters = "KMR",
            correctCount = 8,
            incorrectCount = 1,
            missedCount = 1,
            detailsEncoded = "K|K|CORRECT|450;M|M|CORRECT|520"
        )
        fakeRepository.saveTimeAttackReport(report)

        // Show history
        viewModel.showTimeAttackHistory(true)
        advanceUntilIdle()

        val showingState = viewModel.uiState.value
        assertTrue(showingState.isShowingTimeAttackHistory)
        assertEquals(1, showingState.timeAttackHistory.size)
        assertEquals(8, showingState.timeAttackHistory[0].correctCount)

        // Dismiss history
        viewModel.showTimeAttackHistory(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isShowingTimeAttackHistory)
    }
}
