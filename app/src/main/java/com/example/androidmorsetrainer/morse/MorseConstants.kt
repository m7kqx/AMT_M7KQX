package com.example.androidmorsetrainer.morse

/**
 * Standard Morse code constants, dictionary, and ITU/ARRL timing calculations.
 */
object MorseConstants {

    /**
     * Complete International Morse Code dictionary, including standard Koch characters
     * and special procedural signals (prosigns <BT>, <SK>, <AR>).
     */
    val MORSE_MAP: Map<String, String> = mapOf(
        // Letters
        "A" to ".-",
        "B" to "-...",
        "C" to "-.-.",
        "D" to "-..",
        "E" to ".",
        "F" to "..-.",
        "G" to "--.",
        "H" to "....",
        "I" to "..",
        "J" to ".---",
        "K" to "-.-",
        "L" to ".-..",
        "M" to "--",
        "N" to "-.",
        "O" to "---",
        "P" to ".--.",
        "Q" to "--.-",
        "R" to ".-.",
        "S" to "...",
        "T" to "-",
        "U" to "..-",
        "V" to "...-",
        "W" to ".--",
        "X" to "-..-",
        "Y" to "-.--",
        "Z" to "--..",

        // Numbers
        "0" to "-----",
        "1" to ".----",
        "2" to "..---",
        "3" to "...--",
        "4" to "....-",
        "5" to ".....",
        "6" to "-....",
        "7" to "--...",
        "8" to "---..",
        "9" to "----.",

        // Punctuation
        "." to ".-.-.-",
        "," to "--..--",
        "/" to "-..-.",
        "?" to "..--..",

        // Prosigns (procedural signals)
        "<BT>" to "-...-",   // Break / pause between sections
        "<SK>" to "...-.-",  // Silent Key / End of transmission
        "<AR>" to ".-.-.",   // End of message
        "<KN>" to "-.--.",   // Over, only to the station called
        "<AS>" to ".-..."    // Wait
    )

    val REVERSE_MORSE_MAP: Map<String, String> = MORSE_MAP.entries.associate { (k, v) -> v to k }

    /**
     * Standard Koch character progression sequence (43 characters total).
     */
    val KOCH_SEQUENCE: List<String> = listOf(
        "K", "M", "R", "S", "U", "A", "P", "T", "L", "O",
        "W", "I", ".", "N", "J", "E", "F", "0", "Y", ",",
        "V", "G", "5", "/", "Q", "9", "Z", "H", "3", "8",
        "B", "?", "4", "2", "7", "C", "1", "D", "6", "X",
        "<AR>"
    )

    /**
     * Standard UK/IARU Region 1 Prosigns and common abbreviations sequence.
     */
    val PROSIGNS_SEQUENCE: List<String> = listOf(
        "<AR>", "<BT>", "<SK>", "<KN>", "<AS>", "CQ", "DE", "K", "R", "PSE", "UR", "RST"
    )

    /**
     * Standard PARIS timing standard:
     * "PARIS " consists of 50 unit lengths (including inter-word spacing).
     * Unit length (dit duration in ms) = 1200 / WPM.
     */
    fun calculateUnitDurationMs(wpm: Int): Long {
        require(wpm > 0) { "WPM must be greater than 0" }
        return (1200.0 / wpm).toLong().coerceAtLeast(1L)
    }

    /**
     * Calculate dah duration (3 units).
     */
    fun calculateDahDurationMs(wpm: Int): Long {
        return calculateUnitDurationMs(wpm) * 3
    }

    /**
     * Intra-character space (between dits and dahs of the same character) = 1 unit.
     */
    fun calculateElementSpaceMs(wpm: Int): Long {
        return calculateUnitDurationMs(wpm)
    }

    /**
     * Inter-character space (between different characters) = 3 units, or Farnsworth extended.
     */
    fun calculateCharacterSpaceMs(charWpm: Int, farnsworthWpm: Int? = null): Long {
        return if (farnsworthWpm != null && farnsworthWpm < charWpm) {
            calculateUnitDurationMs(farnsworthWpm) * 3
        } else {
            calculateUnitDurationMs(charWpm) * 3
        }
    }

    /**
     * Inter-word space (between words) = 7 units, or Farnsworth extended.
     */
    fun calculateWordSpaceMs(charWpm: Int, farnsworthWpm: Int? = null): Long {
        return if (farnsworthWpm != null && farnsworthWpm < charWpm) {
            calculateUnitDurationMs(farnsworthWpm) * 7
        } else {
            calculateUnitDurationMs(charWpm) * 7
        }
    }
}
