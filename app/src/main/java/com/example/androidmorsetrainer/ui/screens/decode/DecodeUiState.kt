package com.example.androidmorsetrainer.ui.screens.decode

import androidx.compose.runtime.Immutable

/**
 * Single amplitude sample along with whether Goertzel filter detected the target frequency.
 */
@Immutable
data class AmplitudePoint(
    val amplitude: Float = 0.0f,
    val isTone: Boolean = false
)

/**
 * UI State for the Live Morse Decoder Screen.
 * Fully immutable to allow Jetpack Compose to optimize recomposition on high-refresh displays.
 */
@Immutable
data class DecodeUiState(
    val isListening: Boolean = false,
    val isCalibrating: Boolean = false,
    val isAutoTuning: Boolean = false,
    val autoTunedFrequencyHz: Double? = null,
    val isTonePresent: Boolean = false,
    val targetFrequencyHz: Double = 700.0,
    val currentMagnitude: Double = 0.0,
    val spectralPurity: Double = 0.0,
    val detectionThreshold: Double = 0.05,
    val noiseFloor: Double = 0.02,
    val rawAmplitudes: List<Float> = emptyList(),
    val amplitudePoints: List<AmplitudePoint> = emptyList(),
    val decodedText: String = "",
    val currentMorseSymbol: String = "",
    val estimatedWpm: Int = 20,
    val hasRecordPermission: Boolean = false,
    val userMessage: String? = null
)
