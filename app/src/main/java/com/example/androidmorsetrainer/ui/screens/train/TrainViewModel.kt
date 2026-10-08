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
import com.example.androidmorsetrainer.morse.MorseConstants
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
        const val VISUAL_AID_ACCURACY_THRESHOLD = KochMethodManager.MASTERY_ACCURACY_PERCENT
        const val VISUAL_AID_MIN_ATTEMPTS = KochMethodManager.MASTERY_MIN_ATTEMPTS
        const val VISUAL_AID_SUCCESS_THRESHOLD = 6

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
            sessionBatchSize = defaultDrillLength,
            currentWpm = audioGenerator.wpm.coerceIn(10, 25)
        )
    )
    val uiState: StateFlow<TrainUiState> = _uiState.asStateFlow()

    private val sessionCharacterAttempts = ConcurrentHashMap<String, Int>()
    private val newCharRollingAttempts = mutableListOf<Boolean>()
    private val characterSuccessCounts = ConcurrentHashMap<String, Int>()
    private val characterTotalAttempts = ConcurrentHashMap<String, Int>()
    private val characterCorrectAttempts = ConcurrentHashMap<String, Int>()
    private var playbackJob: Job? = null
    private var guessJob: Job? = null

    private fun getCharactersForMode(mode: TrainingMode, level: Int): List<String> {
        return if (mode == TrainingMode.Prosigns) {
            MorseConstants.PROSIGNS_SEQUENCE.take(level.coerceAtMost(MorseConstants.PROSIGNS_SEQUENCE.size))
        } else {
            kochMethodManager.getCharactersForLevel(level)
        }
    }

    private fun getCharacterSuccessCounts(): Map<String, Int> {
        val pool = _uiState.value.availableCharacters.ifEmpty {
            getCharactersForMode(_uiState.value.trainingMode, _uiState.value.currentLevel)
        }
        val map = mutableMapOf<String, Int>()
        for (char in pool) {
            val key = char.uppercase()
            map[key] = characterSuccessCounts[key] ?: 0
        }
        return map
    }

    private fun getCharacterAccuracies(): Map<String, Float> {
        val pool = _uiState.value.availableCharacters.ifEmpty {
            getCharactersForMode(_uiState.value.trainingMode, _uiState.value.currentLevel)
        }
        val map = mutableMapOf<String, Float>()
        for (char in pool) {
            val key = char.uppercase()
            val total = characterTotalAttempts[key] ?: 0
            val correct = characterCorrectAttempts[key] ?: 0
            val acc = if (total > 0) (correct.toFloat() / total) * 100.0f else 0.0f
            map[key] = acc
        }
        return map
    }

    /**
     * Determines whether a visual hint (Morse dot representation) should be shown for the given character.
     * Evaluated strictly on a per-character basis:
     * - Mastered characters never show hints.
     * - A character is eligible only if its individual success counter is < VISUAL_AID_SUCCESS_THRESHOLD (6).
     * - At Level 1, initial characters "K" and "M" are both eligible until each individually reaches 6 successes.
     * - At Level > 1, only the newly introduced character for that level is eligible, preventing re-triggering for previously mastered characters.
     */
    fun shouldShowHintForCharacter(
        character: String,
        mastered: Set<String>,
        level: Int = _uiState.value.currentLevel
    ): Boolean {
        if (character.isEmpty()) return false
        val upper = character.uppercase()
        val successes = characterSuccessCounts[upper] ?: 0
        if (successes >= VISUAL_AID_SUCCESS_THRESHOLD) return false

        return if (level == 1) {
            (upper == "K" || upper == "M") && upper !in mastered
        } else {
            val latest = kochMethodManager.getLatestCharacterForLevel(level).uppercase()
            upper == latest
        }
    }

    /**
     * Determines the active newly introduced character at the given level that has not yet met proficiency.
     * At level 1, characters are ["K", "M"] (checks "K", then "M").
     * At level > 1, the new character is the latest character for that level.
     */
    private suspend fun determineActiveNewCharacter(
        profileId: Long,
        level: Int,
        mastered: Set<String>
    ): String? {
        val candidates = if (level == 1) {
            listOf("K", "M")
        } else {
            listOf(kochMethodManager.getLatestCharacterForLevel(level))
        }

        for (char in candidates) {
            if (char.uppercase() !in mastered) {
                val stat = profileRepository.getStatForCharacter(profileId, char)
                val successes = characterSuccessCounts[char.uppercase()] ?: stat?.successfulChallenges ?: 0
                val isProficient = if (stat != null) {
                    kochMethodManager.isProficient(stat.correctCount, stat.incorrectCount)
                } else false
                if (!isProficient && successes < VISUAL_AID_SUCCESS_THRESHOLD) {
                    return char
                }
            }
        }
        return null
    }

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
     * Computes the overall career accuracy percentage integer (0-100) across all historical
     * character stats stored in Room for the given profile.
     */
    private suspend fun calculateOverallAccuracy(profileId: Long): Int {
        val stats = profileRepository.getStatsForProfile(profileId)
        val totalCorrect = stats.sumOf { it.correctCount }
        val totalIncorrect = stats.sumOf { it.incorrectCount }
        val totalAttempts = totalCorrect + totalIncorrect
        return if (totalAttempts > 0) {
            Math.round((totalCorrect.toFloat() / totalAttempts) * 100f).toInt()
        } else {
            0
        }
    }

    /**
     * Sets or updates the active profile.
     * Transitions screen into the Pre-Drill Setup state (isSessionActive = false, isSessionFinished = false).
     */
    fun setActiveProfile(profile: UserProfile?) {
        if (profile == null) {
            guessJob?.cancel()
            playbackJob?.cancel()
            sessionCharacterAttempts.clear()
            newCharRollingAttempts.clear()
            _uiState.update { TrainUiState() }
            return
        }

        val currentProfile = _uiState.value.activeProfile
        if (currentProfile?.id == profile.id) {
            // Same profile: If a drill session is currently running (active or showing result),
            // preserve the active session and do NOT cancel jobs or reset to setup mode.
            if (_uiState.value.drillState == DrillState.DrillActive || _uiState.value.drillState == DrillState.ShowingResult) {
                _uiState.update { it.copy(activeProfile = profile) }
                return
            }
            // If in setup or finished state and the Koch level has not changed, just update the profile reference
            if (currentProfile.currentKochLevel == profile.currentKochLevel) {
                _uiState.update { it.copy(activeProfile = profile) }
                return
            }
        }

        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()

        val mode = _uiState.value.trainingMode
        val level = if (mode == TrainingMode.Prosigns) profile.currentProsignLevel else profile.currentKochLevel
        val pool = getCharactersForMode(mode, level)

        Log.d(TAG, "Initialized profile ${profile.name} (id=${profile.id}) at mode $mode Level $level, pool=${pool.joinToString()}")

        viewModelScope.launch(ioDispatcher) {
            val mastered = loadMasteredCharacters(profile.id)
            val overallAcc = calculateOverallAccuracy(profile.id)
            sessionCharacterAttempts.clear()
            newCharRollingAttempts.clear()
            characterSuccessCounts.clear()
            characterTotalAttempts.clear()
            characterCorrectAttempts.clear()

            val stats = profileRepository.getStatsForProfile(profile.id)
            stats.forEach { stat ->
                val key = stat.character.uppercase()
                characterSuccessCounts[key] = maxOf(stat.successfulChallenges, stat.correctCount)
                characterTotalAttempts[key] = stat.totalAttempts
                characterCorrectAttempts[key] = stat.correctCount
            }

            val newChar = determineActiveNewCharacter(profile.id, level, mastered)
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            val successCount = if (newChar != null) (characterSuccessCounts[newChar.uppercase()] ?: 0) else 0
            val isThresholdReached = successCount >= VISUAL_AID_SUCCESS_THRESHOLD
            val isVisualAidActive = newChar != null && !isThresholdReached
            val visualAid = if (isVisualAidActive && dotRep != null) "$newChar $dotRep" else null

            _uiState.update {
                it.copy(
                    activeProfile = profile,
                    activeKochLevel = profile.currentKochLevel,
                    activeProsignLevel = profile.currentProsignLevel,
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
                    overallAccuracy = overallAcc,
                    sessionCharacterAttempts = emptyMap(),
                    isLoading = false,
                    newlyIntroducedCharacter = newChar,
                    newCharacterDotRepresentation = dotRep,
                    newCharacterVisualAid = visualAid,
                    activeHintCharacter = if (isVisualAidActive) newChar else null,
                    activeHintDotRep = if (isVisualAidActive) dotRep else null,
                    showNewCharacterVisualAid = isVisualAidActive,
                    isVisualAidActive = isVisualAidActive,
                    activeNewCharacterSuccessCount = successCount,
                    newCharacterSuccessCount = successCount,
                    characterSuccessCounts = getCharacterSuccessCounts(),
                    characterAccuracies = getCharacterAccuracies()
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
     * Updates the active training mode (Koch vs Prosigns).
     */
    fun setTrainingMode(mode: TrainingMode) {
        if (_uiState.value.trainingMode == mode) return
        _uiState.update { it.copy(trainingMode = mode) }
        val profile = _uiState.value.activeProfile
        if (profile != null) {
            setActiveProfile(profile)
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

        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()
        receiveGuessBuffer = ""

        viewModelScope.launch(ioDispatcher) {
            sessionCharacterAttempts.clear()
            val initialTarget = pickNextTarget(profile.id, currentState.currentLevel)
            val mastered = loadMasteredCharacters(profile.id)

            val stats = profileRepository.getStatsForProfile(profile.id)
            stats.forEach { stat ->
                val key = stat.character.uppercase()
                val existing = characterSuccessCounts[key] ?: 0
                characterSuccessCounts[key] = maxOf(existing, maxOf(stat.successfulChallenges, stat.correctCount))
                characterTotalAttempts[key] = stat.totalAttempts
                characterCorrectAttempts[key] = stat.correctCount
            }

            val level = currentState.currentLevel
            val newChar = determineActiveNewCharacter(profile.id, level, mastered)
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            newCharRollingAttempts.clear()

            val initialTargetUpper = initialTarget.uppercase()
            val initialTargetSuccessCount = characterSuccessCounts[initialTargetUpper] ?: 0
            val isVisualAidActive = shouldShowHintForCharacter(initialTarget, mastered, level)

            val initialHintChar = if (isVisualAidActive) initialTarget else null
            val initialHintDot = if (initialHintChar != null) kochMethodManager.getMorseCode(initialTargetUpper) else null
            val visualAid = if (isVisualAidActive && initialHintChar != null && initialHintDot != null) {
                "$initialHintChar $initialHintDot"
            } else null

            Log.d(TAG, "Receive Drill started for profile ${profile.name} at Level $level, batch=$batchSize, initial target=$initialTarget, visualAid=$visualAid")

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
                    isReplayTone = false,
                    newlyIntroducedCharacter = newChar,
                    newCharacterDotRepresentation = dotRep,
                    newCharacterVisualAid = visualAid,
                    activeHintCharacter = initialHintChar,
                    activeHintDotRep = initialHintDot,
                    showNewCharacterVisualAid = isVisualAidActive,
                    isVisualAidActive = isVisualAidActive,
                    activeNewCharacterSuccessCount = initialTargetSuccessCount,
                    newCharacterSuccessCount = initialTargetSuccessCount,
                    characterSuccessCounts = getCharacterSuccessCounts(),
                    characterAccuracies = getCharacterAccuracies()
                )
            }
            playTone()
        }
    }

    /**
     * Navigates back to the Pre-Drill Setup view from a summary screen.
     */
    fun returnToSetup() {
        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()
        receiveGuessBuffer = ""
        sessionCharacterAttempts.clear()
        val profile = _uiState.value.activeProfile
        viewModelScope.launch(ioDispatcher) {
            val mastered = if (profile != null) loadMasteredCharacters(profile.id) else emptySet()
            val newChar = if (profile != null) determineActiveNewCharacter(profile.id, _uiState.value.currentLevel, mastered) else null
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            val successCount = if (newChar != null) (characterSuccessCounts[newChar.uppercase()] ?: 0) else 0
            val isVisualAidActive = newChar != null && successCount < VISUAL_AID_SUCCESS_THRESHOLD
            val visualAid = if (isVisualAidActive && dotRep != null) "$newChar $dotRep" else null

            _uiState.update {
                it.copy(
                    drillState = DrillState.DrillSetup,
                    currentChallengeIndex = 0,
                    targetCharacter = "",
                    feedbackMessage = null,
                    levelUpMessage = null,
                    newlyIntroducedCharacter = newChar,
                    newCharacterDotRepresentation = dotRep,
                    newCharacterVisualAid = visualAid,
                    activeHintCharacter = if (isVisualAidActive) newChar else null,
                    activeHintDotRep = if (isVisualAidActive) dotRep else null,
                    showNewCharacterVisualAid = isVisualAidActive,
                    isVisualAidActive = isVisualAidActive,
                    activeNewCharacterSuccessCount = successCount,
                    newCharacterSuccessCount = successCount,
                    characterSuccessCounts = getCharacterSuccessCounts(),
                    characterAccuracies = getCharacterAccuracies()
                )
            }
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
     * Updates the active Words Per Minute (WPM) playback speed (10 to 25 WPM).
     * Routes the new speed to MorseAudioGenerator and updates TrainUiState.
     */
    fun setWpm(wpm: Int) {
        val clampedWpm = wpm.coerceIn(10, 25)
        audioGenerator.setWpm(clampedWpm)
        _uiState.update { it.copy(currentWpm = clampedWpm) }
        Log.d(TAG, "Active WPM updated to $clampedWpm")
    }

    /**
     * Plays the Morse audio tone for the current target character on a background thread.
     */
    fun playTone() {
        val target = _uiState.value.targetCharacter
        if (target.isEmpty() || _uiState.value.drillState == DrillState.DrillSetup || _uiState.value.drillState == DrillState.Finished) return

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch(ioDispatcher) {
            val speedWpm = _uiState.value.currentWpm
            _uiState.update { it.copy(isPlayingAudio = true, isReplayTone = true) }
            audioGenerator.setWpm(speedWpm)
            Log.d(TAG, "Playing Morse tone for character: '$target' at $speedWpm WPM")
            try {
                audioGenerator.playCharacter(target, speedWpm)
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
    private var receiveGuessBuffer = ""

    fun updateProsignTextInput(input: String) {
        _uiState.update { it.copy(prosignTextInput = input.uppercase()) }
    }

    fun submitProsignTextInput() {
        val input = _uiState.value.prosignTextInput.trim()
        if (input.isNotEmpty()) {
            submitGuess(input)
            _uiState.update { it.copy(prosignTextInput = "") }
        }
    }

    fun submitGuess(guessedCharacter: String) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val target = currentState.targetCharacter
        if (target.isEmpty() || currentState.drillState != DrillState.DrillActive) return

        val strippedTarget = target.replace("<", "").replace(">", "")
        val isTargetMultiChar = strippedTarget.length > 1

        if (isTargetMultiChar) {
            receiveGuessBuffer += guessedCharacter
            if (receiveGuessBuffer.length < strippedTarget.length) {
                // Wait for more input, optionally you can update UI to show partial input
                return
            }
        } else {
            receiveGuessBuffer = guessedCharacter
        }

        val finalGuess = receiveGuessBuffer
        receiveGuessBuffer = ""

        val isCorrect = finalGuess.equals(strippedTarget, ignoreCase = true)
        Log.d(TAG, "Guess submitted: '$finalGuess', Target: '$target', stripped: '$strippedTarget', isCorrect=$isCorrect, index=${currentState.currentChallengeIndex}/${currentState.sessionBatchSize}")

        guessJob?.cancel()
        guessJob = viewModelScope.launch(ioDispatcher) {
            // Enter ShowingResult state
            _uiState.update { it.copy(drillState = DrillState.ShowingResult) }

            // 1. Update Room CharacterStats with adaptive priority weighting
            val upperTarget = target.uppercase()
            val existingStat = profileRepository.getStatForCharacter(profile.id, target)
            val currentWeight = existingStat?.priorityWeight ?: 1.0f
            val updatedWeight = kochMethodManager.calculateUpdatedWeight(currentWeight, wasCorrect = isCorrect)

            val prevSuccesses = characterSuccessCounts[upperTarget] ?: existingStat?.successfulChallenges ?: 0
            val newSuccesses = if (isCorrect) prevSuccesses + 1 else prevSuccesses
            characterSuccessCounts[upperTarget] = newSuccesses

            val prevTotal = characterTotalAttempts[upperTarget] ?: existingStat?.totalAttempts ?: 0
            characterTotalAttempts[upperTarget] = prevTotal + 1
            val prevCorrect = characterCorrectAttempts[upperTarget] ?: existingStat?.correctCount ?: 0
            if (isCorrect) {
                characterCorrectAttempts[upperTarget] = prevCorrect + 1
            }

            val newStat = existingStat?.copy(
                correctCount = if (isCorrect) existingStat.correctCount + 1 else existingStat.correctCount,
                incorrectCount = if (!isCorrect) existingStat.incorrectCount + 1 else existingStat.incorrectCount,
                priorityWeight = updatedWeight,
                successfulChallenges = newSuccesses
            ) ?: CharacterStats(
                profileId = profile.id,
                character = target,
                correctCount = if (isCorrect) 1 else 0,
                incorrectCount = if (!isCorrect) 1 else 0,
                priorityWeight = updatedWeight,
                successfulChallenges = newSuccesses
            )
            profileRepository.saveCharacterStat(newStat)

            // Update session-level deprivation count
            val prevSessionAttempts = sessionCharacterAttempts[upperTarget] ?: 0
            sessionCharacterAttempts[upperTarget] = prevSessionAttempts + 1

            // 2. Evaluate session accuracy
            val newTotalAttempts = currentState.sessionTotalAttempts + 1
            val newCorrectAttempts = currentState.sessionCorrectAttempts + (if (isCorrect) 1 else 0)
            val newAccuracy = (newCorrectAttempts.toFloat() / newTotalAttempts) * 100.0f
            val isBatchFinished = currentState.currentChallengeIndex >= currentState.sessionBatchSize

            // 3. Evaluate advancement on the latest introduced character
            val currentLevel = currentState.currentLevel
            var advancement: com.example.androidmorsetrainer.morse.AdvancementResult? = null
            var latestCharForLevel = ""
            if (currentState.trainingMode == TrainingMode.Prosigns) {
                 latestCharForLevel = MorseConstants.PROSIGNS_SEQUENCE.getOrNull(currentLevel - 1) ?: ""
                 val latestStat = profileRepository.getStatForCharacter(profile.id, latestCharForLevel)
                 if (latestStat != null && latestStat.isHintThresholdMet && latestStat.accuracyPercentage >= 90f) {
                     if (currentLevel < MorseConstants.PROSIGNS_SEQUENCE.size) {
                         advancement = com.example.androidmorsetrainer.morse.AdvancementResult(currentLevel + 1, MorseConstants.PROSIGNS_SEQUENCE[currentLevel], "Prosigns Level Up: ${MorseConstants.PROSIGNS_SEQUENCE[currentLevel]}")
                     }
                 }
            } else {
                 latestCharForLevel = kochMethodManager.getLatestCharacterForLevel(currentLevel)
                 val latestStat = profileRepository.getStatForCharacter(profile.id, latestCharForLevel)
                 advancement = kochMethodManager.evaluateAdvancement(currentLevel, latestStat)
            }

            val updatedMastered = loadMasteredCharacters(profile.id)

            var effectiveLevel = currentLevel
            var activePool = currentState.availableCharacters
            var levelUpMsg: String? = null

            if (advancement != null) {
                effectiveLevel = advancement.newLevel
                val updatedProfile = if (currentState.trainingMode == TrainingMode.Prosigns) {
                    profile.copy(currentProsignLevel = effectiveLevel)
                } else {
                    profile.copy(currentKochLevel = effectiveLevel)
                }
                profileRepository.updateProfile(updatedProfile)
                activePool = getCharactersForMode(currentState.trainingMode, effectiveLevel)
                levelUpMsg = advancement.message
                Log.d(TAG, "Advancement achieved! ${advancement.message}")
            }

            val drillAccInt = if (newTotalAttempts > 0) {
                Math.round((newCorrectAttempts.toFloat() / newTotalAttempts) * 100f).toInt()
            } else 0
            val overallAcc = calculateOverallAccuracy(profile.id)

            // 4. Track rolling accuracy of active new character
            var activeNewChar = currentState.newlyIntroducedCharacter
            if (activeNewChar != null && target.equals(activeNewChar, ignoreCase = true)) {
                newCharRollingAttempts.add(isCorrect)

                val attemptsCount = newCharRollingAttempts.size
                val recentAttempts = if (attemptsCount >= VISUAL_AID_MIN_ATTEMPTS) {
                    newCharRollingAttempts.takeLast(VISUAL_AID_MIN_ATTEMPTS)
                } else {
                    newCharRollingAttempts
                }
                val recentAccuracy = if (recentAttempts.isNotEmpty()) {
                    (recentAttempts.count { it }.toFloat() / recentAttempts.size) * 100.0f
                } else 0.0f

                val isRollingProficient = attemptsCount >= VISUAL_AID_MIN_ATTEMPTS &&
                        recentAccuracy >= VISUAL_AID_ACCURACY_THRESHOLD
                val isStatProficient = kochMethodManager.isProficient(newStat.correctCount, newStat.incorrectCount)
                val charSuccessCount = characterSuccessCounts[activeNewChar.uppercase()] ?: 0

                if (charSuccessCount > VISUAL_AID_SUCCESS_THRESHOLD && (isRollingProficient || isStatProficient)) {
                    val nextNewChar = determineActiveNewCharacter(profile.id, effectiveLevel, updatedMastered)
                    if (nextNewChar != null && !nextNewChar.equals(activeNewChar, ignoreCase = true)) {
                        activeNewChar = nextNewChar
                        newCharRollingAttempts.clear()
                    } else if (nextNewChar == null) {
                        activeNewChar = null
                        newCharRollingAttempts.clear()
                    }
                }
            }

            if (advancement != null) {
                activeNewChar = advancement.newlyUnlockedCharacter
                newCharRollingAttempts.clear()
            }

            val activeDotRep = if (activeNewChar != null) kochMethodManager.getMorseCode(activeNewChar) else null

            // Strictly per-character evaluation for current challenge
            val isCurrentVisualAidActive = shouldShowHintForCharacter(target, updatedMastered, effectiveLevel)
            val currentHintChar = if (isCurrentVisualAidActive) target else null
            val currentHintDot = if (currentHintChar != null) kochMethodManager.getMorseCode(upperTarget) else null
            val currentVisualAid = if (isCurrentVisualAidActive && currentHintChar != null && currentHintDot != null) {
                "$currentHintChar $currentHintDot"
            } else null
            val currentTargetSuccessCount = characterSuccessCounts[upperTarget] ?: 0

            val reportedSuccessCount = if (activeNewChar != null) {
                characterSuccessCounts[activeNewChar.uppercase()] ?: 0
            } else currentTargetSuccessCount

            if (isBatchFinished) {
                // Halt at completion summary screen
                _uiState.update {
                    it.copy(
                        activeProfile = if (advancement != null) {
                            if (currentState.trainingMode == TrainingMode.Prosigns) profile.copy(currentProsignLevel = effectiveLevel) else profile.copy(currentKochLevel = effectiveLevel)
                        } else profile,
                        activeKochLevel = if (currentState.trainingMode == TrainingMode.Koch) effectiveLevel else profile.currentKochLevel,
                        activeProsignLevel = if (currentState.trainingMode == TrainingMode.Prosigns) effectiveLevel else profile.currentProsignLevel,
                        availableCharacters = activePool,
                        drillState = DrillState.Finished,
                        sessionTotalAttempts = newTotalAttempts,
                        sessionCorrectAttempts = newCorrectAttempts,
                        sessionAccuracy = newAccuracy,
                        lastGuessedCharacter = finalGuess,
                        lastGuessWasCorrect = isCorrect,
                        feedbackMessage = if (isCorrect) "Correct! Target was '$target'" else "Incorrect. Target was '$target', you guessed '$finalGuess'",
                        levelUpMessage = levelUpMsg ?: currentState.levelUpMessage,
                        masteredCharacters = updatedMastered,
                        sessionCharacterAttempts = sessionCharacterAttempts.toMap(),
                        overallAccuracy = overallAcc,
                        lastDrillAccuracy = drillAccInt,
                        isReplayTone = false,
                        newlyIntroducedCharacter = activeNewChar,
                        newCharacterDotRepresentation = activeDotRep,
                        newCharacterVisualAid = null,
                        activeHintCharacter = null,
                        activeHintDotRep = null,
                        showNewCharacterVisualAid = false,
                        isVisualAidActive = false,
                        activeNewCharacterSuccessCount = reportedSuccessCount,
                        newCharacterSuccessCount = reportedSuccessCount,
                        characterSuccessCounts = getCharacterSuccessCounts(),
                        characterAccuracies = getCharacterAccuracies()
                    )
                }
            } else {
                // Wait for 1.5s to show result
                _uiState.update {
                    it.copy(
                        sessionTotalAttempts = newTotalAttempts,
                        sessionCorrectAttempts = newCorrectAttempts,
                        sessionAccuracy = newAccuracy,
                        lastGuessedCharacter = finalGuess,
                        lastGuessWasCorrect = isCorrect,
                        feedbackMessage = if (isCorrect) "Correct! Target was '$target'" else "Incorrect. Target was '$target', you guessed '$finalGuess'",
                        levelUpMessage = levelUpMsg ?: currentState.levelUpMessage,
                        masteredCharacters = updatedMastered,
                        sessionCharacterAttempts = sessionCharacterAttempts.toMap(),
                        overallAccuracy = overallAcc,
                        lastDrillAccuracy = drillAccInt,
                        newlyIntroducedCharacter = activeNewChar,
                        newCharacterDotRepresentation = activeDotRep,
                        newCharacterVisualAid = currentVisualAid,
                        activeHintCharacter = currentHintChar,
                        activeHintDotRep = currentHintDot,
                        showNewCharacterVisualAid = isCurrentVisualAidActive,
                        isVisualAidActive = isCurrentVisualAidActive,
                        activeNewCharacterSuccessCount = reportedSuccessCount,
                        newCharacterSuccessCount = reportedSuccessCount,
                        characterSuccessCounts = getCharacterSuccessCounts(),
                        characterAccuracies = getCharacterAccuracies()
                    )
                }

                kotlinx.coroutines.delay(1500)

                // Pick next challenge using v1.7 dynamic priority weighting
                val nextTarget = pickNextTarget(profile.id, effectiveLevel)
                val nextTargetUpper = nextTarget.uppercase()
                val nextIsVisualAidActive = shouldShowHintForCharacter(nextTarget, updatedMastered, effectiveLevel)
                val nextHintChar = if (nextIsVisualAidActive) nextTarget else null
                val nextHintDot = if (nextHintChar != null) kochMethodManager.getMorseCode(nextTargetUpper) else null
                val nextVisualAid = if (nextIsVisualAidActive && nextHintChar != null && nextHintDot != null) {
                    "$nextHintChar $nextHintDot"
                } else null
                val nextReportedSuccessCount = if (activeNewChar != null) {
                    characterSuccessCounts[activeNewChar.uppercase()] ?: 0
                } else characterSuccessCounts[nextTargetUpper] ?: 0

                _uiState.update {
                    it.copy(
                        activeProfile = if (advancement != null) {
                            if (currentState.trainingMode == TrainingMode.Prosigns) profile.copy(currentProsignLevel = effectiveLevel) else profile.copy(currentKochLevel = effectiveLevel)
                        } else profile,
                        activeKochLevel = if (currentState.trainingMode == TrainingMode.Koch) effectiveLevel else profile.currentKochLevel,
                        activeProsignLevel = if (currentState.trainingMode == TrainingMode.Prosigns) effectiveLevel else profile.currentProsignLevel,
                        availableCharacters = activePool,
                        currentChallengeIndex = currentState.currentChallengeIndex + 1,
                        targetCharacter = nextTarget,
                        lastGuessedCharacter = null,
                        lastGuessWasCorrect = null,
                        feedbackMessage = null,
                        drillState = DrillState.DrillActive,
                        isReplayTone = false,
                        newlyIntroducedCharacter = activeNewChar,
                        newCharacterDotRepresentation = activeDotRep,
                        newCharacterVisualAid = nextVisualAid,
                        activeHintCharacter = nextHintChar,
                        activeHintDotRep = nextHintDot,
                        showNewCharacterVisualAid = nextIsVisualAidActive,
                        isVisualAidActive = nextIsVisualAidActive,
                        activeNewCharacterSuccessCount = nextReportedSuccessCount,
                        newCharacterSuccessCount = nextReportedSuccessCount,
                        characterSuccessCounts = getCharacterSuccessCounts(),
                        characterAccuracies = getCharacterAccuracies()
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
        val pool = getCharactersForMode(_uiState.value.trainingMode, level)
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
        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()
        viewModelScope.launch(ioDispatcher) {
            val level = _uiState.value.activeKochLevel
            sessionCharacterAttempts.clear()
            val nextTarget = pickNextTarget(profile.id, level)
            val isSessionActive = _uiState.value.drillState == DrillState.DrillActive || _uiState.value.drillState == DrillState.ShowingResult

            val stats = profileRepository.getStatsForProfile(profile.id)
            stats.forEach { stat ->
                val key = stat.character.uppercase()
                val existing = characterSuccessCounts[key] ?: 0
                characterSuccessCounts[key] = maxOf(existing, maxOf(stat.successfulChallenges, stat.correctCount))
                characterTotalAttempts[key] = stat.totalAttempts
                characterCorrectAttempts[key] = stat.correctCount
            }

            val mastered = loadMasteredCharacters(profile.id)
            val newChar = determineActiveNewCharacter(profile.id, level, mastered)
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            newCharRollingAttempts.clear()

            val nextTargetUpper = nextTarget.uppercase()
            val nextTargetSuccessCount = characterSuccessCounts[nextTargetUpper] ?: 0
            val isVisualAidActive = shouldShowHintForCharacter(nextTarget, mastered, level)

            val challengeHintChar = if (isVisualAidActive) nextTarget else null
            val challengeHintDot = if (challengeHintChar != null) kochMethodManager.getMorseCode(nextTargetUpper) else null
            val visualAid = if (isVisualAidActive && challengeHintChar != null && challengeHintDot != null) {
                "$challengeHintChar $challengeHintDot"
            } else null

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
                    isReplayTone = false,
                    newlyIntroducedCharacter = newChar,
                    newCharacterDotRepresentation = dotRep,
                    newCharacterVisualAid = visualAid,
                    activeHintCharacter = challengeHintChar,
                    activeHintDotRep = challengeHintDot,
                    showNewCharacterVisualAid = isVisualAidActive,
                    isVisualAidActive = isVisualAidActive,
                    activeNewCharacterSuccessCount = nextTargetSuccessCount,
                    newCharacterSuccessCount = nextTargetSuccessCount,
                    characterSuccessCounts = getCharacterSuccessCounts(),
                    characterAccuracies = getCharacterAccuracies()
                )
            }
            if (isSessionActive) {
                playTone()
            }
        }
    }

    /**
     * Explicitly terminates the current drill state and returns to setup mode.
     * Updates lastDrillAccuracy and overallAccuracy metrics.
     */
    fun quitDrill() {
        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()
        receiveGuessBuffer = ""
        val currentState = _uiState.value
        val drillAccInt = if (currentState.sessionTotalAttempts > 0) {
            Math.round(currentState.sessionAccuracy).toInt()
        } else {
            currentState.lastDrillAccuracy
        }
        val profileId = currentState.activeProfile?.id
        viewModelScope.launch(ioDispatcher) {
            val overallAcc = if (profileId != null) calculateOverallAccuracy(profileId) else currentState.overallAccuracy
            sessionCharacterAttempts.clear()
            val mastered = if (profileId != null) loadMasteredCharacters(profileId) else emptySet()
            val newChar = if (profileId != null) determineActiveNewCharacter(profileId, currentState.activeKochLevel, mastered) else null
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            val successCount = if (newChar != null) (characterSuccessCounts[newChar.uppercase()] ?: 0) else 0
            val isVisualAidActive = newChar != null && successCount < VISUAL_AID_SUCCESS_THRESHOLD
            val visualAid = if (isVisualAidActive && dotRep != null) "$newChar $dotRep" else null

            _uiState.update {
                it.copy(
                    drillState = DrillState.DrillSetup,
                    currentChallengeIndex = 0,
                    targetCharacter = "",
                    isPlayingAudio = false,
                    isReplayTone = false,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    lastDrillAccuracy = drillAccInt,
                    overallAccuracy = overallAcc,
                    newlyIntroducedCharacter = newChar,
                    newCharacterDotRepresentation = dotRep,
                    newCharacterVisualAid = visualAid,
                    activeHintCharacter = if (isVisualAidActive) newChar else null,
                    activeHintDotRep = if (isVisualAidActive) dotRep else null,
                    showNewCharacterVisualAid = isVisualAidActive,
                    isVisualAidActive = isVisualAidActive,
                    activeNewCharacterSuccessCount = successCount,
                    newCharacterSuccessCount = successCount,
                    characterSuccessCounts = getCharacterSuccessCounts(),
                    characterAccuracies = getCharacterAccuracies()
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

    val currentKochLevel: Int
        get() = _uiState.value.activeKochLevel

    /**
     * Backspace action handler for the custom in-app keyboard.
     */
    fun onBackspace() {
        Log.d(TAG, "Backspace triggered")
    }

    override fun onCleared() {
        super.onCleared()
        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()
    }
}
