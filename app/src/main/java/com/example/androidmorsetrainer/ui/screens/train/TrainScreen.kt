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
import androidx.compose.material.icons.filled.Tune
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
                        modifier = modifier
                    )
                }
            }
            DrillState.Finished -> {
                TrainDrillSummaryContent(
                    uiState = uiState,
                    onStartNewDrill = {
                        actualDspManager?.stopListening()
                        viewModel.startLesson(uiState.selectedDrillLength)
                    },
                    onReturnToSetup = viewModel::returnToSetup,
                    onReturnToModeSelection = {
                        actualDspManager?.stopListening()
                        viewModel.returnToModeSelection()
                    },
                    onDismissLevelUpMessage = viewModel::dismissLevelUpMessage,
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        height = keyHeight
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
                        height = keyHeight
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
                        height = keyHeight
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
                        height = keyHeight
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
                    height = keyHeight
                )
                FocusKey(
                    label = ",",
                    isSelected = ',' in selectedCharacters,
                    onClick = { onKeyPress(',') },
                    weight = 1.8f,
                    height = keyHeight
                )
                Spacer(modifier = Modifier.weight(2.8f))
                FocusKey(
                    label = ".",
                    isSelected = '.' in selectedCharacters,
                    onClick = { onKeyPress('.') },
                    weight = 1.8f,
                    height = keyHeight
                )
                FocusKey(
                    label = "/",
                    isSelected = '/' in selectedCharacters,
                    onClick = { onKeyPress('/') },
                    weight = 1.8f,
                    height = keyHeight
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
    onSelectSelectedTrainingMode: (SelectedTrainingMode) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val isFocusMode = uiState.selectedTrainingMode == SelectedTrainingMode.FOCUS || uiState.isFocusMode
    val isProsignMode = !isFocusMode && (uiState.trainingMode == TrainingMode.Prosigns || uiState.selectedTrainingMode == SelectedTrainingMode.Prosign)

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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isProsignMode && !isFocusMode,
                        onClick = {
                            onSelectSelectedTrainingMode(SelectedTrainingMode.Koch)
                            onSelectTrainingMode(TrainingMode.Koch)
                        },
                        label = { Text("Koch Method") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isProsignMode,
                        onClick = {
                            onSelectSelectedTrainingMode(SelectedTrainingMode.Prosign)
                            onSelectTrainingMode(TrainingMode.Prosigns)
                        },
                        label = { Text("Prosigns") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isFocusMode,
                        onClick = {
                            onSelectSelectedTrainingMode(SelectedTrainingMode.FOCUS)
                        },
                        label = { Text("Focus Mode") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Active sequence chips - dynamically labeled based on active mode
                Text(
                    text = when {
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
                    val displayedPool = if (isFocusMode) {
                        uiState.focusSelectedCharacters.sorted().map { it.toString() }
                    } else {
                        uiState.availableCharacters
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

        if (isFocusMode) {
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
            enabled = if (isFocusMode) uiState.focusSelectedCharacters.size in 3..8 else true,
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
    onReturnToModeSelection: () -> Unit = {}
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
    onSetWpm: (Int) -> Unit = {}
) {
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
                .weight(0.6f),
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
                        text = if (uiState.trainingMode == TrainingMode.Prosigns) {
                            "Active characters for Prosigns Level ${uiState.activeProsignLevel}"
                        } else if (uiState.isFocusMode) {
                            "Focus practice with ${uiState.focusSelectedCharacters.size} characters"
                        } else {
                            "Active characters for Koch Level ${uiState.activeKochLevel}"
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
                visible = uiState.isVisualAidActive && uiState.showNewCharacterVisualAid && !visualAidText.isNullOrEmpty(),
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
                .weight(0.4f)
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
                enabled = uiState.hasTarget && uiState.drillState == DrillState.DrillActive,
                lastGuessedCharacter = if (uiState.trainingMode == TrainingMode.Prosigns) null else uiState.lastGuessedCharacter,
                lastGuessWasCorrect = if (uiState.trainingMode == TrainingMode.Prosigns) null else uiState.lastGuessWasCorrect,
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
