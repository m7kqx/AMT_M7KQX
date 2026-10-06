package com.example.androidmorsetrainer.ui.screens.train

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.morse.MorseConstants

@Composable
fun TrainScreen(
    activeProfile: UserProfile?,
    modifier: Modifier = Modifier,
    viewModel: TrainViewModel = viewModel(factory = TrainViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DisposableEffect(uiState.isSessionActive) {
        val activity = context as? Activity
        if (uiState.isSessionActive) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(activeProfile?.id) {
        viewModel.setActiveProfile(activeProfile)
    }

    if (activeProfile == null) {
        NoProfileScreen(modifier = modifier)
    } else {
        when (uiState.drillState) {
            DrillState.DrillSetup -> {
                TrainPreDrillSetupContent(
                    uiState = uiState,
                    profileName = activeProfile.name,
                    onSelectDrillLength = viewModel::setDrillLength,
                    onSelectWpm = viewModel::setWpm,
                    onStartDrill = { viewModel.startLesson(uiState.selectedDrillLength) },
                    modifier = modifier
                )
            }
            DrillState.Finished -> {
                TrainDrillSummaryContent(
                    uiState = uiState,
                    onStartNewDrill = { viewModel.startLesson(uiState.selectedDrillLength) },
                    onReturnToSetup = viewModel::returnToSetup,
                    onDismissLevelUpMessage = viewModel::dismissLevelUpMessage,
                    modifier = modifier
                )
            }
            DrillState.DrillActive, DrillState.ShowingResult -> {
                TrainScreenContent(
                    uiState = uiState,
                    onPlayTone = viewModel::playTone,
                    onGuess = viewModel::submitGuess,
                    onQuitDrill = viewModel::quitDrill,
                    onDismissLevelUpMessage = viewModel::dismissLevelUpMessage,
                    onStartLesson = viewModel::startLesson,
                    onSetWpm = viewModel::setWpm,
                    modifier = modifier
                )
            }
        }
    }
}

/**
 * Dedicated Material 3 Pre-Drill Setup Screen for Receive Mode.
 * Allows the user to select drill length (20, 50, 100 challenges) and review active characters.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainPreDrillSetupContent(
    uiState: TrainUiState,
    profileName: String,
    onSelectDrillLength: (Int) -> Unit,
    onSelectWpm: (Int) -> Unit,
    onStartDrill: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Card: Mode & Trainee Profile
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "RECEIVE DRILL SETUP",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Koch Level ${uiState.activeKochLevel} of 42",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (profileName.isNotEmpty()) {
                            Text(
                                text = "Trainee: $profileName",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "${uiState.availableCharacters.size} Chars",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Active sequence chips
                Text(
                    text = "Active Characters Pool:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    uiState.availableCharacters.forEach { char ->
                        val isMastered = char.uppercase() in uiState.masteredCharacters
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isMastered) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                if (isMastered) Color(0xFF10B981) else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Text(
                                text = char,
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = if (isMastered) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Drill Length Selection Card (20, 50, 100)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Text(
                    text = "SELECT DRILL LENGTH",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Select number of challenges in this practice session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val drillOptions = listOf(20 to "Quick", 50 to "Standard", 100 to "Endurance")
                    drillOptions.forEach { (count, label) ->
                        val isSelected = uiState.selectedDrillLength == count
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectDrillLength(count) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "$count",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }
        }

        // 3. Playback Speed (WPM) Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PLAYBACK SPEED (WPM)",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Scales Paris timing speed (10 to 25 WPM)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                WpmDropdownSelector(
                    currentWpm = uiState.currentWpm,
                    onWpmChange = onSelectWpm,
                    modifier = Modifier.width(135.dp)
                )
            }
        }

        // 4. Drill Objectives & Information Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Training Objectives",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• Listen to synthesized Morse audio tones at standard Paris timing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Legacy v1.7 dynamic priority will increase frequency of newly unlocked or struggling characters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• Achieve ≥ 70% accuracy across ≥ 5 attempts on the latest character to advance to the next Koch level.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))

        // 4. Prominent "Start Drill" Action Button
        Button(
            onClick = onStartDrill,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Start Receive Drill (${uiState.selectedDrillLength} Challenges)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Dedicated Material 3 Completion Summary Screen for Receive Mode.
 */
@Composable
private fun TrainDrillSummaryContent(
    uiState: TrainUiState,
    onStartNewDrill: () -> Unit,
    onReturnToSetup: () -> Unit,
    onDismissLevelUpMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Completion Trophy Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(68.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "DRILL COMPLETE!",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Completed ${uiState.sessionBatchSize} challenges",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // 2. Celebratory Level-Up Banner (if triggered)
        if (uiState.levelUpMessage != null) {
            LevelUpBanner(
                message = uiState.levelUpMessage,
                onDismiss = onDismissLevelUpMessage
            )
        }

        // 3. Performance Summary Metrics
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Performance Breakdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ScoreMetricTile(
                        title = "Drill Accuracy",
                        value = "${uiState.lastDrillAccuracy}%",
                        valueColor = when {
                            uiState.lastDrillAccuracy >= 70 -> Color(0xFF10B981)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    ScoreMetricTile(
                        title = "Overall Accuracy",
                        value = "${uiState.overallAccuracy}%",
                        valueColor = when {
                            uiState.overallAccuracy >= 70 -> Color(0xFF10B981)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    ScoreMetricTile(
                        title = "Score",
                        value = "${uiState.sessionCorrectAttempts} / ${uiState.sessionTotalAttempts}",
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    ScoreMetricTile(
                        title = "Koch Level",
                        value = "${uiState.activeKochLevel}",
                        valueColor = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Career Koch Accuracy",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Cumulative across all practiced characters",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${uiState.overallAccuracy}%",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.overallAccuracy >= 70) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 4. Action Buttons
        Button(
            onClick = onStartNewDrill,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Start Another Drill (${uiState.selectedDrillLength})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = onReturnToSetup,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Change Drill Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun NoProfileScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "No Active Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Please create or select a user profile in the Profiles tab to start your Koch method training.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun TrainScreenContent(
    uiState: TrainUiState,
    onPlayTone: () -> Unit,
    onGuess: (String) -> Unit,
    onQuitDrill: () -> Unit,
    onDismissLevelUpMessage: () -> Unit,
    onStartLesson: () -> Unit,
    modifier: Modifier = Modifier,
    onResetSession: () -> Unit = onQuitDrill,
    onBackspace: () -> Unit = {},
    onSetWpm: (Int) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Upper Information Section (compact, scrollable if height is constrained)
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Celebratory Level-Up Banner (if triggered)
            if (uiState.levelUpMessage != null) {
                LevelUpBanner(
                    message = uiState.levelUpMessage,
                    onDismiss = onDismissLevelUpMessage
                )
            }

            // 2. Real-Time Score & Accuracy Feedback (Horizontal compact layout)
            ScoreFeedbackCard(
                accuracy = uiState.sessionAccuracy,
                accuracyFormatted = uiState.accuracyFormatted,
                correctCount = uiState.sessionCorrectAttempts,
                totalCount = uiState.sessionTotalAttempts,
                minAttempts = uiState.sessionBatchSize,
                onQuitDrill = onQuitDrill,
                challengeIndex = uiState.currentChallengeIndex,
                batchSize = uiState.sessionBatchSize,
                progressFraction = uiState.progressFraction
            )

            // 3. Last Guess Outcome Feedback Banner (Horizontal compact layout)
            if (uiState.lastGuessWasCorrect != null && uiState.feedbackMessage != null) {
                GuessFeedbackBanner(
                    isCorrect = uiState.lastGuessWasCorrect,
                    message = uiState.feedbackMessage
                )
            }

            // 4. Active Character Prompt & WPM Speed Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Select What You Heard",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (uiState.isPlayingAudio) {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "Active characters for Koch Level ${uiState.activeKochLevel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                WpmDropdownSelector(
                    currentWpm = uiState.currentWpm,
                    onWpmChange = onSetWpm,
                    enabled = !uiState.isPlayingAudio,
                    modifier = Modifier.width(135.dp)
                )
            }

            // 5. Dynamic Visual Aid for Newly Introduced Character (Large Morse dot representation)
            val visualAidText = uiState.visualAidText

            AnimatedVisibility(
                visible = uiState.isVisualAidActive && uiState.showNewCharacterVisualAid && !visualAidText.isNullOrEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = visualAidText ?: "",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }

        // 5. Custom In-App Koch Keyboard anchored to bottom of viewable area
        Crossfade(
            targetState = uiState.targetCharacter,
            animationSpec = tween(durationMillis = 250),
            label = "challengeTransition",
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
        ) { _ ->
            KochKeyboard(
                currentKochLevel = uiState.activeKochLevel,
                onCharacterClick = onGuess,
                onRepeatClick = onPlayTone,
                enabled = uiState.hasTarget && uiState.drillState == DrillState.DrillActive,
                lastGuessedCharacter = uiState.lastGuessedCharacter,
                lastGuessWasCorrect = uiState.lastGuessWasCorrect,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AnswerGrid(
    availableCharacters: List<String>,
    lastGuessedCharacter: String?,
    lastGuessWasCorrect: Boolean?,
    masteredCharacters: Set<String> = emptySet(),
    hasTarget: Boolean,
    drillState: DrillState,
    onGuess: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val columnsCount = when {
        availableCharacters.size <= 2 -> 2
        availableCharacters.size == 3 -> 3
        availableCharacters.size == 4 -> 4
        availableCharacters.size <= 8 -> 4
        else -> 5
    }

    val rows = availableCharacters.chunked(columnsCount)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { character ->
                    val isLastGuessed = lastGuessedCharacter == character
                    val morseCode = MorseConstants.MORSE_MAP[character.uppercase()] ?: ""
                    val showHint = character.uppercase() !in masteredCharacters
                    Box(modifier = Modifier.weight(1f)) {
                        AnswerButton(
                            character = character,
                            morseCode = morseCode,
                            showHint = showHint,
                            isLastGuessed = isLastGuessed,
                            lastGuessCorrect = if (isLastGuessed) lastGuessWasCorrect else null,
                            enabled = hasTarget && drillState == DrillState.DrillActive,
                            onClick = { onGuess(character) }
                        )
                    }
                }
                if (rowItems.size < columnsCount) {
                    repeat(columnsCount - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KochLevelHeader(
    activeLevel: Int,
    profileName: String,
    availableCharacters: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Koch Level $activeLevel of 42",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (profileName.isNotEmpty()) {
                        Text(
                            text = "Trainee: $profileName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = "${availableCharacters.size} chars active",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Level progression bar (Level 1..42)
            LinearProgressIndicator(
                progress = { activeLevel / 42f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Unlocked characters preview chips
            Text(
                text = "Active Sequence:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                availableCharacters.forEach { char ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = char,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelUpBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long = 4000L
) {
    // Transient auto-dismissal: automatically dismisses without requiring a manual click
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(durationMs)
        onDismiss()
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreFeedbackCard(
    accuracy: Float,
    accuracyFormatted: String,
    correctCount: Int,
    totalCount: Int,
    minAttempts: Int,
    onQuitDrill: () -> Unit,
    modifier: Modifier = Modifier,
    challengeIndex: Int = 0,
    batchSize: Int = 0,
    progressFraction: Float = 0f
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Header Row: Session title / Challenge progress & Quit Drill button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Session Score",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (batchSize > 0 && challengeIndex > 0) {
                        Text(
                            text = "Challenge $challengeIndex of $batchSize",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                TextButton(
                    onClick = onQuitDrill,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Quit Drill",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Quit Drill",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metrics Row: Horizontally spaced metrics with explicit minWidth and single-line constraints
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScoreMetricTile(
                    title = "Accuracy",
                    value = accuracyFormatted,
                    valueColor = when {
                        totalCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        accuracy >= 90.0f -> Color(0xFF10B981) // Emerald
                        accuracy >= 70.0f -> Color(0xFFF59E0B) // Amber
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                )
                ScoreMetricTile(
                    title = "Score",
                    value = "$correctCount / $totalCount",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                )
                ScoreMetricTile(
                    title = "Target",
                    value = "≥ 90%",
                    valueColor = Color(0xFF10B981),
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                )
            }

            if (batchSize > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Level-up progress helper
            val attemptsRemaining = (minAttempts - totalCount).coerceAtLeast(0)
            val progressText = if (attemptsRemaining > 0) {
                "$attemptsRemaining more attempt${if (attemptsRemaining > 1) "s" else ""} needed to evaluate promotion"
            } else if (accuracy >= 90.0f) {
                "Promotion threshold achieved! Level advances on next evaluation"
            } else {
                "Reach ≥ 90% accuracy over $minAttempts attempts to advance"
            }

            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScoreMetricTile(
    title: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .defaultMinSize(minWidth = 72.dp)
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun GuessFeedbackBanner(
    isCorrect: Boolean,
    message: String,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        val backgroundColor = if (isCorrect) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        val contentColor = if (isCorrect) Color(0xFF1B5E20) else Color(0xFFB71C1C)
        val icon = if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = backgroundColor,
            border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Material 3 Exposed Dropdown Menu for dynamic Words Per Minute (WPM) selection (10 to 25 WPM).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WpmDropdownSelector(
    currentWpm: Int,
    onWpmChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val wpmOptions = remember { (10..25).toList() }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = "$currentWpm WPM",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Speed", style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            wpmOptions.forEach { wpm ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "$wpm WPM",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (wpm == currentWpm) FontWeight.Bold else FontWeight.Normal,
                            color = if (wpm == currentWpm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onWpmChange(wpm)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Modern, tactile answer button showing the bold character glyph and its Morse code pattern below.
 * Scales cleanly on modern high-resolution screens.
 */
@Composable
private fun AnswerButton(
    character: String,
    morseCode: String,
    showHint: Boolean = true,
    isLastGuessed: Boolean,
    lastGuessCorrect: Boolean?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        isLastGuessed && lastGuessCorrect == true -> Color(0xFF10B981)
        isLastGuessed && lastGuessCorrect == false -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val borderWidth = if (isLastGuessed) 2.dp else 1.dp

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(borderWidth, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = when {
                isLastGuessed && lastGuessCorrect == true -> Color(0xFFE8F5E9)
                isLastGuessed && lastGuessCorrect == false -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = character,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = if (character.length > 2) 14.sp else 19.sp,
                    fontFamily = FontFamily.Monospace
                ),
                fontWeight = FontWeight.Bold,
                color = when {
                    isLastGuessed && lastGuessCorrect == true -> Color(0xFF1B5E20)
                    isLastGuessed && lastGuessCorrect == false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            if (showHint && morseCode.isNotEmpty()) {
                Text(
                    text = morseCode,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}
