package com.example.androidmorsetrainer.ui.screens.qso

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidmorsetrainer.audio.DspTelemetry
import com.example.androidmorsetrainer.audio.MorseDSPManager
import com.example.androidmorsetrainer.morse.ExchangeStage
import com.example.androidmorsetrainer.morse.QsoBotProfile
import com.example.androidmorsetrainer.morse.QsoState
import java.util.Locale

/**
 * Jetpack Compose screen for the offline Morse code QSO simulator practice module.
 * Strictly relies on real-time Goertzel DSP microphone decoding for half-duplex practice.
 */
@Composable
fun QsoPracticeScreen(
    modifier: Modifier = Modifier,
    viewModel: QsoViewModel = viewModel(factory = QsoViewModel.Factory)
) {
    val context = LocalContext.current

    // 1. Prevent screen sleep while QSO practice screen is active
    DisposableEffect(Unit) {
        val activity = context as? Activity ?: (context as? ContextWrapper)?.baseContext as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val isMicMuted by viewModel.isMicMuted.collectAsStateWithLifecycle()
    val isAudioProcessing by viewModel.isAudioProcessing.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scrollState = rememberScrollState()

    // Automatically scroll to bottom of the console whenever new transcript lines arrive
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Hardware Calibration & Real-time DSP Telemetry Overlay (Permanently visible at the very top)
        CalibrationTelemetryOverlay(
            telemetry = telemetry,
            onThresholdChanged = { viewModel.updateNoiseFloor(it) }
        )

        // 2. Strict Half-Duplex Microphone & Batch Audio Processing Status Banner
        MicrophoneHalfDuplexBanner(
            isMuted = isMicMuted,
            isProcessing = isAudioProcessing,
            statusText = statusText
        )

        // 3. Simulated Operator Header
        OperatorHeaderCard(profile = activeProfile)

        // 4. Prominent QSO FSM Status Indicator (6 Sequential States)
        QsoStatusIndicatorCard(state = qsoState)

        // 5. QSO Contact Summary Card (Shown when session completes in State 6)
        if (qsoState is QsoState.Summary) {
            QsoSummaryCard(
                summary = qsoState as QsoState.Summary,
                onStartNewQso = {
                    if (hasAudioPermission) {
                        viewModel.startNewQso()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }

        // 6. Permission Alert Banner (if microphone access is not yet granted)
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

        // 7. Scrolling Exchange Transcription Waterfall / Console
        ConsoleTranscriptionCard(
            transcript = transcript,
            isProcessing = isAudioProcessing,
            listState = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )

        // 8. Session Controls: Start New QSO & Clear Log
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
 * Prominent banner indicating half-duplex microphone status:
 * - MUTED (BOT TX): Audio capture paused during simulated bot audio transmission to prevent acoustic self-decoding.
 * - PROCESSING AUDIO: Batch DSP analyzing recorded audio buffer with contextual tuning.
 * - LISTENING: Microphone active, streaming raw PCM audio and monitoring voice activity (VAD).
 */
@Composable
fun MicrophoneHalfDuplexBanner(
    isMuted: Boolean,
    isProcessing: Boolean = false,
    statusText: String = "",
    modifier: Modifier = Modifier
) {
    val bannerColor = when {
        isMuted -> Color(0xFFD32F2F)
        isProcessing -> Color(0xFFFB8C00)
        else -> Color(0xFF2E7D32)
    }
    val containerBg = when {
        isMuted -> Color(0xFFFFEBEE)
        isProcessing -> Color(0xFFFFF3E0)
        else -> Color(0xFFE8F5E9)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.5.dp, bannerColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bannerColor),
                contentAlignment = Alignment.Center
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Microphone Muted" else "Microphone Active",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isMuted -> "MICROPHONE MUTED (BOT TRANSMITTING)"
                        isProcessing -> "PROCESSING AUDIO..."
                        statusText.isNotBlank() -> statusText.uppercase()
                        else -> "LISTENING TO YOUR TRANSMISSION..."
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = bannerColor
                )
                Text(
                    text = when {
                        isMuted ->
                            "Half-duplex lock: Audio input paused during bot playback to prevent self-decoding."
                        isProcessing ->
                            "Batch DSP analyzing entire recorded transmission with contextual tuning..."
                        else ->
                            "Batch recording: Streaming raw audio and monitoring key activity (VAD)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
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
 * Configured for the 6-state sequential conversational loop.
 */
@Composable
private fun QsoStatusIndicatorCard(
    state: QsoState,
    modifier: Modifier = Modifier
) {
    val (statusTitle, statusDetail, targetColor) = when (state) {
        is QsoState.Idle -> Triple(
            "STATE 1: IDLE / READY",
            "Ready for transmission. Transmit 'CQ' to initiate contact.",
            Color(0xFF78909C)
        )
        is QsoState.ListenCQ -> Triple(
            "STATE 1: LISTEN CQ",
            "DSP listening for CQ. Send 'CQ CQ DE [Callsign] K'. Auto-advances on 'K' or 5s silence.",
            Color(0xFF1E88E5)
        )
        is QsoState.InitialResponse -> Triple(
            "STATE 2: INITIAL RESPONSE",
            "Bot transmitted initial response. Transmit your signal report (e.g. 599, 5NN BK).",
            Color(0xFFFB8C00)
        )
        is QsoState.InfoExchange -> Triple(
            "STATE 3: INFO EXCHANGE",
            "Bot transmitted Name & QTH. Transmit acknowledgement (e.g. QSL, TNX, 73).",
            Color(0xFF8E24AA)
        )
        is QsoState.SignOff -> Triple(
            "STATE 4: SIGN OFF",
            "Bot transmitted 73 sign-off. Acknowledge with '73', 'TU', or 'SK'.",
            Color(0xFF00897B)
        )
        is QsoState.DitDitFinale -> Triple(
            "STATE 5: DIT DIT FINALE",
            "Bot listening for final 'E E' (dit-dit) goodbye. Auto-completes on 5s silence.",
            Color(0xFF43A047)
        )
        is QsoState.Summary -> Triple(
            "STATE 6: QSO COMPLETE",
            "QSO completed successfully! Review contact summary below.",
            Color(0xFF2E7D32)
        )
        is QsoState.Error -> Triple(
            "ERROR: RETRYING",
            "Incomprehensible decode. Bot transmitting '${state.message}'...",
            Color(0xFFD32F2F)
        )
        // Legacy states
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
 * Summary card displayed in State 6 showing QSO contact details and action to start another QSO.
 */
@Composable
fun QsoSummaryCard(
    summary: QsoState.Summary,
    onStartNewQso: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, Color(0xFF2E7D32), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF1F8E9)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "QSO SUMMARY & LOG",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "OPERATOR",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                    Text(
                        text = "${summary.botProfile?.callsign ?: "REMOTE"} (${summary.botProfile?.name ?: "Operator"})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Black
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "QTH",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                    Text(
                        text = summary.botProfile?.qth ?: "Unknown",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "YOUR CALLSIGN",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                    Text(
                        text = summary.userCallsign,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Black
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "RST REPORT",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                    Text(
                        text = summary.userReport ?: "599",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Black
                    )
                }
            }

            Button(
                onClick = onStartNewQso,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log Contact & Start Next QSO")
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
    isProcessing: Boolean = false,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "QSO TRANSCRIPTION CONSOLE",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray.copy(alpha = 0.7f)
                    )
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFFB8C00)
                        )
                        Text(
                            text = "DECODING...",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFB8C00)
                        )
                    }
                }

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
                    if (isProcessing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFB8C00)
                            )
                            Text(
                                text = "Processing Audio...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFFB74D)
                            )
                        }
                    } else {
                        Text(
                            text = "Listening to your transmission...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray
                        )
                    }
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
                            line.startsWith("SYS: Mic") -> Color(0xFFFF8A80) // Soft red/coral for mic muting
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

                    if (isProcessing) {
                        item {
                            Surface(
                                color = Color(0xFF261C00),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, Color(0xFFFB8C00).copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = Color(0xFFFB8C00)
                                    )
                                    Text(
                                        text = "Processing Audio...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFFFB74D)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Real-time Hardware Calibration and DSP Telemetry overlay.
 * Permanently displayed at the top of the QSO practice screen for tuning squelch,
 * monitoring Goertzel signal magnitude, and tracking dynamic timing engine metrics.
 */
@Composable
fun CalibrationTelemetryOverlay(
    telemetry: DspTelemetry,
    onThresholdChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val isToneDetected = telemetry.currentMagnitude >= telemetry.noiseFloorThreshold
    val activeColor = if (isToneDetected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
    val maxDisplayMagnitude = 0.300f
    val normalizedProgress = (telemetry.currentMagnitude / maxDisplayMagnitude).coerceIn(0f, 1f)
    val calculatedWpm = if (telemetry.currentDitAverageMs > 0) {
        (1200f / telemetry.currentDitAverageMs).toInt()
    } else {
        0
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Title & Tone Detection Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Calibration & Telemetry",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "DSP CALIBRATION & TELEMETRY",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Tone Detection Status Pill
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isToneDetected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isToneDetected) Color(0xFF81C784) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isToneDetected) Color.Green else Color.Gray)
                        )
                        Text(
                            text = if (isToneDetected) "TONE DETECTED" else "SQUELCHED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isToneDetected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Live Magnitude Meter
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Goertzel Magnitude",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "%.4f / %.3f", telemetry.currentMagnitude, telemetry.noiseFloorThreshold),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isToneDetected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LinearProgressIndicator(
                    progress = { normalizedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = activeColor,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                )
            }

            // Key Timing Metrics: Last Tone, Last Silence, Dit Baseline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Last Tone Box
                TimingMetricBox(
                    label = "LAST TONE",
                    value = "${telemetry.lastToneDurationMs} ms",
                    modifier = Modifier.weight(1f)
                )

                // Last Silence Box
                TimingMetricBox(
                    label = "LAST SILENCE",
                    value = "${telemetry.lastSilenceDurationMs} ms",
                    modifier = Modifier.weight(1f)
                )

                // Dit Average Box
                TimingMetricBox(
                    label = "DIT BASELINE",
                    value = String.format(Locale.US, "%.0f ms (%dW)", telemetry.currentDitAverageMs, calculatedWpm),
                    modifier = Modifier.weight(1f)
                )
            }

            // Noise Floor / Squelch Slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Squelch Threshold",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "%.3f", telemetry.noiseFloorThreshold),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = telemetry.noiseFloorThreshold.coerceIn(
                        MorseDSPManager.MIN_SQUELCH_MAGNITUDE.toFloat(),
                        MorseDSPManager.MAX_SQUELCH_MAGNITUDE.toFloat()
                    ),
                    onValueChange = onThresholdChanged,
                    valueRange = MorseDSPManager.MIN_SQUELCH_MAGNITUDE.toFloat()..MorseDSPManager.MAX_SQUELCH_MAGNITUDE.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TimingMetricBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
