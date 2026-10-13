package com.example.androidmorsetrainer.ui.screens.train

import androidx.compose.runtime.Immutable
import com.example.androidmorsetrainer.data.local.entity.UserProfile

enum class DrillState {
    ModeSelection,
    DrillSetup,
    DrillActive,
    ShowingResult,
    Finished
}

enum class TrainingMode {
    Koch,
    Prosigns
}

enum class SelectedTrainingMode {
    None,
    Koch,
    Prosign,
    FOCUS,
    TIME_ATTACK;

    val trainingMode: TrainingMode?
        get() = when (this) {
            Koch -> TrainingMode.Koch
            Prosign -> TrainingMode.Prosigns
            FOCUS -> TrainingMode.Koch
            TIME_ATTACK -> TrainingMode.Koch
            None -> null
        }

    companion object {
        val Prosigns: SelectedTrainingMode get() = Prosign
        val Focus: SelectedTrainingMode get() = FOCUS
        val TimeAttack: SelectedTrainingMode get() = TIME_ATTACK
    }
}

/**
 * UI State representing the interactive Koch Train Screen.
 * Immutability annotation helps Jetpack Compose optimize recomposition passes on high-refresh screens.
 */
@Immutable
data class TrainUiState(
    val activeProfile: UserProfile? = null,
    val activeKochLevel: Int = 1,
    val activeProsignLevel: Int = 1,
    val trainingMode: TrainingMode = TrainingMode.Koch,
    val selectedTrainingMode: SelectedTrainingMode = SelectedTrainingMode.None,
    val currentWpm: Int = 20,
    val availableCharacters: List<String> = emptyList(),
    val targetCharacter: String = "",
    val drillState: DrillState = DrillState.ModeSelection,
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
    val characterAccuracies: Map<String, Float> = emptyMap(),
    val prosignTextInput: String = "",
    val hideBottomBarPreference: Boolean = false,
    val focusSelectedCharacters: Set<Char> = setOf('K', 'M', 'R'),
    val timeAttackResponseWindowSeconds: Float = 2.0f,
    val timeAttackInterChallengeDelayMs: Long = 1000L,
    val timeAttackChallengeCount: Int = 20,
    val timeAttackSelectedCharacters: Set<Char> = setOf('K', 'M', 'R'),
    val timeAttackCorrectCount: Int = 0,
    val timeAttackIncorrectCount: Int = 0,
    val timeAttackMissedCount: Int = 0,
    val timeAttackChallengeResults: List<com.example.androidmorsetrainer.data.local.entity.TimeAttackChallengeResult> = emptyList(),
    val latestTimeAttackReport: com.example.androidmorsetrainer.data.local.entity.TimeAttackReport? = null,
    val timeAttackTimerFraction: Float = 1.0f,
    val timeAttackRemainingSeconds: Float = 0.0f,
    val isTimeAttackAwaitingInput: Boolean = false,
    val timeAttackHistory: List<com.example.androidmorsetrainer.data.local.entity.TimeAttackReport> = emptyList(),
    val isShowingTimeAttackHistory: Boolean = false
) {
    val isFocusMode: Boolean
        get() = selectedTrainingMode == SelectedTrainingMode.FOCUS

    val isTimeAttackMode: Boolean
        get() = selectedTrainingMode == SelectedTrainingMode.TIME_ATTACK

    val currentLevel: Int
        get() = if (trainingMode == TrainingMode.Prosigns) activeProsignLevel else activeKochLevel

    val activeNewCharacter: String?
        get() = newlyIntroducedCharacter ?: activeHintCharacter

    val visualAidText: String?
        get() = if (isTimeAttackMode || !isVisualAidActive || !showNewCharacterVisualAid) {
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
