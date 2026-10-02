package com.example.androidmorsetrainer.ui.screens.train

import androidx.compose.runtime.Immutable
import com.example.androidmorsetrainer.data.local.entity.UserProfile

enum class DrillState {
    DrillSetup,
    DrillActive,
    ShowingResult,
    Finished
}

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
    val drillState: DrillState = DrillState.DrillSetup,
    val isPlayingAudio: Boolean = false,
    val isReplayTone: Boolean = false,
    val selectedDrillLength: Int = 20,
    val sessionBatchSize: Int = 20,
    val currentChallengeIndex: Int = 0,
    val sessionTotalAttempts: Int = 0,
    val sessionCorrectAttempts: Int = 0,
    val sessionAccuracy: Float = 0.0f,
    val lastGuessedCharacter: String? = null,
    val lastGuessWasCorrect: Boolean? = null,
    val feedbackMessage: String? = null,
    val levelUpMessage: String? = null,
    val masteredCharacters: Set<String> = emptySet(),
    val sessionCharacterAttempts: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val showStartLessonDialog: Boolean = false
) {
    val hasTarget: Boolean
        get() = targetCharacter.isNotEmpty()

    val accuracyFormatted: String
        get() = if (sessionTotalAttempts == 0) "0.0%" else String.format("%.1f%%", sessionAccuracy)

    val progressFraction: Float
        get() = if (sessionBatchSize > 0) {
            (currentChallengeIndex.coerceAtLeast(1) - 1).toFloat() / sessionBatchSize
        } else 0f
}
