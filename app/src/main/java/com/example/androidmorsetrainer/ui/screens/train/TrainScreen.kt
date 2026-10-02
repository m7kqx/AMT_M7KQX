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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.morse.MorseConstants

@Composable
fun TrainScreen(
    activeProfile: UserProfile?,
    modifier: Modifier = Modifier,
    viewModel: TrainViewModel = viewModel(factory = TrainViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(activeProfile?.id, activeProfile?.currentKochLevel) {
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
                    onResetSession = viewModel::resetSession,
                    onDismissLevelUpMessage = viewModel::dismissLevelUpMessage,
                    onStartLesson = viewModel::startLesson,
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

        // 3. Drill Objectives & Information Card
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
                        title = "Accuracy",
                        value = uiState.accuracyFormatted,
                        valueColor = when {
                            uiState.sessionAccuracy >= 70.0f -> Color(0xFF10B981)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainScreenContent(
    uiState: TrainUiState,
    onPlayTone: () -> Unit,
    onGuess: (String) -> Unit,
    onResetSession: () -> Unit,
    onDismissLevelUpMessage: () -> Unit,
    onStartLesson: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Dynamic grid scaling: cleanly balances small character pools (Level 1-3)
    // while scaling adaptively on modern high-resolution displays.
    val gridColumns = when {
        uiState.availableCharacters.size <= 2 -> GridCells.Fixed(2)
        uiState.availableCharacters.size == 3 -> GridCells.Fixed(3)
        uiState.availableCharacters.size == 4 -> GridCells.Fixed(4)
        else -> GridCells.Adaptive(minSize = 64.dp)
    }

    LazyVerticalGrid(
        columns = gridColumns,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (uiState.drillState == DrillState.DrillSetup) {
            // 1. Session Progress Banner ("Challenge 5/20")
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Challenge ${uiState.currentChallengeIndex} of ${uiState.sessionBatchSize}",
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Accuracy: ${uiState.accuracyFormatted}",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { uiState.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // 2. Active Koch Level visual indicator and progression
            item(span = { GridItemSpan(maxLineSpan) }) {
                KochLevelHeader(
                    activeLevel = uiState.activeKochLevel,
                    profileName = uiState.activeProfile?.name.orEmpty(),
                    availableCharacters = uiState.availableCharacters
                )
            }
        }

        // 3. Celebratory Level-Up Banner (if triggered)
        if (uiState.levelUpMessage != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LevelUpBanner(
                    message = uiState.levelUpMessage,
                    onDismiss = onDismissLevelUpMessage
                )
            }
        }

        // 4. Real-Time Score & Accuracy Feedback
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScoreFeedbackCard(
                accuracy = uiState.sessionAccuracy,
                accuracyFormatted = uiState.accuracyFormatted,
                correctCount = uiState.sessionCorrectAttempts,
                totalCount = uiState.sessionTotalAttempts,
                minAttempts = uiState.sessionBatchSize,
                onResetSession = onResetSession
            )
        }

        // 5. Last Guess Outcome Feedback Banner
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp), // Fixed height to prevent UI shifting
                contentAlignment = Alignment.Center
            ) {
                if (uiState.lastGuessWasCorrect != null && uiState.feedbackMessage != null) {
                    GuessFeedbackBanner(
                        isCorrect = uiState.lastGuessWasCorrect,
                        message = uiState.feedbackMessage
                    )
                }
            }
        }

        // 6. Large "Play Tone" Button
        item(span = { GridItemSpan(maxLineSpan) }) {
            PlayToneButton(
                isPlaying = uiState.isPlayingAudio,
                drillState = uiState.drillState,
                onClick = if (uiState.drillState == DrillState.DrillSetup) onStartLesson else onPlayTone
            )
        }

        // 7. Answer Grid Section Title
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                Text(
                    text = "Select What You Heard",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Active characters for Koch Level ${uiState.activeKochLevel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 8. Dynamic Grid of Answer Buttons with Crossfade animation on challenge state change
        item(span = { GridItemSpan(maxLineSpan) }) {
            Crossfade(
                targetState = uiState.targetCharacter,
                animationSpec = tween(durationMillis = 250),
                label = "challengeTransition"
            ) { _ ->
                AnswerGrid(
                    availableCharacters = uiState.availableCharacters,
                    lastGuessedCharacter = uiState.lastGuessedCharacter,
                    lastGuessWasCorrect = uiState.lastGuessWasCorrect,
                    masteredCharacters = uiState.masteredCharacters,
                    hasTarget = uiState.hasTarget,
                    drillState = uiState.drillState,
                    onGuess = onGuess
                )
            }
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
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
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
    onResetSession: () -> Unit
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
                Text(
                    text = "Session Score",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onResetSession,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Session",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Reset", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Metric 1: Accuracy %
                ScoreMetricTile(
                    title = "Accuracy",
                    value = accuracyFormatted,
                    valueColor = when {
                        totalCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                        accuracy >= 90.0f -> Color(0xFF10B981) // Emerald
                        accuracy >= 70.0f -> Color(0xFFF59E0B) // Amber
                        else -> MaterialTheme.colorScheme.error
                    }
                )

                // Metric 2: Score Ratio
                ScoreMetricTile(
                    title = "Score",
                    value = "$correctCount / $totalCount",
                    valueColor = MaterialTheme.colorScheme.onSurface
                )

                // Metric 3: Promotion Threshold
                ScoreMetricTile(
                    title = "Target",
                    value = "≥ 90%",
                    valueColor = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ScoreMetricTile(
    title: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun GuessFeedbackBanner(
    isCorrect: Boolean,
    message: String
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val backgroundColor = if (isCorrect) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        val contentColor = if (isCorrect) Color(0xFF1B5E20) else Color(0xFFB71C1C)
        val icon = if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = backgroundColor,
            border = BorderStroke(1.dp, contentColor.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun PlayToneButton(
    isPlaying: Boolean,
    drillState: DrillState,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = if (drillState != DrillState.DrillSetup) !isPlaying else true,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isPlaying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Playing Morse Tone...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else if (drillState == DrillState.DrillSetup) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Start Drill",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Repeat Tone",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
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
