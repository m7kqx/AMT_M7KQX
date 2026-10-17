package com.example.androidmorsetrainer.morse

/**
 * Encapsulates Morse code timing thresholds and decision boundaries calculated with ±35% tolerances
 * based on the current rolling dit duration.
 */
data class MorseTimingThresholds(
    val ditDurationMs: Double,
    val ditMinMs: Double = ditDurationMs * 0.65,
    val ditMaxMs: Double = ditDurationMs * 1.35,
    val dahMinMs: Double = ditDurationMs * 1.95,
    val dahMaxMs: Double = ditDurationMs * 4.05,
    val charSpaceThresholdMs: Double = ditDurationMs * 1.8,
    val wordSpaceThresholdMs: Double = ditDurationMs * 4.5
) {
    val ditDahBoundaryMs: Double get() = ditDurationMs * 1.65

    fun isDit(durationMs: Long): Boolean = durationMs.toDouble() < ditDahBoundaryMs
    fun isDah(durationMs: Long): Boolean = durationMs.toDouble() >= ditDahBoundaryMs
    fun isCharacterSpace(silenceMs: Long): Boolean =
        silenceMs.toDouble() >= charSpaceThresholdMs && silenceMs.toDouble() < wordSpaceThresholdMs
    fun isWordSpace(silenceMs: Long): Boolean = silenceMs.toDouble() >= wordSpaceThresholdMs
}

/**
 * Adaptive Morse timing engine that maintains a rolling average of 'dit' durations
 * and computes dynamic ±35% timing thresholds for element and gap classification.
 *
 * Implements strict outlier filtering to prevent abnormally long dahs (e.g., > 4.05x dit)
 * or transient noise from skewing the timing model.
 */
class DynamicTimingEngine(
    initialWpm: Int = 20,
    val minDitMs: Double = 25.0,  // ~48 WPM upper speed limit
    val maxDitMs: Double = 240.0 // ~5 WPM lower speed limit
) {
    companion object {
        const val MIN_VALID_PULSE_MS = 15L
        private const val MAX_HISTORY_SAMPLES = 16
    }

    var rollingDitMs: Double = (1200.0 / initialWpm).coerceIn(minDitMs, maxDitMs)
        private set

    private val samples = ArrayDeque<Double>()

    init {
        reset(initialWpm)
    }

    /**
     * Returns the current dynamic timing thresholds based on the rolling dit duration.
     */
    fun getThresholds(): MorseTimingThresholds {
        return MorseTimingThresholds(ditDurationMs = rollingDitMs)
    }

    /**
     * Records an active tone pulse (key-up) and updates the rolling dit average.
     * Filters out transient clicks (< 15ms) and long dah outliers (> dahMaxMs)
     * to protect the stability of the timing model.
     *
     * @param durationMs Length of the tone pulse in milliseconds.
     */
    fun recordElement(durationMs: Long) {
        if (durationMs < MIN_VALID_PULSE_MS) return

        val thresholds = getThresholds()

        // Constraint: Filter out outlier durations (like long dahs) so they do not skew the rolling dit average
        if (durationMs > thresholds.dahMaxMs) {
            return
        }

        // Normalize element duration to equivalent dit unit
        val equivalentDit = if (thresholds.isDah(durationMs)) {
            durationMs / 3.0
        } else {
            durationMs.toDouble()
        }

        if (equivalentDit in minDitMs..maxDitMs) {
            samples.addLast(equivalentDit)
            if (samples.size > MAX_HISTORY_SAMPLES) {
                samples.removeFirst()
            }
            rollingDitMs = samples.average().coerceIn(minDitMs, maxDitMs)
        }
    }

    /**
     * Resets the timing engine to a specified WPM target.
     */
    fun reset(wpm: Int = 20) {
        rollingDitMs = (1200.0 / wpm).coerceIn(minDitMs, maxDitMs)
        samples.clear()
        repeat(4) { samples.add(rollingDitMs) }
    }
}
