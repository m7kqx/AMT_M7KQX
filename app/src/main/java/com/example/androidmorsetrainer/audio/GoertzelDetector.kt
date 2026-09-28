package com.example.androidmorsetrainer.audio

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight, high-performance Goertzel algorithm detector for single-frequency sidetone detection.
 * Avoids heavy FFT/DFT libraries by using a second-order IIR Goertzel filter.
 */
class GoertzelDetector(
    val sampleRate: Int = 44100,
    var targetFrequencyHz: Double = 700.0,
    val blockSize: Int = 512
) {
    private var coeff: Double = 0.0

    init {
        updateCoeff()
    }

    /**
     * Updates target frequency (typically between 600Hz and 800Hz for CW sidetones).
     */
    fun setTargetFrequency(frequencyHz: Double) {
        targetFrequencyHz = frequencyHz
        updateCoeff()
    }

    private fun updateCoeff() {
        val omega = 2.0 * Math.PI * (targetFrequencyHz / sampleRate)
        coeff = 2.0 * cos(omega)
    }

    data class DetectionResult(
        val targetMagnitude: Double,
        val totalRms: Double,
        val spectralPurity: Double // Ratio of tone energy to overall energy (0.0 to 1.0)
    )

    /**
     * Processes a block of 16-bit PCM audio samples.
     * Operates purely with basic arithmetic without heap allocations inside the loop.
     */
    fun process(samples: ShortArray, length: Int = samples.size): DetectionResult {
        val n = min(length, blockSize)
        if (n <= 0) return DetectionResult(0.0, 0.0, 0.0)

        var sPrev = 0.0
        var sPrev2 = 0.0
        var totalSumSquares = 0.0

        for (i in 0 until n) {
            val sampleNorm = samples[i].toDouble() / Short.MAX_VALUE
            totalSumSquares += sampleNorm * sampleNorm

            val s = sampleNorm + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }

        // Final Goertzel power calculation: P = sPrev^2 + sPrev2^2 - coeff * sPrev * sPrev2
        val power = sPrev * sPrev + sPrev2 * sPrev2 - coeff * sPrev * sPrev2
        val magnitude = sqrt(max(0.0, power)) / (n / 2.0)
        val totalRms = sqrt(totalSumSquares / n)

        val totalEnergy = totalSumSquares * (n / 2.0)
        val spectralPurity = if (totalEnergy > 1e-6) {
            min(1.0, max(0.0, power / totalEnergy))
        } else {
            0.0
        }

        return DetectionResult(
            targetMagnitude = magnitude,
            totalRms = totalRms,
            spectralPurity = spectralPurity
        )
    }
}
