package app.tennyo.mobile.link.session

import kotlinx.coroutines.flow.Flow

/** What the platform session notification says about the session right now. */
enum class SessionStatus {
    CheckingReachability,
    WaitingForApproval,
    ConfirmPairing,
    ConfirmingPairing,
    Connecting,
    Connected,
    Reconnecting,
    ConnectionLost,
    ConnectionFailed,
    PairingFailed,
    DisconnectedTimeout,
    Ended,
    EndingSession,
    BackgroundSync,
    BackgroundPaused,
}

data class SessionNotificationInfo(
    val sessionId: String,
    val status: SessionStatus,
    val projectName: String?,
    val startedAtEpochMillis: Long,
)

interface SessionNotification {
    val endSessionRequests: Flow<String>

    fun start(info: SessionNotificationInfo)
    fun update(info: SessionNotificationInfo)
    fun startIdleKeepAlive(info: SessionNotificationInfo) = update(info)
    fun stopIdleKeepAlive(sessionId: String) = Unit
    fun stop(sessionId: String)

    /** Ends the session the notification shows, exactly as its End action does. */
    fun endSession()
}
