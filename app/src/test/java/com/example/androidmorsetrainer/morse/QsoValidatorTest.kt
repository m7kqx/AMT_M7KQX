package com.example.androidmorsetrainer.morse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QsoValidator] verifying the Global Gibberish Filter
 * and State-Specific Keyword Matching.
 */
class QsoValidatorTest {

    private val botCall = "LZ2BB"
    private val userCall = "M7KQX"

    @Test
    fun testGlobalGibberishFilter_rejectsEmptyOrBlankInput() {
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "   ", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.InitialResponse, "", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.InfoExchange, "", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.SignOff, "", botCall, userCall))
    }

    @Test
    fun testGlobalGibberishFilter_rejectsLessThanThreeCharsUnlessValidKeyword() {
        // Non-keyword 1 and 2 character inputs are rejected immediately
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "A", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "AB", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "X", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.InitialResponse, "59", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.InfoExchange, "NO", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.SignOff, "HI", botCall, userCall))
    }

    @Test
    fun testGlobalGibberishFilter_rejectsRepeatingLettersFromNoiseFloorFailure() {
        // 5+ repeating characters like EEEEE or TTTTT must return false
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "EEEEE", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "TTTTT", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.ListenCQ, "CQ EEEEE K", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.InitialResponse, "599 TTTTT", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.InfoExchange, "NAME EEEEE", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(QsoState.SignOff, "73 TTTTT", botCall, userCall))
    }

    @Test
    fun testCqInitialCallState_keywordMatching() {
        val state = QsoState.ListenCQ

        // Contains "CQ"
        assertTrue(QsoValidator.validateDecode(state, "CQ", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "CQ CQ", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "CQ CQ DE M7KQX K", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "cq cq de m7kqx k", botCall, userCall))

        // Contains botCallsign
        assertTrue(QsoValidator.validateDecode(state, "LZ2BB DE M7KQX K", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "lz2bb de m7kqx", botCall, userCall))

        // Incomprehensible / no CQ or bot callsign
        assertFalse(QsoValidator.validateDecode(state, "HELLO WORLD K", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(state, "DE M7KQX K", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(state, "ASDFGHJKL", botCall, userCall))
    }

    @Test
    fun testRstExchangeState_keywordMatching() {
        val state = QsoState.InitialResponse

        // Contains "599"
        assertTrue(QsoValidator.validateDecode(state, "599", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "UR 599 BK", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "UR 599 599 BK", botCall, userCall))

        // Contains "5NN"
        assertTrue(QsoValidator.validateDecode(state, "5NN", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "UR 5NN BK", botCall, userCall))

        // Contains "RST"
        assertTrue(QsoValidator.validateDecode(state, "RST 579", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "UR RST IS 589", botCall, userCall))

        // Missing all 3 keywords
        assertFalse(QsoValidator.validateDecode(state, "HELLO", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(state, "579", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(state, "REPORT GOOD", botCall, userCall))
    }

    @Test
    fun testInfoExchangeState_keywordMatching() {
        val state = QsoState.InfoExchange

        // Contains "NAME"
        assertTrue(QsoValidator.validateDecode(state, "NAME CHRIS", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "MY NAME IS CHRIS", botCall, userCall))

        // Contains "OP"
        assertTrue(QsoValidator.validateDecode(state, "OP CHRIS", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "TNX OP TIM", botCall, userCall))

        // Contains "QTH"
        assertTrue(QsoValidator.validateDecode(state, "QTH LONDON", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "MY QTH IS SOFIA", botCall, userCall))

        // Contains "HR"
        assertTrue(QsoValidator.validateDecode(state, "HR IN DALLAS", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "WX HR SUNNY", botCall, userCall))

        // Contains "FB"
        assertTrue(QsoValidator.validateDecode(state, "FB OM", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "QSL FB", botCall, userCall))

        // Missing expected keywords
        assertFalse(QsoValidator.validateDecode(state, "73 DE M7KQX", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(state, "RANDOM GIBBERISH", botCall, userCall))
    }

    @Test
    fun testSignOffState_keywordMatching() {
        val state = QsoState.SignOff

        // Contains "73"
        assertTrue(QsoValidator.validateDecode(state, "73", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "73 DE M7KQX", botCall, userCall))

        // Contains "TU"
        assertTrue(QsoValidator.validateDecode(state, "TU", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "TU FER QSO", botCall, userCall))

        // Contains "EE"
        assertTrue(QsoValidator.validateDecode(state, "EE", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "73 EE", botCall, userCall))

        // Contains "GB"
        assertTrue(QsoValidator.validateDecode(state, "GB", botCall, userCall))
        assertTrue(QsoValidator.validateDecode(state, "GB OM 73", botCall, userCall))

        // Missing expected keywords
        assertFalse(QsoValidator.validateDecode(state, "THANKS VERY MUCH", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(state, "GOODBYE FRIEND", botCall, userCall))
        assertFalse(QsoValidator.validateDecode(state, "NOPE", botCall, userCall))
    }
}
