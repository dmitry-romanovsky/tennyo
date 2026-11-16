package app.tennyo.mobile.workspace

import app.tennyo.mobile.link.ConnectStep
import app.tennyo.mobile.link.DegradedReason
import app.tennyo.mobile.link.EndReason
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.LiveSync

/**
 * The workspace's view of [link]: which screen step shows, how the chrome describes the connection, and what the
 * banner explains. Fields a pane action set (busy, an action issue) survive while the link stays live.
 */
internal fun WorkspaceState.withLink(link: LinkState, ticket: LinkTicket?): WorkspaceState =
    when (link) {
        is LinkState.Connecting -> connecting(link.step)
        is LinkState.Pairing -> copy(
            connectionState = ConnectionState.CONNECTING,
            screenStep = WorkspaceStep.Pairing,
            pairingConfirmation = link.confirmation,
            issue = null,
            unexpectedConnectionError = null,
        )

        is LinkState.Live -> live(link.sync)
        is LinkState.Degraded -> degraded(link.reason, ticket)
        is LinkState.Ended -> ended(link.reason, ticket)
    }

private fun WorkspaceState.connecting(step: ConnectStep): WorkspaceState =
    copy(
        connectionState = if (step == ConnectStep.Resuming) ConnectionState.CONNECTED else ConnectionState.CONNECTING,
        screenStep = when (step) {
            ConnectStep.AwaitingApproval -> WorkspaceStep.WaitingForIde
            ConnectStep.Reaching, ConnectStep.Resuming -> WorkspaceStep.Initiating
        },
        pairingConfirmation = null,
        issue = when (step) {
            ConnectStep.Reaching -> WorkspaceIssue.Known.Connecting
            ConnectStep.AwaitingApproval -> WorkspaceIssue.Known.WaitingForApproval
            ConnectStep.Resuming -> null
        },
        unexpectedConnectionError = null,
    )

private fun WorkspaceState.live(sync: LiveSync): WorkspaceState {
    val wasLive = connectionState == ConnectionState.CONNECTED && screenStep == WorkspaceStep.Connected
    val settled = !wasLive || sync == LiveSync.Ready
    return copy(
        connectionState = ConnectionState.CONNECTED,
        screenStep = WorkspaceStep.Connected,
        liveSyncState = when (sync) {
            LiveSync.Attaching -> LiveSyncState.CONNECTING
            LiveSync.Ready -> LiveSyncState.READY
            LiveSync.Reattaching -> LiveSyncState.RECONNECTING
        },
        pairingConfirmation = null,
        issue = if (settled) null else issue,
        unexpectedConnectionError = if (settled) null else unexpectedConnectionError,
        dismissedConnectionMessageKind = if (settled) null else dismissedConnectionMessageKind,
    )
}

private fun WorkspaceState.degraded(reason: DegradedReason, ticket: LinkTicket?): WorkspaceState =
    when (reason) {
        DegradedReason.Reconnecting -> copy(
            connectionState = ConnectionState.RECONNECTING,
            screenStep = WorkspaceStep.Connected,
            liveSyncState = LiveSyncState.RECONNECTING,
            issue = WorkspaceIssue.Known.Interrupted,
            unexpectedConnectionError = null,
        )

        is DegradedReason.Lost -> unavailable(ConnectionState.FAILED, WorkspaceIssue.Known.Lost, reason.fault)
        is DegradedReason.Failed ->
            unavailable(ConnectionState.FAILED, reason.fault.toWorkspaceIssue(ticket), reason.fault)
    }

private fun WorkspaceState.ended(reason: EndReason, ticket: LinkTicket?): WorkspaceState =
    copy(
        connectionState = when (reason) {
            EndReason.FromNotification -> ConnectionState.DISCONNECTED
            else -> ConnectionState.NEEDS_NEW_LINK
        },
        liveSyncState = LiveSyncState.DISCONNECTED,
        screenStep = WorkspaceStep.Unavailable,
        issue = reason.toWorkspaceIssue(ticket),
        unexpectedConnectionError = null,
        pairingConfirmation = null,
    )

private fun WorkspaceState.unavailable(
    connectionState: ConnectionState,
    issue: WorkspaceIssue,
    fault: LinkFault,
): WorkspaceState =
    copy(
        connectionState = connectionState,
        screenStep = WorkspaceStep.Unavailable,
        liveSyncState = LiveSyncState.FAILED,
        issue = issue,
        unexpectedConnectionError = fault.toUnexpectedConnectionError(issue),
    )

internal fun EndReason.toWorkspaceIssue(ticket: LinkTicket?): WorkspaceIssue = when (this) {
    EndReason.ByUser -> WorkspaceIssue.Known.SessionEnded
    EndReason.FromNotification -> WorkspaceIssue.Known.Disconnected
    EndReason.TimedOut -> WorkspaceIssue.Known.TimedOut
    EndReason.PairingWordsExpired -> WorkspaceIssue.Known.PairingWordsExpired
    EndReason.PairingStopped -> WorkspaceIssue.Known.PairingStoppedTooManyAttempts
    is EndReason.ByHost -> message?.let(WorkspaceIssue::Reported) ?: WorkspaceIssue.Known.SessionEnded
    is EndReason.Fault -> fault.toWorkspaceIssue(ticket)
}

/** How a failed connection is explained; the host's own words win when it sent any. */
internal fun LinkFault.toWorkspaceIssue(ticket: LinkTicket?): WorkspaceIssue = when (this) {
    is LinkFault.Host -> message?.takeIf { it.isNotBlank() }?.let(WorkspaceIssue::Reported)
        ?: WorkspaceIssue.Known.RequestFailed

    is LinkFault.ProtocolMismatch -> WorkspaceIssue.Known.SecurityVersionMismatch
    is LinkFault.PairingGridUnusable -> WorkspaceIssue.Known.PairingGridUnusable
    is LinkFault.StreamClosed -> if (beforeLiveSync) {
        WorkspaceIssue.Known.EventStreamClosedBeforeLiveSync
    } else {
        WorkspaceIssue.Known.EventStreamClosed
    }

    is LinkFault.Unreachable -> when (reason) {
        LinkFault.UnreachableReason.LocalOnlyAddresses -> WorkspaceIssue.Known.LocalOnlyAddresses
        LinkFault.UnreachableReason.NoEndpoints,
        LinkFault.UnreachableReason.UnpinnedAddress -> WorkspaceIssue.Known.NoEndpoints
    }

    is LinkFault.Transport ->
        if (isConnectTimeout) {
            WorkspaceIssue.HostUnreachable(ticket?.endpointCandidates().orEmpty().joinToString())
        } else {
            message?.takeIf { it.isNotBlank() }?.let(WorkspaceIssue::Reported) ?: WorkspaceIssue.ConnectFailed
        }
}

/** A failure the app did not anticipate keeps its stack trace behind "Show full error". */
internal fun LinkFault.toUnexpectedConnectionError(issue: WorkspaceIssue): UnexpectedConnectionError? =
    if (isUnexpected) UnexpectedConnectionError(issue, (cause ?: this).stackTraceToString()) else null

internal val LinkFault.isUnexpected: Boolean
    get() = this is LinkFault.Transport && !isConnectTimeout
