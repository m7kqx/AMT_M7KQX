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
 * Supports the 6-stage sequential conversational loop, error state, and legacy states.
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

    // Error recovery state
    data class Error(
        val previousState: QsoState,
        val message: String = "AGN AGN?"
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
 * Implements forgiving contextual validation via [QsoValidator] and routes to error
 * recovery ("AGN AGN?") when input fails validation or 5-second silence timeout occurs.
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
        val INFO_ACK_REGEX = Regex("""\b(NAME|OP|QTH|HR|FB|QSL|TNX|TU|R|RR|RRR|OK|73)\b""", RegexOption.IGNORE_CASE)

        // Matches standard sign-offs (73, TU, SK, QSL, etc.)
        val SIGNOFF_REGEX = Regex("""\b(73|TU|SK|QSL|GL|TNX|THANKS|RRR|EE|GB)\b""", RegexOption.IGNORE_CASE)

        // Matches Dit-Dit final exchange in State 5 (E E, EE, or E)
        val DIT_DIT_REGEX = Regex("""\b(E\s+E|EE|E)\b""", RegexOption.IGNORE_CASE)
    }

    /**
     * Evaluates incoming decoded Morse string tokens to trigger sequential FSM state transitions.
     * Passes accumulated tokens to [QsoValidator] when "K" is received or when keywords match.
     *
     * @param input Raw text or decoded Morse token/word.
     */
    fun processInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        when (_state.value) {
            is QsoState.Idle -> {
                _state.value = QsoState.ListenCQ
                processInput(trimmed)
                return
            }

            is QsoState.Summary -> {
                if (trimmed.equals("RESET", ignoreCase = true) || trimmed.equals("LOG", ignoreCase = true)) {
                    reset()
                }
                return
            }

            is QsoState.LoggingContact -> {
                if (trimmed.equals("RESET", ignoreCase = true) || trimmed.equals("LOG", ignoreCase = true)) {
                    reset()
                }
                return
            }

            else -> {
                if (cqBuffer.isNotEmpty()) cqBuffer.append(" ")
                cqBuffer.append(trimmed)

                val hasEndingK = ENDING_K_REGEX.containsMatchIn(trimmed) || trimmed.equals("K", ignoreCase = true)
                val botCall = activeBotProfile?.callsign ?: ""

                // Evaluate immediately if ending in "K"
                if (hasEndingK) {
                    evaluateAccumulated()
                } else if (_state.value is QsoState.DitDitFinale) {
                    if (DIT_DIT_REGEX.containsMatchIn(trimmed) || trimmed.equals("EE", ignoreCase = true)) {
                        cqBuffer.clear()
                        _ditDitReceivedFlow.tryEmit(Unit)
                    }
                } else if (_state.value !is QsoState.ListenCQ &&
                    QsoValidator.validateDecode(_state.value, cqBuffer.toString(), botCall, userCallsign)
                ) {
                    // For states 2, 3, 4: evaluate immediately if input is already valid
                    evaluateAccumulated()
                }
            }
        }
    }

    /**
     * Evaluates accumulated Morse string using [QsoValidator].
     * If validation fails, triggers error recovery and remains in the exact same listening state.
     * If validation passes, transitions FSM to the next sequential state.
     */
    fun evaluateAccumulated() {
        val text = cqBuffer.toString().trim()
        cqBuffer.clear()
        if (text.isEmpty()) return

        val currentState = _state.value
        val botCall = activeBotProfile?.callsign ?: ""

        val isValid = QsoValidator.validateDecode(
            state = currentState,
            decodedText = text,
            botCallsign = botCall,
            userCallsign = userCallsign
        )

        if (!isValid) {
            triggerErrorRecovery("AGN AGN?")
            return
        }

        when (currentState) {
            is QsoState.Idle,
            is QsoState.ListenCQ -> {
                val callsign = extractCallsign(text)
                if (callsign != null) {
                    userCallsign = callsign
                    remoteCallsign = callsign
                }
                _state.value = QsoState.InitialResponse
            }

            is QsoState.InitialResponse -> {
                val report = extractReport(text)
                remoteReport = report ?: "599"
                _state.value = QsoState.InfoExchange
            }

            is QsoState.InfoExchange -> {
                _state.value = QsoState.SignOff
            }

            is QsoState.SignOff -> {
                _state.value = QsoState.DitDitFinale
                if (DIT_DIT_REGEX.containsMatchIn(text) || text.contains("EE", ignoreCase = true)) {
                    _ditDitReceivedFlow.tryEmit(Unit)
                }
            }

            is QsoState.DitDitFinale -> {
                if (DIT_DIT_REGEX.containsMatchIn(text) || text.contains("EE", ignoreCase = true)) {
                    _ditDitReceivedFlow.tryEmit(Unit)
                }
            }

            is QsoState.Error -> {
                _state.value = currentState.previousState
                evaluateAccumulated()
            }

            is QsoState.Summary -> {
                if (text.equals("RESET", ignoreCase = true) || text.equals("LOG", ignoreCase = true)) {
                    reset()
                }
            }

            // Legacy states
            is QsoState.TransmittingCQ -> {
                _state.value = QsoState.InitialResponse
            }
            is QsoState.ListeningForCaller -> {
                val callsign = extractCallsign(text)
                if (callsign != null) {
                    userCallsign = callsign
                    remoteCallsign = callsign
                }
                _state.value = QsoState.InitialResponse
            }
            is QsoState.Exchanging -> {
                val report = extractReport(text)
                remoteReport = report ?: "599"
                _state.value = QsoState.InfoExchange
            }
            is QsoState.LoggingContact -> {
                if (text.equals("RESET", ignoreCase = true) || text.equals("LOG", ignoreCase = true)) {
                    reset()
                }
            }
        }
    }

    /**
     * Backward-compatible alias for State 1 CQ evaluation.
     */
    fun evaluateCq() {
        if (cqBuffer.toString().trim().isEmpty()) return
        evaluateAccumulated()
    }

    /**
     * Handles silence watchdog timeouts across all listening states.
     * When 5 seconds of silence occur, passes accumulated string to [QsoValidator].
     * If the decode buffer is empty (user hasn't started keying yet), keeps listening without triggering error.
     * If validation fails, triggers error state and returns to listening state.
     */
    fun onSilenceTimeout() {
        when (_state.value) {
            is QsoState.ListenCQ,
            is QsoState.InitialResponse,
            is QsoState.InfoExchange,
            is QsoState.SignOff -> {
                val text = cqBuffer.toString().trim()
                if (text.isEmpty()) {
                    // Buffer is empty: user hasn't started keying yet. Keep listening, do not trigger error.
                    return
                }
                evaluateAccumulated()
            }
            is QsoState.DitDitFinale -> {
                val text = cqBuffer.toString().trim()
                cqBuffer.clear()
                if (text.isNotEmpty() && (DIT_DIT_REGEX.containsMatchIn(text) || text.contains("EE", ignoreCase = true))) {
                    _ditDitReceivedFlow.tryEmit(Unit)
                } else {
                    completeQso()
                }
            }
            else -> { /* No-op for terminal states */ }
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
     * Triggers error recovery: transitions FSM to [QsoState.Error], emits "AGN AGN?",
     * and returns to the exact same listening state.
     */
    fun triggerErrorRecovery(message: String = "AGN AGN?") {
        val listeningState = when (val s = _state.value) {
            is QsoState.Error -> s.previousState
            else -> s
        }
        _state.value = QsoState.Error(previousState = listeningState, message = message)
        _errorRecoveryFlow.tryEmit(message)
        _state.value = listeningState
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
