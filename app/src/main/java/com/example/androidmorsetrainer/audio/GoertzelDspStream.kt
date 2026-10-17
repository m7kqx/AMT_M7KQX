package com.example.androidmorsetrainer.audio

import android.util.Log
import com.example.androidmorsetrainer.morse.DspCharacterStream
import com.example.androidmorsetrainer.morse.DynamicTimingEngine
import com.example.androidmorsetrainer.morse.MorseConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Hardware integration implementation of [DspCharacterStream].
 *
 * Wraps [MorseDSPManager] (handling microphone capture via AudioRecord and real-time Goertzel FFT)
 * and [DynamicTimingEngine] to decode Morse elements and spaces into text tokens and words.
 *
 * Concurrency:
 * - Audio capture runs strictly on [Dispatchers.IO].
 * - Goertzel signal processing and Morse element classification run strictly on [Dispatchers.Default].
 */
open class GoertzelDspStream(
    val dspManager: MorseDSPManager = MorseDSPManager(),
    val timingEngine: DynamicTimingEngine = DynamicTimingEngine(initialWpm = 20),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : DspCharacterStream {

    companion object {
        private const val TAG = "GoertzelDspStream"
    }

    private val _characterFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val characterFlow: Flow<String> = _characterFlow.asSharedFlow()

    private val currentSymbol = StringBuilder()
    private val currentWord = StringBuilder()

    private var lastToneEndTimeMs: Long = 0L
    private var listeningJob: Job? = null
    private var silenceWatchdogJob: Job? = null

    init {
        start()
    }

    /**
     * Starts microphone audio capture and Goertzel tone processing on background threads.
     */
    @Synchronized
    open fun start() {
        if (listeningJob?.isActive == true) return

        dspManager.startListening()

        listeningJob = scope.launch(defaultDispatcher) {
            dspManager.toneEvents.collect { event ->
                if (event.isTonePresent) {
                    onKeyToneStarted(event.timestampMs)
                } else {
                    onKeyToneEnded(event.durationMs, event.timestampMs)
                }
            }
        }
    }

    /**
     * Key-down handler: Triggered when a tone starts.
     * Evaluates preceding silence duration using [DynamicTimingEngine.getThresholds]
     * to detect character or word boundaries.
     */
    open fun onKeyToneStarted(timestampMs: Long = System.currentTimeMillis()) {
        silenceWatchdogJob?.cancel()

        if (lastToneEndTimeMs > 0L) {
            val silenceDuration = (timestampMs - lastToneEndTimeMs).coerceAtLeast(0L)
            val thresholds = timingEngine.getThresholds()

            if (thresholds.isWordSpace(silenceDuration)) {
                // Word space crossed
                commitCharacter()
                commitWord()
            } else if (thresholds.isCharacterSpace(silenceDuration)) {
                // Character space crossed
                commitCharacter()
            }
        }
    }

    /**
     * Key-up handler: Triggered when a tone ends.
     * Passes tone duration to [DynamicTimingEngine.recordElement], classifies dit/dah,
     * and sets up a watchdog timer for trailing silence.
     */
    open fun onKeyToneEnded(durationMs: Long, timestampMs: Long = System.currentTimeMillis()) {
        if (durationMs < DynamicTimingEngine.MIN_VALID_PULSE_MS) return

        lastToneEndTimeMs = timestampMs

        // 1. Pass duration to DynamicTimingEngine (outlier-filtered rolling dit update)
        timingEngine.recordElement(durationMs)

        // 2. Classify tone as dit or dah using thresholds
        val thresholds = timingEngine.getThresholds()
        val symbol = if (thresholds.isDit(durationMs)) '.' else '-'
        currentSymbol.append(symbol)

        // 3. Start silence watchdog for trailing character or word completion
        startSilenceWatchdog()
    }

    private fun startSilenceWatchdog() {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = scope.launch(defaultDispatcher) {
            val thresholds = timingEngine.getThresholds()

            // Wait until character boundary threshold
            delay(thresholds.charSpaceThresholdMs.toLong())
            commitCharacter()

            // Wait for word boundary threshold
            val remainingWordDelay = (thresholds.wordSpaceThresholdMs - thresholds.charSpaceThresholdMs).toLong()
            if (remainingWordDelay > 0) {
                delay(remainingWordDelay)
                commitWord()
            }
        }
    }

    private fun commitCharacter() {
        if (currentSymbol.isEmpty()) return
        val pattern = currentSymbol.toString()
        currentSymbol.clear()

        val char = MorseConstants.REVERSE_MORSE_MAP[pattern] ?: "?"
        currentWord.append(char)
        Log.d(TAG, "Decoded character '$char' from pattern '$pattern'")
    }

    private fun commitWord() {
        commitCharacter()
        if (currentWord.isEmpty()) return
        val word = currentWord.toString().trim()
        currentWord.clear()

        if (word.isNotEmpty()) {
            Log.d(TAG, "Decoded word committed: '$word'")
            _characterFlow.tryEmit(word)
        }
    }

    /**
     * Stops DSP tone processing and releases AudioRecord hardware resources.
     */
    @Synchronized
    open fun stop() {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        listeningJob?.cancel()
        listeningJob = null
        dspManager.stopListening()
        currentSymbol.clear()
        currentWord.clear()
        lastToneEndTimeMs = 0L
        Log.d(TAG, "GoertzelDspStream stopped and microphone capture released")
    }

    /**
     * Allows manual injection of Morse tokens into the stream for testing or simulation.
     */
    open fun emitManual(input: String) {
        val trimmed = input.trim()
        if (trimmed.isNotEmpty()) {
            _characterFlow.tryEmit(trimmed)
        }
    }

    /**
     * Exposes current in-progress symbol for testing and verification.
     */
    fun getCurrentSymbol(): String = currentSymbol.toString()

    /**
     * Exposes current in-progress word for testing and verification.
     */
    fun getCurrentWord(): String = currentWord.toString()
}
