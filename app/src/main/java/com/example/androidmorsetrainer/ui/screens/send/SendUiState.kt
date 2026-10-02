package com.example.androidmorsetrainer.ui.screens.send

import androidx.compose.runtime.Immutable
import com.example.androidmorsetrainer.data.local.entity.UserProfile

/**
 * Verification state for momentary visual feedback in the main challenge window.
 */
enum class VerificationStatus {
    IDLE,
    CORRECT,
    INCORRECT
}

/**
 * UI State for the Hardware Keying Send Practice Screen.
 * Fully immutable for Jetpack Compose state efficiency.
 */
@Immutable
data class SendUiState(
    val activeProfile: UserProfile? = null,
    val activeKochLevel: Int = 1,
    val targetCharacter: String = "",
    val targetMorsePattern: String = "",
    val availableCharacters: List<String> = emptyList(),
    val isListening: Boolean = false,
    val isTonePresent: Boolean = false,
    val targetFrequencyHz: Double = 700.0,
    val isCustomFrequency: Boolean = false,
    val customFrequencyString: String = "700",
    val squelchLevel: Float = 0.091f,
    val detectionThreshold: Double = 0.05,
    val currentMagnitude: Double = 0.0,
    val liveDecodedText: String = "",
    val currentMorseSymbol: String = "",
    val estimatedWpm: Int = 20,
    val selectedDrillLength: Int = 20,
    val sessionBatchSize: Int = 20,
    val currentChallengeIndex: Int = 0,
    val isSessionActive: Boolean = false,
    val isSessionFinished: Boolean = false,
    val sessionTotalAttempts: Int = 0,
    val sessionCorrectAttempts: Int = 0,
    val sessionAccuracy: Float = 0.0f,
    val verificationStatus: VerificationStatus = VerificationStatus.IDLE,
    val lastEvaluatedChar: String? = null,
    val lastKeyedCharacter: String? = null,
    val lastKeyWasCorrect: Boolean? = null,
    val feedbackMessage: String? = null,
    val levelUpMessage: String? = null,
    val masteredCharacters: Set<String> = emptySet(),
    val sessionCharacterAttempts: Map<String, Int> = emptyMap(),
    val hasRecordPermission: Boolean = false,
    val userMessage: String? = null,
    val showStartLessonDialog: Boolean = false
) {
    val accuracyPercentage: Int
        get() = sessionAccuracy.toInt()

    val showTargetHint: Boolean
        get() = targetCharacter.isNotEmpty() && targetCharacter.uppercase() !in masteredCharacters

    val hasTarget: Boolean
        get() = targetCharacter.isNotEmpty()

    val progressFraction: Float
        get() = if (sessionBatchSize > 0) {
            (currentChallengeIndex.coerceAtLeast(1) - 1).toFloat() / sessionBatchSize
        } else 0f
}
