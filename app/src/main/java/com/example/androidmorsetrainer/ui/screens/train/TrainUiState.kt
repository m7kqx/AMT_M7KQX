package com.example.androidmorsetrainer.ui.screens.train

import androidx.compose.runtime.Immutable
import com.example.androidmorsetrainer.data.local.entity.UserProfile

/**
 * UI State representing the interactive Koch Train Screen.
 * Immutability annotation helps Jetpack Compose optimize recomposition passes on high-refresh screens.
 */
@Immutable
data class TrainUiState(
    val activeProfile: UserProfile? = null,
    val activeKochLevel: Int = 1,
    val availableCharacters: List<String> = emptyList(),
    val targetCharacter: String = "",
    val isPlayingAudio: Boolean = false,
    val sessionTotalAttempts: Int = 0,
    val sessionCorrectAttempts: Int = 0,
    val sessionAccuracy: Float = 0.0f,
    val lastGuessedCharacter: String? = null,
    val lastGuessWasCorrect: Boolean? = null,
    val feedbackMessage: String? = null,
    val levelUpMessage: String? = null,
    val isLoading: Boolean = false
) {
    val hasTarget: Boolean
        get() = targetCharacter.isNotEmpty()

    val accuracyFormatted: String
        get() = if (sessionTotalAttempts == 0) "0.0%" else String.format("%.1f%%", sessionAccuracy)
}
