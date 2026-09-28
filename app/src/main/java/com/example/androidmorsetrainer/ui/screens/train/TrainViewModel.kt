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
import kotlin.math.min

/**
 * ViewModel managing the interactive Koch method training screen.
 * Handles audio synthesis trigger, answer evaluation, Room database statistics updates,
 * adaptive priority weighting, and automatic Koch level progression at 90% accuracy.
 */
class TrainViewModel(
    private val profileRepository: ProfileRepository,
    private val kochMethodManager: KochMethodManager,
    private val audioGenerator: MorseAudioGenerator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val minAttemptsForLevelUp: Int = DEFAULT_MIN_ATTEMPTS
) : ViewModel() {

    companion object {
        private const val TAG = "TrainViewModel"
        const val DEFAULT_MIN_ATTEMPTS = 10
        const val ACCURACY_THRESHOLD_PERCENT = 90.0f

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

    private val _uiState = MutableStateFlow(TrainUiState())
    val uiState: StateFlow<TrainUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null

    /**
     * Sets or updates the active profile.
     * Preserves current challenge if the same profile and level are retained.
     */
    fun setActiveProfile(profile: UserProfile?) {
        if (profile == null) {
            _uiState.update { TrainUiState() }
            return
        }

        val currentProfile = _uiState.value.activeProfile
        if (currentProfile?.id == profile.id && currentProfile.currentKochLevel == profile.currentKochLevel) {
            _uiState.update { it.copy(activeProfile = profile) }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            val level = profile.currentKochLevel
            val pool = kochMethodManager.getCharactersForLevel(level)
            val initialTarget = pickNextTarget(profile.id, level)

            Log.d(TAG, "Initialized profile ${profile.name} (id=${profile.id}) at Level $level, pool=${pool.joinToString()}, initial target=$initialTarget")

            _uiState.update {
                it.copy(
                    activeProfile = profile,
                    activeKochLevel = level,
                    availableCharacters = pool,
                    targetCharacter = initialTarget,
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    lastGuessedCharacter = null,
                    lastGuessWasCorrect = null,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Plays the Morse audio tone for the current target character on a background thread.
     */
    fun playTone() {
        val target = _uiState.value.targetCharacter
        if (target.isEmpty()) return

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isPlayingAudio = true) }
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
     * Updates Room CharacterStats with adaptive priority weights,
     * checks for Koch level advancement (>= 90% accuracy over minimum attempts),
     * and picks the next target character.
     */
    fun submitGuess(guessedCharacter: String) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val target = currentState.targetCharacter
        if (target.isEmpty()) return

        val isCorrect = guessedCharacter.equals(target, ignoreCase = true)
        Log.d(TAG, "Guess submitted: '$guessedCharacter', Target: '$target', isCorrect=$isCorrect")

        viewModelScope.launch(ioDispatcher) {
            // 1. Update CharacterStats in Room database with adaptive priority weighting
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
            Log.d(TAG, "Updated CharacterStats for '$target': weight=$updatedWeight, correct=${newStat.correctCount}, incorrect=${newStat.incorrectCount}")

            // 2. Evaluate session accuracy
            val newTotalAttempts = currentState.sessionTotalAttempts + 1
            val newCorrectAttempts = currentState.sessionCorrectAttempts + (if (isCorrect) 1 else 0)
            val newAccuracy = (newCorrectAttempts.toFloat() / newTotalAttempts) * 100.0f

            // 3. Check for Koch level promotion threshold (>= 90% accuracy over minAttempts)
            val currentLevel = currentState.activeKochLevel
            val canAdvance = currentLevel < kochMethodManager.maxLevel &&
                    newTotalAttempts >= minAttemptsForLevelUp &&
                    newAccuracy >= ACCURACY_THRESHOLD_PERCENT

            if (canAdvance) {
                val nextLevel = min(currentLevel + 1, kochMethodManager.maxLevel)
                val updatedProfile = profile.copy(currentKochLevel = nextLevel)
                profileRepository.updateProfile(updatedProfile)

                val newPool = kochMethodManager.getCharactersForLevel(nextLevel)
                val newlyUnlockedChar = newPool.lastOrNull() ?: ""
                val nextTarget = pickNextTarget(profile.id, nextLevel)

                Log.d(TAG, "Koch level advanced to $nextLevel! Accuracy: $newAccuracy%, Unlocked: '$newlyUnlockedChar', Next Target: '$nextTarget'")

                _uiState.update {
                    it.copy(
                        activeProfile = updatedProfile,
                        activeKochLevel = nextLevel,
                        availableCharacters = newPool,
                        targetCharacter = nextTarget,
                        sessionTotalAttempts = 0,
                        sessionCorrectAttempts = 0,
                        sessionAccuracy = 0.0f,
                        lastGuessedCharacter = guessedCharacter,
                        lastGuessWasCorrect = isCorrect,
                        feedbackMessage = if (isCorrect) "Correct! Target was '$target'" else "Incorrect. Target was '$target', you guessed '$guessedCharacter'",
                        levelUpMessage = "Promoted to Level $nextLevel! New character '$newlyUnlockedChar' unlocked!"
                    )
                }
            } else {
                val nextTarget = pickNextTarget(profile.id, currentLevel)

                _uiState.update {
                    it.copy(
                        targetCharacter = nextTarget,
                        sessionTotalAttempts = newTotalAttempts,
                        sessionCorrectAttempts = newCorrectAttempts,
                        sessionAccuracy = newAccuracy,
                        lastGuessedCharacter = guessedCharacter,
                        lastGuessWasCorrect = isCorrect,
                        feedbackMessage = if (isCorrect) "Correct! Target was '$target'" else "Incorrect. Target was '$target', you guessed '$guessedCharacter'",
                        levelUpMessage = null
                    )
                }
            }
        }
    }

    /**
     * Samples the next target character using fitness/roulette-wheel weighting from Room stats.
     */
    private suspend fun pickNextTarget(profileId: Long, level: Int): String {
        val pool = kochMethodManager.getCharactersForLevel(level)
        val stats = profileRepository.getWeightedStatsForProfile(profileId)
        val weightsMap = stats.associate { it.character to it.priorityWeight }
        return kochMethodManager.getWeightedRandomCharacter(pool, weightsMap)
    }

    /**
     * Resets the current session accuracy and attempt counters.
     */
    fun resetSession() {
        val profile = _uiState.value.activeProfile ?: return
        viewModelScope.launch(ioDispatcher) {
            val level = _uiState.value.activeKochLevel
            val nextTarget = pickNextTarget(profile.id, level)
            _uiState.update {
                it.copy(
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    lastGuessedCharacter = null,
                    lastGuessWasCorrect = null,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    targetCharacter = nextTarget
                )
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
