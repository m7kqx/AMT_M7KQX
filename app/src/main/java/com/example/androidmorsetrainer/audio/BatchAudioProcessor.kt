package com.example.androidmorsetrainer.audio

import android.util.Log
import com.example.androidmorsetrainer.morse.MorseConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Batch DSP audio decoder that analyzes completely recorded PCM audio buffers.
 * Runs Goertzel tone analysis as fast as the CPU allows, contextually tunes
 * a definitive noise floor and locked WPM average across the entire transmission,
 * and decodes the Morse code into a final translated string.
 */
class BatchAudioProcessor(
    val sampleRate: Int = 44100,
    val targetFrequencyHz: Double = 700.0,
    val blockSize: Int = 512,
    val manualNoiseFloorThreshold: Float? = null
) {
    companion object {
        private const val TAG = "BatchAudioProcessor"
        private const val MIN_SPECTRAL_PURITY = 0.15
        private const val MIN_VALID_PULSE_MS = 20L
    }

    /**
     * Processes a completely recorded raw 16-bit PCM (little-endian mono) audio byte buffer.
     *
     * @param pcmBytes Raw PCM byte array.
     * @return Decoded Morse string, or empty string if no valid CW transmission is detected.
     */
    fun process(pcmBytes: ByteArray): String {
        if (pcmBytes.size < blockSize * 2) return ""

        val sampleCount = pcmBytes.size / 2
        val samples = ShortArray(sampleCount)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        return process(samples)
    }

    /**
     * Processes an array of 16-bit PCM audio samples.
     */
    fun process(samples: ShortArray): String {
        val blockCount = samples.size / blockSize
        if (blockCount == 0) return ""

        val detector = GoertzelDetector(
            sampleRate = sampleRate,
            targetFrequencyHz = targetFrequencyHz,
            blockSize = blockSize,
            squelchThreshold = 0.0
        )

        val blockDurationMs = (blockSize * 1000.0) / sampleRate
        val magnitudes = DoubleArray(blockCount)
        val purities = DoubleArray(blockCount)
        val blockBuffer = ShortArray(blockSize)

        for (b in 0 until blockCount) {
            System.arraycopy(samples, b * blockSize, blockBuffer, 0, blockSize)
            val result = detector.process(blockBuffer)
            magnitudes[b] = result.targetMagnitude
            purities[b] = result.spectralPurity
        }

        // 1. Contextual Tuning: Definitive Noise Floor Calculation
        val sortedMags = magnitudes.sorted()
        // Ambient noise baseline: 20th percentile samples silence and inter-element gaps reliably
        val ambientNoise = sortedMags[(sortedMags.size * 0.20).toInt()]
        val peakMagnitude = sortedMags.last()

        if (peakMagnitude < 0.02) {
            Log.d(TAG, "No detectable CW tone energy in recorded buffer (Peak: $peakMagnitude)")
            return ""
        }

        val dynamicThreshold = max(ambientNoise * 2.2, ambientNoise + (peakMagnitude - ambientNoise) * 0.35)
        val squelch = manualNoiseFloorThreshold?.toDouble() ?: dynamicThreshold

        Log.d(TAG, "Batch analysis: ambientNoise=%.4f, peak=%.4f, squelch=%.4f".format(ambientNoise, peakMagnitude, squelch))

        // 2. Pulse and Gap Segmentation
        data class Span(val isTone: Boolean, val durationMs: Long)

        val rawSpans = mutableListOf<Span>()
        var currentIsTone = magnitudes[0] >= squelch && purities[0] >= MIN_SPECTRAL_PURITY
        var currentSpanBlocks = 0

        for (b in 0 until blockCount) {
            val isTone = magnitudes[b] >= squelch && purities[b] >= MIN_SPECTRAL_PURITY
            if (isTone == currentIsTone) {
                currentSpanBlocks++
            } else {
                rawSpans.add(Span(currentIsTone, (currentSpanBlocks * blockDurationMs).toLong()))
                currentIsTone = isTone
                currentSpanBlocks = 1
            }
        }
        if (currentSpanBlocks > 0) {
            rawSpans.add(Span(currentIsTone, (currentSpanBlocks * blockDurationMs).toLong()))
        }

        // Filter transient glitches (< 20ms)
        val filteredSpans = mutableListOf<Span>()
        for (span in rawSpans) {
            if (filteredSpans.isEmpty()) {
                filteredSpans.add(span)
            } else {
                val prev = filteredSpans.last()
                if (span.durationMs < MIN_VALID_PULSE_MS && span.isTone != prev.isTone) {
                    filteredSpans[filteredSpans.lastIndex] = prev.copy(durationMs = prev.durationMs + span.durationMs)
                } else if (span.isTone == prev.isTone) {
                    filteredSpans[filteredSpans.lastIndex] = prev.copy(durationMs = prev.durationMs + span.durationMs)
                } else {
                    filteredSpans.add(span)
                }
            }
        }

        // 3. Contextual Tuning: Locked WPM Average
        val toneDurations = filteredSpans.filter { it.isTone && it.durationMs >= MIN_VALID_PULSE_MS }.map { it.durationMs }
        if (toneDurations.isEmpty()) {
            Log.d(TAG, "No valid CW tone pulses found in recording")
            return ""
        }

        val sortedTones = toneDurations.sorted()
        val minTone = sortedTones.first().toDouble()
        val maxTone = sortedTones.last().toDouble()

        val lockedDitMs: Double
        val ditDahBoundary: Double

        if (maxTone / minTone >= 1.7) {
            val split = minTone * 1.8
            val dits = toneDurations.filter { it < split }
            val dahs = toneDurations.filter { it >= split }
            val ditAvg = if (dits.isNotEmpty()) dits.average() else dahs.average() / 3.0
            val dahAvg = if (dahs.isNotEmpty()) dahs.average() else ditAvg * 3.0
            lockedDitMs = ditAvg.coerceIn(25.0, 240.0)
            ditDahBoundary = (ditAvg + dahAvg) / 2.0
        } else {
            val avg = sortedTones.average()
            if (avg > 140.0) {
                lockedDitMs = (avg / 3.0).coerceIn(25.0, 240.0)
                ditDahBoundary = lockedDitMs * 1.8
            } else {
                lockedDitMs = avg.coerceIn(25.0, 240.0)
                ditDahBoundary = lockedDitMs * 1.8
            }
        }

        val lockedWpm = (1200.0 / lockedDitMs).roundToInt()
        Log.d(TAG, "Locked timing: dit=${lockedDitMs.roundToInt()}ms ($lockedWpm WPM), boundary=${ditDahBoundary.roundToInt()}ms")

        // 4. Decode Morse Elements, Characters, and Words
        var startIndex = 0
        while (startIndex < filteredSpans.size && !filteredSpans[startIndex].isTone) {
            startIndex++
        }
        var endIndex = filteredSpans.size - 1
        while (endIndex >= 0 && !filteredSpans[endIndex].isTone) {
            endIndex--
        }

        if (startIndex > endIndex) return ""

        val resultText = StringBuilder()
        val currentWord = StringBuilder()
        val currentSymbol = StringBuilder()

        fun commitCharacter() {
            if (currentSymbol.isNotEmpty()) {
                val pattern = currentSymbol.toString()
                currentSymbol.clear()
                val ch = MorseConstants.REVERSE_MORSE_MAP[pattern] ?: "?"
                currentWord.append(ch)
            }
        }

        fun commitWord() {
            commitCharacter()
            if (currentWord.isNotEmpty()) {
                if (resultText.isNotEmpty()) resultText.append(" ")
                resultText.append(currentWord)
                currentWord.clear()
            }
        }

        for (i in startIndex..endIndex) {
            val span = filteredSpans[i]
            if (span.isTone) {
                val symbol = if (span.durationMs < ditDahBoundary) '.' else '-'
                currentSymbol.append(symbol)
            } else {
                if (span.durationMs >= lockedDitMs * 4.2) {
                    commitWord()
                } else if (span.durationMs >= lockedDitMs * 1.6) {
                    commitCharacter()
                }
            }
        }

        commitWord()
        val decoded = resultText.toString().trim()
        Log.d(TAG, "Batch decoding result: '$decoded'")
        return decoded
    }
}
