package com.example.androidmorsetrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.androidmorsetrainer.morse.MorseConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Audio generator using the Android AudioTrack API to synthesize clean 16-bit PCM sine wave tones
 * for Morse code playback without audio clicks or UI thread blocking.
 */
open class MorseAudioGenerator(
    val sampleRate: Int = 44100,
    var frequencyHz: Int = 700,
    wpm: Int = 20,
    var farnsworthWpm: Int? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var _wpm: Int = wpm
    val wpm: Int
        get() = _wpm

    companion object {
        private const val TAG = "MorseAudioGenerator"
        private const val AMPLITUDE_FACTOR = 0.8
        private const val RAMP_DURATION_SEC = 0.005 // 5ms raised cosine attack/decay to eliminate key clicks
    }

    private var audioTrack: AudioTrack? = null
    private val isStopping = AtomicBoolean(false)

    private val _isPlaying = MutableStateFlow(false)
    open val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    init {
        try {
            initAudioTrack()
        } catch (_: Throwable) {
            // AudioTrack unavailable in host JVM unit test environment
        }
    }

    @Synchronized
    private fun initAudioTrack() {
        if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBufferSize.coerceAtLeast(sampleRate / 4) // ~250ms buffer

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        Log.d(TAG, "AudioTrack initialized: sampleRate=$sampleRate, frequency=$frequencyHz, bufferSize=$bufferSize")
    }

    private var currentPhase: Double = 0.0

    /**
     * Synthesizes a sine wave PCM buffer of specified duration with raised-cosine (Hann) envelope
     * on the leading and trailing edges to prevent click artifacts.
     */
    fun generateToneBuffer(durationMs: Long, freq: Int = frequencyHz): ShortArray {
        val totalSamples = ((sampleRate * durationMs) / 1000.0).toInt().coerceAtLeast(1)
        val buffer = ShortArray(totalSamples)

        val rampSamples = min(
            (sampleRate * RAMP_DURATION_SEC).toInt(),
            totalSamples / 2
        )

        val twoPiF = 2.0 * Math.PI * freq
        val phaseIncrement = twoPiF / sampleRate
        val maxAmp = (Short.MAX_VALUE * AMPLITUDE_FACTOR).toInt()

        for (i in 0 until totalSamples) {
            val sineVal = sin(currentPhase)
            currentPhase += phaseIncrement
            if (currentPhase >= 2.0 * Math.PI) {
                currentPhase -= 2.0 * Math.PI
            }

            // Hann / Raised Cosine window for smooth attack and decay
            val envelope = when {
                rampSamples > 0 && i < rampSamples -> {
                    0.5 * (1.0 - cos(Math.PI * i / rampSamples))
                }
                rampSamples > 0 && i >= totalSamples - rampSamples -> {
                    val decayIndex = i - (totalSamples - rampSamples)
                    0.5 * (1.0 + cos(Math.PI * decayIndex / rampSamples))
                }
                else -> 1.0
            }

            buffer[i] = (maxAmp * envelope * sineVal).toInt().toShort()
        }

        return buffer
    }

    /**
     * Synthesizes a zero-amplitude PCM buffer of specified duration.
     */
    fun generateSilenceBuffer(durationMs: Long): ShortArray {
        val totalSamples = ((sampleRate * durationMs) / 1000.0).toInt().coerceAtLeast(1)
        val phaseIncrement = 2.0 * Math.PI * frequencyHz / sampleRate
        currentPhase = (currentPhase + phaseIncrement * totalSamples) % (2.0 * Math.PI)
        return ShortArray(totalSamples)
    }

    /**
     * Plays a continuous sine wave tone for the given duration in milliseconds.
     */
    open suspend fun playTone(durationMs: Long) = withContext(defaultDispatcher) {
        if (durationMs <= 0) return@withContext
        val buffer = generateToneBuffer(durationMs, frequencyHz)
        writePcmBuffer(buffer)
    }

    /**
     * Plays silence for the given duration in milliseconds.
     */
    open suspend fun playSilence(durationMs: Long) = withContext(defaultDispatcher) {
        if (durationMs <= 0) return@withContext
        val buffer = generateSilenceBuffer(durationMs)
        writePcmBuffer(buffer)
    }

    /**
     * Plays a single Dit (1 unit duration at current WPM).
     */
    open suspend fun playDit() {
        val ditDuration = MorseConstants.calculateUnitDurationMs(wpm)
        playTone(ditDuration)
    }

    /**
     * Plays a single Dah (3 unit durations at current WPM).
     */
    open suspend fun playDah() {
        val dahDuration = MorseConstants.calculateDahDurationMs(wpm)
        playTone(dahDuration)
    }

    /**
     * Converts a bracketed prosign (e.g., "<SK>", "<AR>") into a single continuous sequence
     * of dits and dahs without spaces, so it can be transmitted with only 1-unit intra-character
     * pauses between elements and bypassing the 3-unit inter-character pause.
     */
    fun getProsignMorsePattern(prosign: String): String? {
        val trimmed = prosign.trim()
        if (!trimmed.startsWith("<") || !trimmed.endsWith(">") || trimmed.length < 3) {
            return null
        }
        val inner = trimmed.substring(1, trimmed.length - 1).uppercase()
        val sb = StringBuilder()
        for (ch in inner) {
            val morse = MorseConstants.MORSE_MAP[ch.toString()] ?: return null
            sb.append(morse.replace(" ", ""))
        }
        return sb.toString()
    }

    /**
     * Tokenizes an input string into bracketed prosigns (e.g. "<SK>"), words/letters, and spaces.
     */
    fun tokenizeInputString(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val regex = Regex("""<[A-Za-z0-9]+>|\s+|\S""")
        val matches = regex.findAll(input)
        for (match in matches) {
            val value = match.value
            if (value.isBlank()) {
                tokens.add(" ")
            } else {
                tokens.add(value)
            }
        }
        return tokens
    }

    /**
     * Plays a Morse pattern of dots and dashes (e.g. ".-" or space-separated "-.-. --.-").
     * Applies standard timing:
     * - Dit duration = 1 unit (DIT_MULTIPLIER = 1)
     * - Dah duration = 3 units (DAH_MULTIPLIER = 3)
     * - Intra-character element spaces = 1 unit (INTRA_CHARACTER_SPACE_MULTIPLIER = 1)
     * - Inter-character spaces between letter parts = 3 units (INTER_CHARACTER_SPACE_MULTIPLIER = 3)
     * - Word space between words = 7 units (WORD_SPACE_MULTIPLIER = 7)
     */
    suspend fun playMorsePattern(pattern: String) = withContext(defaultDispatcher) {
        val elementSpace = MorseConstants.calculateElementSpaceMs(wpm)
        val charSpace = MorseConstants.calculateCharacterSpaceMs(wpm, farnsworthWpm)
        val wordSpace = MorseConstants.calculateWordSpaceMs(wpm, farnsworthWpm)
        val parts = pattern.split(" ")
        for (p in parts.indices) {
            if (isStopping.get() || !isActive) break
            val part = parts[p]
            if (part == "/" || part.isEmpty()) {
                playSilence(wordSpace)
                continue
            }
            for (i in part.indices) {
                if (isStopping.get() || !isActive) break

                when (part[i]) {
                    '.' -> playDit()
                    '-' -> playDah()
                }

                // Intra-character space between elements (except after the final element of this part)
                if (i < part.length - 1) {
                    playSilence(elementSpace)
                }
            }

            // Inter-character space between parts
            if (p < parts.size - 1 && parts[p + 1] != "/" && parts[p + 1].isNotEmpty()) {
                playSilence(charSpace)
            }
        }
    }

    private val playbackMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Updates the playback speed in Words Per Minute (WPM) dynamically.
     * Recalculates dot, dash, and inter-character spacing durations based on standard Morse timing rules.
     */
    open fun setWpm(newWpm: Int) {
        val clamped = newWpm.coerceIn(10, 25)
        if (this._wpm != clamped) {
            this._wpm = clamped
            Log.d(TAG, "MorseAudioGenerator WPM set to $clamped (dit=${MorseConstants.calculateUnitDurationMs(clamped)}ms, dah=${MorseConstants.calculateDahDurationMs(clamped)}ms)")
        }
    }

    /**
     * Plays a character or prosign (e.g. "K", "A", or "<BT>") with optional WPM speed override.
     */
    open suspend fun playCharacter(character: String, speedWpm: Int?) {
        speedWpm?.let { setWpm(it) }
        playCharacter(character)
    }

    /**
     * Plays a character or prosign (e.g. "K", "A", or "<BT>").
     */
    open suspend fun playCharacter(character: String) = withContext(defaultDispatcher) {
        playbackMutex.withLock {
            stop()
            isStopping.set(false)
            _isPlaying.value = true
            currentPhase = 0.0
            try {
                playCharacterInternal(character)
                // Pad with silence to guarantee we exceed minBufferSize, forcing immediate hardware playback
                // and ensuring the coroutine blocks until the tone has physically rendered.
                playSilence(400)
            } finally {
                _isPlaying.value = false
            }
        }
    }

    private suspend fun playCharacterInternal(character: String) {
        val trimmed = character.trim()

        if (character == " " || (character.isNotEmpty() && character.isBlank())) {
            // Space in the input string triggers the 7-unit silence delay (WORD_SPACE_MULTIPLIER)
            playSilence(MorseConstants.calculateWordSpaceMs(wpm, farnsworthWpm))
            return
        }

        if (character.contains(" ")) {
            val tokens = tokenizeInputString(character)
            val charSpace = MorseConstants.calculateCharacterSpaceMs(wpm, farnsworthWpm)
            val wordSpace = MorseConstants.calculateWordSpaceMs(wpm, farnsworthWpm)
            for (i in tokens.indices) {
                if (isStopping.get() || !kotlin.coroutines.coroutineContext.isActive) break
                val tok = tokens[i]
                if (tok == " ") {
                    playSilence(wordSpace)
                } else {
                    playCharacterInternal(tok)
                    if (i < tokens.size - 1 && tokens[i + 1] != " ") {
                        playSilence(charSpace)
                    }
                }
            }
            return
        }

        // Bracketed prosign (e.g., <SK>, <AR>): concatenated into a single continuous sequence
        // of dits and dahs, applying only the 1-unit intra-character pause between elements
        // and bypassing the 3-unit inter-character pause.
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length > 2) {
            val prosignPattern = getProsignMorsePattern(trimmed)
                ?: MorseConstants.MORSE_MAP[trimmed.uppercase()]?.replace(" ", "")
            if (prosignPattern != null) {
                playMorsePattern(prosignPattern)
                return
            }
        }

        val upper = trimmed.uppercase()
        val directPattern = MorseConstants.MORSE_MAP[upper]
        if (directPattern != null) {
            playMorsePattern(directPattern)
            return
        }

        // Parse multi-character sequence / abbreviation if not directly mapped
        val charSpace = MorseConstants.calculateCharacterSpaceMs(wpm, farnsworthWpm)
        val clean = upper.replace("<", "").replace(">", "")
        val parts = clean.mapNotNull { MorseConstants.MORSE_MAP[it.toString()] }
        if (parts.isNotEmpty()) {
            for (i in parts.indices) {
                if (isStopping.get() || !kotlin.coroutines.coroutineContext.isActive) break
                playMorsePattern(parts[i])
                if (i < parts.size - 1) {
                    playSilence(charSpace)
                }
            }
        }
    }

    /**
     * Plays a complete sequence of characters or words with standard or Farnsworth spacing.
     * Invokes [onCharacterPlayed] before each character is sounded.
     */
    suspend fun playSequence(
        characters: List<String>,
        onCharacterPlayed: ((String) -> Unit)? = null
    ) = withContext(defaultDispatcher) {
        playbackMutex.withLock {
            stop()
            isStopping.set(false)
            _isPlaying.value = true
            currentPhase = 0.0

            try {
                val charSpace = MorseConstants.calculateCharacterSpaceMs(wpm, farnsworthWpm)
                val wordSpace = MorseConstants.calculateWordSpaceMs(wpm, farnsworthWpm)

                for (i in characters.indices) {
                    if (isStopping.get() || !isActive) break

                    val char = characters[i]
                    if (char == " " || char.isBlank()) {
                        playSilence(wordSpace)
                        continue
                    }

                    onCharacterPlayed?.invoke(char)
                    playCharacterInternal(char)

                    if (i < characters.size - 1 && characters[i + 1] != " " && characters[i + 1].isNotBlank()) {
                        playSilence(charSpace)
                    }
                }
                if (!isStopping.get() && isActive) {
                    // Pad with silence to guarantee we exceed minBufferSize, forcing immediate hardware playback
                    playSilence(400)
                }
            } finally {
                _isPlaying.value = false
            }
        }
    }

    /**
     * Plays a complete string of Morse characters, words, and bracketed prosigns (e.g. "CQ DE <SK>").
     * Spaces in the input string trigger the standard 7-unit silence delay.
     * Bracketed prosigns are concatenated into a single continuous sequence bypassing the 3-unit pause.
     */
    open suspend fun playString(
        input: String,
        onCharacterPlayed: ((String) -> Unit)? = null
    ) {
        val tokens = tokenizeInputString(input)
        playSequence(tokens, onCharacterPlayed)
    }

    private suspend fun writePcmBuffer(buffer: ShortArray) = withContext(defaultDispatcher) {
        if (isStopping.get() || !isActive) return@withContext

        synchronized(this@MorseAudioGenerator) {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack()
            }
        }

        val track = audioTrack ?: return@withContext
        var written = 0
        while (written < buffer.size && !isStopping.get() && isActive) {
            val count = track.write(buffer, written, buffer.size - written)
            if (count < 0) {
                Log.e(TAG, "AudioTrack write error: $count")
                break
            }
            written += count
        }
    }

    /**
     * Stops current playback immediately and flushes the audio track.
     */
    open fun stop() {
        isStopping.set(true)
        _isPlaying.value = false
        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.pause()
                    it.flush()
                    it.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
        }
    }

    /**
     * Releases system AudioTrack resources.
     */
    open fun release() {
        stop()
        synchronized(this) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }
        }
    }
}
