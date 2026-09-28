package com.example.androidmorsetrainer.ui.screens.train

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
        TrainScreenContent(
            uiState = uiState,
            onPlayTone = viewModel::playTone,
            onGuess = viewModel::submitGuess,
            onResetSession = viewModel::resetSession,
            onDismissLevelUpMessage = viewModel::dismissLevelUpMessage,
            modifier = modifier
        )
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
        // 1. Active Koch Level visual indicator and progression
        item(span = { GridItemSpan(maxLineSpan) }) {
            KochLevelHeader(
                activeLevel = uiState.activeKochLevel,
                profileName = uiState.activeProfile?.name.orEmpty(),
                availableCharacters = uiState.availableCharacters
            )
        }

        // 2. Celebratory Level-Up Banner (if triggered)
        if (uiState.levelUpMessage != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LevelUpBanner(
                    message = uiState.levelUpMessage,
                    onDismiss = onDismissLevelUpMessage
                )
            }
        }

        // 3. Real-Time Score & Accuracy Feedback
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScoreFeedbackCard(
                accuracy = uiState.sessionAccuracy,
                accuracyFormatted = uiState.accuracyFormatted,
                correctCount = uiState.sessionCorrectAttempts,
                totalCount = uiState.sessionTotalAttempts,
                minAttempts = TrainViewModel.DEFAULT_MIN_ATTEMPTS,
                onResetSession = onResetSession
            )
        }

        // 4. Last Guess Outcome Feedback Banner
        if (uiState.lastGuessWasCorrect != null && uiState.feedbackMessage != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GuessFeedbackBanner(
                    isCorrect = uiState.lastGuessWasCorrect,
                    message = uiState.feedbackMessage
                )
            }
        }

        // 5. Large "Play Tone" Button
        item(span = { GridItemSpan(maxLineSpan) }) {
            PlayToneButton(
                isPlaying = uiState.isPlayingAudio,
                hasTarget = uiState.hasTarget,
                hasGuessedBefore = uiState.lastGuessedCharacter != null,
                onClick = onPlayTone
            )
        }

        // 6. Answer Grid Section Title
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

        // 7. Dynamic Grid of Answer Buttons for Active Characters
        items(uiState.availableCharacters, key = { it }) { character ->
            val isLastGuessed = uiState.lastGuessedCharacter == character
            val morseCode = MorseConstants.MORSE_MAP[character.uppercase()] ?: ""
            AnswerButton(
                character = character,
                morseCode = morseCode,
                isLastGuessed = isLastGuessed,
                lastGuessCorrect = if (isLastGuessed) uiState.lastGuessWasCorrect else null,
                enabled = uiState.hasTarget,
                onClick = { onGuess(character) }
            )
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
    hasTarget: Boolean,
    hasGuessedBefore: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = hasTarget && !isPlaying,
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
            } else {
                Icon(
                    imageVector = if (hasGuessedBefore) Icons.Default.GraphicEq else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (hasGuessedBefore) "Replay Tone" else "Play Morse Tone",
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
            if (morseCode.isNotEmpty()) {
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
