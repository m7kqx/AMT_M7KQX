package com.example.androidmorsetrainer.ui.screens.send

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.audio.MorseDSPManager
import com.example.androidmorsetrainer.data.local.entity.CharacterStats
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.data.repository.ProfileRepository
import com.example.androidmorsetrainer.morse.CwDecoder
import com.example.androidmorsetrainer.morse.KochMethodManager
import com.example.androidmorsetrainer.morse.MorseConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * ViewModel managing the Phase 7.5 Hardware Keying Practice ("Send" mode).
 * Captures user radio sidetone via microphone, analyzes tones via Goertzel DSP,
 * decodes Morse via CwDecoder, and verifies keyed characters against Koch challenges in real-time.
 */
class SendViewModel(
    private val profileRepository: ProfileRepository,
    private val kochMethodManager: KochMethodManager,
    private val dspManager: MorseDSPManager,
    decoder: CwDecoder? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val sessionBatchSize: Int = DEFAULT_BATCH_SIZE
) : ViewModel() {

    companion object {
        private const val TAG = "SendViewModel"
        const val DEFAULT_DRILL_LENGTH = 20
        const val DEFAULT_BATCH_SIZE = DEFAULT_DRILL_LENGTH
        const val DEFAULT_MIN_ATTEMPTS = 5
        const val FEEDBACK_HOLD_MS = 750L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MorseTrainerApplication)
                SendViewModel(
                    profileRepository = application.container.profileRepository,
                    kochMethodManager = application.container.kochMethodManager,
                    dspManager = application.container.morseDSPManager
                )
            }
        }
    }

    val activeDecoder: CwDecoder = decoder ?: CwDecoder(initialWpm = 20, scope = viewModelScope)

    private val _uiState = MutableStateFlow(
        SendUiState(
            hasRecordPermission = dspManager.hasRecordPermission(),
            targetFrequencyHz = dspManager.targetFrequencyHz,
            detectionThreshold = dspManager.squelchThreshold,
            squelchLevel = MorseDSPManager.magnitudeToSquelchLevel(dspManager.squelchThreshold),
            selectedDrillLength = sessionBatchSize,
            sessionBatchSize = sessionBatchSize
        )
    )
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    private val sessionCharacterAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val _squelchLevel = MutableStateFlow(
        MorseDSPManager.magnitudeToSquelchLevel(dspManager.squelchThreshold)
    )
    val squelchLevel: StateFlow<Float> = _squelchLevel.asStateFlow()

    @Volatile
    private var isEvaluating: Boolean = false

    init {
        observeDspState()
        observeToneEvents()
        observeDecoder()
        val hasPerm = dspManager.hasRecordPermission()
        _uiState.update { it.copy(hasRecordPermission = hasPerm) }
        if (hasPerm) {
            dspManager.startListening()
        }
    }

    private fun observeDspState() {
        viewModelScope.launch {
            dspManager.dspState.collect { dsp ->
                val level = MorseDSPManager.magnitudeToSquelchLevel(dsp.detectionThreshold)
                _squelchLevel.value = level
                _uiState.update {
                    it.copy(
                        isListening = dsp.isListening,
                        isTonePresent = dsp.isTonePresent,
                        currentMagnitude = dsp.currentMagnitude,
                        detectionThreshold = dsp.detectionThreshold,
                        squelchLevel = level
                    )
                }
            }
        }
    }

    private fun observeToneEvents() {
        viewModelScope.launch(defaultDispatcher) {
            dspManager.toneEvents.collect { event ->
                if (event.isTonePresent) {
                    activeDecoder.onToneStarted()
                } else {
                    activeDecoder.onToneCompleted(event.durationMs)
                }
            }
        }
    }

    private fun observeDecoder() {
        viewModelScope.launch {
            activeDecoder.decodedText.collect { text ->
                _uiState.update { it.copy(liveDecodedText = text) }
            }
        }
        viewModelScope.launch {
            activeDecoder.currentSymbol.collect { symbol ->
                _uiState.update { it.copy(currentMorseSymbol = symbol) }
            }
        }
        viewModelScope.launch {
            activeDecoder.estimatedWpm.collect { wpm ->
                _uiState.update { it.copy(estimatedWpm = wpm) }
            }
        }
        viewModelScope.launch {
            activeDecoder.characterEvents.collect { keyedChar ->
                verifyKeyedCharacter(keyedChar)
            }
        }
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
     * Sets or updates the active profile and prepares the Pre-Drill Setup / Calibration state.
     */
    fun setActiveProfile(profile: UserProfile?) {
        if (profile == null) {
            sessionCharacterAttempts.clear()
            _uiState.update { SendUiState() }
            return
        }

        val currentProfile = _uiState.value.activeProfile
        if (currentProfile?.id == profile.id && currentProfile.currentKochLevel == profile.currentKochLevel && _uiState.value.isSessionActive) {
            _uiState.update { it.copy(activeProfile = profile) }
            return
        }

        val level = profile.currentKochLevel
        val pool = kochMethodManager.getCharactersForLevel(level)

        viewModelScope.launch(ioDispatcher) {
            val initialTarget = pickNextTarget(profile.id, level)
            val pattern = MorseConstants.MORSE_MAP[initialTarget] ?: ""
            val mastered = loadMasteredCharacters(profile.id)
            sessionCharacterAttempts.clear()

            Log.d(TAG, "Hardware Keying initialized for ${profile.name} at Level $level, target='$initialTarget'")

            _uiState.update {
                it.copy(
                    activeProfile = profile,
                    activeKochLevel = level,
                    availableCharacters = pool,
                    targetCharacter = initialTarget,
                    targetMorsePattern = pattern,
                    showStartLessonDialog = false,
                    isSessionActive = false,
                    isSessionFinished = false,
                    currentChallengeIndex = 0,
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    verificationStatus = VerificationStatus.IDLE,
                    lastEvaluatedChar = null,
                    lastKeyedCharacter = null,
                    lastKeyWasCorrect = null,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    masteredCharacters = mastered,
                    sessionCharacterAttempts = emptyMap(),
                    liveDecodedText = "",
                    currentMorseSymbol = ""
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
     * Starts a structured training lesson batch with the chosen drill length.
     */
    fun startLesson(drillLength: Int? = null) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val batchSize = drillLength ?: currentState.selectedDrillLength

        viewModelScope.launch(ioDispatcher) {
            sessionCharacterAttempts.clear()
            val initialTarget = pickNextTarget(profile.id, currentState.activeKochLevel)
            val pattern = MorseConstants.MORSE_MAP[initialTarget] ?: ""
            val mastered = loadMasteredCharacters(profile.id)

            activeDecoder.clear()
            isEvaluating = false

            _uiState.update {
                it.copy(
                    selectedDrillLength = batchSize,
                    sessionBatchSize = batchSize,
                    showStartLessonDialog = false,
                    isSessionActive = true,
                    isSessionFinished = false,
                    currentChallengeIndex = 1,
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    targetCharacter = initialTarget,
                    targetMorsePattern = pattern,
                    verificationStatus = VerificationStatus.IDLE,
                    lastEvaluatedChar = null,
                    masteredCharacters = mastered,
                    sessionCharacterAttempts = emptyMap(),
                    feedbackMessage = null,
                    levelUpMessage = null,
                    liveDecodedText = "",
                    currentMorseSymbol = ""
                )
            }

            if (dspManager.hasRecordPermission()) {
                dspManager.startListening()
            }
        }
    }

    /**
     * Navigates back to the Pre-Drill Setup & Calibration view from a summary screen.
     */
    fun returnToSetup() {
        sessionCharacterAttempts.clear()
        activeDecoder.clear()
        _uiState.update {
            it.copy(
                isSessionActive = false,
                isSessionFinished = false,
                currentChallengeIndex = 0,
                feedbackMessage = null,
                levelUpMessage = null,
                verificationStatus = VerificationStatus.IDLE
            )
        }
        if (dspManager.hasRecordPermission()) {
            dspManager.startListening()
        }
    }

    /**
     * Dismisses the Start Lesson dialog (legacy compatibility).
     */
    fun dismissStartLessonDialog() {
        _uiState.update { it.copy(showStartLessonDialog = false) }
    }

    /**
     * Re-opens the Start Lesson dialog (legacy compatibility).
     */
    fun openStartLessonDialog() {
        _uiState.update { it.copy(showStartLessonDialog = true) }
    }

    /**
     * Verifies a keyed character from CwDecoder against the active Koch challenge.
     * Displays a momentary "Correct" or "Incorrect" indicator in the main challenge window
     * before rendering the next character challenge or halting at completion.
     */
    fun verifyKeyedCharacter(keyedChar: String) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val target = currentState.targetCharacter
        if (target.isEmpty() || !currentState.isSessionActive || currentState.isSessionFinished || isEvaluating) return

        val isCorrect = keyedChar.equals(target, ignoreCase = true)
        Log.d(TAG, "Keyed: '$keyedChar', Target: '$target', isCorrect=$isCorrect, index=${currentState.currentChallengeIndex}/${currentState.sessionBatchSize}")

        isEvaluating = true
        activeDecoder.clear()

        viewModelScope.launch(ioDispatcher) {
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

            // 2. Compute session metrics
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
                Log.d(TAG, "Hardware Keying Koch advancement achieved! ${advancement.message}")
            }

            // 4. Render momentary verification status in the main challenge window
            _uiState.update {
                it.copy(
                    verificationStatus = if (isCorrect) VerificationStatus.CORRECT else VerificationStatus.INCORRECT,
                    lastEvaluatedChar = keyedChar,
                    lastKeyedCharacter = keyedChar,
                    lastKeyWasCorrect = isCorrect,
                    sessionTotalAttempts = newTotalAttempts,
                    sessionCorrectAttempts = newCorrectAttempts,
                    sessionAccuracy = newAccuracy,
                    masteredCharacters = updatedMastered,
                    sessionCharacterAttempts = sessionCharacterAttempts.toMap(),
                    feedbackMessage = if (isCorrect) "Correct! Target '$target' sent cleanly." else "Decoded '$keyedChar', expected '$target'."
                )
            }

            // Hold visual feedback indicator before rendering next challenge
            delay(FEEDBACK_HOLD_MS)

            if (isBatchFinished) {
                // Halt at completion state & release audio capture
                dspManager.stopListening()

                _uiState.update {
                    it.copy(
                        activeProfile = if (advancement != null) profile.copy(currentKochLevel = effectiveLevel) else profile,
                        activeKochLevel = effectiveLevel,
                        availableCharacters = activePool,
                        verificationStatus = VerificationStatus.IDLE,
                        isSessionActive = false,
                        isSessionFinished = true,
                        levelUpMessage = levelUpMsg ?: currentState.levelUpMessage,
                        liveDecodedText = ""
                    )
                }
            } else {
                // Render next character challenge using v1.7 dynamic priority weighting
                val nextTarget = pickNextTarget(profile.id, effectiveLevel)
                val nextPattern = MorseConstants.MORSE_MAP[nextTarget] ?: ""

                _uiState.update {
                    it.copy(
                        activeProfile = if (advancement != null) profile.copy(currentKochLevel = effectiveLevel) else profile,
                        activeKochLevel = effectiveLevel,
                        availableCharacters = activePool,
                        currentChallengeIndex = currentState.currentChallengeIndex + 1,
                        targetCharacter = nextTarget,
                        targetMorsePattern = nextPattern,
                        verificationStatus = VerificationStatus.IDLE,
                        levelUpMessage = levelUpMsg ?: currentState.levelUpMessage,
                        liveDecodedText = "",
                        currentMorseSymbol = ""
                    )
                }
            }

            activeDecoder.clear()
            isEvaluating = false
        }
    }

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
     * Skips the current challenge and loads a new target from the current level pool.
     */
    fun skipChallenge() {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        if (!currentState.isSessionActive || currentState.isSessionFinished) return

        viewModelScope.launch(ioDispatcher) {
            val isBatchFinished = currentState.currentChallengeIndex >= currentState.sessionBatchSize
            if (isBatchFinished) {
                dspManager.stopListening()
                _uiState.update {
                    it.copy(
                        isSessionActive = false,
                        isSessionFinished = true,
                        verificationStatus = VerificationStatus.IDLE
                    )
                }
            } else {
                val nextTarget = pickNextTarget(profile.id, currentState.activeKochLevel)
                activeDecoder.clear()
                _uiState.update {
                    it.copy(
                        currentChallengeIndex = currentState.currentChallengeIndex + 1,
                        targetCharacter = nextTarget,
                        targetMorsePattern = MorseConstants.MORSE_MAP[nextTarget] ?: "",
                        feedbackMessage = null,
                        lastKeyedCharacter = null,
                        lastKeyWasCorrect = null,
                        liveDecodedText = "",
                        verificationStatus = VerificationStatus.IDLE
                    )
                }
            }
        }
    }

    /**
     * Starts microphone audio capture and Goertzel DSP analysis.
     */
    fun startListening() {
        if (!dspManager.hasRecordPermission()) {
            _uiState.update {
                it.copy(
                    hasRecordPermission = false,
                    userMessage = "Microphone permission is required to detect radio sidetone."
                )
            }
            return
        }
        _uiState.update { it.copy(hasRecordPermission = true, userMessage = null) }
        dspManager.startListening()
    }

    /**
     * Stops microphone audio recording and analysis.
     */
    fun stopListening() {
        dspManager.stopListening()
    }

    /**
     * Toggles between listening and paused states.
     */
    fun toggleListening() {
        if (_uiState.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    /**
     * Updates target CW sidetone frequency (e.g., 600Hz, 700Hz, 800Hz).
     */
    fun setTargetFrequency(frequencyHz: Double) {
        dspManager.setTargetFrequency(frequencyHz)
        _uiState.update { it.copy(targetFrequencyHz = frequencyHz) }
    }

    /**
     * Updates Goertzel squelch threshold from normalized UI slider (0.0 to 1.0).
     */
    fun setSquelchLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _squelchLevel.value = clamped
        val magnitude = MorseDSPManager.squelchLevelToMagnitude(clamped)
        dspManager.setSquelchThreshold(magnitude)
    }

    /**
     * Clears live decoded text buffer.
     */
    fun clearDecodedText() {
        activeDecoder.clear()
    }

    /**
     * Updates state when runtime microphone permission changes.
     */
    fun onPermissionResult(isGranted: Boolean) {
        _uiState.update { it.copy(hasRecordPermission = isGranted) }
        if (isGranted && !_uiState.value.isSessionFinished) {
            startListening()
        }
    }

    /**
     * Clears user feedback / snackbar messages.
     */
    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        dspManager.stopListening()
        activeDecoder.clear()
    }
}
