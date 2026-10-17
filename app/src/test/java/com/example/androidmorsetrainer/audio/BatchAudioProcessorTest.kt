package com.example.androidmorsetrainer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BatchAudioProcessorTest {

    private lateinit var audioGenerator: MorseAudioGenerator
    private lateinit var processor: BatchAudioProcessor

    @Before
    fun setUp() {
        audioGenerator = MorseAudioGenerator(
            sampleRate = 44100,
            frequencyHz = 700,
            wpm = 20
        )
        processor = BatchAudioProcessor(
            sampleRate = 44100,
            targetFrequencyHz = 700.0,
            blockSize = 512
        )
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        return bytes
    }

    @Test
    fun testEmptyOrSubBlockAudioReturnsEmptyString() {
        val emptyResult = processor.process(ByteArray(0))
        assertEquals("", emptyResult)

        val smallResult = processor.process(ByteArray(100))
        assertEquals("", smallResult)
    }

    @Test
    fun testSilenceReturnsEmptyString() {
        // 1 second of silence
        val silentSamples = ShortArray(44100)
        val result = processor.process(silentSamples)
        assertEquals("", result)
    }

    @Test
    fun testDecodesSingleCharacterK() {
        // 'K' is -.-
        val kSamples = audioGenerator.generateMorsePatternBuffer(
            pattern = "-.-",
            speedWpm = 20,
            initialSilenceMs = 50L
        )
        // Add trailing silence (e.g. 200ms)
        val trailingSilence = ShortArray(8820)
        val combined = ShortArray(kSamples.size + trailingSilence.size)
        System.arraycopy(kSamples, 0, combined, 0, kSamples.size)
        System.arraycopy(trailingSilence, 0, combined, kSamples.size, trailingSilence.size)

        val pcmBytes = shortsToBytes(combined)
        val decoded = processor.process(pcmBytes)
        assertEquals("K", decoded)
    }

    @Test
    fun testDecodesWordCQ() {
        // 'CQ' is -.-. --.-
        val cqSamples = audioGenerator.generateMorsePatternBuffer(
            pattern = "-.-. --.-",
            speedWpm = 20,
            initialSilenceMs = 50L
        )
        val trailingSilence = ShortArray(8820)
        val combined = ShortArray(cqSamples.size + trailingSilence.size)
        System.arraycopy(cqSamples, 0, combined, 0, cqSamples.size)
        System.arraycopy(trailingSilence, 0, combined, cqSamples.size, trailingSilence.size)

        val pcmBytes = shortsToBytes(combined)
        val decoded = processor.process(pcmBytes)
        assertEquals("CQ", decoded)
    }

    @Test
    fun testDecodesTwoWordsWithSpace() {
        // 'CQ CQ' is -.-. --.- / -.-. --.-
        val samples = audioGenerator.generateMorsePatternBuffer(
            pattern = "-.-. --.- / -.-. --.-",
            speedWpm = 20,
            initialSilenceMs = 50L
        )
        val trailingSilence = ShortArray(8820)
        val combined = ShortArray(samples.size + trailingSilence.size)
        System.arraycopy(samples, 0, combined, 0, samples.size)
        System.arraycopy(trailingSilence, 0, combined, samples.size, trailingSilence.size)

        val pcmBytes = shortsToBytes(combined)
        val decoded = processor.process(pcmBytes)
        assertEquals("CQ CQ", decoded)
    }

    @Test
    fun testManualNoiseFloorThresholdTuning() {
        val samples = audioGenerator.generateMorsePatternBuffer(
            pattern = "-.-",
            speedWpm = 20,
            initialSilenceMs = 50L
        )
        val pcmBytes = shortsToBytes(samples)

        // With realistic squelch threshold
        val tunedProcessor = BatchAudioProcessor(
            sampleRate = 44100,
            targetFrequencyHz = 700.0,
            blockSize = 512,
            manualNoiseFloorThreshold = 0.05f
        )
        val decoded = tunedProcessor.process(pcmBytes)
        assertEquals("K", decoded)

        // With impossibly high squelch threshold, should squelch everything
        val highSquelchProcessor = BatchAudioProcessor(
            sampleRate = 44100,
            targetFrequencyHz = 700.0,
            blockSize = 512,
            manualNoiseFloorThreshold = 999.0f
        )
        val squelched = highSquelchProcessor.process(pcmBytes)
        assertEquals("", squelched)
    }
}
