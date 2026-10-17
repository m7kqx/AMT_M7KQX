package com.example.androidmorsetrainer.morse

/**
 * Contextual validation engine for Morse code QSO decodes.
 * Filters DSP gibberish and validates incoming transmissions against expected state keywords.
 */
object QsoValidator {

    // Regex matching 5 or more consecutive identical letters or digits (e.g., "EEEEE", "TTTTT")
    private val REPEATING_LETTERS_REGEX = Regex("""(?i)([A-Z0-9])\1{4,}""")

    /**
     * Validates decoded Morse text contextually based on the current QSO state.
     *
     * @param state Current FSM state.
     * @param decodedText Decoded Morse string from DSP.
     * @param botCallsign Callsign of the simulated bot operator.
     * @param userCallsign Callsign of the human operator.
     * @return `true` if input is valid and passes contextual filters; `false` if gibberish or invalid.
     */
    fun validateDecode(
        state: QsoState,
        decodedText: String,
        botCallsign: String,
        userCallsign: String
    ): Boolean {
        val clean = decodedText.trim().uppercase()

        // 1. Global Gibberish Filter: empty
        if (clean.isEmpty()) {
            return false
        }

        // 2. Global Gibberish Filter: 5+ consecutive repeating letters/digits (noise floor failure)
        if (REPEATING_LETTERS_REGEX.containsMatchIn(clean)) {
            return false
        }

        // 3. Global Gibberish Filter: less than 3 characters long
        // Permitted 2-character keywords per state are accepted if they match the expected keywords
        if (clean.length < 3 && !isPermittedShortKeyword(state, clean, botCallsign)) {
            return false
        }

        // 4. State-Specific Keyword Matching
        return when (state) {
            is QsoState.ListenCQ,
            is QsoState.Idle,
            is QsoState.TransmittingCQ,
            is QsoState.ListeningForCaller -> {
                // CQ/Initial Call State: Return true if decodedText contains botCallsign OR "CQ"
                clean.contains("CQ") || (botCallsign.isNotBlank() && clean.contains(botCallsign.trim().uppercase()))
            }

            is QsoState.InitialResponse,
            is QsoState.Exchanging -> {
                // RST Exchange State: Return true if decodedText contains "599", "5NN", or "RST"
                clean.contains("599") || clean.contains("5NN") || clean.contains("RST")
            }

            is QsoState.InfoExchange -> {
                // Info Exchange State: Return true if decodedText contains "NAME", "OP", "QTH", "HR", or "FB"
                clean.contains("NAME") || clean.contains("OP") || clean.contains("QTH") || clean.contains("HR") || clean.contains("FB")
            }

            is QsoState.SignOff -> {
                // Sign Off State: Return true if decodedText contains "73", "TU", "EE", or "GB"
                clean.contains("73") || clean.contains("TU") || clean.contains("EE") || clean.contains("GB")
            }

            is QsoState.DitDitFinale -> {
                clean.contains("EE") || clean.contains("E E") || clean == "E"
            }

            is QsoState.Error -> {
                validateDecode(state.previousState, decodedText, botCallsign, userCallsign)
            }

            is QsoState.Summary,
            is QsoState.LoggingContact -> {
                false
            }
        }
    }

    private fun isPermittedShortKeyword(state: QsoState, clean: String, botCallsign: String): Boolean {
        return when (state) {
            is QsoState.ListenCQ,
            is QsoState.Idle,
            is QsoState.TransmittingCQ,
            is QsoState.ListeningForCaller -> {
                clean == "CQ" || (botCallsign.length <= 2 && clean == botCallsign.trim().uppercase())
            }
            is QsoState.InitialResponse,
            is QsoState.Exchanging -> {
                false // 599, 5NN, RST are all 3+ chars
            }
            is QsoState.InfoExchange -> {
                clean in listOf("OP", "HR", "FB")
            }
            is QsoState.SignOff -> {
                clean in listOf("73", "TU", "EE", "GB")
            }
            is QsoState.DitDitFinale -> {
                clean in listOf("EE", "E")
            }
            is QsoState.Error -> {
                isPermittedShortKeyword(state.previousState, clean, botCallsign)
            }
            is QsoState.Summary,
            is QsoState.LoggingContact -> false
        }
    }
}
