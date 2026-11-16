package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalFrame
import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState
import app.tennyo.mobile.link.pane.withClearedRefresh
import app.tennyo.mobile.link.pane.withLoaded
import app.tennyo.mobile.link.pane.withRefreshFailed
import app.tennyo.mobile.link.pane.withRefreshStarted

/** Every change to [TerminalState]; applied only through [reduce]. */
internal sealed interface TerminalMsg {
    data class Created(val tab: TerminalTab, val loadedAt: Long) : TerminalMsg
    data class Selected(val terminalId: String) : TerminalMsg
    data class PendingSelectionCleared(val terminalId: String) : TerminalMsg

    /** Closed by the user: the neighbour it hands the selection to stays pending until the IDE agrees. */
    data class Closed(val terminalId: String) : TerminalMsg

    /** The IDE no longer has [terminalId]. */
    data class Missing(val terminalId: String) : TerminalMsg
    data class TabsReceived(val tabs: TerminalTabsState) : TerminalMsg
    data class FrameReceived(val frame: TerminalFrame, val receivedAt: Long) : TerminalMsg

    /** A full HTTP snapshot of [terminalId] is loaded, including an intentionally empty terminal. */
    data class SnapshotLoaded(val terminalId: String, val loadedAt: Long) : TerminalMsg
    data class SnapshotRefreshStarted(val terminalId: String) : TerminalMsg
    data class SnapshotRefreshFailed(val terminalId: String) : TerminalMsg
    data class SnapshotRefreshCleared(val terminalId: String) : TerminalMsg
}

internal fun reduce(state: TerminalState, msg: TerminalMsg): TerminalState = when (msg) {
    is TerminalMsg.Created -> state.upsertCreatedTerminal(msg.tab).let { updated ->
        if (msg.tab.snapshot == null) {
            updated
        } else {
            updated.copy(snapshotFreshness = updated.snapshotFreshness.withLoaded(msg.tab.id, msg.loadedAt))
        }
    }

    is TerminalMsg.Selected -> state.copy(selectedTerminalId = msg.terminalId, pendingSelectionId = msg.terminalId)
    is TerminalMsg.PendingSelectionCleared ->
        state.copy(pendingSelectionId = state.pendingSelectionId.takeIf { it != msg.terminalId })

    is TerminalMsg.Closed -> state.removeTerminal(msg.terminalId, keepAdjacentSelectionPending = true)
    is TerminalMsg.Missing -> state.removeTerminal(msg.terminalId)
    is TerminalMsg.TabsReceived -> state.applyTerminalTabsState(msg.tabs)
    is TerminalMsg.FrameReceived -> {
        val updated = state.applyTerminalFrameState(msg.frame)
        val needsStyleRecovery = msg.frame.terminalId in updated.styleRecoveryTerminalIds
        if (!msg.frame.hasVisibleTerminalPayload() || needsStyleRecovery) {
            updated
        } else {
            updated.copy(
                snapshotFreshness = updated.snapshotFreshness.withLoaded(msg.frame.terminalId, msg.receivedAt),
            )
        }
    }

    is TerminalMsg.SnapshotLoaded ->
        if (state.selectedTerminal?.id != msg.terminalId) {
            state
        } else {
            state.copy(snapshotFreshness = state.snapshotFreshness.withLoaded(msg.terminalId, msg.loadedAt))
        }

    is TerminalMsg.SnapshotRefreshStarted -> state.copy(
        snapshotFreshness = state.snapshotFreshness
            .withRefreshStarted(msg.terminalId, state.snapshotRequestKind(msg.terminalId)),
    )

    is TerminalMsg.SnapshotRefreshFailed ->
        state.copy(snapshotFreshness = state.snapshotFreshness.withRefreshFailed(msg.terminalId))

    is TerminalMsg.SnapshotRefreshCleared ->
        state.copy(snapshotFreshness = state.snapshotFreshness.withClearedRefresh(msg.terminalId))
}
