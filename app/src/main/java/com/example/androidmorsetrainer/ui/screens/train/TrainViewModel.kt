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
        const val VISUAL_AID_PROSIGN_SUCCESS_THRESHOLD = 3
        const val PROSIGN_CONTINUOUS_SUCCESS_THRESHOLD = 6
        const val MIN_KOCH_LEVEL = MorseConstants.MIN_KOCH_LEVEL
        const val MAX_KOCH_LEVEL = MorseConstants.MAX_KOCH_LEVEL

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
            kochMethodManager.getCharactersForLevel(level.coerceIn(kochMethodManager.minLevel, kochMethodManager.maxLevel))
        }
    }

    fun getVisualAidSuccessThreshold(mode: TrainingMode = _uiState.value.trainingMode): Int {
        return if (mode == TrainingMode.Prosigns) VISUAL_AID_PROSIGN_SUCCESS_THRESHOLD else VISUAL_AID_SUCCESS_THRESHOLD
    }

    fun isProsign(character: String): Boolean {
        val trimmed = character.trim()
        val isBracketed = trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length > 2
        val clean = if (isBracketed) trimmed.substring(1, trimmed.length - 1).uppercase() else trimmed.uppercase()
        return isBracketed || clean.length > 1 || MorseConstants.PROSIGNS_SEQUENCE.any {
            val seqClean = it.replace("<", "").replace(">", "").uppercase()
            it.uppercase() == trimmed.uppercase() || seqClean == clean
        }
    }

    private fun getSuccessCount(character: String): Int {
        val upper = character.uppercase()
        val clean = upper.replace("<", "").replace(">", "")
        return characterSuccessCounts[upper]
            ?: characterSuccessCounts[clean]
            ?: characterSuccessCounts["<$clean>"]
            ?: 0
    }

    private suspend fun getStatResilient(profileId: Long, character: String): CharacterStats? {
        val clean = character.replace("<", "").replace(">", "")
        return profileRepository.getStatForCharacter(profileId, character)
            ?: profileRepository.getStatForCharacter(profileId, clean)
            ?: profileRepository.getStatForCharacter(profileId, "<$clean>")
    }

    private fun recordStatCache(
        character: String,
        successes: Int,
        totalAttempts: Int? = null,
        correctAttempts: Int? = null
    ) {
        val upper = character.uppercase()
        val clean = upper.replace("<", "").replace(">", "")
        val bracketed = "<$clean>"
        characterSuccessCounts[upper] = successes
        characterSuccessCounts[clean] = successes
        characterSuccessCounts[bracketed] = successes
        if (totalAttempts != null) {
            characterTotalAttempts[upper] = totalAttempts
            characterTotalAttempts[clean] = totalAttempts
            characterTotalAttempts[bracketed] = totalAttempts
        }
        if (correctAttempts != null) {
            characterCorrectAttempts[upper] = correctAttempts
            characterCorrectAttempts[clean] = correctAttempts
            characterCorrectAttempts[bracketed] = correctAttempts
        }
    }

    private fun getCharacterSuccessCounts(): Map<String, Int> {
        val pool = _uiState.value.availableCharacters.ifEmpty {
            getCharactersForMode(_uiState.value.trainingMode, _uiState.value.currentLevel)
        }
        val map = mutableMapOf<String, Int>()
        for (char in pool) {
            val key = char.uppercase()
            map[key] = getSuccessCount(key)
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
            val clean = key.replace("<", "").replace(">", "")
            val total = characterTotalAttempts[key] ?: characterTotalAttempts[clean] ?: 0
            val correct = characterCorrectAttempts[key] ?: characterCorrectAttempts[clean] ?: 0
            val acc = if (total > 0) (correct.toFloat() / total) * 100.0f else 0.0f
            map[key] = acc
        }
        return map
    }

    /**
     * Determines whether a visual hint (Morse dot representation) should be shown for the given character.
     * Evaluated strictly on a per-character basis:
     * - Mastered characters never show hints.
     * - In Koch mode: eligible only if individual success counter < VISUAL_AID_SUCCESS_THRESHOLD (6).
     * - In Prosigns mode: eligible only if individual success counter < VISUAL_AID_PROSIGN_SUCCESS_THRESHOLD (3).
     * - At Level 1 (Koch): initial characters "K" and "M" are both eligible until each individually reaches 6 successes.
     * - At Level > 1: only the newly introduced character for that level is eligible.
     */
    fun shouldShowHintForCharacter(
        character: String,
        mastered: Set<String>,
        level: Int = _uiState.value.currentLevel
    ): Boolean {
        if (character.isEmpty()) return false
        val upper = character.uppercase()
        val cleanUpper = upper.replace("<", "").replace(">", "")
        val threshold = getVisualAidSuccessThreshold(_uiState.value.trainingMode)
        val successes = getSuccessCount(upper)
        if (successes >= threshold) return false

        if (_uiState.value.selectedTrainingMode == SelectedTrainingMode.FOCUS) {
            return upper !in mastered && cleanUpper !in mastered && successes < threshold
        }

        return if (_uiState.value.trainingMode == TrainingMode.Prosigns) {
            val latest = MorseConstants.PROSIGNS_SEQUENCE.getOrNull(level - 1)?.uppercase()
            val cleanLatest = latest?.replace("<", "")?.replace(">", "")
            (upper == latest || cleanUpper == cleanLatest) && (upper !in mastered && cleanUpper !in mastered)
        } else {
            if (level == 1) {
                (upper == "K" || upper == "M") && upper !in mastered
            } else {
                val latest = kochMethodManager.getLatestCharacterForLevel(level).uppercase()
                upper == latest
            }
        }
    }

    /**
     * Determines the active newly introduced character at the given level that has not yet met proficiency.
     * In Prosigns mode, checks the prosign introduced at that level against the 3-success threshold.
     * In Koch mode: At level 1, characters are ["K", "M"] (checks "K", then "M"); at level > 1, checks latest character.
     */
    private suspend fun determineActiveNewCharacter(
        profileId: Long,
        level: Int,
        mastered: Set<String>
    ): String? {
        val candidates = if (_uiState.value.trainingMode == TrainingMode.Prosigns) {
            val char = MorseConstants.PROSIGNS_SEQUENCE.getOrNull(level - 1)
            if (char != null) listOf(char) else emptyList()
        } else {
            if (level == 1) {
                listOf("K", "M")
            } else {
                listOf(kochMethodManager.getLatestCharacterForLevel(level))
            }
        }

        val threshold = getVisualAidSuccessThreshold(_uiState.value.trainingMode)

        for (char in candidates) {
            val cleanChar = char.uppercase().replace("<", "").replace(">", "")
            if (char.uppercase() !in mastered && cleanChar !in mastered) {
                val stat = getStatResilient(profileId, char)
                val successes = getSuccessCount(char).let { if (it > 0) it else (stat?.successfulChallenges ?: 0) }
                val isProficient = if (stat != null) {
                    kochMethodManager.isProficient(stat.correctCount, stat.incorrectCount)
                } else false
                if (!isProficient && successes < threshold) {
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
        val set = mutableSetOf<String>()
        stats.filter { stat ->
            kochMethodManager.isProficient(stat.correctCount, stat.incorrectCount)
        }.forEach { stat ->
            val upper = stat.character.uppercase()
            val clean = upper.replace("<", "").replace(">", "")
            set.add(upper)
            set.add(clean)
            set.add("<$clean>")
        }
        return set
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

        val mode = _uiState.value.trainingMode
        val currentProfile = _uiState.value.activeProfile
        if (currentProfile?.id == profile.id) {
            // Same profile: If a drill session is currently running (active or showing result),
            // preserve the active session and do NOT cancel jobs or reset to setup mode.
            if (_uiState.value.drillState == DrillState.DrillActive || _uiState.value.drillState == DrillState.ShowingResult) {
                val currentModeLevel = if (mode == TrainingMode.Prosigns) {
                    profile.currentProsignLevel.coerceIn(1, MorseConstants.PROSIGNS_SEQUENCE.size)
                } else {
                    profile.currentKochLevel.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL)
                }
                val newPool = getCharactersForMode(mode, currentModeLevel)
                _uiState.update {
                    it.copy(
                        activeProfile = profile,
                        activeKochLevel = profile.currentKochLevel.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL),
                        activeProsignLevel = profile.currentProsignLevel.coerceIn(1, MorseConstants.PROSIGNS_SEQUENCE.size),
                        availableCharacters = newPool
                    )
                }
                return
            }
            // If in setup or finished state and the level and character pool for the active mode have not changed, just update the profile reference
            val currentModeLevel = if (mode == TrainingMode.Prosigns) {
                profile.currentProsignLevel.coerceIn(1, MorseConstants.PROSIGNS_SEQUENCE.size)
            } else {
                profile.currentKochLevel.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL)
            }
            val activeModeLevel = if (mode == TrainingMode.Prosigns) _uiState.value.activeProsignLevel else _uiState.value.activeKochLevel
            val expectedPool = getCharactersForMode(mode, currentModeLevel)
            if (currentProfile.currentKochLevel == profile.currentKochLevel &&
                currentProfile.currentProsignLevel == profile.currentProsignLevel &&
                currentModeLevel == activeModeLevel &&
                _uiState.value.availableCharacters == expectedPool
            ) {
                _uiState.update { it.copy(activeProfile = profile) }
                return
            }
        }

        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()

        val level = if (mode == TrainingMode.Prosigns) {
            profile.currentProsignLevel.coerceIn(1, MorseConstants.PROSIGNS_SEQUENCE.size)
        } else {
            profile.currentKochLevel.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL)
        }
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
                val successes = maxOf(stat.successfulChallenges, stat.correctCount)
                recordStatCache(
                    character = stat.character,
                    successes = successes,
                    totalAttempts = stat.totalAttempts,
                    correctAttempts = stat.correctCount
                )
            }

            val threshold = getVisualAidSuccessThreshold(mode)
            val newChar = determineActiveNewCharacter(profile.id, level, mastered)
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            val successCount = if (newChar != null) getSuccessCount(newChar) else 0
            val isThresholdReached = successCount >= threshold
            val isVisualAidActive = newChar != null && !isThresholdReached
            val visualAid = if (isVisualAidActive && dotRep != null) "$newChar $dotRep" else null

            audioGenerator.setWpm(profile.preferredWpm)

            _uiState.update {
                it.copy(
                    activeProfile = profile,
                    activeKochLevel = profile.currentKochLevel.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL),
                    activeProsignLevel = profile.currentProsignLevel.coerceIn(1, MorseConstants.PROSIGNS_SEQUENCE.size),
                    currentWpm = profile.preferredWpm,
                    availableCharacters = if (_uiState.value.selectedTrainingMode == SelectedTrainingMode.FOCUS) {
                        _uiState.value.focusSelectedCharacters.map { it.toString() }
                    } else pool,
                    targetCharacter = "",
                    isPlayingAudio = false,
                    isReplayTone = false,
                    showStartLessonDialog = false,
                    drillState = if (_uiState.value.selectedTrainingMode == SelectedTrainingMode.None) DrillState.ModeSelection else DrillState.DrillSetup,
                    selectedTrainingMode = _uiState.value.selectedTrainingMode,
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
     * Handles selection events from the TrainModeSelection view and transitions
     * navigation state to the setup view for the selected mode.
     */
    fun selectTrainingMode(selectedMode: SelectedTrainingMode) {
        val mappedMode = when (selectedMode) {
            SelectedTrainingMode.Koch -> TrainingMode.Koch
            SelectedTrainingMode.Prosign -> TrainingMode.Prosigns
            SelectedTrainingMode.FOCUS -> TrainingMode.Koch
            SelectedTrainingMode.None -> _uiState.value.trainingMode
        }
        val targetDrillState = if (selectedMode == SelectedTrainingMode.None) {
            DrillState.ModeSelection
        } else {
            DrillState.DrillSetup
        }

        val focusChars = if (_uiState.value.focusSelectedCharacters.size < 3) {
            setOf('K', 'M', 'R')
        } else {
            _uiState.value.focusSelectedCharacters
        }

        _uiState.update {
            it.copy(
                selectedTrainingMode = selectedMode,
                trainingMode = mappedMode,
                drillState = targetDrillState,
                focusSelectedCharacters = focusChars,
                availableCharacters = if (selectedMode == SelectedTrainingMode.FOCUS) {
                    focusChars.map { c -> c.toString() }
                } else it.availableCharacters
            )
        }

        val profile = _uiState.value.activeProfile
        if (profile != null && selectedMode != SelectedTrainingMode.None) {
            setActiveProfile(profile)
            _uiState.update {
                it.copy(
                    selectedTrainingMode = selectedMode,
                    trainingMode = mappedMode,
                    drillState = DrillState.DrillSetup,
                    focusSelectedCharacters = focusChars,
                    availableCharacters = if (selectedMode == SelectedTrainingMode.FOCUS) {
                        focusChars.map { c -> c.toString() }
                    } else it.availableCharacters
                )
            }
        }
    }

    /**
     * Toggles selection of a character in Focus Mode.
     * Strictly enforces a minimum of 3 and a maximum of 8 characters.
     */
    fun toggleFocusCharacter(character: Char) {
        val upperChar = character.uppercaseChar()
        if (!MorseConstants.MORSE_MAP.containsKey(upperChar.toString())) return

        _uiState.update { state ->
            val current = state.focusSelectedCharacters
            if (upperChar in current) {
                if (current.size > 3) {
                    val updated = current - upperChar
                    state.copy(
                        focusSelectedCharacters = updated,
                        availableCharacters = if (state.selectedTrainingMode == SelectedTrainingMode.FOCUS) {
                            updated.map { it.toString() }
                        } else state.availableCharacters
                    )
                } else {
                    state
                }
            } else {
                if (current.size < 8) {
                    val updated = current + upperChar
                    state.copy(
                        focusSelectedCharacters = updated,
                        availableCharacters = if (state.selectedTrainingMode == SelectedTrainingMode.FOCUS) {
                            updated.map { it.toString() }
                        } else state.availableCharacters
                    )
                } else {
                    state
                }
            }
        }
    }

    fun toggleFocusCharacter(character: String) {
        val firstChar = character.trim().firstOrNull() ?: return
        toggleFocusCharacter(firstChar)
    }

    fun selectTrainingMode(mode: TrainingMode) {
        selectTrainingMode(
            when (mode) {
                TrainingMode.Koch -> SelectedTrainingMode.Koch
                TrainingMode.Prosigns -> SelectedTrainingMode.Prosign
            }
        )
    }

    /**
     * Navigates back from setup or drill to the Mode Selection screen.
     */
    fun returnToModeSelection() {
        guessJob?.cancel()
        playbackJob?.cancel()
        audioGenerator.stop()
        receiveGuessBuffer = ""
        sessionCharacterAttempts.clear()
        _uiState.update {
            it.copy(
                selectedTrainingMode = SelectedTrainingMode.None,
                drillState = DrillState.ModeSelection,
                currentChallengeIndex = 0,
                targetCharacter = "",
                isPlayingAudio = false,
                isReplayTone = false,
                feedbackMessage = null,
                levelUpMessage = null
            )
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
        val selected = when (mode) {
            TrainingMode.Koch -> SelectedTrainingMode.Koch
            TrainingMode.Prosigns -> SelectedTrainingMode.Prosign
        }
        if (_uiState.value.trainingMode == mode && _uiState.value.selectedTrainingMode == selected) return
        _uiState.update {
            it.copy(
                trainingMode = mode,
                selectedTrainingMode = selected
            )
        }
        val profile = _uiState.value.activeProfile
        if (profile != null) {
            setActiveProfile(profile)
        }
    }

    /**
     * Updates the "Hide Bottom Bar" preference.
     */
    fun setHideBottomBarPreference(hide: Boolean) {
        _uiState.update { it.copy(hideBottomBarPreference = hide) }
    }

    /**
     * Initiates an active training drill with the specified or pre-selected length.
     * Selects the initial target character using v1.7 dynamic priority weighting.
     */
    fun startLesson(drillLength: Int? = null) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val batchSize = drillLength ?: currentState.selectedDrillLength

        if (currentState.selectedTrainingMode == SelectedTrainingMode.FOCUS && currentState.focusSelectedCharacters.size < 3) {
            return
        }

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
                val existing = getSuccessCount(stat.character)
                val successes = maxOf(existing, maxOf(stat.successfulChallenges, stat.correctCount))
                recordStatCache(
                    character = stat.character,
                    successes = successes,
                    totalAttempts = stat.totalAttempts,
                    correctAttempts = stat.correctCount
                )
            }

            val level = currentState.currentLevel
            val newChar = determineActiveNewCharacter(profile.id, level, mastered)
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            newCharRollingAttempts.clear()

            val initialTargetUpper = initialTarget.uppercase()
            val initialTargetSuccessCount = getSuccessCount(initialTargetUpper)
            val isVisualAidActive = shouldShowHintForCharacter(initialTarget, mastered, level)

            val initialHintChar = if (isVisualAidActive) initialTarget else null
            val initialHintDot = if (initialHintChar != null) kochMethodManager.getMorseCode(initialTargetUpper) else null
            val visualAid = if (isVisualAidActive && initialHintChar != null && initialHintDot != null) {
                "$initialHintChar $initialHintDot"
            } else null

            Log.d(TAG, "Receive Drill started for profile ${profile.name} at Level $level, batch=$batchSize, initial target=$initialTarget, visualAid=$visualAid")

            val activeSelected = if (currentState.selectedTrainingMode != SelectedTrainingMode.None) {
                currentState.selectedTrainingMode
            } else {
                if (currentState.trainingMode == TrainingMode.Prosigns) SelectedTrainingMode.Prosign else SelectedTrainingMode.Koch
            }

            val focusPool = currentState.focusSelectedCharacters.map { it.toString() }

            _uiState.update {
                it.copy(
                    selectedTrainingMode = activeSelected,
                    availableCharacters = if (activeSelected == SelectedTrainingMode.FOCUS) focusPool else it.availableCharacters,
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
            val threshold = getVisualAidSuccessThreshold(_uiState.value.trainingMode)
            val successCount = if (newChar != null) getSuccessCount(newChar) else 0
            val isVisualAidActive = newChar != null && successCount < threshold
            val visualAid = if (isVisualAidActive && dotRep != null) "$newChar $dotRep" else null

            val activeSelected = if (_uiState.value.selectedTrainingMode != SelectedTrainingMode.None) {
                _uiState.value.selectedTrainingMode
            } else {
                if (_uiState.value.trainingMode == TrainingMode.Prosigns) SelectedTrainingMode.Prosign else SelectedTrainingMode.Koch
            }

            _uiState.update {
                it.copy(
                    selectedTrainingMode = activeSelected,
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
        val profile = _uiState.value.activeProfile
        if (profile != null) {
            viewModelScope.launch(ioDispatcher) {
                profileRepository.updatePreferredWpm(profile.id, clampedWpm)
            }
        }
    }

    /**
     * Plays the Morse audio tone for the current target character on a background thread.
     * Transitions prosigns from standard 1:3:7 timing to continuous character spacing
     * once the proficiency threshold of 6 or more successful attempts is achieved.
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
                val profile = _uiState.value.activeProfile
                val cachedCount = getSuccessCount(target)
                val successCount = if (cachedCount > 0 || profile == null) {
                    cachedCount
                } else {
                    val stat = getStatResilient(profile.id, target)
                    stat?.let { maxOf(it.successfulChallenges, it.correctCount) } ?: 0
                }
                val useContinuous = (_uiState.value.trainingMode == TrainingMode.Prosigns || isProsign(target)) &&
                        successCount >= PROSIGN_CONTINUOUS_SUCCESS_THRESHOLD
                audioGenerator.playCharacter(target, speedWpm, useContinuous)
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
        val cleanGuess = guessedCharacter.replace("<", "").replace(">", "").trim()

        val finalGuess = cleanGuess
        receiveGuessBuffer = ""

        val isCorrect = finalGuess.equals(strippedTarget, ignoreCase = true) ||
            finalGuess.equals(target, ignoreCase = true) ||
            guessedCharacter.trim().equals(target, ignoreCase = true)
        Log.d(TAG, "Guess submitted: '$finalGuess', Target: '$target', stripped: '$strippedTarget', isCorrect=$isCorrect, index=${currentState.currentChallengeIndex}/${currentState.sessionBatchSize}")

        guessJob?.cancel()
        guessJob = viewModelScope.launch(ioDispatcher) {
            // Enter ShowingResult state
            _uiState.update { it.copy(drillState = DrillState.ShowingResult) }

            // 1. Update Room CharacterStats with adaptive priority weighting
            val upperTarget = target.uppercase()
            val existingStat = getStatResilient(profile.id, target)
            val currentWeight = existingStat?.priorityWeight ?: 1.0f
            val updatedWeight = kochMethodManager.calculateUpdatedWeight(currentWeight, wasCorrect = isCorrect)

            val prevSuccesses = getSuccessCount(upperTarget).let { if (it > 0) it else (existingStat?.successfulChallenges ?: 0) }
            val newSuccesses = if (isCorrect) prevSuccesses + 1 else prevSuccesses

            val prevTotal = (characterTotalAttempts[upperTarget] ?: existingStat?.totalAttempts ?: 0)
            val prevCorrect = (characterCorrectAttempts[upperTarget] ?: existingStat?.correctCount ?: 0)
            val newTotal = prevTotal + 1
            val newCorrect = if (isCorrect) prevCorrect + 1 else prevCorrect

            recordStatCache(
                character = target,
                successes = newSuccesses,
                totalAttempts = newTotal,
                correctAttempts = newCorrect
            )

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
            if (currentState.selectedTrainingMode == SelectedTrainingMode.FOCUS) {
                // Focus Mode has no level advancement; stays within user-selected characters
                advancement = null
            } else if (currentState.trainingMode == TrainingMode.Prosigns) {
                 latestCharForLevel = MorseConstants.PROSIGNS_SEQUENCE.getOrNull(currentLevel - 1) ?: ""
                 val latestStat = getStatResilient(profile.id, latestCharForLevel)
                 val isHintMet = (latestStat?.successfulChallenges ?: 0) >= VISUAL_AID_PROSIGN_SUCCESS_THRESHOLD
                 if (latestStat != null && isHintMet && latestStat.accuracyPercentage >= 90f) {
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
                effectiveLevel = if (currentState.trainingMode == TrainingMode.Prosigns) {
                    advancement.newLevel.coerceIn(1, MorseConstants.PROSIGNS_SEQUENCE.size)
                } else {
                    advancement.newLevel.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL)
                }
                val updatedProfile = if (currentState.trainingMode == TrainingMode.Prosigns) {
                    profile.copy(currentProsignLevel = effectiveLevel)
                } else {
                    profile.copy(currentKochLevel = effectiveLevel)
                }
                profileRepository.updateProfile(updatedProfile)
                activePool = getCharactersForMode(currentState.trainingMode, effectiveLevel)
                levelUpMsg = advancement.message
                Log.d(TAG, "Advancement achieved! ${advancement.message}")
            } else if (currentState.selectedTrainingMode == SelectedTrainingMode.FOCUS) {
                activePool = currentState.focusSelectedCharacters.map { it.toString() }
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
                val charSuccessCount = getSuccessCount(activeNewChar)
                val activeModeThreshold = getVisualAidSuccessThreshold(currentState.trainingMode)

                if (charSuccessCount > activeModeThreshold && (isRollingProficient || isStatProficient)) {
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
            val currentTargetSuccessCount = getSuccessCount(upperTarget)

            val reportedSuccessCount = if (activeNewChar != null) {
                getSuccessCount(activeNewChar)
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
                        activeProfile = if (advancement != null) {
                            if (currentState.trainingMode == TrainingMode.Prosigns) profile.copy(currentProsignLevel = effectiveLevel) else profile.copy(currentKochLevel = effectiveLevel)
                        } else currentState.activeProfile,
                        activeKochLevel = if (currentState.trainingMode == TrainingMode.Koch) effectiveLevel else (currentState.activeProfile?.currentKochLevel ?: currentState.activeKochLevel),
                        activeProsignLevel = if (currentState.trainingMode == TrainingMode.Prosigns) effectiveLevel else (currentState.activeProfile?.currentProsignLevel ?: currentState.activeProsignLevel),
                        availableCharacters = activePool,
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
                    getSuccessCount(activeNewChar)
                } else getSuccessCount(nextTargetUpper)

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
        val pool = if (_uiState.value.selectedTrainingMode == SelectedTrainingMode.FOCUS) {
            _uiState.value.focusSelectedCharacters.map { it.toString() }.ifEmpty { listOf("K", "M", "R") }
        } else {
            getCharactersForMode(_uiState.value.trainingMode, level)
        }
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
            val level = _uiState.value.currentLevel
            sessionCharacterAttempts.clear()
            val nextTarget = pickNextTarget(profile.id, level)
            val isSessionActive = _uiState.value.drillState == DrillState.DrillActive || _uiState.value.drillState == DrillState.ShowingResult

            val stats = profileRepository.getStatsForProfile(profile.id)
            stats.forEach { stat ->
                val existing = getSuccessCount(stat.character)
                val successes = maxOf(existing, maxOf(stat.successfulChallenges, stat.correctCount))
                recordStatCache(
                    character = stat.character,
                    successes = successes,
                    totalAttempts = stat.totalAttempts,
                    correctAttempts = stat.correctCount
                )
            }

            val mastered = loadMasteredCharacters(profile.id)
            val newChar = determineActiveNewCharacter(profile.id, level, mastered)
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            newCharRollingAttempts.clear()

            val nextTargetUpper = nextTarget.uppercase()
            val nextTargetSuccessCount = getSuccessCount(nextTargetUpper)
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
            val newChar = if (profileId != null) determineActiveNewCharacter(profileId, currentState.currentLevel, mastered) else null
            val dotRep = if (newChar != null) kochMethodManager.getMorseCode(newChar) else null
            val threshold = getVisualAidSuccessThreshold(currentState.trainingMode)
            val successCount = if (newChar != null) getSuccessCount(newChar) else 0
            val isVisualAidActive = newChar != null && successCount < threshold
            val visualAid = if (isVisualAidActive && dotRep != null) "$newChar $dotRep" else null
            val activeSelected = if (currentState.selectedTrainingMode != SelectedTrainingMode.None) {
                currentState.selectedTrainingMode
            } else {
                if (currentState.trainingMode == TrainingMode.Prosigns) SelectedTrainingMode.Prosign else SelectedTrainingMode.Koch
            }

            _uiState.update {
                it.copy(
                    selectedTrainingMode = activeSelected,
                    availableCharacters = if (activeSelected == SelectedTrainingMode.FOCUS) {
                        currentState.focusSelectedCharacters.map { it.toString() }
                    } else currentState.availableCharacters,
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
     * Progression override logic for debug menu and manual level configuration.
     * Capped strictly between 1 and MorseConstants.MAX_KOCH_LEVEL (39).
     */
    fun overrideKochLevel(level: Int) {
        val clamped = level.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL)
        val profile = _uiState.value.activeProfile
        if (profile != null) {
            val updated = profile.copy(currentKochLevel = clamped)
            viewModelScope.launch(ioDispatcher) {
                profileRepository.updateProfile(updated)
            }
        }
        val pool = getCharactersForMode(_uiState.value.trainingMode, clamped)
        _uiState.update {
            it.copy(
                activeKochLevel = clamped,
                availableCharacters = if (it.selectedTrainingMode == SelectedTrainingMode.FOCUS) it.availableCharacters else pool
            )
        }
    }

    fun setKochLevel(level: Int) {
        overrideKochLevel(level)
    }

    fun overrideKochLevel(profileId: Long, level: Int) {
        val clamped = level.coerceIn(kochMethodManager.minLevel, MorseConstants.MAX_KOCH_LEVEL)
        viewModelScope.launch(ioDispatcher) {
            val profile = profileRepository.getProfileById(profileId)
            if (profile != null) {
                profileRepository.updateProfile(profile.copy(currentKochLevel = clamped))
            }
        }
        if (_uiState.value.activeProfile?.id == profileId) {
            overrideKochLevel(clamped)
        }
    }

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
