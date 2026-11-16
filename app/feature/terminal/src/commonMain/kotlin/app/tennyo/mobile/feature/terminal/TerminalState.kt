package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalTab
import app.tennyo.mobile.link.pane.ContentFreshness
import app.tennyo.mobile.link.pane.ContentRefreshState
import app.tennyo.mobile.link.pane.ContentRequestKind
import app.tennyo.mobile.link.pane.canStartRefresh
import app.tennyo.mobile.link.pane.needsRefresh

/** The terminal pane: tabs, streamed snapshots, and the compact-style cache they decode against. */
data class TerminalState(
    val tabs: List<TerminalTab> = emptyList(),
    val snapshots: Map<String, TerminalSnapshot> = emptyMap(),
    val snapshotRevisions: Map<String, Long> = emptyMap(),
    val snapshotFreshness: Map<String, ContentFreshness> = emptyMap(),
    val styleEpoch: Long = 0,
    val styleDefinitions: Map<Int, TerminalStyleDefinition> = emptyMap(),
    /** Terminals whose next snapshot must carry every style, after a frame referenced one the phone lacks. */
    val styleRecoveryTerminalIds: Set<String> = emptySet(),
    val selectedTerminalId: String? = null,
    /** A terminal selected on the phone that the IDE has not confirmed yet. */
    val pendingSelectionId: String? = null,
) {
    val safeSelectedIndex: Int
        get() = tabs.indexOfFirst { it.id == selectedTerminalId }.takeIf { it >= 0 } ?: 0

    val selectedTerminal: TerminalTab?
        get() = selectedTerminalId?.let { id -> tabs.firstOrNull { it.id == id } } ?: tabs.firstOrNull()
}

fun TerminalState.snapshotNeedsRefresh(terminalId: String, nowEpochMillis: Long): Boolean =
    snapshotFreshness.needsRefresh(terminalId, nowEpochMillis)

fun TerminalState.snapshotCanStartRefresh(terminalId: String): Boolean = snapshotFreshness.canStartRefresh(terminalId)

fun TerminalState.snapshotRequestKind(terminalId: String): ContentRequestKind =
    if (snapshotHasRenderableContent(terminalId)) ContentRequestKind.Refresh else ContentRequestKind.InitialLoad

fun TerminalState.snapshotHasRenderableContent(terminalId: String): Boolean {
    val terminal = tabs.firstOrNull { it.id == terminalId } ?: return false
    val snapshot = snapshots[terminalId] ?: terminal.snapshot
    return snapshotFreshness[terminalId]?.hasLoadedOnce == true ||
        snapshot?.text?.isNotEmpty() == true ||
        terminal.text.isNotEmpty()
}

fun TerminalState.selectedSnapshotShowsLoader(nowEpochMillis: Long): Boolean {
    val terminal = selectedTerminal ?: return false
    return !snapshotHasRenderableContent(terminal.id) &&
        (snapshotFreshness[terminal.id]?.refreshState == ContentRefreshState.Loading ||
            snapshotNeedsRefresh(terminal.id, nowEpochMillis))
}

/** The requests the terminal pane may need for one sync pass. */
enum class TerminalRequest {
    Tabs,
    SelectedSnapshot,
}

fun TerminalState.syncRequests(
    nowEpochMillis: Long,
    metadata: Boolean,
    explicitContent: Boolean = false,
): Set<TerminalRequest> = buildSet {
    if (metadata) add(TerminalRequest.Tabs)
    val terminal = selectedTerminal ?: return@buildSet
    val wanted = explicitContent || snapshotNeedsRefresh(terminal.id, nowEpochMillis)
    if (wanted && snapshotCanStartRefresh(terminal.id)) {
        add(TerminalRequest.SelectedSnapshot)
    }
}
