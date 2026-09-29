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
    val minAttemptsForLevelUp: Int = DEFAULT_MIN_ATTEMPTS
) : ViewModel() {

    companion object {
        private const val TAG = "SendViewModel"
        const val DEFAULT_MIN_ATTEMPTS = 10
        const val ACCURACY_THRESHOLD_PERCENT = 90.0f

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
            squelchLevel = MorseDSPManager.magnitudeToSquelchLevel(dspManager.squelchThreshold)
        )
    )
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    private val _squelchLevel = MutableStateFlow(
        MorseDSPManager.magnitudeToSquelchLevel(dspManager.squelchThreshold)
    )
    val squelchLevel: StateFlow<Float> = _squelchLevel.asStateFlow()

    init {
        observeDspState()
        observeToneEvents()
        observeDecoder()
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
     * Sets or updates the active profile and prepares the initial Koch challenge.
     */
    fun setActiveProfile(profile: UserProfile?) {
        if (profile == null) {
            _uiState.update { SendUiState() }
            return
        }

        val currentProfile = _uiState.value.activeProfile
        if (currentProfile?.id == profile.id && currentProfile.currentKochLevel == profile.currentKochLevel && _uiState.value.targetCharacter.isNotEmpty()) {
            _uiState.update { it.copy(activeProfile = profile) }
            return
        }

        val level = profile.currentKochLevel
        val pool = kochMethodManager.getCharactersForLevel(level)

        viewModelScope.launch(ioDispatcher) {
            val initialTarget = pickNextTarget(profile.id, level)
            val pattern = MorseConstants.MORSE_MAP[initialTarget] ?: ""

            Log.d(TAG, "Hardware Keying initialized for ${profile.name} at Level $level, target='$initialTarget'")

            _uiState.update {
                it.copy(
                    activeProfile = profile,
                    activeKochLevel = level,
                    availableCharacters = pool,
                    targetCharacter = initialTarget,
                    targetMorsePattern = pattern,
                    sessionTotalAttempts = 0,
                    sessionCorrectAttempts = 0,
                    sessionAccuracy = 0.0f,
                    lastKeyedCharacter = null,
                    lastKeyWasCorrect = null,
                    feedbackMessage = null,
                    levelUpMessage = null,
                    liveDecodedText = "",
                    currentMorseSymbol = ""
                )
            }
        }
    }

    /**
     * Verifies a keyed character from CwDecoder against the active Koch challenge.
     */
    fun verifyKeyedCharacter(keyedChar: String) {
        val currentState = _uiState.value
        val profile = currentState.activeProfile ?: return
        val target = currentState.targetCharacter
        if (target.isEmpty()) return

        val isCorrect = keyedChar.equals(target, ignoreCase = true)
        Log.d(TAG, "Keyed: '$keyedChar', Target: '$target', isCorrect=$isCorrect")

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

            // 2. Compute session metrics
            val newTotalAttempts = currentState.sessionTotalAttempts + 1
            val newCorrectAttempts = currentState.sessionCorrectAttempts + (if (isCorrect) 1 else 0)
            val newAccuracy = (newCorrectAttempts.toFloat() / newTotalAttempts) * 100.0f

            if (isCorrect) {
                // Clear decoder buffer immediately on correct match
                activeDecoder.clear()

                // Check Koch promotion criteria (>= 90% accuracy over minimum attempts)
                val currentLevel = currentState.activeKochLevel
                val canAdvance = currentLevel < kochMethodManager.maxLevel &&
                        newTotalAttempts >= minAttemptsForLevelUp &&
                        newAccuracy >= ACCURACY_THRESHOLD_PERCENT

                if (canAdvance) {
                    val nextLevel = min(currentLevel + 1, kochMethodManager.maxLevel)
                    val updatedProfile = profile.copy(currentKochLevel = nextLevel)
                    profileRepository.updateProfile(updatedProfile)

                    val newPool = kochMethodManager.getCharactersForLevel(nextLevel)
                    val newlyUnlocked = newPool.lastOrNull() ?: ""
                    val nextTarget = pickNextTarget(profile.id, nextLevel)

                    _uiState.update {
                        it.copy(
                            activeProfile = updatedProfile,
                            activeKochLevel = nextLevel,
                            availableCharacters = newPool,
                            targetCharacter = nextTarget,
                            targetMorsePattern = MorseConstants.MORSE_MAP[nextTarget] ?: "",
                            sessionTotalAttempts = 0,
                            sessionCorrectAttempts = 0,
                            sessionAccuracy = 0.0f,
                            lastKeyedCharacter = keyedChar,
                            lastKeyWasCorrect = true,
                            feedbackMessage = "Perfect! Target was '$target'",
                            levelUpMessage = "Promoted to Level $nextLevel! '$newlyUnlocked' unlocked!",
                            liveDecodedText = ""
                        )
                    }
                } else {
                    val nextTarget = pickNextTarget(profile.id, currentLevel)
                    _uiState.update {
                        it.copy(
                            targetCharacter = nextTarget,
                            targetMorsePattern = MorseConstants.MORSE_MAP[nextTarget] ?: "",
                            sessionTotalAttempts = newTotalAttempts,
                            sessionCorrectAttempts = newCorrectAttempts,
                            sessionAccuracy = newAccuracy,
                            lastKeyedCharacter = keyedChar,
                            lastKeyWasCorrect = true,
                            feedbackMessage = "Correct! Target '$target' sent cleanly.",
                            levelUpMessage = null,
                            liveDecodedText = ""
                        )
                    }
                }
            } else {
                // Incorrect match: keep target active, give feedback
                _uiState.update {
                    it.copy(
                        sessionTotalAttempts = newTotalAttempts,
                        sessionAccuracy = newAccuracy,
                        lastKeyedCharacter = keyedChar,
                        lastKeyWasCorrect = false,
                        feedbackMessage = "Decoded '$keyedChar', expected '$target'. Key again!",
                        levelUpMessage = null
                    )
                }
            }
        }
    }

    private suspend fun pickNextTarget(profileId: Long, level: Int): String {
        val pool = kochMethodManager.getCharactersForLevel(level)
        val stats = profileRepository.getWeightedStatsForProfile(profileId)
        val weightsMap = stats.associate { it.character to it.priorityWeight }
        return kochMethodManager.getWeightedRandomCharacter(pool, weightsMap)
    }

    /**
     * Skips the current challenge and loads a new target from the current level pool.
     */
    fun skipChallenge() {
        val profile = _uiState.value.activeProfile ?: return
        viewModelScope.launch(ioDispatcher) {
            val nextTarget = pickNextTarget(profile.id, _uiState.value.activeKochLevel)
            activeDecoder.clear()
            _uiState.update {
                it.copy(
                    targetCharacter = nextTarget,
                    targetMorsePattern = MorseConstants.MORSE_MAP[nextTarget] ?: "",
                    feedbackMessage = null,
                    lastKeyedCharacter = null,
                    lastKeyWasCorrect = null,
                    liveDecodedText = ""
                )
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
        if (isGranted) {
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
