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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.androidmorsetrainer.ui.theme.SdrPhosphorActive
import com.example.androidmorsetrainer.ui.theme.SdrPhosphorCyan
import kotlin.math.max

@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    viewModel: DecodeViewModel = viewModel(factory = DecodeViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SetupScreenContent(
        uiState = uiState,
        onSetFrequency = viewModel::setTargetFrequency,
        onAutoDetectPitch = viewModel::autoDetectPitch,
        onCalibrate = viewModel::calibrateNoiseFloor,
        onDismissMessage = viewModel::clearUserMessage,
        onSquelchChange = viewModel::setSquelchLevel,
        modifier = modifier
    )
}

@Composable
fun SetupScreenContent(
    uiState: DecodeUiState,
    onSetFrequency: (Double) -> Unit,
    onAutoDetectPitch: () -> Unit,
    onCalibrate: () -> Unit,
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

        SdrSquelchControlCard(
            squelchLevel = uiState.squelchLevel,
            detectionThreshold = uiState.detectionThreshold,
            onSquelchChange = onSquelchChange
        )
    }
}

@Composable
fun SdrTargetFrequencyPanel(
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
fun MetricChip(label: String, value: String) {
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

@Composable
fun DecodeCalibrationControls(
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

@Composable
fun SdrSquelchControlCard(
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
