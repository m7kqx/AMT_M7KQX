package com.example.androidmorsetrainer.ui.screens.send

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidmorsetrainer.data.local.entity.UserProfile
import kotlin.math.max

/**
 * Phase 7.5: Hardware Keying Practice Screen ("Send" Mode).
 * Supports Pre-Drill Setup & Sidetone Calibration, finite drill batches,
 * and post-drill summary results.
 */
@Composable
fun SendScreen(
    activeProfile: UserProfile?,
    modifier: Modifier = Modifier,
    viewModel: SendViewModel = viewModel(factory = SendViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentView = LocalView.current

    // Active Screen Wake-Lock while Send mode is active
    DisposableEffect(Unit) {
        currentView.keepScreenOn = true
        onDispose {
            currentView.keepScreenOn = false
            viewModel.stopListening()
        }
    }

    // Sync active profile from parent Navigation
    LaunchedEffect(activeProfile?.id, activeProfile?.currentKochLevel) {
        viewModel.setActiveProfile(activeProfile)
    }

    // Microphone permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
    }

    if (activeProfile == null) {
        SendNoProfileScreen(modifier = modifier)
    } else {
        when {
            !uiState.isSessionActive && !uiState.isSessionFinished -> {
                SendPreDrillSetupContent(
                    uiState = uiState,
                    profileName = activeProfile.name,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onSelectDrillLength = viewModel::setDrillLength,
                    onSetFrequency = viewModel::setTargetFrequency,
                    onSetCustomFrequencyMode = viewModel::setCustomFrequencyMode,
                    onSetCustomFrequencyString = viewModel::setCustomFrequencyString,
                    onSetSquelch = viewModel::setSquelchLevel,
                    onToggleListening = viewModel::toggleListening,
                    onStartDrill = { viewModel.startLesson(uiState.selectedDrillLength) },
                    modifier = modifier
                )
            }
            uiState.isSessionFinished -> {
                SendDrillSummaryContent(
                    uiState = uiState,
                    onStartNewDrill = { viewModel.startLesson(uiState.selectedDrillLength) },
                    onReturnToSetup = viewModel::returnToSetup,
                    modifier = modifier
                )
            }
            else -> {
                SendScreenContent(
                    uiState = uiState,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onToggleListening = viewModel::toggleListening,
                    onSetFrequency = viewModel::setTargetFrequency,
                    onSetCustomFrequencyMode = viewModel::setCustomFrequencyMode,
                    onSetCustomFrequencyString = viewModel::setCustomFrequencyString,
                    onSetSquelch = viewModel::setSquelchLevel,
                    onClearDecodedText = viewModel::clearDecodedText,
                    onSkipChallenge = viewModel::skipChallenge,
                    onReturnToSetup = viewModel::returnToSetup,
                    onDismissMessage = viewModel::clearUserMessage,
                    modifier = modifier
                )
            }
        }
    }
}

/**
 * Dedicated Material 3 Pre-Drill Setup & Sidetone Calibration Screen for Send Mode.
 * Enables adjusting squelch against ambient noise using the Goertzel Red/Green dot
 * and choosing drill length before starting the practice session.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SendPreDrillSetupContent(
    uiState: SendUiState,
    profileName: String,
    onRequestPermission: () -> Unit,
    onSelectDrillLength: (Int) -> Unit,
    onSetFrequency: (Double) -> Unit,
    onSetCustomFrequencyMode: (Boolean) -> Unit,
    onSetCustomFrequencyString: (String) -> Unit,
    onSetSquelch: (Float) -> Unit,
    onToggleListening: () -> Unit,
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
        // 1. Header Card: Mode & Profile
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
                            text = "KEYING DRILL SETUP & CALIBRATION",
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

        // 2. Pre-Drill Sidetone Calibration Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "PRE-DRILL SIDETONE CALIBRATION",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Key your physical radio now to calibrate squelch against room noise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!uiState.hasRecordPermission) {
                    SendPermissionRequiredCard(onRequestPermission = onRequestPermission)
                }

                // Live Red/Green Goertzel Tone Dot and Frequency Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Fixed-size Red/Green Goertzel dot
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        uiState.isTonePresent -> Color(0xFF10B981)
                                        uiState.isListening -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.outlineVariant
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = when {
                                    uiState.isTonePresent -> "SIDETONE DETECTED"
                                    uiState.isListening -> "LISTENING TO MIC"
                                    else -> "RECEIVER OFF"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.isTonePresent) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pitch: ${uiState.targetFrequencyHz.toInt()} Hz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Pitch Selector Dropdown
                    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    val options = listOf(550.0, 600.0, 650.0, 700.0)

                    Column(horizontalAlignment = Alignment.End) {
                        androidx.compose.material3.ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.width(130.dp)
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = if (uiState.isCustomFrequency) "Custom" else "${uiState.targetFrequencyHz.toInt()} Hz",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor(),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                options.forEach { freq ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("${freq.toInt()} Hz") },
                                        onClick = {
                                            onSetFrequency(freq)
                                            expanded = false
                                        }
                                    )
                                }
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Custom") },
                                    onClick = {
                                        onSetCustomFrequencyMode(true)
                                        expanded = false
                                    }
                                )
                            }
                        }
                        
                        if (uiState.isCustomFrequency) {
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = uiState.customFrequencyString,
                                onValueChange = onSetCustomFrequencyString,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier.width(130.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                singleLine = true,
                                suffix = { Text("Hz", style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }

                // Real-Time Signal Level Bar
                val signalRatio = (uiState.currentMagnitude / max(0.001, uiState.detectionThreshold * 2.0)).toFloat().coerceIn(0f, 1f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SIG",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { signalRatio },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (uiState.isTonePresent) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = String.format("%.3f", uiState.currentMagnitude),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Embedded Squelch Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Squelch Threshold",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format("%.3f mag (%.0f%%)", uiState.detectionThreshold, uiState.squelchLevel * 100f),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = uiState.squelchLevel,
                        onValueChange = onSetSquelch,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Adjust slider until dot turns RED when silent and GREEN when keying sidetone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                // Toggle Listening
                OutlinedButton(
                    onClick = onToggleListening,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isListening) "Pause Calibration Receiver" else "Resume Calibration Receiver",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 3. Drill Length Selection Card (20, 50, 100)
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
                    text = "Select number of keying challenges in this practice session",
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

        // 4. Drill Objectives Card
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
                    text = "Keying Drill Objectives",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• Key the displayed Koch characters into your microphone using your physical keyer/radio.",
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

        // 5. Prominent "Start Keying Drill" Action Button
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
                    text = "Start Keying Drill (${uiState.selectedDrillLength} Challenges)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Dedicated Material 3 Completion Summary Screen for Send Mode.
 */
@Composable
private fun SendDrillSummaryContent(
    uiState: SendUiState,
    onStartNewDrill: () -> Unit,
    onReturnToSetup: () -> Unit,
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
                    text = "KEYING DRILL COMPLETE!",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Completed ${uiState.sessionBatchSize} keying challenges",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // 2. Celebratory Level-Up Banner (if triggered)
        if (uiState.levelUpMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF10B981).copy(alpha = 0.15f)
                ),
                border = BorderStroke(1.5.dp, Color(0xFF10B981)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = uiState.levelUpMessage,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }
            }
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
                        value = "${uiState.accuracyPercentage}%",
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
                text = "Change Drill Setup / Calibrate",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
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
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Active Keying Training Screen Content.
 */
@Composable
fun SendScreenContent(
    uiState: SendUiState,
    onRequestPermission: () -> Unit,
    onToggleListening: () -> Unit,
    onSetFrequency: (Double) -> Unit,
    onSetCustomFrequencyMode: (Boolean) -> Unit,
    onSetCustomFrequencyString: (String) -> Unit,
    onSetSquelch: (Float) -> Unit,
    onClearDecodedText: () -> Unit,
    onSkipChallenge: () -> Unit,
    onReturnToSetup: () -> Unit,
    onDismissMessage: () -> Unit,
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
        // 1. Permission Warning Banner
        if (!uiState.hasRecordPermission) {
            SendPermissionRequiredCard(onRequestPermission = onRequestPermission)
        }

        // 2. User Notification / Error Banner
        AnimatedVisibility(
            visible = uiState.userMessage != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            uiState.userMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismissMessage) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // 3. Level-Up Promotion Banner
        AnimatedVisibility(
            visible = uiState.levelUpMessage != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            uiState.levelUpMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF10B981).copy(alpha = 0.15f)
                    ),
                    border = BorderStroke(1.5.dp, Color(0xFF10B981)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }
        }

        // 4. Active Koch Character Challenge Card (with Session Progress Bar)
        SendChallengeCard(
            targetCharacter = uiState.targetCharacter,
            targetMorsePattern = uiState.targetMorsePattern,
            activeKochLevel = uiState.activeKochLevel,
            currentChallengeIndex = uiState.currentChallengeIndex,
            sessionBatchSize = uiState.sessionBatchSize,
            progressFraction = uiState.progressFraction,
            verificationStatus = uiState.verificationStatus,
            lastEvaluatedChar = uiState.lastEvaluatedChar,
            showTargetHint = uiState.showTargetHint,
            onSkipChallenge = onSkipChallenge
        )

        // 5. Live Keying Real-Time Feedback (Decoded Text & Timing Verification)
        SendLiveKeyingFeedbackCard(
            liveDecodedText = uiState.liveDecodedText,
            currentMorseSymbol = uiState.currentMorseSymbol,
            targetCharacter = uiState.targetCharacter,
            lastKeyWasCorrect = uiState.lastKeyWasCorrect,
            feedbackMessage = uiState.feedbackMessage,
            estimatedWpm = uiState.estimatedWpm,
            onClearText = onClearDecodedText
        )

        // 6. Tone Detection Status & Target Sidetone Frequency Instrument
        SendToneDetectorStatusCard(
            isTonePresent = uiState.isTonePresent,
            isListening = uiState.isListening,
            targetFreqHz = uiState.targetFrequencyHz,
            isCustomFrequency = uiState.isCustomFrequency,
            customFrequencyString = uiState.customFrequencyString,
            magnitude = uiState.currentMagnitude,
            threshold = uiState.detectionThreshold,
            onSetFrequency = onSetFrequency,
            onSetCustomFrequencyMode = onSetCustomFrequencyMode,
            onSetCustomFrequencyString = onSetCustomFrequencyString
        )

        // 7. Dynamic Squelch Control Slider
        SendSquelchControlCard(
            squelchLevel = uiState.squelchLevel,
            detectionThreshold = uiState.detectionThreshold,
            currentMagnitude = uiState.currentMagnitude,
            onSetSquelch = onSetSquelch
        )

        // 8. Session Score & Progress Card
        SendSessionProgressCard(
            sessionTotal = uiState.sessionTotalAttempts,
            sessionCorrect = uiState.sessionCorrectAttempts,
            accuracyPercent = uiState.accuracyPercentage,
            batchSize = uiState.sessionBatchSize,
            currentChallengeIndex = uiState.currentChallengeIndex,
            availableCharacters = uiState.availableCharacters
        )

        // 9. Main Action Controls (Receiver toggle & return to setup)
        SendControlsToolbar(
            isListening = uiState.isListening,
            onToggleListening = onToggleListening,
            onReturnToSetup = onReturnToSetup
        )
    }
}

/**
 * High-visibility tone status panel with Red/Green Goertzel detection dot.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SendToneDetectorStatusCard(
    isTonePresent: Boolean,
    isListening: Boolean,
    targetFreqHz: Double,
    isCustomFrequency: Boolean,
    customFrequencyString: String,
    magnitude: Double,
    threshold: Double,
    onSetFrequency: (Double) -> Unit,
    onSetCustomFrequencyMode: (Boolean) -> Unit,
    onSetCustomFrequencyString: (String) -> Unit
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
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Fixed-size Red/Green Goertzel dot indicator (zero layout jitter)
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isTonePresent -> Color(0xFF10B981)
                                    isListening -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (isTonePresent) "SIDETONE DETECTED" else if (isListening) "LISTENING TO MIC" else "RECEIVER OFF",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isTonePresent) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Target Sidetone: ${targetFreqHz.toInt()} Hz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Quick Pitch Tuning Dropdown
                var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                val options = listOf(550.0, 600.0, 650.0, 700.0)

                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.material3.ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.width(130.dp)
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = if (isCustomFrequency) "Custom" else "${targetFreqHz.toInt()} Hz",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            options.forEach { freq ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("${freq.toInt()} Hz") },
                                    onClick = {
                                        onSetFrequency(freq)
                                        expanded = false
                                    }
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Custom") },
                                onClick = {
                                    onSetCustomFrequencyMode(true)
                                    expanded = false
                                }
                            )
                        }
                    }

                    if (isCustomFrequency) {
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = customFrequencyString,
                            onValueChange = onSetCustomFrequencyString,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.width(130.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            singleLine = true,
                            suffix = { Text("Hz", style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real-Time Signal Level Bar
            val signalRatio = (magnitude / max(0.001, threshold * 2.0)).toFloat().coerceIn(0f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SIG",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = { signalRatio },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (isTonePresent) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format("%.3f", magnitude),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Prominent Koch Method Character Challenge card.
 */
@Composable
private fun SendChallengeCard(
    targetCharacter: String,
    targetMorsePattern: String,
    activeKochLevel: Int,
    currentChallengeIndex: Int,
    sessionBatchSize: Int,
    progressFraction: Float,
    verificationStatus: VerificationStatus,
    lastEvaluatedChar: String?,
    showTargetHint: Boolean,
    onSkipChallenge: () -> Unit
) {
    val containerBorderColor = when (verificationStatus) {
        VerificationStatus.CORRECT -> Color(0xFF10B981)
        VerificationStatus.INCORRECT -> MaterialTheme.colorScheme.error
        VerificationStatus.IDLE -> MaterialTheme.colorScheme.primary
    }

    val containerBgColor = when (verificationStatus) {
        VerificationStatus.CORRECT -> Color(0xFF10B981).copy(alpha = 0.12f)
        VerificationStatus.INCORRECT -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        VerificationStatus.IDLE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerBgColor),
        border = BorderStroke(2.dp, containerBorderColor),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row: Koch Level and Batch Progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = "KOCH LEVEL $activeKochLevel",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "Challenge $currentChallengeIndex of $sessionBatchSize",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedButton(
                    onClick = onSkipChallenge,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip Challenge",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Skip", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Batch Progress Indicator
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Verification Status / Target Character Display
            when (verificationStatus) {
                VerificationStatus.CORRECT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Correct",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(60.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "CORRECT!",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF10B981)
                        )
                        Text(
                            text = "Cleanly keyed '$targetCharacter'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                VerificationStatus.INCORRECT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Incorrect",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(60.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "INCORRECT",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Decoded '${lastEvaluatedChar ?: "?"}' • Expected '$targetCharacter'",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                VerificationStatus.IDLE -> {
                    // Active Challenge Display
                    Text(
                        text = "KEY THIS CHARACTER:",
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Large Target Character
                    Text(
                        text = targetCharacter.ifEmpty { "..." },
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 72.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Adaptive Visual Hints
                    if (showTargetHint) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = if (targetMorsePattern.isNotEmpty()) "[ $targetMorsePattern ]" else "[ - ]",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 3.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Mastered (≥70%) • Hint Hidden",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live Decoded Text & Timing Feedback Card.
 */
@Composable
private fun SendLiveKeyingFeedbackCard(
    liveDecodedText: String,
    currentMorseSymbol: String,
    targetCharacter: String,
    lastKeyWasCorrect: Boolean?,
    feedbackMessage: String?,
    estimatedWpm: Int,
    onClearText: () -> Unit
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
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LIVE DECODED KEYING",
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$estimatedWpm WPM",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(onClick = onClearText, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Decoded Terminal Window
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (liveDecodedText.isEmpty()) {
                        Text(
                            text = "Key your Morse code into the microphone...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    } else {
                        Text(
                            text = liveDecodedText,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Current In-Progress Morse Symbol Pulse
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Pulse: ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (currentMorseSymbol.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = if (currentMorseSymbol.isNotEmpty()) "[ $currentMorseSymbol ]" else "[ -- ]",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = if (currentMorseSymbol.isNotEmpty()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // Real-Time Feedback Result Message
            if (feedbackMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val isSuccess = lastKeyWasCorrect == true
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSuccess) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer,
                    border = BorderStroke(1.dp, if (isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = feedbackMessage,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * User-Adjustable Squelch Control Slider.
 */
@Composable
private fun SendSquelchControlCard(
    squelchLevel: Float,
    detectionThreshold: Double,
    currentMagnitude: Double,
    onSetSquelch: (Float) -> Unit
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
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SQUELCH THRESHOLD",
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = String.format("%.3f mag (%.0f%%)", detectionThreshold, squelchLevel * 100f),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Slider(
                value = squelchLevel,
                onValueChange = onSetSquelch,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Drag squelch slider above room ambient noise level to avoid false triggers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Session score and progress card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendSessionProgressCard(
    sessionTotal: Int,
    sessionCorrect: Int,
    accuracyPercent: Int,
    batchSize: Int,
    currentChallengeIndex: Int,
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
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SESSION PERFORMANCE",
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Batch: $sessionTotal / $batchSize",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$accuracyPercent%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (accuracyPercent >= 70) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Accuracy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$sessionCorrect / $sessionTotal",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Correct / Attempts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "≥ 70%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Pass Target",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (availableCharacters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Active Pool: ${availableCharacters.joinToString("  ")}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Controls Toolbar for toggling microphone receiver or exiting drill back to setup.
 */
@Composable
private fun SendControlsToolbar(
    isListening: Boolean,
    onToggleListening: () -> Unit,
    onReturnToSetup: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onToggleListening,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isListening) "Pause Keying Receiver" else "Resume Keying Receiver",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = onReturnToSetup,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Exit Drill to Setup",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Permission Required Warning Card.
 */
@Composable
private fun SendPermissionRequiredCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Microphone Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Hardware keying practice requires microphone access to detect your physical radio sidetone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(text = "Grant Microphone Permission", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}

@Composable
private fun SendNoProfileScreen(modifier: Modifier = Modifier) {
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
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
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
                    text = "Please create or select a user profile to begin keying training.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
