package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CwDecoderTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var decoder: CwDecoder

    @Before
    fun setUp() {
        // Initial 20 WPM (dit duration = 60ms)
        decoder = CwDecoder(initialWpm = 20, scope = testScope)
    }

    @Test
    fun initialState_isClean() {
        assertEquals("", decoder.decodedText.value)
        assertEquals("", decoder.currentSymbol.value)
        assertEquals(20, decoder.estimatedWpm.value)
        assertEquals(60.0, decoder.ditDurationMs, 0.1)
    }

    @Test
    fun processTonePulse_classifiesDitAndDahCorrectly() = testScope.runTest {
        // Dit at 20 WPM is ~60ms (< 1.8 * 60 = 108ms)
        decoder.onToneCompleted(60L)
        assertEquals(".", decoder.currentSymbol.value)

        // Dah at 20 WPM is ~180ms (>= 108ms)
        decoder.onToneCompleted(180L)
        assertEquals(".-", decoder.currentSymbol.value)
    }

    @Test
    fun silenceWatchdog_decodesSingleCharacterAfterInterCharacterSpace() = testScope.runTest {
        // Send letter "A" (.-): Dit, element space, Dah, char space
        decoder.onToneCompleted(60L) // '.'
        advanceTimeBy(60) // within element space, no commit yet
        assertEquals(".", decoder.currentSymbol.value)
        assertEquals("", decoder.decodedText.value)

        decoder.onToneStarted()
        decoder.onToneCompleted(180L) // '-'
        assertEquals(".-", decoder.currentSymbol.value)
        assertEquals("", decoder.decodedText.value)

        // Advance time to pass character space threshold (~2.2 * 60 = 132ms)
        advanceTimeBy(150)

        // ".-" should be decoded to "A"
        assertEquals("A", decoder.decodedText.value)
        assertEquals("", decoder.currentSymbol.value)
    }

    @Test
    fun silenceWatchdog_decodesWordSpaceAfterLongSilence() = testScope.runTest {
        // Send "E" (.)
        decoder.onToneCompleted(60L)
        // Advance past character space threshold (150ms)
        advanceTimeBy(150)
        assertEquals("E", decoder.decodedText.value)

        // Advance past word space threshold (another 200ms -> total > 5.5 * 60 = 330ms)
        advanceTimeBy(250)
        assertEquals("E ", decoder.decodedText.value)
    }

    @Test
    fun decodeMultipleCharacters_formsWord() = testScope.runTest {
        // Send "S" (...)
        decoder.onToneCompleted(60L)
        advanceTimeBy(40)
        decoder.onToneCompleted(60L)
        advanceTimeBy(40)
        decoder.onToneCompleted(60L)
        // Allow character space to elapse
        advanceTimeBy(150)
        assertEquals("S", decoder.decodedText.value)

        // Send "O" (---)
        decoder.onToneCompleted(180L)
        advanceTimeBy(40)
        decoder.onToneCompleted(180L)
        advanceTimeBy(40)
        decoder.onToneCompleted(180L)
        advanceTimeBy(150)
        assertEquals("SO", decoder.decodedText.value)

        // Send "S" (...)
        decoder.onToneCompleted(60L)
        advanceTimeBy(40)
        decoder.onToneCompleted(60L)
        advanceTimeBy(40)
        decoder.onToneCompleted(60L)
        advanceTimeBy(150)
        assertEquals("SOS", decoder.decodedText.value)
    }

    @Test
    fun transientGlitches_areIgnored() = testScope.runTest {
        // Pulse shorter than 15ms
        decoder.onToneCompleted(10L)
        assertEquals("", decoder.currentSymbol.value)
        advanceUntilIdle()
        assertEquals("", decoder.decodedText.value)
    }

    @Test
    fun clear_resetsAllState() = testScope.runTest {
        decoder.onToneCompleted(60L)
        advanceTimeBy(150)
        assertEquals("E", decoder.decodedText.value)

        decoder.clear()
        assertEquals("", decoder.decodedText.value)
        assertEquals("", decoder.currentSymbol.value)
    }
}
