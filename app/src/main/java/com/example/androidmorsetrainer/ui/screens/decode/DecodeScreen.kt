package com.example.androidmorsetrainer.ui.screens.decode

import android.Manifest
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current

    DisposableEffect(uiState.isListening) {
        val activity = context as? Activity
        if (uiState.isListening) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
    }

    DecodeScreenContent(
        uiState = uiState,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onToggleListening = viewModel::toggleListening,
        onClearText = viewModel::clearDecodedText,
        onAutoDetectPitch = viewModel::autoDetectPitch,
        modifier = modifier
    )
}

@Composable
fun DecodeScreenContent(
    uiState: DecodeUiState,
    onRequestPermission: () -> Unit,
    onToggleListening: () -> Unit,
    onClearText: () -> Unit,
    onAutoDetectPitch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!uiState.hasRecordPermission) {
            PermissionRequiredCard(onRequestPermission = onRequestPermission)
        }

        SdrOscilloscopeCard(
            amplitudePoints = uiState.amplitudePoints,
            isTonePresent = uiState.isTonePresent,
            detectionThreshold = uiState.detectionThreshold,
            noiseFloor = uiState.noiseFloor,
            isListening = uiState.isListening,
            targetFreqHz = uiState.targetFrequencyHz
        )

        DecodedTeletypeCard(
            decodedText = uiState.decodedText,
            currentMorseSymbol = uiState.currentMorseSymbol,
            estimatedWpm = uiState.estimatedWpm,
            isListening = uiState.isListening,
            onClearText = onClearText,
            modifier = Modifier.weight(1f)
        )

        DecodeControlsToolbar(
            isListening = uiState.isListening,
            isAutoTuning = uiState.isAutoTuning,
            onToggleListening = onToggleListening,
            onAutoDetectPitch = onAutoDetectPitch
        )
    }
}

@Composable
private fun SdrOscilloscopeCard(
    amplitudePoints: List<AmplitudePoint>,
    isTonePresent: Boolean,
    detectionThreshold: Double,
    noiseFloor: Double,
    isListening: Boolean,
    targetFreqHz: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SdrScreenBg),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, SdrGraticuleAccent)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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

            SdrCanvasWaveform(
                amplitudePoints = amplitudePoints,
                detectionThreshold = detectionThreshold,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

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

        val quarterHeight = height / 4f
        drawLine(color = SdrGraticule, start = Offset(0f, quarterHeight), end = Offset(width, quarterHeight), strokeWidth = 1f)
        drawLine(color = SdrGraticule, start = Offset(0f, quarterHeight * 3f), end = Offset(width, quarterHeight * 3f), strokeWidth = 1f)

        drawLine(
            color = SdrGraticuleAccent,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1.5f
        )

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

        val stepX = width / count
        val maxHalfHeight = centerY - 6f

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

@Composable
private fun DecodedTeletypeCard(
    decodedText: String,
    currentMorseSymbol: String,
    estimatedWpm: Int,
    isListening: Boolean,
    onClearText: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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

            val scrollState = rememberScrollState()
            LaunchedEffect(decodedText) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
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

@Composable
private fun DecodeControlsToolbar(
    isListening: Boolean,
    isAutoTuning: Boolean,
    onToggleListening: () -> Unit,
    onAutoDetectPitch: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onAutoDetectPitch,
            enabled = !isAutoTuning,
            modifier = Modifier
                .weight(1f)
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            if (isAutoTuning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = "Auto-Detect Tone",
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Auto Tune",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Button(
            onClick = onToggleListening,
            modifier = Modifier
                .weight(1f)
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
                text = if (isListening) "Stop Rx" else "Start Rx",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
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
