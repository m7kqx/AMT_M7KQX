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
    val currentWpm: Int = 20,
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
    val showStartLessonDialog: Boolean = false,
    val overallAccuracy: Int = 0,
    val lastDrillAccuracy: Int = 0,
    val newlyIntroducedCharacter: String? = null,
    val newCharacterDotRepresentation: String? = null,
    val activeHintCharacter: String? = newlyIntroducedCharacter,
    val activeHintDotRep: String? = newCharacterDotRepresentation,
    val newCharacterVisualAid: String? = if (newlyIntroducedCharacter != null && newCharacterDotRepresentation != null) {
        "$newlyIntroducedCharacter $newCharacterDotRepresentation"
    } else if (activeHintCharacter != null && activeHintDotRep != null) {
        "$activeHintCharacter $activeHintDotRep"
    } else null,
    val isVisualAidActive: Boolean = false,
    val showNewCharacterVisualAid: Boolean = false,
    val activeNewCharacterSuccessCount: Int = 0,
    val newCharacterSuccessCount: Int = activeNewCharacterSuccessCount,
    val characterSuccessCounts: Map<String, Int> = emptyMap(),
    val characterAccuracies: Map<String, Float> = emptyMap()
) {
    val activeNewCharacter: String?
        get() = newlyIntroducedCharacter ?: activeHintCharacter

    val visualAidText: String?
        get() = if (!isVisualAidActive || !showNewCharacterVisualAid) {
            null
        } else if (activeHintCharacter != null && activeHintDotRep != null) {
            "$activeHintCharacter $activeHintDotRep"
        } else newCharacterVisualAid
    val hasTarget: Boolean
        get() = targetCharacter.isNotEmpty()

    val accuracyFormatted: String
        get() = if (sessionTotalAttempts == 0) "0.0%" else String.format("%.1f%%", sessionAccuracy)

    val progressFraction: Float
        get() = if (sessionBatchSize > 0) {
            (currentChallengeIndex.coerceAtLeast(1) - 1).toFloat() / sessionBatchSize
        } else 0f

    val isSessionActive: Boolean
        get() = drillState == DrillState.DrillActive || drillState == DrillState.ShowingResult

    val isSessionFinished: Boolean
        get() = drillState == DrillState.Finished
}
