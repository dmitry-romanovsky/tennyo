package app.tennyo.mobile.workspace

import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.generated.resources.Res
import app.tennyo.mobile.generated.resources.connect_local_only_addresses
import app.tennyo.mobile.generated.resources.connecting_description
import app.tennyo.mobile.generated.resources.connection_interrupted
import app.tennyo.mobile.generated.resources.connection_lost
import app.tennyo.mobile.generated.resources.connection_reason_disconnected
import app.tennyo.mobile.generated.resources.connection_timed_out
import app.tennyo.mobile.generated.resources.event_stream_closed
import app.tennyo.mobile.generated.resources.event_stream_closed_before_live_sync
import app.tennyo.mobile.generated.resources.no_remote_endpoints_available
import app.tennyo.mobile.generated.resources.pairing_confirmation_failed
import app.tennyo.mobile.generated.resources.pairing_grid_unusable
import app.tennyo.mobile.generated.resources.pairing_incorrect_selection
import app.tennyo.mobile.generated.resources.pairing_security_version_mismatch
import app.tennyo.mobile.generated.resources.pairing_stopped_too_many_attempts
import app.tennyo.mobile.generated.resources.pairing_words_expired
import app.tennyo.mobile.generated.resources.remote_request_failed
import app.tennyo.mobile.generated.resources.session_ended
import app.tennyo.mobile.generated.resources.tcp_connection_not_accepted
import app.tennyo.mobile.generated.resources.tcp_connection_not_accepted_at
import app.tennyo.mobile.generated.resources.unable_to_connect_to_remote_ide
import app.tennyo.mobile.generated.resources.waiting_approval_description
import app.tennyo.mobile.link.PairingIssue
import org.jetbrains.compose.resources.StringResource

/**
 * Why the workspace is not simply live, as its banner or unavailable screen explains it. Logic branches on the type;
 * only the UI turns it into text.
 */
sealed interface WorkspaceIssue {
    /** An issue the app words itself. */
    enum class Known(val text: StringResource) : WorkspaceIssue {
        Connecting(Res.string.connecting_description),
        WaitingForApproval(Res.string.waiting_approval_description),
        Interrupted(Res.string.connection_interrupted),
        Lost(Res.string.connection_lost),
        TimedOut(Res.string.connection_timed_out),
        Disconnected(Res.string.connection_reason_disconnected),
        SessionEnded(Res.string.session_ended),
        PairingWordsExpired(Res.string.pairing_words_expired),
        PairingStoppedTooManyAttempts(Res.string.pairing_stopped_too_many_attempts),
        PairingGridUnusable(Res.string.pairing_grid_unusable),
        SecurityVersionMismatch(Res.string.pairing_security_version_mismatch),
        EventStreamClosedBeforeLiveSync(Res.string.event_stream_closed_before_live_sync),
        EventStreamClosed(Res.string.event_stream_closed),
        LocalOnlyAddresses(Res.string.connect_local_only_addresses),
        NoEndpoints(Res.string.no_remote_endpoints_available),
        RequestFailed(Res.string.remote_request_failed),
    }

    /** The host could not be reached over TCP at [endpoints] (empty when the ticket named none). */
    data class HostUnreachable(val endpoints: String) : WorkspaceIssue

    /** Text the host sent, or the transport's own description of a failure; shown as received. */
    data class Reported(val text: String) : WorkspaceIssue

    /** A connection failure with no description of its own. */
    data object ConnectFailed : WorkspaceIssue
}

val WorkspaceIssue.text: UiText
    get() = when (this) {
        is WorkspaceIssue.Known -> UiText.Resource(text)
        is WorkspaceIssue.HostUnreachable ->
            if (endpoints.isBlank()) {
                UiText.Resource(Res.string.tcp_connection_not_accepted)
            } else {
                UiText.Resource(Res.string.tcp_connection_not_accepted_at, endpoints)
            }

        is WorkspaceIssue.Reported -> UiText.Verbatim(text)
        WorkspaceIssue.ConnectFailed -> UiText.Resource(Res.string.unable_to_connect_to_remote_ide)
    }

val PairingIssue.text: UiText
    get() = when (this) {
        PairingIssue.ConfirmationFailed -> UiText.Resource(Res.string.pairing_confirmation_failed)
        PairingIssue.IncorrectSelection -> UiText.Resource(Res.string.pairing_incorrect_selection)
        is PairingIssue.Reported -> UiText.Verbatim(text)
    }
