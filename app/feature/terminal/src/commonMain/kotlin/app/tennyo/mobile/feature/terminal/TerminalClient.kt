package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkResult
import app.tennyo.contract.TerminalSnapshotResponse
import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState
import app.tennyo.mobile.link.LinkCalls
import app.tennyo.mobile.link.request

/**
 * The IDE's terminals: tabs and snapshots, and the typed input and select calls. Live output arrives as
 * pushes instead.
 */
internal class TerminalClient(private val link: LinkCalls) {
    suspend fun getTerminalTabs(): TerminalTabsState =
        link.request<LinkResult.TerminalTabs>(LinkCall.TerminalTabs).state

    suspend fun getTerminalSnapshot(terminalId: String, includeAllStyles: Boolean = false): TerminalSnapshotResponse =
        link.request<LinkResult.TerminalSnapshot>(LinkCall.TerminalSnapshot(terminalId, includeAllStyles)).snapshot

    suspend fun createTerminal(): TerminalTab = link.request<LinkResult.Terminal>(LinkCall.CreateTerminal).tab

    /** Shows the IDE's Terminal tool window; `false` when the IDE declined. */
    suspend fun ensureTerminalOpen(): Boolean =
        link.request<LinkResult.Done>(LinkCall.OpenTerminalWindow).accepted

    suspend fun selectTerminal(terminalId: String): Boolean =
        link.request<LinkResult.Done>(LinkCall.SelectTerminal(terminalId)).accepted

    suspend fun sendTerminalInput(terminalId: String, text: String): Boolean =
        link.request<LinkResult.Done>(LinkCall.TerminalInput(terminalId, text)).accepted

    suspend fun closeTerminal(terminalId: String) {
        link.call(LinkCall.CloseTerminal(terminalId))
    }
}
