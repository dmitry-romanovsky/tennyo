package app.tennyo.host.engine.rpc

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkLimits
import app.tennyo.contract.LinkResult
import app.tennyo.host.engine.session.HostSession

internal fun HostSession.createTerminal(): LinkResult =
    LinkResult.Terminal(
        terminal.createTerminal()
            ?: throw CallFault(LinkFaultCode.UNSUPPORTED, "The terminal on this computer is unavailable."),
    )

internal fun HostSession.terminalSnapshot(call: LinkCall.TerminalSnapshot): LinkResult =
    LinkResult.TerminalSnapshot(
        terminal.terminalSnapshot(call.terminalId, call.allStyles)
            ?: throw CallFault(LinkFaultCode.TARGET_MISSING, "Terminal tab is no longer available."),
    )

/** Typed input; a refusal also shows as an error line in the terminal the user is looking at. */
internal fun HostSession.terminalInput(call: LinkCall.TerminalInput): LinkResult {
    if (call.text.isEmpty()) return LinkResult.Done(accepted = false)
    if (call.text.utf8SizeExceeds(LinkLimits.MAX_TERMINAL_INPUT_BYTES)) {
        showTerminalError(call.terminalId, "Terminal input is too large.\n")
        return LinkResult.Done(accepted = false)
    }
    val written = terminal.writeTerminal(call.terminalId, call.text)
    if (!written) showTerminalError(call.terminalId, "Terminal is not running.\n")
    return LinkResult.Done(written)
}

internal fun HostSession.selectTerminal(call: LinkCall.SelectTerminal): LinkResult {
    val selected = terminal.selectTerminal(call.terminalId)
    if (!selected) showTerminalError(call.terminalId, "Terminal tab is no longer available.\n")
    return LinkResult.Done(selected)
}

private fun HostSession.showTerminalError(terminalId: String, message: String) {
    val tabs = terminal.tabs(includeSnapshots = false)
    val target = terminal.selectedTerminalId()?.takeIf { selected -> tabs.any { it.id == selected } }
        ?: tabs.firstOrNull()?.id
        ?: terminalId
    terminal.emitTerminalTextFrame(target, message, isError = true)
}
