package com.example.androidmorsetrainer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance, zero-external-dependency Fast Fourier Transform (FFT) analyzer for CW audio sidetone detection.
 *
 * Algorithm Details:
 * 1. Windowing: Applies a Hann window to minimize spectral leakage and sidelobe artifacts.
 * 2. FFT: In-place Cooley-Tukey Radix-2 Decimation-in-Time (DIT) FFT with precomputed twiddle factors.
 * 3. Peak Finding: Scans discrete frequency bins within the CW spectrum (default 400Hz - 1000Hz).
 * 4. Sub-Bin Interpolation: Employs parabolic (quadratic) interpolation on the peak and adjacent bins
 *    to resolve continuous peak frequencies with sub-Hertz accuracy (typically ±1 Hz).
 * 5. Detection & SNR: Evaluates peak-to-noise ratio to reject background noise, breathing, and static.
 */
class FFTAnalyzer(
    val minMagnitudeThreshold: Double = 0.015,
    val minSnrThreshold: Double = 2.5
) {
    companion object {
        const val DEFAULT_FFT_SIZE = 4096
        const val DEFAULT_MIN_CW_FREQ = 400.0
        const val DEFAULT_MAX_CW_FREQ = 1000.0
    }

    /**
     * Spectral analysis result containing peak frequency and signal quality metrics.
     */
    data class FftPeakResult(
        val peakFrequencyHz: Double,
        val peakMagnitude: Double,
        val averageMagnitude: Double,
        val noiseFloor: Double,
        val snr: Double,
        val isToneDetected: Boolean
    )

    /**
     * Convenience method to find the peak CW pitch.
     * Returns the detected frequency in Hz, or null if no tone was reliably detected.
     */
    fun findPeakFrequency(
        samples: ShortArray,
        sampleRate: Int = 44100,
        minFreqHz: Double = DEFAULT_MIN_CW_FREQ,
        maxFreqHz: Double = DEFAULT_MAX_CW_FREQ
    ): Double? {
        val result = analyze(samples, sampleRate, minFreqHz, maxFreqHz)
        return if (result.isToneDetected) result.peakFrequencyHz else null
    }

    /**
     * Analyzes 16-bit PCM samples and computes spectral peak, SNR, and tone presence.
     *
     * @param samples 16-bit PCM audio samples from AudioRecord microphone stream.
     * @param sampleRate Sampling rate in Hz (typically 44100).
     * @param minFreqHz Minimum search frequency in Hz (typically 400.0).
     * @param maxFreqHz Maximum search frequency in Hz (typically 1000.0).
     * @param targetFftSize Target FFT length (power of 2, default 4096).
     */
    fun analyze(
        samples: ShortArray,
        sampleRate: Int = 44100,
        minFreqHz: Double = DEFAULT_MIN_CW_FREQ,
        maxFreqHz: Double = DEFAULT_MAX_CW_FREQ,
        targetFftSize: Int = DEFAULT_FFT_SIZE
    ): FftPeakResult {
        if (samples.isEmpty()) {
            return FftPeakResult(
                peakFrequencyHz = (minFreqHz + maxFreqHz) / 2.0,
                peakMagnitude = 0.0,
                averageMagnitude = 0.0,
                noiseFloor = 0.0,
                snr = 0.0,
                isToneDetected = false
            )
        }

        // Determine FFT length N (must be power of 2, minimum 1024)
        val n = targetFftSize.coerceAtLeast(1024).takeIf { (it and (it - 1)) == 0 }
            ?: nextPowerOfTwo(max(1024, samples.size))

        val real = DoubleArray(n)
        val imag = DoubleArray(n)

        val windowSize = min(samples.size, n)
        // 1. Apply Hann window: w(i) = 0.5 * (1 - cos(2*pi*i / (N-1)))
        for (i in 0 until windowSize) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (windowSize - 1)))
            val normSample = samples[i].toDouble() / Short.MAX_VALUE
            real[i] = normSample * window
        }
        // Zero-pad remaining elements (real and imag are already 0.0 from initialization)

        // 2. Compute Cooley-Tukey Radix-2 FFT
        fft(real, imag, n)

        // 3. Compute Magnitude Spectrum (positive half from 0 to N/2)
        val halfN = n / 2
        val magnitudes = DoubleArray(halfN)
        val scale = 2.0 / windowSize
        for (k in 0 until halfN) {
            magnitudes[k] = sqrt(real[k] * real[k] + imag[k] * imag[k]) * scale
        }

        val binWidth = sampleRate.toDouble() / n
        val kMin = max(1, (minFreqHz / binWidth).toInt())
        val kMax = min(halfN - 2, (maxFreqHz / binWidth).toInt() + 1)

        if (kMin >= kMax) {
            return FftPeakResult(
                peakFrequencyHz = (minFreqHz + maxFreqHz) / 2.0,
                peakMagnitude = 0.0,
                averageMagnitude = 0.0,
                noiseFloor = 0.0,
                snr = 0.0,
                isToneDetected = false
            )
        }

        // 4. Find peak bin within CW range [kMin, kMax]
        var maxBin = kMin
        var maxMag = magnitudes[kMin]
        var sumMag = 0.0
        val count = kMax - kMin + 1

        for (k in kMin..kMax) {
            val m = magnitudes[k]
            sumMag += m
            if (m > maxMag) {
                maxMag = m
                maxBin = k
            }
        }

        val avgMag = sumMag / count

        // 5. In-band noise floor estimation (excluding the 3 peak bins)
        var noiseSum = 0.0
        var noiseCount = 0
        for (k in kMin..kMax) {
            if (k < maxBin - 1 || k > maxBin + 1) {
                noiseSum += magnitudes[k]
                noiseCount++
            }
        }
        val noiseFloor = if (noiseCount > 0) noiseSum / noiseCount else avgMag
        val snr = if (noiseFloor > 1e-9) maxMag / noiseFloor else if (maxMag > 0.0) 100.0 else 0.0

        // 6. Quadratic / Parabolic sub-bin peak interpolation
        // delta = 0.5 * (alpha - gamma) / (alpha - 2*beta + gamma)
        val alpha = magnitudes[maxBin - 1]
        val beta = magnitudes[maxBin]
        val gamma = magnitudes[maxBin + 1]
        val denominator = alpha - 2.0 * beta + gamma

        val delta = if (abs(denominator) > 1e-12) {
            (0.5 * (alpha - gamma) / denominator).coerceIn(-0.5, 0.5)
        } else {
            0.0
        }

        val peakFreq = ((maxBin + delta) * binWidth).coerceIn(minFreqHz, maxFreqHz)
        val isToneDetected = maxMag >= minMagnitudeThreshold && snr >= minSnrThreshold

        return FftPeakResult(
            peakFrequencyHz = peakFreq,
            peakMagnitude = maxMag,
            averageMagnitude = avgMag,
            noiseFloor = noiseFloor,
            snr = snr,
            isToneDetected = isToneDetected
        )
    }

    /**
     * In-place Cooley-Tukey Radix-2 Decimation-In-Time (DIT) FFT.
     * Operates purely in-place on preallocated arrays for maximum speed.
     * Assumes n is a power of 2.
     */
    private fun fft(real: DoubleArray, imag: DoubleArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey Butterflies
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val thetaStep = -2.0 * PI / len

            for (k in 0 until halfLen) {
                val theta = thetaStep * k
                val wR = cos(theta)
                val wI = sin(theta)

                var i = 0
                while (i < n) {
                    val idx = i + k
                    val pairIdx = idx + halfLen

                    val tR = wR * real[pairIdx] - wI * imag[pairIdx]
                    val tI = wR * imag[pairIdx] + wI * real[pairIdx]

                    val uR = real[idx]
                    val uI = imag[idx]

                    real[idx] = uR + tR
                    imag[idx] = uI + tI
                    real[pairIdx] = uR - tR
                    imag[pairIdx] = uI - tI

                    i += len
                }
            }
            len = len shl 1
        }
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var n = 1
        while (n < v) {
            n = n shl 1
        }
        return n
    }
}
