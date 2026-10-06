package com.example.androidmorsetrainer.ui.screens.train

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KochKeyboardTest {

    @Test
    fun extendedKochSequence_containsExact41Characters() {
        assertEquals(41, EXTENDED_KOCH_SEQUENCE.size)
    }

    @Test
    fun extendedKochSequence_orderMatchesStandardLcwoPlusEqualSign() {
        val expectedFirstTen = listOf("K", "M", "R", "S", "U", "A", "P", "T", "L", "O")
        assertEquals(expectedFirstTen, EXTENDED_KOCH_SEQUENCE.take(10))

        assertEquals("K", EXTENDED_KOCH_SEQUENCE[0])
        assertEquals("M", EXTENDED_KOCH_SEQUENCE[1])
        assertEquals("R", EXTENDED_KOCH_SEQUENCE[2])
        assertEquals("X", EXTENDED_KOCH_SEQUENCE[39])
        assertEquals("=", EXTENDED_KOCH_SEQUENCE[40])
    }

    @Test
    fun extendedKochSequence_containsAllRequiredCharacterTypes() {
        val alphabet = ('A'..'Z').map { it.toString() }
        val digits = ('0'..'9').map { it.toString() }
        val punctuationAndProsigns = listOf(".", ",", "/", "?", "=")

        alphabet.forEach { letter ->
            assertTrue("Sequence must contain letter $letter", EXTENDED_KOCH_SEQUENCE.contains(letter))
        }

        digits.forEach { digit ->
            assertTrue("Sequence must contain digit $digit", EXTENDED_KOCH_SEQUENCE.contains(digit))
        }

        punctuationAndProsigns.forEach { symbol ->
            assertTrue("Sequence must contain symbol $symbol", EXTENDED_KOCH_SEQUENCE.contains(symbol))
        }
    }

    @Test
    fun isKochCharacterUnlocked_level1_unlocksOnlyKAndM() {
        assertTrue(isKochCharacterUnlocked("K", 1))
        assertTrue(isKochCharacterUnlocked("M", 1))

        // All subsequent characters must be locked at Level 1
        assertFalse(isKochCharacterUnlocked("R", 1))
        assertFalse(isKochCharacterUnlocked("S", 1))
        assertFalse(isKochCharacterUnlocked("A", 1))
        assertFalse(isKochCharacterUnlocked("1", 1))
        assertFalse(isKochCharacterUnlocked("?", 1))
        assertFalse(isKochCharacterUnlocked("=", 1))
    }

    @Test
    fun isKochCharacterUnlocked_level2_unlocksK_M_R() {
        assertTrue(isKochCharacterUnlocked("K", 2))
        assertTrue(isKochCharacterUnlocked("M", 2))
        assertTrue(isKochCharacterUnlocked("R", 2))

        assertFalse(isKochCharacterUnlocked("S", 2))
    }

    @Test
    fun isKochCharacterUnlocked_actionRowPunctuation() {
        // '?' is at index 31
        assertEquals(31, EXTENDED_KOCH_INDEX_MAP["?"])
        assertFalse(isKochCharacterUnlocked("?", 30))
        assertTrue(isKochCharacterUnlocked("?", 31))
        assertTrue(isKochCharacterUnlocked("?", 35))

        // ',' is at index 19
        assertEquals(19, EXTENDED_KOCH_INDEX_MAP[","])
        assertFalse(isKochCharacterUnlocked(",", 18))
        assertTrue(isKochCharacterUnlocked(",", 19))

        // '.' is at index 12
        assertEquals(12, EXTENDED_KOCH_INDEX_MAP["."])
        assertFalse(isKochCharacterUnlocked(".", 11))
        assertTrue(isKochCharacterUnlocked(".", 12))

        // '/' is at index 23
        assertEquals(23, EXTENDED_KOCH_INDEX_MAP["/"])
        assertFalse(isKochCharacterUnlocked("/", 22))
        assertTrue(isKochCharacterUnlocked("/", 23))

        // '=' is at index 40
        assertEquals(40, EXTENDED_KOCH_INDEX_MAP["="])
        assertFalse(isKochCharacterUnlocked("=", 39))
        assertTrue(isKochCharacterUnlocked("=", 40))
        assertTrue(isKochCharacterUnlocked("=", 42))
    }

    @Test
    fun unknownCharacter_isNotUnlocked() {
        assertFalse(isKochCharacterUnlocked("UNKNOWN", 42))
        assertFalse(isKochCharacterUnlocked("@", 42))
    }
}
