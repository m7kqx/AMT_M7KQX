package com.example.androidmorsetrainer.ui.screens.decode

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidmorsetrainer.ui.theme.SdrGraticule
import com.example.androidmorsetrainer.ui.theme.SdrGraticuleAccent
import com.example.androidmorsetrainer.ui.theme.SdrPhosphorActive
import com.example.androidmorsetrainer.ui.theme.SdrPhosphorActiveGlow
import com.example.androidmorsetrainer.ui.theme.SdrPhosphorCyan
import com.example.androidmorsetrainer.ui.theme.SdrPhosphorIdle
import com.example.androidmorsetrainer.ui.theme.SdrScreenBg
import com.example.androidmorsetrainer.ui.theme.SdrThresholdLine
import kotlin.math.max

@Composable
fun DecodeScreen(
    modifier: Modifier = Modifier,
    viewModel: DecodeViewModel = viewModel(factory = DecodeViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
    }

    DecodeScreenContent(
        uiState = uiState,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onToggleListening = viewModel::toggleListening,
        onSetFrequency = viewModel::setTargetFrequency,
        onAutoDetectPitch = viewModel::autoDetectPitch,
        onCalibrate = viewModel::calibrateNoiseFloor,
        onClearText = viewModel::clearDecodedText,
        onDismissMessage = viewModel::clearUserMessage,
        onSquelchChange = viewModel::setSquelchLevel,
        modifier = modifier
    )
}

@Composable
fun DecodeScreenContent(
    uiState: DecodeUiState,
    onRequestPermission: () -> Unit,
    onToggleListening: () -> Unit,
    onSetFrequency: (Double) -> Unit,
    onAutoDetectPitch: () -> Unit,
    onCalibrate: () -> Unit,
    onClearText: () -> Unit,
    onDismissMessage: () -> Unit,
    onSquelchChange: (Float) -> Unit,
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
        // 1. Permission Banner (if not granted)
        if (!uiState.hasRecordPermission) {
            PermissionRequiredCard(onRequestPermission = onRequestPermission)
        }

        // 2. Feedback Notification Banner (Auto-Tune confirmation or info)
        AnimatedVisibility(
            visible = uiState.userMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            uiState.userMessage?.let { msg ->
                val isSuccess = uiState.autoTunedFrequencyHz != null && !uiState.isAutoTuning
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess)
                            SdrPhosphorActive.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSuccess) SdrPhosphorActive else MaterialTheme.colorScheme.outlineVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isSuccess) SdrPhosphorActive else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = onDismissMessage,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. High-Visibility SDR Target Frequency Detection Instrument
        SdrTargetFrequencyPanel(
            isTonePresent = uiState.isTonePresent,
            isListening = uiState.isListening,
            isAutoTuning = uiState.isAutoTuning,
            autoTunedFrequencyHz = uiState.autoTunedFrequencyHz,
            targetFreqHz = uiState.targetFrequencyHz,
            magnitude = uiState.currentMagnitude,
            spectralPurity = uiState.spectralPurity,
            threshold = uiState.detectionThreshold,
            noiseFloor = uiState.noiseFloor,
            onSetFrequency = onSetFrequency
        )

        DecodeCalibrationControls(
            isListening = uiState.isListening,
            isCalibrating = uiState.isCalibrating,
            isAutoTuning = uiState.isAutoTuning,
            onCalibrate = onCalibrate,
            onAutoDetectPitch = onAutoDetectPitch
        )
        
        // 4. Dark, High-Contrast SDR Waterfall / Oscilloscope Canvas Visualizer
        SdrOscilloscopeCard(
            amplitudePoints = uiState.amplitudePoints,
            isTonePresent = uiState.isTonePresent,
            detectionThreshold = uiState.detectionThreshold,
            noiseFloor = uiState.noiseFloor,
            isListening = uiState.isListening,
            targetFreqHz = uiState.targetFrequencyHz
        )

        // 5. Dynamic Squelch Magnitude Threshold Slider Control
        SdrSquelchControlCard(
            squelchLevel = uiState.squelchLevel,
            detectionThreshold = uiState.detectionThreshold,
            onSquelchChange = onSquelchChange
        )

        // 6. Live Decoded CW Teletype Terminal
        DecodedTeletypeCard(
            decodedText = uiState.decodedText,
            currentMorseSymbol = uiState.currentMorseSymbol,
            estimatedWpm = uiState.estimatedWpm,
            isListening = uiState.isListening,
            onClearText = onClearText
        )

        // 6. Controls Toolbar (Primary Receiver toggle)
        DecodeControlsToolbar(
            isListening = uiState.isListening,
            onToggleListening = onToggleListening
        )
    }
}

/**
 * SDR Target Frequency Instrument Panel with glowing lock status and signal metrics.
 */
@Composable
private fun SdrTargetFrequencyPanel(
    isTonePresent: Boolean,
    isListening: Boolean,
    isAutoTuning: Boolean = false,
    autoTunedFrequencyHz: Double? = null,
    targetFreqHz: Double,
    magnitude: Double,
    spectralPurity: Double,
    threshold: Double,
    noiseFloor: Double,
    onSetFrequency: (Double) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val activeGlowColor = if (isAutoTuning) SdrPhosphorCyan else SdrPhosphorActive
    val idleIndicatorColor = Color(0xFF64748B)

    val indicatorColor by animateColorAsState(
        targetValue = if (isTonePresent || isAutoTuning) activeGlowColor else idleIndicatorColor,
        animationSpec = tween(100),
        label = "indicatorColor"
    )

    val isAutoLocked = autoTunedFrequencyHz != null && targetFreqHz == autoTunedFrequencyHz

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isTonePresent) activeGlowColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(
            width = if (isTonePresent || isAutoLocked) 1.5.dp else 1.dp,
            color = if (isTonePresent) activeGlowColor else if (isAutoLocked) SdrPhosphorActive.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Glowing Phosphor LED Lock Indicator
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .then(if (isTonePresent || isAutoTuning) Modifier.scale(pulseScale) else Modifier)
                            .clip(CircleShape)
                            .background(indicatorColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isTonePresent || isAutoTuning) Color.White else Color(0xFF334155))
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = when {
                                isAutoTuning -> "ANALYZING SPECTRUM (FFT)..."
                                isTonePresent -> "CARRIER LOCKED (CW)"
                                isListening -> "DSP SEARCHING"
                                else -> "RECEIVER IDLE"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.5.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isTonePresent) SdrPhosphorActive else if (isAutoTuning) SdrPhosphorCyan else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Target Sidetone: ${targetFreqHz.toInt()} Hz" + if (isAutoLocked) " • Auto-Locked" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAutoLocked) SdrPhosphorActive else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isAutoLocked) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Quick Frequency Tuning Selector Chips with Auto-Detected frequency support
                val baseFreqs = listOf(550.0, 600.0, 650.0, 700.0)
                val displayFreqs = if (autoTunedFrequencyHz != null && autoTunedFrequencyHz !in baseFreqs) {
                    baseFreqs + autoTunedFrequencyHz
                } else {
                    baseFreqs
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    displayFreqs.forEach { freq ->
                        val isSelected = targetFreqHz == freq
                        val isThisAuto = autoTunedFrequencyHz != null && freq == autoTunedFrequencyHz
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSetFrequency(freq) },
                            label = {
                                Text(
                                    text = if (isThisAuto) "${freq.toInt()}Hz ★" else "${freq.toInt()}Hz",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (isThisAuto) SdrPhosphorActive.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = if (isThisAuto) SdrPhosphorActive else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Real-Time Signal Level Bar
            val signalRatio = ((magnitude / max(0.001, threshold * 2.0)).toFloat()).coerceIn(0f, 1f)
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
                    color = if (isTonePresent) SdrPhosphorActive else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // DSP Metrics Dashboard
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricChip(label = "MAGNITUDE", value = String.format("%.3f", magnitude))
                MetricChip(label = "PURITY", value = "${(spectralPurity * 100).toInt()}%")
                MetricChip(label = "THRESH", value = String.format("%.3f", threshold))
                MetricChip(label = "NOISE", value = String.format("%.3f", noiseFloor))
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * SDR Waterfall / Oscilloscope Visualizer Card styled with deep dark CRT bezel and graticule lines.
 */
@Composable
private fun SdrOscilloscopeCard(
    amplitudePoints: List<AmplitudePoint>,
    isTonePresent: Boolean,
    detectionThreshold: Double,
    noiseFloor: Double,
    isListening: Boolean,
    targetFreqHz: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SdrScreenBg),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, SdrGraticuleAccent)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Oscilloscope Top Header HUD
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = SdrPhosphorCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "OSCILLOSCOPE // CW SPECTRUM",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = SdrPhosphorCyan
                    )
                }

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isTonePresent) Color(0xFF10B981) else MaterialTheme.colorScheme.error)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Canvas Waveform with Dual-Pass Phosphor Trace and Graticule
            SdrCanvasWaveform(
                amplitudePoints = amplitudePoints,
                detectionThreshold = detectionThreshold,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // HUD Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TIME/DIV: 12ms • FC: ${targetFreqHz.toInt()}Hz",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = Color(0xFF64748B)
                )
                Text(
                    text = "THR: ${String.format("%.3f", detectionThreshold)} • NF: ${String.format("%.3f", noiseFloor)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

/**
 * Ultra-optimized Canvas drawing logic for SDR Oscilloscope & Waterfall visualizer.
 * Executes purely in Draw phase without heap allocations.
 */
@Composable
private fun SdrCanvasWaveform(
    amplitudePoints: List<AmplitudePoint>,
    detectionThreshold: Double,
    modifier: Modifier = Modifier
) {
    val dashedEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f) }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF04070B))
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val points = amplitudePoints
        val count = points.size

        if (width <= 0f || height <= 0f) return@Canvas

        // 1. Draw Oscilloscope Graticule Grid Lines (8 vertical divisions, 4 horizontal divisions)
        val numVerticalGraticules = 8
        val stepGridX = width / numVerticalGraticules
        for (v in 1 until numVerticalGraticules) {
            val gx = v * stepGridX
            drawLine(
                color = SdrGraticule,
                start = Offset(gx, 0f),
                end = Offset(gx, height),
                strokeWidth = 1f
            )
        }

        // Horizontal graticules (quarter lines)
        val quarterHeight = height / 4f
        drawLine(color = SdrGraticule, start = Offset(0f, quarterHeight), end = Offset(width, quarterHeight), strokeWidth = 1f)
        drawLine(color = SdrGraticule, start = Offset(0f, quarterHeight * 3f), end = Offset(width, quarterHeight * 3f), strokeWidth = 1f)

        // Center Baseline (Prominent graticule)
        drawLine(
            color = SdrGraticuleAccent,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1.5f
        )

        // Threshold boundary markers
        val thresholdYOffset = (detectionThreshold.toFloat() * centerY * 2.5f).coerceIn(0f, centerY - 6f)
        if (thresholdYOffset > 0f) {
            drawLine(
                color = SdrThresholdLine.copy(alpha = 0.6f),
                start = Offset(0f, centerY - thresholdYOffset),
                end = Offset(width, centerY - thresholdYOffset),
                strokeWidth = 1f,
                pathEffect = dashedEffect
            )
            drawLine(
                color = SdrThresholdLine.copy(alpha = 0.6f),
                start = Offset(0f, centerY + thresholdYOffset),
                end = Offset(width, centerY + thresholdYOffset),
                strokeWidth = 1f,
                pathEffect = dashedEffect
            )
        }

        if (count == 0) return@Canvas

        // 2. Horizontally Scrolling Dual-Pass Phosphor Trace
        val stepX = width / count
        val maxHalfHeight = centerY - 6f

        // Pass A: Outer Phosphor Ambient Glow (for active Morse tones)
        for (i in 0 until count) {
            val point = points[i]
            if (point.isTone) {
                val x = i * stepX
                val barHalfHeight = max(3f, point.amplitude * maxHalfHeight * 3.2f).coerceAtMost(maxHalfHeight)
                drawLine(
                    color = SdrPhosphorActiveGlow,
                    start = Offset(x, centerY - barHalfHeight),
                    end = Offset(x, centerY + barHalfHeight),
                    strokeWidth = max(2.5f, stepX * 1.3f)
                )
            }
        }

        // Pass B: Inner Sharp Phosphor Trace
        for (i in 0 until count) {
            val point = points[i]
            val x = i * stepX
            val barHalfHeight = max(2f, point.amplitude * maxHalfHeight * 3.2f).coerceAtMost(maxHalfHeight)

            val traceColor = if (point.isTone) SdrPhosphorActive else SdrPhosphorIdle

            drawLine(
                color = traceColor,
                start = Offset(x, centerY - barHalfHeight),
                end = Offset(x, centerY + barHalfHeight),
                strokeWidth = max(1.2f, stepX * 0.75f)
            )
        }
    }
}

/**
 * Dynamic User-Adjustable Squelch Threshold Control Panel.
 * Maps normalized UI slider (0.0 .. 1.0) to underlying Goertzel magnitude detection threshold.
 */
@Composable
private fun SdrSquelchControlCard(
    squelchLevel: Float,
    detectionThreshold: Double,
    onSquelchChange: (Float) -> Unit
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
                onValueChange = onSquelchChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Adjust threshold above ambient noise floor line on oscilloscope.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Live Decoded CW Teletype Card with monospace typography and in-progress element indicators.
 */
@Composable
private fun DecodedTeletypeCard(
    decodedText: String,
    currentMorseSymbol: String,
    estimatedWpm: Int,
    isListening: Boolean,
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
                .padding(18.dp)
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
                        text = "Decoded Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "~$estimatedWpm WPM",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    IconButton(onClick = onClearText) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Decoded Text",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Teletype Terminal Window
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (decodedText.isEmpty()) {
                        Text(
                            text = if (isListening) "Receiving... Key or play CW audio into mic." else "Click 'Start Listening' to begin audio decode.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        Text(
                            text = decodedText,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 22.sp,
                                letterSpacing = 2.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Current In-Progress Morse Pulse Symbol
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
        }
    }
}

/**
 * Calibration Controls Toolbar for auto-detecting CW pitch and calibrating noise floor.
 */
@Composable
private fun DecodeCalibrationControls(
    isListening: Boolean,
    isCalibrating: Boolean,
    isAutoTuning: Boolean,
    onCalibrate: () -> Unit,
    onAutoDetectPitch: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Auto-Detect Pitch Button
        Button(
            onClick = onAutoDetectPitch,
            enabled = !isAutoTuning && !isCalibrating,
            modifier = Modifier
                .weight(1.1f)
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            if (isAutoTuning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Detecting...",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Auto-Detect Pitch",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Calibrate Noise Floor Button
        OutlinedButton(
            onClick = onCalibrate,
            enabled = isListening && !isCalibrating && !isAutoTuning,
            modifier = Modifier
                .weight(0.9f)
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isCalibrating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Calibrating...", style = MaterialTheme.typography.labelSmall)
            } else {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Calibrate",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Receiver Controls Toolbar for starting/stopping stream.
 */
@Composable
private fun DecodeControlsToolbar(
    isListening: Boolean,
    onToggleListening: () -> Unit
) {
    // Primary Action: Start / Stop Receiver
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
            text = if (isListening) "Stop Receiver" else "Start Receiver",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PermissionRequiredCard(onRequestPermission: () -> Unit) {
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
                text = "To decode incoming Morse audio tones, the application requires access to the microphone.",
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
                Text(text = "Grant Permission", color = MaterialTheme.colorScheme.onError)
            }
        }
    }
}
