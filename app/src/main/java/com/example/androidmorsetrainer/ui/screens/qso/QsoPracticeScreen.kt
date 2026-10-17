package com.example.androidmorsetrainer.ui.screens.qso

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidmorsetrainer.morse.ExchangeStage
import com.example.androidmorsetrainer.morse.QsoBotProfile
import com.example.androidmorsetrainer.morse.QsoState

/**
 * Jetpack Compose screen for the offline Morse code QSO simulator practice module.
 */
@Composable
fun QsoPracticeScreen(
    modifier: Modifier = Modifier,
    viewModel: QsoViewModel = viewModel(factory = QsoViewModel.Factory)
) {
    val context = LocalContext.current

    // Audio permission state management
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            viewModel.startNewQso()
        }
    }

    // Collect StateFlows safely using lifecycle-aware collection
    val qsoState by viewModel.qsoState.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()

    var manualInputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Automatically scroll to bottom of the console whenever new transcript lines arrive
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Simulated Operator Header
        OperatorHeaderCard(profile = activeProfile)

        // 2. Prominent QSO FSM Status Indicator
        QsoStatusIndicatorCard(state = qsoState)

        // 3. Permission Alert Banner (if microphone access is not yet granted)
        if (!hasAudioPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MicOff,
                        contentDescription = "Microphone Required",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Microphone permission is required for real-time Goertzel DSP Morse decoding. Tap 'Grant Mic & Start' below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 4. Scrolling Exchange Transcription Waterfall / Console
        ConsoleTranscriptionCard(
            transcript = transcript,
            listState = listState,
            modifier = Modifier.weight(1f)
        )

        // 5. Manual / Quick Simulation Input Row
        SimulationInputRow(
            text = manualInputText,
            onTextChanged = { manualInputText = it },
            onSend = {
                if (manualInputText.isNotBlank()) {
                    viewModel.simulateInput(manualInputText)
                    manualInputText = ""
                }
            },
            onQuickSend = { quickToken ->
                viewModel.simulateInput(quickToken)
            }
        )

        // 6. Controls: Start New QSO (with audio permission gate) & Clear Log
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (hasAudioPermission) {
                        viewModel.startNewQso()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasAudioPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(
                    imageVector = if (hasAudioPermission) Icons.Default.Refresh else Icons.Default.Mic,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (hasAudioPermission) "Start New QSO" else "Grant Mic & Start QSO")
            }

            OutlinedButton(
                onClick = { viewModel.clearTranscript() }
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Log")
            }
        }
    }
}

/**
 * Header card showing the active simulated operator's identity (Callsign, Name, QTH, and WPM).
 */
@Composable
private fun OperatorHeaderCard(
    profile: QsoBotProfile?,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = "Simulated Station",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column {
                    Text(
                        text = profile?.callsign ?: "CONNECTING...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = profile?.name ?: "Unknown",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )

                        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))

                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = profile?.qth ?: "QTH",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Speed Pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "WPM",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "${profile?.wpm ?: 0} WPM",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Prominent status indicator card displaying the current [QsoState].
 */
@Composable
private fun QsoStatusIndicatorCard(
    state: QsoState,
    modifier: Modifier = Modifier
) {
    val (statusTitle, statusDetail, targetColor) = when (state) {
        is QsoState.Idle -> Triple(
            "IDLE",
            "Ready for transmission. Send 'CQ' to initiate contact.",
            Color(0xFF78909C)
        )
        is QsoState.TransmittingCQ -> Triple(
            "TRANSMITTING CQ",
            "Transmitting callsign. Finish call with 'K'.",
            Color(0xFF1E88E5)
        )
        is QsoState.ListeningForCaller -> Triple(
            "LISTENING FOR CALLER",
            "Waiting for remote station response...",
            Color(0xFF43A047)
        )
        is QsoState.Exchanging -> when (state.stage) {
            ExchangeStage.AWAITING_CALLSIGN -> Triple(
                "EXCHANGING: CALLSIGN",
                "Awaiting caller callsign.",
                Color(0xFFFB8C00)
            )
            ExchangeStage.AWAITING_REPORT -> Triple(
                "EXCHANGING: SIGNAL REPORT",
                "Exchange in progress. Awaiting RST report (e.g. 599).",
                Color(0xFFE65100)
            )
            ExchangeStage.COMPLETED -> Triple(
                "EXCHANGING: COMPLETED",
                "Report received. Sign off with '73' or 'TU'.",
                Color(0xFF8E24AA)
            )
        }
        is QsoState.LoggingContact -> Triple(
            "LOGGING CONTACT",
            "QSO completed successfully! Logging to logbook.",
            Color(0xFF00897B)
        )
    }

    val animatedColor by animateColorAsState(targetValue = targetColor, label = "StatusColor")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, animatedColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = animatedColor.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(animatedColor)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = animatedColor
                )
                Text(
                    text = statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Scrolling waterfall/console log card displaying real-time QSO exchange transcriptions.
 */
@Composable
private fun ConsoleTranscriptionCard(
    transcript: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF141414)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "QSO TRANSCRIPTION CONSOLE",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray.copy(alpha = 0.7f)
                )

                Text(
                    text = "${transcript.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (transcript.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Awaiting Morse transmissions...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(transcript) { line ->
                        val textColor = when {
                            line.startsWith("TX") -> Color(0xFF81C784) // Green for user transmission
                            line.startsWith("RX") -> Color(0xFFFFD54F) // Amber for bot transmission
                            line.startsWith("===") -> Color(0xFF64B5F6) // Blue for headers
                            line.startsWith("SYS") -> Color(0xFFB0BEC5) // Slate for system states
                            line.startsWith("DSP") -> Color(0xFF80DEEA) // Cyan for DSP decoded
                            else -> Color.White
                        }

                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            ),
                            fontFamily = FontFamily.Monospace,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Quick simulation action chips and manual input row for testing and practicing.
 */
@Composable
private fun SimulationInputRow(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onQuickSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Quick Action Chips for Morse QSO tokens
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val quickTokens = listOf("CQ", "K", "M7KQX", "599", "73")
            for (token in quickTokens) {
                FilterChip(
                    selected = false,
                    onClick = { onQuickSend(token) },
                    label = {
                        Text(
                            token,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }

        // Text input field
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Simulate Morse Input (e.g. CQ, 599)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )

            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send Input",
                    tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    }
}
