package com.example.androidmorsetrainer.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mock subclass of [MorseAudioGenerator] for testing [SineWaveMorsePlayer].
 */
class FakeMorseAudioGenerator : MorseAudioGenerator() {
    var playedStrings = mutableListOf<String>()
    var wasStopped = false
    var wasReleased = false

    override suspend fun playString(
        input: String,
        useContinuousProsign: Boolean,
        onCharacterPlayed: ((String) -> Unit)?
    ) {
        playedStrings.add(input)
    }

    override fun stop() {
        wasStopped = true
    }

    override fun release() {
        wasReleased = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SineWaveMorsePlayerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeGenerator: FakeMorseAudioGenerator
    private lateinit var player: SineWaveMorsePlayer

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGenerator = FakeMorseAudioGenerator()
        player = SineWaveMorsePlayer(
            audioGenerator = fakeGenerator,
            defaultDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testPlayTransmissionSetsWpmAndPlaysString() = runTest {
        player.playTransmission("M7KQX DE LZ2BB UR 599 BK", 18)

        assertEquals(18, fakeGenerator.wpm)
        assertEquals(1, fakeGenerator.playedStrings.size)
        assertEquals("M7KQX DE LZ2BB UR 599 BK", fakeGenerator.playedStrings.first())
    }

    @Test
    fun testBlankTransmissionIgnored() = runTest {
        player.playTransmission("   ", 20)
        assertEquals(0, fakeGenerator.playedStrings.size)
    }

    @Test
    fun testStopAndReleaseForwardToGenerator() {
        player.stop()
        assertTrue(fakeGenerator.wasStopped)

        player.release()
        assertTrue(fakeGenerator.wasReleased)
    }
}
