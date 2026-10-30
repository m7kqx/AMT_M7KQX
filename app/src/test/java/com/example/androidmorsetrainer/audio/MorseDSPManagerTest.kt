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

class MorseDSPManagerTest {

    private lateinit var dspManager: MorseDSPManager
    private val sampleRate = 44100

    @Before
    fun setUp() {
        dspManager = MorseDSPManager(
            context = null,
            sampleRate = sampleRate,
            blockSize = 512,
            targetFrequencyHz = 700.0,
            squelchThreshold = 0.03
        )
    }

    private fun generateSineWaveBlock(
        freqHz: Double,
        amplitude: Double = 0.6,
        numSamples: Int = 512,
        startIndex: Int = 0
    ): ShortArray {
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * freqHz * (startIndex + i) / sampleRate
            val sampleVal = (amplitude * Short.MAX_VALUE * sin(angle)).toInt()
            buffer[i] = sampleVal.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return buffer
    }

    @Test
    fun initialState_isSearchingAndNotLocked() {
        assertEquals(MorseDSPManager.PitchLockState.SEARCHING, dspManager.pitchLockState)
        assertFalse(dspManager.isPitchLocked)
        assertNull(dspManager.lockedFrequencyHz)
        assertEquals(700.0, dspManager.targetFrequencyHz, 0.01)
        assertFalse(dspManager.dspState.value.isPitchLocked)
        assertNull(dspManager.dspState.value.lockedFrequencyHz)
    }

    @Test
    fun processPcmBlock_whenSineWaveStreamed_detectsAndLocksDominantFrequency() {
        val targetFreq = 600.0
        var now = 1000L
        var sampleIndex = 0

        // Stream enough blocks (e.g. 6 blocks of 512 samples = 3072 samples > 2048 FFT buffer)
        for (i in 0 until 8) {
            val block = generateSineWaveBlock(
                freqHz = targetFreq,
                amplitude = 0.6,
                numSamples = 512,
                startIndex = sampleIndex
            )
            sampleIndex += 512
            now += 50L
            dspManager.processPcmBlock(block, now = now)
        }

        // Verify that manager locked onto ~600Hz
        assertEquals(MorseDSPManager.PitchLockState.LOCKED, dspManager.pitchLockState)
        assertTrue(dspManager.isPitchLocked)
        assertNotNull(dspManager.lockedFrequencyHz)
        assertEquals(targetFreq, dspManager.targetFrequencyHz, 2.0)
        assertEquals(targetFreq, dspManager.lockedFrequencyHz!!, 2.0)
        assertTrue(dspManager.dspState.value.isPitchLocked)
        assertEquals(targetFreq, dspManager.dspState.value.lockedFrequencyHz!!, 2.0)
    }

    @Test
    fun hysteresisDelay_maintainsLockDuringBriefSignalLoss() {
        val targetFreq = 650.0
        var now = 1000L
        var sampleIndex = 0

        // 1. Feed 650 Hz tone until locked
        for (i in 0 until 8) {
            val block = generateSineWaveBlock(
                freqHz = targetFreq,
                amplitude = 0.6,
                numSamples = 512,
                startIndex = sampleIndex
            )
            sampleIndex += 512
            now += 50L
            dspManager.processPcmBlock(block, now = now)
        }

        assertTrue(dspManager.isPitchLocked)
        assertEquals(MorseDSPManager.PitchLockState.LOCKED, dspManager.pitchLockState)

        // 2. Feed silence for 1500ms (< default lock timeout of 2500ms)
        val silence = ShortArray(512) { 0 }
        now += 1500L
        dspManager.processPcmBlock(silence, now = now)

        // Lock must still be held
        assertTrue("Lock should still be maintained within hysteresis window", dspManager.isPitchLocked)
        assertEquals(MorseDSPManager.PitchLockState.LOCKED, dspManager.pitchLockState)
        assertEquals(targetFreq, dspManager.targetFrequencyHz, 2.0)
    }

    @Test
    fun hysteresisDelay_releasesLockAfterSignalLossTimeout() {
        val targetFreq = 550.0
        var now = 1000L
        var sampleIndex = 0

        // 1. Lock onto 550 Hz
        for (i in 0 until 8) {
            val block = generateSineWaveBlock(
                freqHz = targetFreq,
                amplitude = 0.6,
                numSamples = 512,
                startIndex = sampleIndex
            )
            sampleIndex += 512
            now += 50L
            dspManager.processPcmBlock(block, now = now)
        }
        assertTrue(dspManager.isPitchLocked)

        // 2. Feed silence past timeout (2500ms + 100ms = 2600ms)
        val silence = ShortArray(512) { 0 }
        now += (MorseDSPManager.DEFAULT_LOCK_TIMEOUT_MS + 100L)
        dspManager.processPcmBlock(silence, now = now)

        // Lock must now be released to SEARCHING
        assertFalse("Lock must be released after timeout", dspManager.isPitchLocked)
        assertEquals(MorseDSPManager.PitchLockState.SEARCHING, dspManager.pitchLockState)
        assertNull(dspManager.lockedFrequencyHz)
        assertFalse(dspManager.dspState.value.isPitchLocked)
        assertNull(dspManager.dspState.value.lockedFrequencyHz)
    }

    @Test
    fun manualLockAndReleaseControls() {
        dspManager.lockOnFrequency(850.0)
        assertEquals(MorseDSPManager.PitchLockState.LOCKED, dspManager.pitchLockState)
        assertTrue(dspManager.isPitchLocked)
        assertEquals(850.0, dspManager.targetFrequencyHz, 0.01)
        assertEquals(850.0, dspManager.lockedFrequencyHz!!, 0.01)

        dspManager.releaseFrequencyLock()
        assertEquals(MorseDSPManager.PitchLockState.SEARCHING, dspManager.pitchLockState)
        assertFalse(dspManager.isPitchLocked)
        assertNull(dspManager.lockedFrequencyHz)
    }

    @Test
    fun processPcmBlock_retunesWhenSenderChangesFrequency() {
        var now = 1000L
        var sampleIndex = 0

        // 1. Lock onto 600 Hz
        for (i in 0 until 8) {
            val block = generateSineWaveBlock(
                freqHz = 600.0,
                amplitude = 0.6,
                numSamples = 512,
                startIndex = sampleIndex
            )
            sampleIndex += 512
            now += 50L
            dspManager.processPcmBlock(block, now = now)
        }
        assertEquals(600.0, dspManager.targetFrequencyHz, 2.0)

        // 2. Sender retunes to 750 Hz after 600ms gap
        now += 600L
        for (i in 0 until 8) {
            val block = generateSineWaveBlock(
                freqHz = 750.0,
                amplitude = 0.6,
                numSamples = 512,
                startIndex = sampleIndex
            )
            sampleIndex += 512
            now += 50L
            dspManager.processPcmBlock(block, now = now)
        }

        // Verify retuned to ~750 Hz
        assertEquals(750.0, dspManager.targetFrequencyHz, 2.0)
        assertTrue(dspManager.isPitchLocked)
    }
}
