package com.example.androidmorsetrainer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class FFTAnalyzerTest {

    private lateinit var analyzer: FFTAnalyzer
    private val sampleRate = 44100

    @Before
    fun setUp() {
        analyzer = FFTAnalyzer(
            minMagnitudeThreshold = 0.015,
            minSnrThreshold = 2.5
        )
    }

    /**
     * Helper to generate a 16-bit PCM sine wave of [freqHz] for [numSamples].
     */
    private fun generateSineWave(
        freqHz: Double,
        amplitude: Double = 0.6,
        numSamples: Int = 4096
    ): ShortArray {
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * freqHz * i / sampleRate
            val sampleVal = (amplitude * Short.MAX_VALUE * sin(angle)).toInt()
            buffer[i] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return buffer
    }

    @Test
    fun detectsPureSineWave_at700Hz() {
        val pcm = generateSineWave(freqHz = 700.0, amplitude = 0.5, numSamples = 4096)
        val result = analyzer.analyze(pcm, sampleRate = sampleRate)

        assertTrue("Tone should be detected", result.isToneDetected)
        assertEquals("Frequency should be within 1.5 Hz of 700 Hz", 700.0, result.peakFrequencyHz, 1.5)
        assertTrue("SNR should be high for pure tone", result.snr > 10.0)
        assertTrue("Magnitude should exceed threshold", result.peakMagnitude > 0.05)

        val freq = analyzer.findPeakFrequency(pcm, sampleRate = sampleRate)
        assertNotNull(freq)
        assertEquals(700.0, freq!!, 1.5)
    }

    @Test
    fun detectsPureSineWave_at550Hz() {
        val pcm = generateSineWave(freqHz = 550.0, amplitude = 0.4, numSamples = 4096)
        val result = analyzer.analyze(pcm, sampleRate = sampleRate)

        assertTrue(result.isToneDetected)
        assertEquals(550.0, result.peakFrequencyHz, 1.5)
    }

    @Test
    fun detectsPureSineWave_at850Hz() {
        val pcm = generateSineWave(freqHz = 850.0, amplitude = 0.45, numSamples = 4096)
        val result = analyzer.analyze(pcm, sampleRate = sampleRate)

        assertTrue(result.isToneDetected)
        assertEquals(850.0, result.peakFrequencyHz, 1.5)
    }

    @Test
    fun detectsPureSineWave_atBandEdges() {
        val pcmLow = generateSineWave(freqHz = 420.0, amplitude = 0.5, numSamples = 4096)
        val resultLow = analyzer.analyze(pcmLow, sampleRate = sampleRate)
        assertTrue(resultLow.isToneDetected)
        assertEquals(420.0, resultLow.peakFrequencyHz, 2.0)

        val pcmHigh = generateSineWave(freqHz = 980.0, amplitude = 0.5, numSamples = 4096)
        val resultHigh = analyzer.analyze(pcmHigh, sampleRate = sampleRate)
        assertTrue(resultHigh.isToneDetected)
        assertEquals(980.0, resultHigh.peakFrequencyHz, 2.0)
    }

    @Test
    fun detectsTone_inPresenceOfNoise() {
        val numSamples = 4096
        val targetFreq = 650.0
        val pcm = ShortArray(numSamples)
        val random = Random(42)

        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * targetFreq * i / sampleRate
            val tone = 0.4 * Short.MAX_VALUE * sin(angle)
            val noise = (random.nextDouble() - 0.5) * 0.1 * Short.MAX_VALUE
            val sampleVal = (tone + noise).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = sampleVal.toShort()
        }

        val result = analyzer.analyze(pcm, sampleRate = sampleRate)
        assertTrue(result.isToneDetected)
        assertEquals(650.0, result.peakFrequencyHz, 2.5)
        assertTrue(result.snr > 3.0)
    }

    @Test
    fun rejectsPureSilence() {
        val pcm = ShortArray(4096) { 0 }
        val result = analyzer.analyze(pcm, sampleRate = sampleRate)

        assertFalse("Silence should not be detected as tone", result.isToneDetected)
        assertEquals(0.0, result.peakMagnitude, 1e-6)
        assertNull(analyzer.findPeakFrequency(pcm, sampleRate = sampleRate))
    }

    @Test
    fun rejectsLowAmplitudeAmbientNoise() {
        val random = Random(123)
        // Noise below detection threshold (magnitude ~ 0.005)
        val pcm = ShortArray(4096) {
            ((random.nextDouble() - 0.5) * 0.005 * Short.MAX_VALUE).toInt().toShort()
        }
        val result = analyzer.analyze(pcm, sampleRate = sampleRate)

        assertFalse("Low noise floor should not trigger tone detection", result.isToneDetected)
        assertNull(analyzer.findPeakFrequency(pcm, sampleRate = sampleRate))
    }

    @Test
    fun rejectsOutOfBandTones() {
        // 200 Hz tone (below 400 Hz CW band)
        val pcmLow = generateSineWave(freqHz = 200.0, amplitude = 0.5, numSamples = 4096)
        val resultLow = analyzer.analyze(pcmLow, sampleRate = sampleRate)
        assertFalse("200 Hz is below CW band", resultLow.isToneDetected)

        // 2500 Hz tone (above 1000 Hz CW band)
        val pcmHigh = generateSineWave(freqHz = 2500.0, amplitude = 0.5, numSamples = 4096)
        val resultHigh = analyzer.analyze(pcmHigh, sampleRate = sampleRate)
        assertFalse("2500 Hz is above CW band", resultHigh.isToneDetected)
    }

    @Test
    fun handlesDifferentBufferSizes() {
        // 2048 samples
        val pcm2048 = generateSineWave(freqHz = 730.0, amplitude = 0.5, numSamples = 2048)
        val result2048 = analyzer.analyze(pcm2048, sampleRate = sampleRate)
        assertTrue(result2048.isToneDetected)
        assertEquals(730.0, result2048.peakFrequencyHz, 2.5)

        // 1024 samples
        val pcm1024 = generateSineWave(freqHz = 730.0, amplitude = 0.5, numSamples = 1024)
        val result1024 = analyzer.analyze(pcm1024, sampleRate = sampleRate)
        assertTrue(result1024.isToneDetected)
        assertEquals(730.0, result1024.peakFrequencyHz, 4.0)
    }

    @Test
    fun handlesEmptyBufferGracefully() {
        val result = analyzer.analyze(ShortArray(0), sampleRate = sampleRate)
        assertFalse(result.isToneDetected)
        assertEquals(0.0, result.peakMagnitude, 1e-6)
    }
}
