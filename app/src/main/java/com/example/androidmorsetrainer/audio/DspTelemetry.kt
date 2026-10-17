package com.example.androidmorsetrainer.audio

/**
 * Real-time Goertzel DSP telemetry data for hardware calibration and timing diagnostics.
 *
 * @property currentMagnitude The current signal energy magnitude detected at the target CW frequency.
 * @property lastToneDurationMs The duration in milliseconds of the most recent tone element (dit or dah).
 * @property lastSilenceDurationMs The duration in milliseconds of the most recent silence interval.
 * @property currentDitAverageMs The dynamic rolling average of a single 'dit' unit in milliseconds.
 * @property noiseFloorThreshold The detection squelch threshold required to register a tone.
 */
data class DspTelemetry(
    val currentMagnitude: Float = 0f,
    val lastToneDurationMs: Long = 0L,
    val lastSilenceDurationMs: Long = 0L,
    val currentDitAverageMs: Float = 60f,
    val noiseFloorThreshold: Float = 0.05f,
    val currentElementBuffer: String = ""
)
