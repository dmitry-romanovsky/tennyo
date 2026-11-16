package app.tennyo.mobile.feature.terminal

import app.tennyo.mobile.link.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class TerminalInputDispatcher(
    private val scope: CoroutineScope,
    private val sendInput: suspend (terminalId: String, text: String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val buffer = StringBuilder()
    private var terminalId: String? = null
    private var drainJob: Job? = null

    fun send(terminalId: String, text: String) {
        if (text.isEmpty()) return
        if (this.terminalId != terminalId) {
            buffer.clear()
            this.terminalId = terminalId
        }
        buffer.append(text)
        if (drainJob?.isActive != true) {
            drainJob = scope.launch { drain(terminalId) }
        }
    }

    fun clear() {
        buffer.clear()
        terminalId = null
        drainJob?.cancel()
        drainJob = null
    }

    private suspend fun drain(initialTerminalId: String) {
        var activeTerminalId = initialTerminalId
        while (true) {
            delay(TERMINAL_INPUT_BATCH_DELAY_MILLIS)
            if (terminalId != activeTerminalId) {
                activeTerminalId = terminalId ?: return
            }
            if (buffer.isEmpty()) return
            val chunk = buffer.toString()
            buffer.clear()
            runCatchingCancellable { sendInput(activeTerminalId, chunk) }
                .onFailure { throwable ->
                    clear()
                    onFailure(throwable)
                    return
                }
        }
    }
}

private const val TERMINAL_INPUT_BATCH_DELAY_MILLIS = 16L
