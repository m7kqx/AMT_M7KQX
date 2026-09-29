package com.example.androidmorsetrainer.ui.screens.send

import androidx.compose.runtime.Immutable
import com.example.androidmorsetrainer.data.local.entity.UserProfile

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
    val squelchLevel: Float = 0.091f,
    val detectionThreshold: Double = 0.05,
    val currentMagnitude: Double = 0.0,
    val liveDecodedText: String = "",
    val currentMorseSymbol: String = "",
    val estimatedWpm: Int = 20,
    val sessionTotalAttempts: Int = 0,
    val sessionCorrectAttempts: Int = 0,
    val sessionAccuracy: Float = 0.0f,
    val lastKeyedCharacter: String? = null,
    val lastKeyWasCorrect: Boolean? = null,
    val feedbackMessage: String? = null,
    val levelUpMessage: String? = null,
    val hasRecordPermission: Boolean = false,
    val userMessage: String? = null
) {
    val accuracyPercentage: Int
        get() = sessionAccuracy.toInt()
}
