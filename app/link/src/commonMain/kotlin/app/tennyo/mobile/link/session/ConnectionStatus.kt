package app.tennyo.mobile.link.session

import app.tennyo.contract.SessionTimeouts
import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/**
 * The connection bookkeeping the handshake, event-stream, ping, reconnect, background-idle and persistence parts of a
 * session share. None of it is shown; the shown part is [app.tennyo.mobile.link.LinkState].
 */
internal data class ConnectionSnapshot(
    val sessionEndedByHost: Boolean = false,
    val sessionConnectedInMemory: Boolean = false,
    val hasPersistedResumableSession: Boolean = false,
    val channelLossStartedAtEpochMillis: Long? = null,
    /** The last push this device applied; a reconnect resumes after it. */
    val lastPushSeq: Long? = null,
    val pendingSecurityTicket: LinkTicket? = null,
    val reconnectAttempts: Int = 0,
    val notificationStartedAtEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    val approvedDeviceIdleTimeoutMillis: Long = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS,
    val lastSuccessfulPingAtEpochMillis: Long = 0L,
    val lastPersistedPingAtEpochMillis: Long = 0L,
)

/**
 * The single owner of [ConnectionSnapshot]. The parts share this holder but can only read the current snapshot or
 * replace it atomically through [update], so multi-field transitions land together.
 */
internal class ConnectionStatus(initial: ConnectionSnapshot = ConnectionSnapshot()) {
    private val state = MutableStateFlow(initial)

    val current: ConnectionSnapshot get() = state.value

    fun update(transform: (ConnectionSnapshot) -> ConnectionSnapshot) {
        state.update(transform)
    }

    /** Records when the channel was first lost, keeping the earliest time across retries; returns it. */
    fun markChannelLoss(nowEpochMillis: Long): Long {
        update {
            it.copy(channelLossStartedAtEpochMillis = it.channelLossStartedAtEpochMillis ?: nowEpochMillis)
        }
        return checkNotNull(current.channelLossStartedAtEpochMillis)
    }

    val sessionEndedByHost: Boolean get() = current.sessionEndedByHost
    val sessionConnectedInMemory: Boolean get() = current.sessionConnectedInMemory
    val hasPersistedResumableSession: Boolean get() = current.hasPersistedResumableSession
    val channelLossStartedAtEpochMillis: Long? get() = current.channelLossStartedAtEpochMillis
    val lastPushSeq: Long? get() = current.lastPushSeq
    val pendingSecurityTicket: LinkTicket? get() = current.pendingSecurityTicket
    val reconnectAttempts: Int get() = current.reconnectAttempts
    val notificationStartedAtEpochMillis: Long get() = current.notificationStartedAtEpochMillis
    val approvedDeviceIdleTimeoutMillis: Long get() = current.approvedDeviceIdleTimeoutMillis
    val lastSuccessfulPingAtEpochMillis: Long get() = current.lastSuccessfulPingAtEpochMillis
    val lastPersistedPingAtEpochMillis: Long get() = current.lastPersistedPingAtEpochMillis

    /** Whether reconnecting should resume the host session this device already holds rather than claim a new one. */
    val shouldResume: Boolean
        get() = !sessionEndedByHost && (sessionConnectedInMemory || hasPersistedResumableSession)
}
