package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the distinct stages of exchanging information during a QSO.
 */
enum class ExchangeStage {
    AWAITING_CALLSIGN,
    AWAITING_REPORT,
    COMPLETED
}

/**
 * Finite State Machine states for the offline Morse code QSO simulator.
 */
sealed class QsoState {
    data object Idle : QsoState()
    data object TransmittingCQ : QsoState()
    data object ListeningForCaller : QsoState()
    data class Exchanging(val stage: ExchangeStage) : QsoState()
    data object LoggingContact : QsoState()
}

/**
 * Manages the QSO Finite State Machine lifecycle and processes decoded Morse
 * or user input to trigger synchronous, predictable transitions between QSO states.
 *
 * Designed as a pure Kotlin class independent of Android UI and DSP frameworks.
 */
class QsoManager(initialState: QsoState = QsoState.Idle) {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<QsoState> = _state.asStateFlow()
    val stateFlow: StateFlow<QsoState> get() = state

    var remoteCallsign: String? = null
        private set

    var remoteReport: String? = null
        private set

    companion object {
        // Matches standard amateur radio callsigns, e.g. M7KQX, G4ABC, W1AW, 2E0XYZ
        private val CALLSIGN_REGEX = Regex("""\b[A-Z0-9]{1,3}[0-9][A-Z]{1,4}\b""", RegexOption.IGNORE_CASE)

        // Matches CW signal reports such as 599, 579, 5NN (cut numbers), 59
        private val REPORT_REGEX = Regex("""\b([1-5][0-9]{1,2}|[1-5]N{1,2}|[1-5][0-9]N)\b""", RegexOption.IGNORE_CASE)

        // Matches CQ calls
        private val CQ_REGEX = Regex("""\bCQ\b""", RegexOption.IGNORE_CASE)

        // Matches procedural signal 'K' (invitation to transmit) as a standalone token
        private val ENDING_K_REGEX = Regex("""(?:\s|^)K(?:\s|$)""", RegexOption.IGNORE_CASE)

        // Matches standard sign-offs (73, TU, SK, QSL, etc.)
        private val SIGNOFF_REGEX = Regex("""\b(73|TU|SK|QSL|GL|TNX|THANKS|RRR|EE)\b""", RegexOption.IGNORE_CASE)
    }

    /**
     * Evaluates the injected string buffer using lightweight Regex and string matching
     * to trigger state transitions synchronously.
     *
     * @param input Raw text or decoded Morse string pattern.
     */
    fun processInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        when (val currentState = _state.value) {
            is QsoState.Idle -> {
                if (CQ_REGEX.containsMatchIn(trimmed)) {
                    if (ENDING_K_REGEX.containsMatchIn(trimmed)) {
                        // Full CQ completed in one buffer (e.g., "CQ CQ DE M7KQX K")
                        _state.value = QsoState.ListeningForCaller
                    } else {
                        // Started calling CQ
                        _state.value = QsoState.TransmittingCQ
                    }
                }
            }

            is QsoState.TransmittingCQ -> {
                if (ENDING_K_REGEX.containsMatchIn(trimmed) || trimmed.equals("K", ignoreCase = true)) {
                    // Finished transmitting CQ, now listening for a caller
                    _state.value = QsoState.ListeningForCaller
                } else {
                    val callsign = extractCallsign(trimmed)
                    if (callsign != null) {
                        remoteCallsign = callsign
                        _state.value = QsoState.Exchanging(ExchangeStage.AWAITING_REPORT)
                    }
                }
            }

            is QsoState.ListeningForCaller -> {
                val callsign = extractCallsign(trimmed)
                if (callsign != null) {
                    remoteCallsign = callsign
                    _state.value = QsoState.Exchanging(ExchangeStage.AWAITING_REPORT)
                }
            }

            is QsoState.Exchanging -> {
                when (currentState.stage) {
                    ExchangeStage.AWAITING_CALLSIGN -> {
                        val callsign = extractCallsign(trimmed)
                        if (callsign != null) {
                            remoteCallsign = callsign
                            _state.value = QsoState.Exchanging(ExchangeStage.AWAITING_REPORT)
                        }
                    }

                    ExchangeStage.AWAITING_REPORT -> {
                        val report = extractReport(trimmed)
                        if (report != null) {
                            remoteReport = report
                            if (SIGNOFF_REGEX.containsMatchIn(trimmed)) {
                                _state.value = QsoState.LoggingContact
                            } else {
                                _state.value = QsoState.Exchanging(ExchangeStage.COMPLETED)
                            }
                        }
                    }

                    ExchangeStage.COMPLETED -> {
                        if (SIGNOFF_REGEX.containsMatchIn(trimmed)) {
                            _state.value = QsoState.LoggingContact
                        }
                    }
                }
            }

            is QsoState.LoggingContact -> {
                if (trimmed.equals("RESET", ignoreCase = true) || trimmed.equals("LOG", ignoreCase = true)) {
                    reset()
                }
            }
        }
    }

    /**
     * Resets the FSM back to Idle and clears stored contact data.
     */
    fun reset() {
        _state.value = QsoState.Idle
        remoteCallsign = null
        remoteReport = null
    }

    private fun extractCallsign(text: String): String? {
        return CALLSIGN_REGEX.find(text)?.value?.uppercase()
    }

    private fun extractReport(text: String): String? {
        return REPORT_REGEX.find(text)?.value?.uppercase()
    }
}
