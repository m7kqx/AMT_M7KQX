package com.example.androidmorsetrainer.morse

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Adaptive Continuous Wave (CW) Morse Code Decoder.
 * Dynamically adapts to sending speed (WPM) by maintaining a rolling average of dit unit lengths.
 * Differentiates dits (1x), dahs (3x), element spaces (1x), character spaces (3x), and word spaces (7x)
 * in accordance with standard ITU/PARIS Morse specifications.
 */
class CwDecoder(
    initialWpm: Int = 20,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CwDecoder"
        const val MIN_DIT_MS = 25.0   // ~48 WPM upper speed limit
        const val MAX_DIT_MS = 240.0  // ~5 WPM lower speed limit
        const val MIN_VALID_TONE_MS = 15L // Disregard transient microphone clicks
    }

    var ditDurationMs: Double = (1200.0 / initialWpm).coerceIn(MIN_DIT_MS, MAX_DIT_MS)
        private set

    private val _decodedText = MutableStateFlow("")
    val decodedText: StateFlow<String> = _decodedText.asStateFlow()

    private val _currentSymbol = MutableStateFlow("")
    val currentSymbol: StateFlow<String> = _currentSymbol.asStateFlow()

    private val _estimatedWpm = MutableStateFlow(initialWpm)
    val estimatedWpm: StateFlow<Int> = _estimatedWpm.asStateFlow()

    private var silenceJob: Job? = null

    /**
     * Called when a tone begins (onset). Cancels pending silence timeouts to avoid premature commits.
     */
    fun onToneStarted() {
        silenceJob?.cancel()
    }

    /**
     * Called when a tone finishes (offset). Classifies the pulse as a Dit or Dah,
     * adapts the rolling WPM timing, and sets a watchdog timer for character/word spacing.
     */
    fun onToneCompleted(durationMs: Long) {
        if (durationMs < MIN_VALID_TONE_MS) {
            Log.d(TAG, "Ignored transient glitch pulse (${durationMs}ms)")
            return
        }

        silenceJob?.cancel()

        // Geometric boundary between dit (1x) and dah (3x) is approximately 1.8x
        val ditDahBoundary = ditDurationMs * 1.8
        val isDah = durationMs >= ditDahBoundary
        val element = if (isDah) '-' else '.'

        _currentSymbol.update { it + element }

        // Dynamic WPM adaptation using exponential rolling average
        val observedDitLength = if (isDah) durationMs / 3.0 else durationMs.toDouble()
        ditDurationMs = (ditDurationMs * 0.82 + observedDitLength * 0.18).coerceIn(MIN_DIT_MS, MAX_DIT_MS)
        val currentWpm = (1200.0 / ditDurationMs).roundToInt()
        _estimatedWpm.value = currentWpm

        Log.d(
            TAG,
            "Tone: ${durationMs}ms -> '$element', rollingDit=${ditDurationMs.roundToInt()}ms, WPM=$currentWpm, currentPattern='${_currentSymbol.value}'"
        )

        startSilenceWatchdog()
    }

    private fun startSilenceWatchdog() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            // Inter-character space standard is 3x. Commit character at ~2.2x to handle loose spacing
            val charSpaceDelay = (ditDurationMs * 2.2).toLong()
            delay(charSpaceDelay)

            commitCurrentCharacter()

            // Word space standard is 7x. Additional delay to reach ~5.5x
            val additionalWordSpaceDelay = (ditDurationMs * 3.3).toLong()
            delay(additionalWordSpaceDelay)

            commitWordSpace()
        }
    }

    private fun commitCurrentCharacter() {
        val symbol = _currentSymbol.value
        if (symbol.isEmpty()) return

        val char = MorseConstants.REVERSE_MORSE_MAP[symbol] ?: "?"
        Log.d(TAG, "Decoded Character: '$char' (Pattern: '$symbol')")

        _decodedText.update { it + char }
        _currentSymbol.value = ""
    }

    private fun commitWordSpace() {
        _decodedText.update { current ->
            if (current.isNotEmpty() && !current.endsWith(" ")) {
                Log.d(TAG, "Decoded Word Space")
                "$current "
            } else {
                current
            }
        }
    }

    /**
     * Clears decoded message buffer and in-progress symbols.
     */
    fun clear() {
        silenceJob?.cancel()
        _currentSymbol.value = ""
        _decodedText.value = ""
    }
}
