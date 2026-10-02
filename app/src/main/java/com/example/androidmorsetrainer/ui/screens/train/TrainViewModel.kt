package com.example.androidmorsetrainer.ui.screens.train

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.audio.MorseAudioGenerator
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import com.example.androidmorsetrainer.morse.KochMethodManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ViewModel managing the interactive Koch method training screen (Receive Mode).
 * Implements pre-drill setup configuration, finite drill batches, and
 * v1.7 dynamic priority challenge weighting and advancement logic.
 */
class TrainViewModel(
    private val profileRepository: ProfileRepository,
    private val kochMethodManager: KochMethodManager,
    private val audioGenerator: MorseAudioGenerator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val defaultDrillLength: Int = DEFAULT_DRILL_LENGTH
) : ViewModel() {

    companion object {
        private const val TAG = "TrainViewModel"
        const val DEFAULT_DRILL_LENGTH = 20
        const val DEFAULT_MIN_ATTEMPTS = 5

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MorseTrainerApplication)
                TrainViewModel(
                    profileRepository = application.container.profileRepository,
                    kochMethodManager = application.container.kochMethodManager,
                    audioGenerator = application.container.morseAudioGenerator
                )
            }
        }
    }

    private val _uiState = MutableStateFlow(
        TrainUiState(
            selectedDrillLength = defaultDrillLength,
            sessionBatchSize = defaultDrillLength
        )
    )
    val uiState: StateFlow<TrainUiState> = _uiState.asStateFlow()

    private val sessionCharacterAttempts = ConcurrentHashMap<String, Int>()
    private var playbackJob: Job? = null

    /**
     * Identifies characters where historical accuracy in Room meets proficiency (>= 5 attempts and >= 70%).
     * Visual Morse dotreps are hidden for these mastered characters.
     */
    private suspend fun loadMasteredCharacters(profileId: Long): Set<String> {
        val stats = profileRepository.getStatsForProfile(profileId)
        return stats.filter { stat ->
            kochMethodManager.isProficient(stat.correctCount, stat.incorrectCount)
        }.map { it.character.uppercase() }.toSet()
    }

    /**
     * Sets or updates the active profile.
     * Transitions screen into the Pre-Drill Setup state (isSessionActive = false, isSessionFinished = false).
     */
    fun setActiveProfile(profile: UserProfile?) {
        if (profile == null) {
            sessionCharacterAttempts.clear()
            _uiState.update { TrainUiState() }
            return
        }

        val currentProfile = _uiState.value.activeProfile
        if (currentProfile?.id == profile.id && currentProfile.currentKochLevel == profile.currentKochLevel && _uiState.value.drillState == DrillState.DrillActive) {
            _uiState.update { it.copy(activeProfile = profile) }
            return
        }

        val level = profile.currentKochLevel
        val pool = kochMethodManager.getCharactersForLevel(level)

        Log.d(TAG, "Initialized profile ${profile.name} (id=${profile.id}) at Level $level, pool=${pool.joinToString()}")

        viewModelScope.launch(ioDispatcher) {
            val mastered = loadMasteredCharacters(profile.id)
            sessionCharacterAttempts.clear()
            _uiState.update {
                it.copy(
                    activeProfile = profile,
                    activeKochLevel = level,
                    availableCharacters = pool,
                    targetCharacter = "",
                    isPlayingAudio = false,
                    isReplayTone = false,
                    showStartLessonDialog = false,
                    drillState = DrillState.DrillSetup,
                    currentChallengeIndex = 0,
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    lastGuessedCharacter = null,
                    lastGuessWasCorrect = null,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    masteredCharacters = mastered,
                    sessionCharacterAttempts = emptyMap(),
                    isLoading = false
                )
            }
        }
    }

    /**
     * Updates the user-selected drill length from the Pre-Drill Setup screen (e.g. 20, 50, 100).
     */
    fun setDrillLength(length: Int) {
        val clamped = length.coerceIn(5, 500)
        _uiState.update {
            it.copy(
                selectedDrillLength = clamped,
                sessionBatchSize = clamped
            )
        }
    }

    /**
     * Initiates an active training drill with the specified or pre-selected length.
     * Selects the initial target character using v1.7 dynamic priority weighting.
     */
    fun startLesson(drillLength: Int? = null) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val batchSize = drillLength ?: currentState.selectedDrillLength

        viewModelScope.launch(ioDispatcher) {
            sessionCharacterAttempts.clear()
            val initialTarget = pickNextTarget(profile.id, currentState.activeKochLevel)
            val mastered = loadMasteredCharacters(profile.id)

            Log.d(TAG, "Receive Drill started for profile ${profile.name} at Level ${currentState.activeKochLevel}, batch=$batchSize, initial target=$initialTarget")

            _uiState.update {
                it.copy(
                    selectedDrillLength = batchSize,
                    sessionBatchSize = batchSize,
                    currentChallengeIndex = 1,
                    drillState = DrillState.DrillActive,
                    showStartLessonDialog = false,
                    targetCharacter = initialTarget,
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    masteredCharacters = mastered,
                    sessionCharacterAttempts = emptyMap(),
                    lastGuessedCharacter = null,
                    lastGuessWasCorrect = null,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    isReplayTone = false
                )
            }
            playTone()
        }
    }

    /**
     * Navigates back to the Pre-Drill Setup view from a summary screen.
     */
    fun returnToSetup() {
        sessionCharacterAttempts.clear()
        _uiState.update {
            it.copy(
                drillState = DrillState.DrillSetup,
                currentChallengeIndex = 0,
                targetCharacter = "",
                feedbackMessage = null,
                levelUpMessage = null
            )
        }
    }

    /**
     * Dismisses the Start Lesson dialog (legacy compatibility).
     */
    fun dismissStartLessonDialog() {
        _uiState.update { it.copy(showStartLessonDialog = false) }
    }

    /**
     * Opens the Start Lesson dialog (legacy compatibility).
     */
    fun openStartLessonDialog() {
        _uiState.update { it.copy(showStartLessonDialog = true) }
    }

    /**
     * Plays the Morse audio tone for the current target character on a background thread.
     */
    fun playTone() {
        val target = _uiState.value.targetCharacter
        if (target.isEmpty() || _uiState.value.drillState == DrillState.DrillSetup || _uiState.value.drillState == DrillState.Finished) return

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isPlayingAudio = true, isReplayTone = true) }
            Log.d(TAG, "Playing Morse tone for character: '$target'")
            try {
                audioGenerator.playCharacter(target)
            } catch (e: CancellationException) {
                Log.d(TAG, "Tone playback cancelled for character: '$target'")
            } catch (e: Exception) {
                Log.e(TAG, "Error during tone playback: ${e.message}", e)
            } finally {
                _uiState.update { it.copy(isPlayingAudio = false) }
            }
        }
    }

    /**
     * Processes a user guess against the current target character.
     * Updates Room CharacterStats, evaluates v1.7 Koch advancement on the latest level character,
     * updates session deprivation metrics, and halts at completion screen when the batch finishes.
     */
    fun submitGuess(guessedCharacter: String) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val target = currentState.targetCharacter
        if (target.isEmpty() || currentState.drillState != DrillState.DrillActive) return

        val isCorrect = guessedCharacter.equals(target, ignoreCase = true)
        Log.d(TAG, "Guess submitted: '$guessedCharacter', Target: '$target', isCorrect=$isCorrect, index=${currentState.currentChallengeIndex}/${currentState.sessionBatchSize}")

        viewModelScope.launch(ioDispatcher) {
            // Enter ShowingResult state
            _uiState.update { it.copy(drillState = DrillState.ShowingResult) }

            // 1. Update Room CharacterStats with adaptive priority weighting
            val existingStat = profileRepository.getStatForCharacter(profile.id, target)
            val currentWeight = existingStat?.priorityWeight ?: 1.0f
            val updatedWeight = kochMethodManager.calculateUpdatedWeight(currentWeight, wasCorrect = isCorrect)

            val newStat = existingStat?.copy(
                correctCount = if (isCorrect) existingStat.correctCount + 1 else existingStat.correctCount,
                incorrectCount = if (!isCorrect) existingStat.incorrectCount + 1 else existingStat.incorrectCount,
                priorityWeight = updatedWeight
            ) ?: CharacterStats(
                profileId = profile.id,
                character = target,
                correctCount = if (isCorrect) 1 else 0,
                incorrectCount = if (!isCorrect) 1 else 0,
                priorityWeight = updatedWeight
            )
            profileRepository.saveCharacterStat(newStat)

            // Update session-level deprivation count
            val upperTarget = target.uppercase()
            val prevSessionAttempts = sessionCharacterAttempts[upperTarget] ?: 0
            sessionCharacterAttempts[upperTarget] = prevSessionAttempts + 1

            // 2. Evaluate session accuracy
            val newTotalAttempts = currentState.sessionTotalAttempts + 1
            val newCorrectAttempts = currentState.sessionCorrectAttempts + (if (isCorrect) 1 else 0)
            val newAccuracy = (newCorrectAttempts.toFloat() / newTotalAttempts) * 100.0f
            val isBatchFinished = currentState.currentChallengeIndex >= currentState.sessionBatchSize

            // 3. Evaluate v1.7 Koch level advancement on the latest introduced character
            val currentLevel = currentState.activeKochLevel
            val latestCharForLevel = kochMethodManager.getLatestCharacterForLevel(currentLevel)
            val latestStat = profileRepository.getStatForCharacter(profile.id, latestCharForLevel)
            val advancement = kochMethodManager.evaluateAdvancement(currentLevel, latestStat)

            val updatedMastered = loadMasteredCharacters(profile.id)

            var effectiveLevel = currentLevel
            var activePool = currentState.availableCharacters
            var levelUpMsg: String? = null

            if (advancement != null) {
                effectiveLevel = advancement.newLevel
                val updatedProfile = profile.copy(currentKochLevel = effectiveLevel)
                profileRepository.updateProfile(updatedProfile)
                activePool = kochMethodManager.getCharactersForLevel(effectiveLevel)
                levelUpMsg = advancement.message
                Log.d(TAG, "Koch advancement achieved! ${advancement.message}")
            }

            if (isBatchFinished) {
                // Halt at completion summary screen
                _uiState.update {
                    it.copy(
                        activeProfile = if (advancement != null) profile.copy(currentKochLevel = effectiveLevel) else profile,
                        activeKochLevel = effectiveLevel,
                        availableCharacters = activePool,
                        drillState = DrillState.Finished,
                        sessionTotalAttempts = newTotalAttempts,
                        sessionCorrectAttempts = newCorrectAttempts,
                        sessionAccuracy = newAccuracy,
                        lastGuessedCharacter = guessedCharacter,
                        lastGuessWasCorrect = isCorrect,
                        feedbackMessage = if (isCorrect) "Correct! Target was '$target'" else "Incorrect. Target was '$target', you guessed '$guessedCharacter'",
                        levelUpMessage = levelUpMsg ?: currentState.levelUpMessage,
                        masteredCharacters = updatedMastered,
                        sessionCharacterAttempts = sessionCharacterAttempts.toMap(),
                        isReplayTone = false
                    )
                }
            } else {
                // Wait for 1.5s to show result
                _uiState.update {
                    it.copy(
                        sessionTotalAttempts = newTotalAttempts,
                        sessionCorrectAttempts = newCorrectAttempts,
                        sessionAccuracy = newAccuracy,
                        lastGuessedCharacter = guessedCharacter,
                        lastGuessWasCorrect = isCorrect,
                        feedbackMessage = if (isCorrect) "Correct! Target was '$target'" else "Incorrect. Target was '$target', you guessed '$guessedCharacter'",
                        levelUpMessage = levelUpMsg ?: currentState.levelUpMessage,
                        masteredCharacters = updatedMastered,
                        sessionCharacterAttempts = sessionCharacterAttempts.toMap()
                    )
                }
                
                kotlinx.coroutines.delay(1500)

                // Pick next challenge using v1.7 dynamic priority weighting
                val nextTarget = pickNextTarget(profile.id, effectiveLevel)
                _uiState.update {
                    it.copy(
                        activeProfile = if (advancement != null) profile.copy(currentKochLevel = effectiveLevel) else profile,
                        activeKochLevel = effectiveLevel,
                        availableCharacters = activePool,
                        currentChallengeIndex = currentState.currentChallengeIndex + 1,
                        targetCharacter = nextTarget,
                        lastGuessedCharacter = null,
                        lastGuessWasCorrect = null,
                        feedbackMessage = null,
                        drillState = DrillState.DrillActive,
                        isReplayTone = false
                    )
                }
                playTone()
            }
        }
    }

    /**
     * Samples the next target character using v1.7 roulette-wheel weighting from Room global stats and session deprivation.
     */
    private suspend fun pickNextTarget(profileId: Long, level: Int): String {
        val pool = kochMethodManager.getCharactersForLevel(level)
        val statsList = profileRepository.getStatsForProfile(profileId)
        val statsMap = statsList.associateBy { it.character.uppercase() }
        return kochMethodManager.getNextChallenge(
            pool = pool,
            globalStats = statsMap,
            sessionAttempts = sessionCharacterAttempts
        )
    }

    /**
     * Resets the current session accuracy and attempt counters.
     */
    fun resetSession() {
        val profile = _uiState.value.activeProfile ?: return
        viewModelScope.launch(ioDispatcher) {
            val level = _uiState.value.activeKochLevel
            sessionCharacterAttempts.clear()
            val nextTarget = pickNextTarget(profile.id, level)
            val isSessionActive = _uiState.value.drillState == DrillState.DrillActive || _uiState.value.drillState == DrillState.ShowingResult
            _uiState.update {
                it.copy(
                    currentChallengeIndex = if (isSessionActive) 1 else 0,
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    lastGuessedCharacter = null,
                    lastGuessWasCorrect = null,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    targetCharacter = nextTarget,
                    sessionCharacterAttempts = emptyMap(),
                    isReplayTone = false
                )
            }
            if (isSessionActive) {
                playTone()
            }
        }
    }

    /**
     * Clears the level-up celebratory banner.
     */
    fun dismissLevelUpMessage() {
        _uiState.update { it.copy(levelUpMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        audioGenerator.stop()
    }
}
