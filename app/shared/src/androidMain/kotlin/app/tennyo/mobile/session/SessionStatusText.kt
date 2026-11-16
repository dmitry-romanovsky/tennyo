package app.tennyo.mobile.session

import androidx.annotation.StringRes
import app.tennyo.mobile.R
import app.tennyo.mobile.link.session.SessionStatus

@get:StringRes
internal val SessionStatus.notificationText: Int
    get() = when (this) {
        SessionStatus.CheckingReachability -> R.string.session_status_checking_reachability
        SessionStatus.WaitingForApproval -> R.string.session_status_waiting_for_approval
        SessionStatus.ConfirmPairing -> R.string.session_status_confirm_pairing
        SessionStatus.ConfirmingPairing -> R.string.session_status_confirming_pairing
        SessionStatus.Connecting -> R.string.session_status_connecting
        SessionStatus.Connected -> R.string.session_status_connected
        SessionStatus.Reconnecting -> R.string.session_status_reconnecting
        SessionStatus.ConnectionLost -> R.string.session_status_connection_lost
        SessionStatus.ConnectionFailed -> R.string.session_status_connection_failed
        SessionStatus.PairingFailed -> R.string.session_status_pairing_failed
        SessionStatus.DisconnectedTimeout -> R.string.session_status_disconnected_timeout
        SessionStatus.Ended -> R.string.session_status_ended
        SessionStatus.EndingSession -> R.string.session_status_ending
        SessionStatus.BackgroundSync -> R.string.session_status_background_sync
        SessionStatus.BackgroundPaused -> R.string.session_status_background_paused
    }
