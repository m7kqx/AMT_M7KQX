package com.example.androidmorsetrainer.morse

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the distinct stages of exchanging information during a legacy QSO.
 */
enum class ExchangeStage {
    AWAITING_CALLSIGN,
    AWAITING_REPORT,
    COMPLETED
}

/**
 * Finite State Machine states for the offline Morse code QSO simulator.
 * Supports the 6-stage sequential conversational loop and legacy states for compatibility.
 */
sealed class QsoState {
    // 6 Sequential States for the Offline QSO Conversational Loop
    data object ListenCQ : QsoState()
    data object InitialResponse : QsoState()
    data object InfoExchange : QsoState()
    data object SignOff : QsoState()
    data object DitDitFinale : QsoState()
    data class Summary(
        val botProfile: QsoBotProfile? = null,
        val userCallsign: String = "M7KQX",
        val userReport: String? = null,
        val isCompleted: Boolean = true
    ) : QsoState()

    // Legacy states for backwards compatibility
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
 * Implements the 6-stage sequential conversational loop:
 * State 1 (Listen CQ) -> State 2 (Initial Response) -> State 3 (Info Exchange) ->
 * State 4 (Sign Off) -> State 5 (Dit Dit Finale) -> State 6 (Summary).
 * Includes global error recovery and 5-second silence watchdog handling.
 */
class QsoManager(initialState: QsoState = QsoState.ListenCQ) {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<QsoState> = _state.asStateFlow()
    val stateFlow: StateFlow<QsoState> get() = state

    var userCallsign: String = "M7KQX"
        private set

    var remoteCallsign: String? = null
        private set

    var remoteReport: String? = null
        private set

    var activeBotProfile: QsoBotProfile? = null

    private val _errorRecoveryFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val errorRecoveryFlow: SharedFlow<String> = _errorRecoveryFlow.asSharedFlow()

    private val _ditDitReceivedFlow = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)
    val ditDitReceivedFlow: SharedFlow<Unit> = _ditDitReceivedFlow.asSharedFlow()

    private val cqBuffer = StringBuilder()

    companion object {
        // Matches standard amateur radio callsigns, e.g. M7KQX, G4ABC, W1AW, 2E0XYZ, LZ2BB
        val CALLSIGN_REGEX = Regex("""\b[A-Z0-9]{1,3}[0-9][A-Z]{1,4}\b""", RegexOption.IGNORE_CASE)

        // Matches CW signal reports such as 599, 579, 5NN (cut numbers), 59
        val REPORT_REGEX = Regex("""\b([1-5][0-9]{1,2}|[1-5]N{1,2}|[1-5][0-9]N)\b""", RegexOption.IGNORE_CASE)

        // Matches CQ calls
        val CQ_REGEX = Regex("""\bCQ\b""", RegexOption.IGNORE_CASE)

        // Matches procedural signal 'K' (invitation to transmit) as a standalone token
        val ENDING_K_REGEX = Regex("""(?:\s|^)K(?:\s|$)""", RegexOption.IGNORE_CASE)

        // Matches acknowledgement / exchange responses in State 3
        val INFO_ACK_REGEX = Regex("""\b(QSL|TNX|TU|R|RR|RRR|FB|OK|NAME|QTH|73)\b""", RegexOption.IGNORE_CASE)

        // Matches standard sign-offs (73, TU, SK, QSL, etc.)
        val SIGNOFF_REGEX = Regex("""\b(73|TU|SK|QSL|GL|TNX|THANKS|RRR|EE)\b""", RegexOption.IGNORE_CASE)

        // Matches Dit-Dit final exchange in State 5 (E E, EE, or E)
        val DIT_DIT_REGEX = Regex("""\b(E\s+E|EE|E)\b""", RegexOption.IGNORE_CASE)
    }

    /**
     * Evaluates incoming decoded Morse string tokens to trigger sequential FSM state transitions.
     *
     * @param input Raw text or decoded Morse token/word.
     */
    fun processInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        when (val currentState = _state.value) {
            is QsoState.Idle -> {
                // If in Idle, transition to ListenCQ and process
                _state.value = QsoState.ListenCQ
                processInput(trimmed)
            }

            is QsoState.ListenCQ -> {
                if (cqBuffer.isNotEmpty()) cqBuffer.append(" ")
                cqBuffer.append(trimmed)

                // Advance if 'K' is decoded
                if (ENDING_K_REGEX.containsMatchIn(trimmed) || trimmed.equals("K", ignoreCase = true)) {
                    evaluateCq()
                }
            }

            is QsoState.InitialResponse -> {
                val report = extractReport(trimmed)
                if (report != null) {
                    remoteReport = report
                    _state.value = QsoState.InfoExchange
                } else {
                    // Global Error Recovery: incomprehensible input based on expected context
                    triggerErrorRecovery()
                }
            }

            is QsoState.InfoExchange -> {
                if (INFO_ACK_REGEX.containsMatchIn(trimmed)) {
                    _state.value = QsoState.SignOff
                } else {
                    triggerErrorRecovery()
                }
            }

            is QsoState.SignOff -> {
                if (DIT_DIT_REGEX.matches(trimmed)) {
                    _state.value = QsoState.DitDitFinale
                    _ditDitReceivedFlow.tryEmit(Unit)
                } else if (SIGNOFF_REGEX.containsMatchIn(trimmed)) {
                    _state.value = QsoState.DitDitFinale
                } else {
                    triggerErrorRecovery()
                }
            }

            is QsoState.DitDitFinale -> {
                if (DIT_DIT_REGEX.containsMatchIn(trimmed)) {
                    _ditDitReceivedFlow.tryEmit(Unit)
                }
            }

            is QsoState.Summary -> {
                if (trimmed.equals("RESET", ignoreCase = true) || trimmed.equals("LOG", ignoreCase = true)) {
                    reset()
                }
            }

            // Legacy FSM state handling for compatibility
            is QsoState.TransmittingCQ -> {
                if (ENDING_K_REGEX.containsMatchIn(trimmed) || trimmed.equals("K", ignoreCase = true)) {
                    _state.value = QsoState.ListeningForCaller
                } else {
                    val callsign = extractCallsign(trimmed)
                    if (callsign != null) {
                        userCallsign = callsign
                        remoteCallsign = callsign
                        _state.value = QsoState.Exchanging(ExchangeStage.AWAITING_REPORT)
                    }
                }
            }

            is QsoState.ListeningForCaller -> {
                val callsign = extractCallsign(trimmed)
                if (callsign != null) {
                    userCallsign = callsign
                    remoteCallsign = callsign
                    _state.value = QsoState.Exchanging(ExchangeStage.AWAITING_REPORT)
                }
            }

            is QsoState.Exchanging -> {
                when (currentState.stage) {
                    ExchangeStage.AWAITING_CALLSIGN -> {
                        val callsign = extractCallsign(trimmed)
                        if (callsign != null) {
                            userCallsign = callsign
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
     * Evaluates accumulated CQ transmission in State 1 (Listen CQ).
     * If valid callsign is decoded, transitions to State 2 (Initial Response).
     * Otherwise triggers error recovery.
     */
    fun evaluateCq() {
        val text = cqBuffer.toString().trim()
        cqBuffer.clear()
        if (text.isEmpty()) {
            triggerErrorRecovery()
            return
        }

        val callsign = extractCallsign(text)
        val hasCq = CQ_REGEX.containsMatchIn(text)

        if (callsign != null && (hasCq || text.contains("DE", ignoreCase = true) || text.length <= 15)) {
            userCallsign = callsign
            remoteCallsign = callsign
            _state.value = QsoState.InitialResponse
        } else {
            triggerErrorRecovery()
        }
    }

    /**
     * Handles silence watchdog timeouts in State 1 and State 5.
     */
    fun onSilenceTimeout() {
        when (_state.value) {
            is QsoState.ListenCQ -> {
                if (cqBuffer.isNotEmpty()) {
                    evaluateCq()
                }
            }
            is QsoState.DitDitFinale -> {
                completeQso()
            }
            else -> { /* No-op for other states */ }
        }
    }

    /**
     * Transitions FSM to State 6 (Summary).
     */
    fun completeQso() {
        _state.value = QsoState.Summary(
            botProfile = activeBotProfile,
            userCallsign = userCallsign,
            userReport = remoteReport ?: "599",
            isCompleted = true
        )
    }

    /**
     * Triggers global error recovery: Bot emits "AGN AGN?" and FSM remains in current listening state.
     */
    fun triggerErrorRecovery(message: String = "AGN AGN?") {
        _errorRecoveryFlow.tryEmit(message)
    }

    /**
     * Resets the FSM back to State 1 (Listen CQ) and clears stored contact data.
     */
    fun reset(toState: QsoState = QsoState.ListenCQ) {
        _state.value = toState
        userCallsign = "M7KQX"
        remoteCallsign = null
        remoteReport = null
        cqBuffer.clear()
    }

    fun getCqBufferContent(): String = cqBuffer.toString()

    private fun extractCallsign(text: String): String? {
        return CALLSIGN_REGEX.find(text)?.value?.uppercase()
    }

    private fun extractReport(text: String): String? {
        return REPORT_REGEX.find(text)?.value?.uppercase()
    }
}
