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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import java.text.DateFormat
import java.util.Date
import com.example.androidmorsetrainer.data.local.entity.TimeAttackOutcome
import com.example.androidmorsetrainer.data.local.entity.TimeAttackChallengeResult
import com.example.androidmorsetrainer.data.local.entity.TimeAttackReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.androidmorsetrainer.MorseTrainerApplication
import com.example.androidmorsetrainer.audio.MorseDSPManager
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import com.example.androidmorsetrainer.morse.MorseConstants
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction

@Composable
fun TrainScreen(
    activeProfile: UserProfile?,
    modifier: Modifier = Modifier,
    viewModel: TrainViewModel = viewModel(factory = TrainViewModel.Factory),
    dspManager: MorseDSPManager? = null,
    trainingMode: String = "Receive",
    mode: String = trainingMode
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actualDspManager = dspManager ?: (context.applicationContext as? MorseTrainerApplication)?.container?.morseDSPManager
    val lifecycleOwner = LocalLifecycleOwner.current

    val effectiveMode = if (mode != "Receive") mode else trainingMode
    val isSendMode = effectiveMode.equals("Send", ignoreCase = true)

    DisposableEffect(lifecycleOwner, effectiveMode) {
        if (isSendMode) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                        if (actualDspManager?.hasRecordPermission() == true) {
                            actualDspManager.startListening()
                        }
                    }
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                        actualDspManager?.stopListening()
                    }
                    else -> {}
                }
            }

            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (actualDspManager?.hasRecordPermission() == true) {
                    actualDspManager.startListening()
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                actualDspManager?.stopListening()
            }
        } else {
            actualDspManager?.stopListening()
            onDispose {
                actualDspManager?.stopListening()
            }
        }
    }

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
    } else if (uiState.isShowingTimeAttackHistory) {
        TimeAttackHistoryScreen(
            uiState = uiState,
            profileName = activeProfile.name,
            onNavigateBack = {
                viewModel.showTimeAttackHistory(false)
            },
            modifier = modifier
        )
    } else {
        when (uiState.drillState) {
            DrillState.ModeSelection -> {
                TrainModeSelection(
                    uiState = uiState,
                    profileName = activeProfile.name,
                    onSelectMode = { selectedMode ->
                        actualDspManager?.stopListening()
                        viewModel.selectTrainingMode(selectedMode)
                    },
                    modifier = modifier
                )
            }
            DrillState.DrillSetup -> {
                if (uiState.selectedTrainingMode == SelectedTrainingMode.None) {
                    TrainModeSelection(
                        uiState = uiState,
                        profileName = activeProfile.name,
                        onSelectMode = { selectedMode ->
                            actualDspManager?.stopListening()
                            viewModel.selectTrainingMode(selectedMode)
                        },
                        modifier = modifier
                    )
                } else {
                    TrainSetupScreen(
                        uiState = uiState,
                        profileName = activeProfile.name,
                        onNavigateBack = {
                            actualDspManager?.stopListening()
                            viewModel.returnToModeSelection()
                        },
                        onSelectTrainingMode = { mode ->
                            actualDspManager?.stopListening()
                            viewModel.setTrainingMode(mode)
                        },
                        onSelectDrillLength = viewModel::setDrillLength,
                        onSelectWpm = viewModel::setWpm,
                        onToggleHideBottomBar = viewModel::setHideBottomBarPreference,
                        onStartDrill = {
                            actualDspManager?.stopListening()
                            viewModel.startLesson(uiState.selectedDrillLength)
                        },
                        onToggleFocusCharacter = viewModel::toggleFocusCharacter,
                        onSelectSelectedTrainingMode = { selectedMode ->
                            actualDspManager?.stopListening()
                            viewModel.selectTrainingMode(selectedMode)
                        },
                        onSelectTimeAttackResponseWindow = viewModel::setTimeAttackResponseWindow,
                        onSelectTimeAttackInterChallengeDelay = viewModel::setTimeAttackInterChallengeDelay,
                        onSelectTimeAttackChallengeCount = viewModel::setTimeAttackChallengeCount,
                        onToggleTimeAttackCharacter = viewModel::toggleTimeAttackCharacter,
                        onOpenTimeAttackHistory = {
                            viewModel.showTimeAttackHistory(true)
                        },
                        modifier = modifier
                    )
                }
            }
            DrillState.Finished -> {
                TrainDrillSummaryContent(
                    uiState = uiState,
                    onStartNewDrill = {
                        actualDspManager?.stopListening()
                        viewModel.startLesson(if (uiState.isTimeAttackMode) uiState.timeAttackChallengeCount else uiState.selectedDrillLength)
                    },
                    onReturnToSetup = viewModel::returnToSetup,
                    onReturnToModeSelection = {
                        actualDspManager?.stopListening()
                        viewModel.returnToModeSelection()
                    },
                    onDismissLevelUpMessage = viewModel::dismissLevelUpMessage,
                    onOpenTimeAttackHistory = {
                        viewModel.showTimeAttackHistory(true)
                    },
                    modifier = modifier
                )
            }
            DrillState.DrillActive, DrillState.ShowingResult -> {
                TrainScreenContent(
                    uiState = uiState,
                    onPlayTone = viewModel::playTone,
                    onGuess = viewModel::submitGuess,
                    onUpdateProsignTextInput = viewModel::updateProsignTextInput,
                    onSubmitProsignTextInput = viewModel::submitProsignTextInput,
                    onQuitDrill = viewModel::quitDrill,
                    onDismissLevelUpMessage = viewModel::dismissLevelUpMessage,
                    onStartLesson = {
                        actualDspManager?.stopListening()
                        viewModel.startLesson()
                    },
                    onSetWpm = viewModel::setWpm,
                    onToggleProgressionPause = viewModel::toggleProgressionPause,
                    modifier = modifier
                )
            }
        }
    }
}

/**
 * Data class representing a training curriculum item for the mode selection screen.
 */
data class TrainingModeCardItem(
    val mode: SelectedTrainingMode,
    val title: String,
    val subtitle: String,
    val description: String,
    val levelBadge: String,
    val poolBadge: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val featureTag: String
)

/**
 * Scalable, card-based Mode Selection Screen for Receive Mode.
 * Displays available curriculums (Koch Method and Prosigns & Abbreviations) in a LazyColumn,
 * facilitating future additions of specialized training tracks.
 */
@Composable
fun TrainModeSelection(
    uiState: TrainUiState,
    profileName: String,
    onSelectMode: (SelectedTrainingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modeItems = remember(uiState.activeKochLevel, uiState.activeProsignLevel, uiState.focusSelectedCharacters) {
        listOf(
            TrainingModeCardItem(
                mode = SelectedTrainingMode.Koch,
                title = "Koch Method",
                subtitle = "Sequential Character Reflex Training",
                description = "Master Morse characters sequentially at full target speed (10–25 WPM). Builds acoustic reflex recognition without counting dots and dashes. Dynamic priority weighting increases challenge frequency for newer or struggling characters.",
                levelBadge = "Level ${uiState.activeKochLevel} of ${MorseConstants.MAX_KOCH_LEVEL}",
                poolBadge = "${(uiState.activeKochLevel + 1).coerceAtMost(MorseConstants.KOCH_SEQUENCE.size)} Characters",
                icon = Icons.Default.School,
                featureTag = "Single-Character Keypad"
            ),
            TrainingModeCardItem(
                mode = SelectedTrainingMode.Prosign,
                title = "Prosigns & Abbreviations",
                subtitle = "Procedural Signals & Operating Codes",
                description = "Master run-together procedural signals (<AR>, <SK>, <BT>, <AS>, <SOS>) and common CW radio abbreviations. Features multi-character input and automated transition to continuous acoustic spacing upon proficiency.",
                levelBadge = "Level ${uiState.activeProsignLevel} of 12",
                poolBadge = "${uiState.activeProsignLevel.coerceAtMost(MorseConstants.PROSIGNS_SEQUENCE.size)} Prosigns",
                icon = Icons.Default.Radio,
                featureTag = "Multi-Character Input"
            ),
            TrainingModeCardItem(
                mode = SelectedTrainingMode.FOCUS,
                title = "Focus Mode",
                subtitle = "Targeted Character Practice",
                description = "Custom selection of 3 to 8 characters for concentrated drill sessions at standard Paris timing (10–25 WPM). Ideal for troubleshooting difficult characters, building reflex speed on specific letter clusters, or warming up.",
                levelBadge = "Custom (${uiState.focusSelectedCharacters.size}/8)",
                poolBadge = "${uiState.focusSelectedCharacters.size} Characters",
                icon = Icons.Default.Tune,
                featureTag = "Custom 3–8 Selection"
            ),
            TrainingModeCardItem(
                mode = SelectedTrainingMode.TIME_ATTACK,
                title = "Time Attack",
                subtitle = "Rapid Reflex Decoding",
                description = "Decode Morse characters within a strict 1.0 to 3.5-second countdown window. Challenges your reflex speed with custom character subsets and detailed reporting of correct, incorrect, and missed responses.",
                levelBadge = "${String.format(java.util.Locale.US, "%.1f", uiState.timeAttackResponseWindowSeconds)}s Window",
                poolBadge = "${uiState.timeAttackSelectedCharacters.size} Characters",
                icon = Icons.Default.Timer,
                featureTag = "1.0–3.5s Reaction Timer"
            )
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
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
                    Text(
                        text = "RECEIVE TRAINING",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select Training Mode",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (profileName.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Trainee: $profileName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Choose a curriculum below to configure your practice drill. Progress and accuracy stats are tracked separately for each training track.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }

        items(modeItems) { item ->
            TrainModeCard(
                item = item,
                onClick = { onSelectMode(item.mode) }
            )
        }
    }
}

@Composable
private fun TrainModeCard(
    item: TrainingModeCardItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = item.levelBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = item.poolBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = item.featureTag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private val FOCUS_KEYBOARD_ROW_1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val FOCUS_KEYBOARD_ROW_2 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
private val FOCUS_KEYBOARD_ROW_3 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
private val FOCUS_KEYBOARD_ROW_4 = listOf("Z", "X", "C", "V", "B", "N", "M")

/**
 * 5-row proportional virtual keyboard for Focus Mode character selection.
 * Reuses the layout, proportions, and styling of the Koch keyboard,
 * configuring each key as a multi-select toggle with highlight styling.
 */
@Composable
fun FocusSelectionKeyboard(
    selectedCharacters: Set<Char>,
    onKeyPress: (Char) -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: androidx.compose.ui.unit.Dp = 44.dp
) {
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val effectiveKeyHeight = if (keyHeight == 44.dp) {
        when {
            screenHeightDp < 600 -> 30.dp
            screenHeightDp < 700 -> 34.dp
            screenHeightDp < 800 -> 38.dp
            else -> 44.dp
        }
    } else keyHeight
    val rowSpacing = if (screenHeightDp < 700) 4.dp else 6.dp
    val verticalPadding = if (screenHeightDp < 700) 4.dp else 8.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing)
        ) {
            // Row 1: 1 through 0 (10 keys, weight 1.0f each)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FOCUS_KEYBOARD_ROW_1.forEach { charStr ->
                    FocusKey(
                        label = charStr,
                        isSelected = charStr.first() in selectedCharacters,
                        onClick = { onKeyPress(charStr.first()) },
                        height = effectiveKeyHeight
                    )
                }
            }

            // Row 2: Q through P (10 keys, weight 1.0f each)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FOCUS_KEYBOARD_ROW_2.forEach { charStr ->
                    FocusKey(
                        label = charStr,
                        isSelected = charStr.first() in selectedCharacters,
                        onClick = { onKeyPress(charStr.first()) },
                        height = effectiveKeyHeight
                    )
                }
            }

            // Row 3: A through L (0.5x spacers on both ends, 9 keys weight 1.0f each)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Spacer(modifier = Modifier.weight(0.5f))
                FOCUS_KEYBOARD_ROW_3.forEach { charStr ->
                    FocusKey(
                        label = charStr,
                        isSelected = charStr.first() in selectedCharacters,
                        onClick = { onKeyPress(charStr.first()) },
                        height = effectiveKeyHeight
                    )
                }
                Spacer(modifier = Modifier.weight(0.5f))
            }

            // Row 4: Z through M (1.5x spacers on both ends, 7 keys weight 1.0f each)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Spacer(modifier = Modifier.weight(1.5f))
                FOCUS_KEYBOARD_ROW_4.forEach { charStr ->
                    FocusKey(
                        label = charStr,
                        isSelected = charStr.first() in selectedCharacters,
                        onClick = { onKeyPress(charStr.first()) },
                        height = effectiveKeyHeight
                    )
                }
                Spacer(modifier = Modifier.weight(1.5f))
            }

            // Row 5: Action row: '?', ',', 2.8x middle spacer, '.', '/' (1.8x width each for ?, ,, ., /)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FocusKey(
                    label = "?",
                    isSelected = '?' in selectedCharacters,
                    onClick = { onKeyPress('?') },
                    weight = 1.8f,
                    height = effectiveKeyHeight
                )
                FocusKey(
                    label = ",",
                    isSelected = ',' in selectedCharacters,
                    onClick = { onKeyPress(',') },
                    weight = 1.8f,
                    height = effectiveKeyHeight
                )
                Spacer(modifier = Modifier.weight(2.8f))
                FocusKey(
                    label = ".",
                    isSelected = '.' in selectedCharacters,
                    onClick = { onKeyPress('.') },
                    weight = 1.8f,
                    height = effectiveKeyHeight
                )
                FocusKey(
                    label = "/",
                    isSelected = '/' in selectedCharacters,
                    onClick = { onKeyPress('/') },
                    weight = 1.8f,
                    height = effectiveKeyHeight
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FocusKey(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Float = 1.0f,
    height: androidx.compose.ui.unit.Dp = 44.dp
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .weight(weight)
            .height(height)
            .semantics { contentDescription = "Focus Key $label" },
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (isSelected) 4.dp else 1.dp,
        shadowElevation = if (isSelected) 2.dp else 0.dp,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Dedicated Material 3 Pre-Drill Setup Screen for Receive Mode.
 * Dynamically filters its displayed configuration options and training objectives
 * based on the selected training mode (Koch vs. Prosigns vs. Focus).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainSetupScreen(
    uiState: TrainUiState,
    profileName: String,
    onNavigateBack: () -> Unit,
    onSelectTrainingMode: (TrainingMode) -> Unit,
    onSelectDrillLength: (Int) -> Unit,
    onSelectWpm: (Int) -> Unit,
    onToggleHideBottomBar: (Boolean) -> Unit,
    onStartDrill: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFocusCharacter: (Char) -> Unit = {},
    onSelectSelectedTrainingMode: (SelectedTrainingMode) -> Unit = {},
    onSelectTimeAttackResponseWindow: (Float) -> Unit = {},
    onSelectTimeAttackInterChallengeDelay: (Long) -> Unit = {},
    onSelectTimeAttackChallengeCount: (Int) -> Unit = {},
    onToggleTimeAttackCharacter: (Char) -> Unit = {},
    onOpenTimeAttackHistory: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val isTimeAttackMode = uiState.selectedTrainingMode == SelectedTrainingMode.TIME_ATTACK || uiState.isTimeAttackMode
    val isFocusMode = !isTimeAttackMode && (uiState.selectedTrainingMode == SelectedTrainingMode.FOCUS || uiState.isFocusMode)
    val isProsignMode = !isTimeAttackMode && !isFocusMode && (uiState.trainingMode == TrainingMode.Prosigns || uiState.selectedTrainingMode == SelectedTrainingMode.Prosign)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Card: Mode & Trainee Profile with Back Button
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Return to mode selection",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isTimeAttackMode -> "TIME ATTACK DRILL SETUP"
                                isFocusMode -> "FOCUS DRILL SETUP"
                                isProsignMode -> "PROSIGN DRILL SETUP"
                                else -> "KOCH DRILL SETUP"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = when {
                                isTimeAttackMode -> "${String.format(java.util.Locale.US, "%.1f", uiState.timeAttackResponseWindowSeconds)}s Reaction Window"
                                isFocusMode -> "Custom Focus (${uiState.focusSelectedCharacters.size}/8)"
                                isProsignMode -> "Prosigns Level ${uiState.activeProsignLevel} of 12"
                                else -> "Koch Level ${uiState.activeKochLevel} of ${MorseConstants.MAX_KOCH_LEVEL}"
                            },
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
                            text = when {
                                isTimeAttackMode -> "${uiState.timeAttackSelectedCharacters.size} Chars"
                                isFocusMode -> "${uiState.focusSelectedCharacters.size} Chars"
                                isProsignMode -> "${uiState.availableCharacters.size} Prosigns"
                                else -> "${uiState.availableCharacters.size} Chars"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when {
                        isTimeAttackMode -> "Time Attack challenges users to decode characters within a strict 1.0 to 3.5-second countdown window, recording correct, incorrect, and missed responses for detailed review."
                        isFocusMode -> "Focus mode allows custom selection of 3 to 8 characters for concentrated practice at standard Paris timing (10–25 WPM) with visual dotrep hints."
                        isProsignMode -> "Prosigns training focuses on procedural signals (<AR>, <SK>, <BT>, <AS>, <SOS>) and common radio abbreviations with multi-character entry and continuous tone spacing upon proficiency."
                        else -> "Koch method sequentially introduces individual Morse characters at full target speed (10–25 WPM) with dynamic priority weighting to build acoustic reflex recognition."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Mode switching chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !isProsignMode && !isFocusMode && !isTimeAttackMode,
                        onClick = {
                            onSelectSelectedTrainingMode(SelectedTrainingMode.Koch)
                            onSelectTrainingMode(TrainingMode.Koch)
                        },
                        label = { Text("Koch", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isProsignMode,
                        onClick = {
                            onSelectSelectedTrainingMode(SelectedTrainingMode.Prosign)
                            onSelectTrainingMode(TrainingMode.Prosigns)
                        },
                        label = { Text("Prosigns", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isFocusMode,
                        onClick = {
                            onSelectSelectedTrainingMode(SelectedTrainingMode.FOCUS)
                        },
                        label = { Text("Focus", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isTimeAttackMode,
                        onClick = {
                            onSelectSelectedTrainingMode(SelectedTrainingMode.TIME_ATTACK)
                        },
                        label = { Text("Time Attack", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Active sequence chips - dynamically labeled based on active mode
                Text(
                    text = when {
                        isTimeAttackMode -> "Time Attack Character Pool (${uiState.timeAttackSelectedCharacters.size}):"
                        isFocusMode -> "Selected Characters (${uiState.focusSelectedCharacters.size}/8):"
                        isProsignMode -> "Active Prosigns Pool:"
                        else -> "Active Characters Pool:"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val displayedPool = when {
                        isTimeAttackMode -> uiState.timeAttackSelectedCharacters.sorted().map { it.toString() }
                        isFocusMode -> uiState.focusSelectedCharacters.sorted().map { it.toString() }
                        else -> uiState.availableCharacters
                    }
                    displayedPool.forEach { char ->
                        val cleanChar = char.uppercase().replace("<", "").replace(">", "")
                        val isMastered = char.uppercase() in uiState.masteredCharacters ||
                            cleanChar in uiState.masteredCharacters ||
                            "<$cleanChar>" in uiState.masteredCharacters
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

        if (isTimeAttackMode) {
            // 2. Response Window Slider Card (1.0 to 3.5s)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "RESPONSE WINDOW (1.0 – 3.5s)",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Strict timer window to decode character before marked missed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.1f", uiState.timeAttackResponseWindowSeconds)}s",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Slider(
                        value = uiState.timeAttackResponseWindowSeconds.coerceIn(1.0f, 3.5f),
                        onValueChange = { onSelectTimeAttackResponseWindow(Math.round(it * 10f) / 10f) },
                        valueRange = 1.0f..3.5f,
                        steps = 24,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "1.0s (Fast)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "3.5s (Relaxed)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 3. Inter-Challenge Delay Slider Card (0.2 to 3.0s)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "INTER-CHALLENGE DELAY",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Pause duration between consecutive audio challenges",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.1f", uiState.timeAttackInterChallengeDelayMs / 1000f)}s",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Slider(
                        value = (uiState.timeAttackInterChallengeDelayMs / 1000f).coerceIn(0.2f, 3.0f),
                        onValueChange = { onSelectTimeAttackInterChallengeDelay((it * 1000f).toLong()) },
                        valueRange = 0.2f..3.0f,
                        steps = 13,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "0.2s (Fast pace)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "3.0s (Paced)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Challenge Quantity Card
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
                        text = "CHALLENGE QUANTITY",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Number of rapid-fire challenges in this drill session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val attackOptions = listOf(10 to "Sprint", 20 to "Standard", 30 to "Extended", 50 to "Endurance")
                        attackOptions.forEach { (count, label) ->
                            val isSelected = uiState.timeAttackChallengeCount == count
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectTimeAttackChallengeCount(count) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(text = "$count", fontWeight = FontWeight.Bold)
                                        Text(text = label, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 5. Playback Speed (WPM) Card
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                text = "Standard Paris timing speed (10 to 25 WPM)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${uiState.currentWpm} WPM",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Slider(
                        value = uiState.currentWpm.toFloat().coerceIn(10f, 25f),
                        onValueChange = { onSelectWpm(it.roundToInt().coerceIn(10, 25)) },
                        valueRange = 10f..25f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "10 WPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "25 WPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 6. Character Selection Virtual Keyboard Card
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CHALLENGE CHARACTERS",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Select characters to include in the Time Attack challenge pool",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (uiState.timeAttackSelectedCharacters.isNotEmpty()) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Text(
                                text = "${uiState.timeAttackSelectedCharacters.size} Selected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.timeAttackSelectedCharacters.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (uiState.timeAttackSelectedCharacters.isEmpty()) {
                        Text(
                            text = "⚠️ Select at least 1 character to practice.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    FocusSelectionKeyboard(
                        selectedCharacters = uiState.timeAttackSelectedCharacters,
                        onKeyPress = onToggleTimeAttackCharacter
                    )
                }
            }

            // 7. Time Attack Objectives Card
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
                        text = "Time Attack Objectives",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Audio tones play at standard Paris timing (${uiState.currentWpm} WPM).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Decode target within the strict ${String.format(java.util.Locale.US, "%.1f", uiState.timeAttackResponseWindowSeconds)}s reaction window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• If the countdown timer expires before input, the attempt is marked MISSED.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• A pause of ${String.format(java.util.Locale.US, "%.1f", uiState.timeAttackInterChallengeDelayMs / 1000f)}s is applied between consecutive challenges.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• A comprehensive post-drill report tracks correct, incorrect, and missed responses.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 8. Time Attack Drill History Card
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
                            text = "PAST DRILL REPORTS",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (uiState.timeAttackHistory.isNotEmpty()) {
                                "${uiState.timeAttackHistory.size} historical drill report${if (uiState.timeAttackHistory.size > 1) "s" else ""} available"
                            } else {
                                "View past Time Attack drill session history and challenge breakdown"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = onOpenTimeAttackHistory,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("View")
                    }
                }
            }
        } else if (isFocusMode) {
            // 2. Playback Speed (WPM) Slider Card for Focus Mode
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                text = "Standard Paris timing speed (10 to 25 WPM)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${uiState.currentWpm} WPM",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Slider(
                        value = uiState.currentWpm.toFloat().coerceIn(10f, 25f),
                        onValueChange = { onSelectWpm(it.roundToInt().coerceIn(10, 25)) },
                        valueRange = 10f..25f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "10 WPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "25 WPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 3. Challenge Count Slider Card for Focus Mode
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CHALLENGE COUNT",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Select number of challenges in this practice session",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${uiState.selectedDrillLength}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Slider(
                        value = uiState.selectedDrillLength.toFloat().coerceIn(10f, 100f),
                        onValueChange = { onSelectDrillLength(((it / 5).roundToInt() * 5).coerceIn(10, 100)) },
                        valueRange = 10f..100f,
                        steps = 17,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "10 Challenges",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "100 Challenges",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. 5-Row Proportional Keyboard Selection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "FOCUS CHARACTERS",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Select 3 to 8 characters via the virtual keyboard",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (uiState.focusSelectedCharacters.size in 3..8) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Text(
                                text = "${uiState.focusSelectedCharacters.size} / 8",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.focusSelectedCharacters.size in 3..8) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (uiState.focusSelectedCharacters.size < 3) {
                        Text(
                            text = "⚠️ Select at least 3 characters to enable starting the drill.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else if (uiState.focusSelectedCharacters.size == 8) {
                        Text(
                            text = "Maximum of 8 characters selected.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    FocusSelectionKeyboard(
                        selectedCharacters = uiState.focusSelectedCharacters,
                        onKeyPress = onToggleFocusCharacter
                    )
                }
            }

            // 5. Focus Objectives Card
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
                        text = "Focus Training Objectives",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Practice targeted subset of 3 to 8 characters (${uiState.focusSelectedCharacters.sorted().joinToString(", ")}).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Audio tones play at standard Paris timing (${uiState.currentWpm} WPM).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Key in each character using the proportional 5-row virtual keyboard.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Dynamic visual Morse dot aids display until 6 successful identifications are achieved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
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

            // 4. Drill Objectives & Information Card (dynamically filtered by mode)
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
                        text = if (isProsignMode) "Prosigns Training Objectives" else "Koch Training Objectives",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isProsignMode) {
                        Text(
                            text = "• Listen to synthesized Morse audio tones with continuous prosign keying once proficient.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Enter full procedural signals (<AR>, <SK>, <BT>, <AS>, <SOS>) using text input or keyboard.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Dynamic priority increases presentation frequency of newly unlocked or struggling prosigns.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Achieve ≥ 70% accuracy across ≥ 5 attempts on the latest prosign to advance to the next level.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "• Listen to synthesized Morse audio tones at standard Paris timing (10–25 WPM).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Legacy v1.7 dynamic priority will increase frequency of newly unlocked or struggling characters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• Key in each character using the proportional 5-row virtual keyboard.",
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
            }
        }

        // 5. Hide Bottom Bar Setting
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hide Bottom Navigation Bar",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Hides the bar during active drills to maximize keyboard space.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = uiState.hideBottomBarPreference,
                onCheckedChange = onToggleHideBottomBar
            )
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))

        // 6. Prominent "Start Drill" Action Button
        Button(
            onClick = onStartDrill,
            enabled = when {
                isTimeAttackMode -> uiState.timeAttackSelectedCharacters.isNotEmpty()
                isFocusMode -> uiState.focusSelectedCharacters.size in 3..8
                else -> true
            },
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
                    imageVector = when {
                        isTimeAttackMode -> Icons.Default.Timer
                        isFocusMode -> Icons.Default.Tune
                        isProsignMode -> Icons.Default.Radio
                        else -> Icons.Default.School
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        isTimeAttackMode -> "Start Time Attack (${uiState.timeAttackChallengeCount} Challenges)"
                        isFocusMode -> "Start Focus Drill (${uiState.selectedDrillLength} Challenges)"
                        isProsignMode -> "Start Prosign Drill (${uiState.selectedDrillLength} Challenges)"
                        else -> "Start Receive Drill (${uiState.selectedDrillLength} Challenges)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Backward-compatible wrapper delegating to [TrainSetupScreen].
 */
@Composable
private fun TrainPreDrillSetupContent(
    uiState: TrainUiState,
    profileName: String,
    onSelectTrainingMode: (TrainingMode) -> Unit,
    onSelectDrillLength: (Int) -> Unit,
    onSelectWpm: (Int) -> Unit,
    onToggleHideBottomBar: (Boolean) -> Unit,
    onStartDrill: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    TrainSetupScreen(
        uiState = uiState,
        profileName = profileName,
        onNavigateBack = onNavigateBack,
        onSelectTrainingMode = onSelectTrainingMode,
        onSelectDrillLength = onSelectDrillLength,
        onSelectWpm = onSelectWpm,
        onToggleHideBottomBar = onToggleHideBottomBar,
        onStartDrill = onStartDrill,
        modifier = modifier
    )
}

@Composable
private fun TimeAttackChallengeResultRow(
    index: Int,
    result: TimeAttackChallengeResult,
    modifier: Modifier = Modifier
) {
    val statusBg: Color
    val statusFg: Color
    val statusIcon: androidx.compose.ui.graphics.vector.ImageVector
    val statusLabel: String

    when (result.outcome) {
        TimeAttackOutcome.CORRECT -> {
            statusBg = Color(0xFFE8F5E9)
            statusFg = Color(0xFF1B5E20)
            statusIcon = Icons.Default.CheckCircle
            statusLabel = "CORRECT"
        }
        TimeAttackOutcome.INCORRECT -> {
            statusBg = Color(0xFFFFEBEE)
            statusFg = Color(0xFFB71C1C)
            statusIcon = Icons.Default.Cancel
            statusLabel = if (!result.guess.isNullOrBlank()) "GUESS: ${result.guess}" else "INCORRECT"
        }
        TimeAttackOutcome.MISSED -> {
            statusBg = Color(0xFFFFF8E1)
            statusFg = Color(0xFFB45309)
            statusIcon = Icons.Default.Timer
            statusLabel = "MISSED (Timed out)"
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Index & Target Character with Morse dot representation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "#$index",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = result.target,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                val morse = MorseConstants.MORSE_MAP[result.target.uppercase()] ?: ""
                if (morse.isNotEmpty()) {
                    Text(
                        text = morse,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right: Outcome Chip and Response Time in ms
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusFg,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusFg
                        )
                    }
                }

                Text(
                    text = if (result.outcome == TimeAttackOutcome.MISSED) "--" else "${result.responseTimeMs} ms",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Material 3 Completion Summary View for Time Attack Mode.
 * Displays comprehensive session metrics and scrollable challenge audit log.
 */
@Composable
private fun TimeAttackSummaryView(
    uiState: TrainUiState,
    onStartNewDrill: () -> Unit,
    onReturnToSetup: () -> Unit,
    onReturnToModeSelection: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTimeAttackHistory: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val report = uiState.latestTimeAttackReport
    val results: List<TimeAttackChallengeResult> = report?.toChallengeResults() ?: uiState.timeAttackChallengeResults
    val totalCount = report?.totalChallenges ?: if (results.isNotEmpty()) results.size else uiState.sessionBatchSize
    val correctCount = report?.correctCount ?: results.count { it.outcome == TimeAttackOutcome.CORRECT }
    val incorrectCount = report?.incorrectCount ?: results.count { it.outcome == TimeAttackOutcome.INCORRECT }
    val missedCount = report?.missedCount ?: results.count { it.outcome == TimeAttackOutcome.MISSED }
    val accuracyPct = if (totalCount > 0) Math.round((correctCount.toFloat() / totalCount) * 100f).toInt() else 0
    val windowSec = report?.responseWindowSeconds ?: uiState.timeAttackResponseWindowSeconds
    val delayMs = report?.interChallengeDelayMs ?: uiState.timeAttackInterChallengeDelayMs
    val avgResponseMs = if (results.isNotEmpty()) {
        val answered = results.filter { it.outcome != TimeAttackOutcome.MISSED }
        if (answered.isNotEmpty()) Math.round(answered.map { it.responseTimeMs }.average()) else 0L
    } else 0L

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Completion Header Trophy Card
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
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "TIME ATTACK COMPLETE!",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Completed $totalCount challenges with ${String.format(java.util.Locale.US, "%.1f", windowSec)}s reaction window",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
            }
        }

        // 2. Performance Breakdown Card (Accuracy, Correct, Incorrect, Missed)
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
                        value = "$accuracyPct%",
                        valueColor = when {
                            accuracyPct >= 80 -> Color(0xFF10B981)
                            accuracyPct >= 60 -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    ScoreMetricTile(
                        title = "Correct",
                        value = "$correctCount",
                        valueColor = Color(0xFF10B981)
                    )
                    ScoreMetricTile(
                        title = "Incorrect",
                        value = "$incorrectCount",
                        valueColor = MaterialTheme.colorScheme.error
                    )
                    ScoreMetricTile(
                        title = "Missed",
                        value = "$missedCount",
                        valueColor = Color(0xFFF59E0B)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Drill Parameters and Reaction Speed Summary
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Avg Reaction Time",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (avgResponseMs > 0) "$avgResponseMs ms" else "N/A",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Inter-Challenge Delay",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$delayMs ms",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Practiced Characters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val charStr = if (!report?.includedCharacters.isNullOrEmpty()) {
                                report?.includedCharacters ?: ""
                            } else {
                                uiState.timeAttackSelectedCharacters.sorted().joinToString("")
                            }
                            Text(
                                text = charStr,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // 3. Comprehensive Challenge Audit Log
        if (results.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Challenge Audit Log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${results.size} challenges",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        results.forEachIndexed { idx, item ->
                            TimeAttackChallengeResultRow(
                                index = idx + 1,
                                result = item
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

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
                text = "Start Another Time Attack (${uiState.timeAttackChallengeCount})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = onOpenTimeAttackHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.History, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Past Drill Reports (${uiState.timeAttackHistory.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
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
                text = "Change Time Attack Setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        TextButton(
            onClick = onReturnToModeSelection,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Choose Another Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
    modifier: Modifier = Modifier,
    onReturnToModeSelection: () -> Unit = {},
    onOpenTimeAttackHistory: () -> Unit = {}
) {
    if (uiState.isTimeAttackMode || uiState.latestTimeAttackReport != null) {
        TimeAttackSummaryView(
            uiState = uiState,
            onStartNewDrill = onStartNewDrill,
            onReturnToSetup = onReturnToSetup,
            onReturnToModeSelection = onReturnToModeSelection,
            onOpenTimeAttackHistory = onOpenTimeAttackHistory,
            modifier = modifier
        )
        return
    }

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
                        title = if (uiState.trainingMode == TrainingMode.Prosigns) "Prosign Level" else "Koch Level",
                        value = "${uiState.currentLevel}",
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
                                text = if (uiState.trainingMode == TrainingMode.Prosigns) "Career Prosign Accuracy" else "Career Koch Accuracy",
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

        TextButton(
            onClick = onReturnToModeSelection,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Choose Another Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Dedicated Material 3 historical reports screen for Time Attack drills.
 * Retrieves and lists past drill session records with expandable challenge breakdowns.
 */
@Composable
fun TimeAttackHistoryScreen(
    uiState: TrainUiState,
    profileName: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val history = uiState.timeAttackHistory
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Card with Back Navigation
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Return from history",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TIME ATTACK HISTORY",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Past Drill Reports",
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
                            text = "${history.size} Drills",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Historical performance records from Time Attack drills, tracking reaction response windows, overall accuracy, and challenge breakdowns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        // 2. Aggregate Summary Performance Card
        if (history.isNotEmpty()) {
            val totalChallenges = history.sumOf { it.totalChallenges }
            val totalCorrect = history.sumOf { it.correctCount }
            val totalIncorrect = history.sumOf { it.incorrectCount }
            val totalMissed = history.sumOf { it.missedCount }
            val overallAccuracy = if (totalChallenges > 0) {
                Math.round((totalCorrect.toFloat() / totalChallenges) * 100f).toInt()
            } else 0

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Overall Time Attack Performance",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ScoreMetricTile(
                            title = "Accuracy",
                            value = "$overallAccuracy%",
                            valueColor = when {
                                overallAccuracy >= 80 -> Color(0xFF10B981)
                                overallAccuracy >= 60 -> Color(0xFFF59E0B)
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                        ScoreMetricTile(
                            title = "Correct",
                            value = "$totalCorrect",
                            valueColor = Color(0xFF10B981)
                        )
                        ScoreMetricTile(
                            title = "Incorrect",
                            value = "$totalIncorrect",
                            valueColor = MaterialTheme.colorScheme.error
                        )
                        ScoreMetricTile(
                            title = "Missed",
                            value = "$totalMissed",
                            valueColor = Color(0xFFF59E0B)
                        )
                    }
                }
            }
        }

        // 3. Drill Reports List or Empty State
        if (history.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "No Past Drill Reports",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Complete a Time Attack session to record reaction response times and challenge performance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(history, key = { it.id }) { report ->
                    TimeAttackHistoryReportCard(
                        report = report,
                        dateFormat = dateFormat
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeAttackHistoryReportCard(
    report: TimeAttackReport,
    dateFormat: DateFormat,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val challengeResults = remember(report.detailsEncoded) { report.toChallengeResults() }
    val accuracy = report.accuracyPercentage.roundToInt()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Date/Time and Accuracy Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateFormat.format(Date(report.timestamp)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${String.format(java.util.Locale.US, "%.1f", report.responseWindowSeconds)}s window • ${report.totalChallenges} challenges",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        accuracy >= 80 -> Color(0xFFE8F5E9)
                        accuracy >= 60 -> Color(0xFFFFF8E1)
                        else -> Color(0xFFFFEBEE)
                    }
                ) {
                    Text(
                        text = "$accuracy%",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = when {
                            accuracy >= 80 -> Color(0xFF1B5E20)
                            accuracy >= 60 -> Color(0xFFB45309)
                            else -> Color(0xFFB71C1C)
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Score Chips Row (Correct, Incorrect, Missed)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE8F5E9),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Correct: ${report.correctCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFEBEE),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Incorrect: ${report.incorrectCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB71C1C)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFF8E1),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Missed: ${report.missedCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB45309)
                        )
                    }
                }
            }

            if (report.includedCharacters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pool: ${report.includedCharacters}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expand/Collapse Details Toggle
            if (challengeResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isExpanded) "Hide Details" else "View Details (${challengeResults.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse details" else "Expand details",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        challengeResults.forEachIndexed { idx, item ->
                            TimeAttackChallengeResultRow(
                                index = idx + 1,
                                result = item
                            )
                        }
                    }
                }
            }
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
    onUpdateProsignTextInput: (String) -> Unit,
    onSubmitProsignTextInput: () -> Unit,
    onQuitDrill: () -> Unit,
    onDismissLevelUpMessage: () -> Unit,
    onStartLesson: () -> Unit,
    modifier: Modifier = Modifier,
    onResetSession: () -> Unit = onQuitDrill,
    onBackspace: () -> Unit = {},
    onSetWpm: (Int) -> Unit = {},
    onToggleProgressionPause: () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp
    val dynamicKeyHeight = when {
        screenHeightDp < 600 -> 30.dp
        screenHeightDp < 700 -> 34.dp
        screenHeightDp < 800 -> 38.dp
        else -> 46.dp
    }
    val upperScrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Upper Information Section (compact, scrollable if height is constrained)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(upperScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
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
                correctCount = if (uiState.isTimeAttackMode) uiState.timeAttackCorrectCount else uiState.sessionCorrectAttempts,
                totalCount = if (uiState.isTimeAttackMode) (uiState.timeAttackCorrectCount + uiState.timeAttackIncorrectCount + uiState.timeAttackMissedCount) else uiState.sessionTotalAttempts,
                minAttempts = uiState.sessionBatchSize,
                onQuitDrill = onQuitDrill,
                onToggleProgressionPause = onToggleProgressionPause,
                isProgressionPaused = uiState.activeProfile?.isProgressionPaused == true,
                challengeIndex = uiState.currentChallengeIndex,
                batchSize = uiState.sessionBatchSize,
                progressFraction = uiState.progressFraction,
                isTimeAttack = uiState.isTimeAttackMode,
                timeAttackMissedCount = uiState.timeAttackMissedCount,
                timeAttackResponseWindowSeconds = uiState.timeAttackResponseWindowSeconds
            )

            // 3. Time Attack Reaction Timer Bar (Visible during Time Attack mode)
            if (uiState.isTimeAttackMode) {
                TimeAttackTimerBanner(
                    remainingSeconds = uiState.timeAttackRemainingSeconds,
                    totalSeconds = uiState.timeAttackResponseWindowSeconds,
                    fraction = uiState.timeAttackTimerFraction,
                    isAwaitingInput = uiState.isTimeAttackAwaitingInput,
                    isPlayingAudio = uiState.isPlayingAudio
                )
            }

            // 4. Last Guess Outcome Feedback Banner (Horizontal compact layout)
            if (uiState.lastGuessWasCorrect != null && uiState.feedbackMessage != null) {
                GuessFeedbackBanner(
                    isCorrect = uiState.lastGuessWasCorrect,
                    message = uiState.feedbackMessage
                )
            }

            // 5. Active Character Prompt & WPM Speed Selector
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
                        text = if (uiState.isTimeAttackMode) {
                            "Time Attack: ${uiState.timeAttackSelectedCharacters.size} characters (${String.format(java.util.Locale.US, "%.1f", uiState.timeAttackResponseWindowSeconds)}s window)"
                        } else if (uiState.trainingMode == TrainingMode.Prosigns) {
                            "Active characters for Prosigns Level ${uiState.activeProsignLevel}" + if (uiState.activeProfile?.isProgressionPaused == true) " (Paused)" else ""
                        } else if (uiState.isFocusMode) {
                            "Focus practice with ${uiState.focusSelectedCharacters.size} characters"
                        } else {
                            "Active characters for Koch Level ${uiState.activeKochLevel}" + if (uiState.activeProfile?.isProgressionPaused == true) " (Paused)" else ""
                        },
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
                visible = !uiState.isTimeAttackMode && uiState.isVisualAidActive && uiState.showNewCharacterVisualAid && !visualAidText.isNullOrEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = visualAidText ?: "",
                    style = when {
                        (visualAidText?.length ?: 0) > 12 -> MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        (visualAidText?.length ?: 0) > 6 -> MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        else -> MaterialTheme.typography.displayMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }

                if (uiState.trainingMode == TrainingMode.Prosigns) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = uiState.prosignTextInput,
                    onValueChange = { }, // Handled by KochKeyboard
                    label = { Text("Enter Sequence") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = uiState.hasTarget && (uiState.drillState == DrillState.DrillActive || uiState.drillState == DrillState.ShowingResult),
                    readOnly = true // Prevents system keyboard from appearing
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSubmitProsignTextInput,
                        enabled = uiState.hasTarget && uiState.drillState == DrillState.DrillActive && uiState.prosignTextInput.isNotBlank(),
                        
                    ) {
                        Text("Submit")
                    }
                }
            }
        }
        } // End of 60% container

        // Custom In-App Koch Keyboard anchored to bottom of viewable area
        Crossfade(
            targetState = uiState.targetCharacter,
            animationSpec = tween(durationMillis = 250),
            label = "challengeTransition",
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(bottom = 2.dp)
        ) { _ ->
            KochKeyboard(
                currentKochLevel = if (uiState.trainingMode == TrainingMode.Prosigns) 39 else uiState.activeKochLevel,
                availableCharacters = if (uiState.trainingMode == TrainingMode.Prosigns) null else uiState.availableCharacters?.filter { char ->
                    val clean = char.replace("<", "").replace(">", "").uppercase()
                    clean !in listOf("BT", "SK", "AR") && char != "="
                },
                onCharacterClick = { char -> 
                    if (uiState.trainingMode == TrainingMode.Prosigns) {
                        if (uiState.drillState == DrillState.DrillActive) {
                            onUpdateProsignTextInput(uiState.prosignTextInput + char.replace("<", "").replace(">", ""))
                        }
                    } else {
                        onGuess(char)
                    }
                },
                onBackspaceClick = {
                    if (uiState.trainingMode == TrainingMode.Prosigns) {
                        if (uiState.drillState == DrillState.DrillActive && uiState.prosignTextInput.isNotEmpty()) {
                            onUpdateProsignTextInput(uiState.prosignTextInput.dropLast(1))
                        }
                    } else {
                        onBackspace()
                    }
                },
                onRepeatClick = onPlayTone,
                enabled = uiState.hasTarget && (if (uiState.isTimeAttackMode) uiState.isTimeAttackAwaitingInput else uiState.drillState == DrillState.DrillActive),
                lastGuessedCharacter = if (uiState.trainingMode == TrainingMode.Prosigns) null else uiState.lastGuessedCharacter,
                lastGuessWasCorrect = if (uiState.trainingMode == TrainingMode.Prosigns) null else uiState.lastGuessWasCorrect,
                keyHeight = dynamicKeyHeight,
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
                        text = "Koch Level $activeLevel of ${MorseConstants.MAX_KOCH_LEVEL}",
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

            // Level progression bar (Level 1..39)
            LinearProgressIndicator(
                progress = { activeLevel / MorseConstants.MAX_KOCH_LEVEL.toFloat() },
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
private fun TimeAttackTimerBanner(
    remainingSeconds: Float,
    totalSeconds: Float,
    fraction: Float,
    isAwaitingInput: Boolean,
    isPlayingAudio: Boolean,
    modifier: Modifier = Modifier
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
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when {
                            isPlayingAudio -> MaterialTheme.colorScheme.primary
                            isAwaitingInput && fraction > 0.4f -> Color(0xFF10B981)
                            isAwaitingInput && fraction > 0.2f -> Color(0xFFF59E0B)
                            isAwaitingInput -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isPlayingAudio -> "Playing Audio..."
                            isAwaitingInput -> "Reaction Countdown"
                            else -> "Response Window"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isPlayingAudio -> MaterialTheme.colorScheme.primaryContainer
                        isAwaitingInput && fraction > 0.4f -> Color(0xFFE8F5E9)
                        isAwaitingInput && fraction > 0.2f -> Color(0xFFFFF8E1)
                        isAwaitingInput -> Color(0xFFFFEBEE)
                        else -> MaterialTheme.colorScheme.surface
                    }
                ) {
                    Text(
                        text = when {
                            isPlayingAudio -> "Listen..."
                            isAwaitingInput -> "${String.format(java.util.Locale.US, "%.2f", remainingSeconds)}s left"
                            else -> "${String.format(java.util.Locale.US, "%.1f", totalSeconds)}s window"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isPlayingAudio -> MaterialTheme.colorScheme.onPrimaryContainer
                            isAwaitingInput && fraction > 0.4f -> Color(0xFF1B5E20)
                            isAwaitingInput && fraction > 0.2f -> Color(0xFFB45309)
                            isAwaitingInput -> Color(0xFFB71C1C)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { if (isPlayingAudio) 1f else fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = when {
                    isPlayingAudio -> MaterialTheme.colorScheme.primary
                    fraction > 0.4f -> Color(0xFF10B981)
                    fraction > 0.2f -> Color(0xFFF59E0B)
                    else -> MaterialTheme.colorScheme.error
                },
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
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
    onToggleProgressionPause: () -> Unit = {},
    isProgressionPaused: Boolean = false,
    challengeIndex: Int = 0,
    batchSize: Int = 0,
    progressFraction: Float = 0f,
    isTimeAttack: Boolean = false,
    timeAttackMissedCount: Int = 0,
    timeAttackResponseWindowSeconds: Float = 1.5f
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
                        text = if (isTimeAttack) "Time Attack Score" else "Session Score",
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
                    title = if (isTimeAttack) "Missed" else "Target",
                    value = if (isTimeAttack) "$timeAttackMissedCount" else "≥ 90%",
                    valueColor = if (isTimeAttack) Color(0xFFF59E0B) else Color(0xFF10B981),
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
            val progressText = if (isTimeAttack) {
                "Time Attack: ${String.format(java.util.Locale.US, "%.1f", timeAttackResponseWindowSeconds)}s window • $totalCount of $batchSize complete"
            } else if (isProgressionPaused) {
                "Progression paused (level locked) • Accuracy stats are being recorded"
            } else {
                val attemptsRemaining = (minAttempts - totalCount).coerceAtLeast(0)
                if (attemptsRemaining > 0) {
                    "$attemptsRemaining more attempt${if (attemptsRemaining > 1) "s" else ""} needed to evaluate promotion"
                } else if (accuracy >= 90.0f) {
                    "Promotion threshold achieved! Level advances on next evaluation"
                } else {
                    "Reach ≥ 90% accuracy over $minAttempts attempts to advance"
                }
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

            if (!isTimeAttack) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onToggleProgressionPause,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isProgressionPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isProgressionPaused) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = 1.5.dp,
                            color = if (isProgressionPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .semantics {
                                contentDescription = if (isProgressionPaused) "Progression: Paused" else "Progression: Active"
                            }
                    ) {
                        Icon(
                            imageVector = if (isProgressionPaused) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (isProgressionPaused) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isProgressionPaused) "Progression: Paused" else "Progression: Active",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
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
