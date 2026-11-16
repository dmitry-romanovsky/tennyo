package app.tennyo.mobile.link.session

import app.tennyo.contract.LinkCall
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.SessionStats
import app.tennyo.contract.SessionTimeouts
import app.tennyo.mobile.link.DegradedReason
import app.tennyo.mobile.link.EndReason
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LiveSync
import app.tennyo.mobile.link.endsSession
import app.tennyo.mobile.link.toLinkFault
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns connection loss: reconnect backoff, the connection and pairing timeouts, and every path that ends the session.
 * Health reported by the ping loop and the event stream lands here too, since it is what clears a loss.
 */
@Suppress("LongParameterList")
internal class Reconnector(
    private val core: SessionCore,
    private val status: ConnectionStatus,
    private val backgroundIdle: BackgroundIdleState,
    private val persistence: SessionPersistence,
    private val notification: NotificationBridge,
    private val connect: (resume: Boolean) -> Unit,
    private val stopLiveSync: () -> Unit,
    private val cancelConnect: () -> Unit,
) {
    private var connectionTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * The common core of every path that ends the session: stop treating it as resumable and stop its jobs. Callers
     * add the site-specific cleanup and the [LinkState.Ended] reason.
     */
    private fun beginTeardown() {
        status.update { it.copy(sessionEndedByHost = true, sessionConnectedInMemory = false) }
        backgroundIdle.clear()
        persistence.clear()
        stopLiveSync()
        clearConnectionTimeout()
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun end(reason: EndReason, notificationStatus: SessionStatus) {
        core.attachment?.suspendSync(forBackground = false)
        persistence.clearCheckpoint()
        notification.update(notificationStatus)
        core.linkState = LinkState.Ended(reason)
    }

    fun handleSessionExpired(message: String?) {
        beginTeardown()
        end(EndReason.ByHost(message?.takeIf { it.isNotBlank() }), SessionStatus.Ended)
    }

    fun handleConnectionFailure(throwable: Throwable) {
        Logger.w("Link connection failed", throwable)
        val fault = throwable.toLinkFault()
        when {
            fault.endsSession -> {
                beginTeardown()
                end(EndReason.Fault(fault), SessionStatus.Ended)
            }

            core.state.value == LinkState.Degraded(DegradedReason.Reconnecting) ->
                markConnectionLost(retrying = false, throwable = fault)

            else -> {
                notification.update(SessionStatus.ConnectionFailed)
                core.linkState = LinkState.Degraded(DegradedReason.Failed(fault))
            }
        }
    }

    fun scheduleReconnect(throwable: Throwable) {
        Logger.w("Link sync failed", throwable)
        if (backgroundIdle.markSyncDetachedForBackground()) return
        if (status.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            markConnectionLost(retrying = false, throwable = throwable)
            return
        }
        status.update { it.copy(reconnectAttempts = it.reconnectAttempts + 1) }
        markConnectionLost(retrying = true, throwable = throwable)
        reconnectJob?.cancel()
        reconnectJob = core.scope.launch {
            delay((RECONNECT_DELAY_MILLIS * status.reconnectAttempts).milliseconds)
            if (backgroundIdle.markSyncDetachedForBackground()) return@launch
            connect(true)
        }
    }

    fun markConnectionHealthy(lastPingMillis: Long?, stats: SessionStats?) {
        if (status.sessionEndedByHost) return
        clearConnectionTimeout()
        status.update { it.copy(reconnectAttempts = 0) }
        val current = core.state.value
        val wasDisconnected = current is LinkState.Degraded && current.reason !is DegradedReason.Failed
        if (wasDisconnected) {
            notification.update(SessionStatus.Connected)
        }
        val connected = core.channel.value != null
        val sync = if (connected) LiveSync.Ready else LiveSync.Reattaching
        when {
            wasDisconnected -> core.linkState = LinkState.Live(sync)
            connected -> core.updateLive { LinkState.Live(LiveSync.Ready) }
        }
        core.stats.value = when {
            stats != null -> stats.withLocalSessionStats(status, previous = core.stats.value, lastPingMillis)
            lastPingMillis != null -> core.stats.value.copy(lastPingMillis = lastPingMillis)
            else -> core.stats.value
        }
    }

    fun markConnectionLost(retrying: Boolean, throwable: Throwable) {
        Logger.w("Link connection lost; retrying=$retrying", throwable)
        startConnectionTimeout()
        if (!retrying) core.attachment?.suspendSync(forBackground = false)
        notification.update(if (retrying) SessionStatus.Reconnecting else SessionStatus.ConnectionLost)
        core.linkState = LinkState.Degraded(
            if (retrying) DegradedReason.Reconnecting else DegradedReason.Lost(throwable.toLinkFault()),
        )
    }

    fun startConnectionTimeout() {
        if (connectionTimeoutJob?.isActive == true) return
        connectionTimeoutJob = core.scope.launch {
            delay(CONNECTION_TIMEOUT_MILLIS.milliseconds)
            if (!status.sessionEndedByHost) endAfterTimeout(EndReason.TimedOut, SessionStatus.DisconnectedTimeout)
        }
    }

    fun restartPairingConfirmationTimeout(challenge: PairingChallenge) {
        clearConnectionTimeout()
        connectionTimeoutJob = core.scope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            delay((challenge.expiresAtEpochMillis - now).coerceAtLeast(0L).milliseconds)
            if (!status.sessionEndedByHost && core.state.value is LinkState.Pairing) {
                endAfterTimeout(EndReason.PairingWordsExpired, SessionStatus.PairingFailed)
            }
        }
    }

    fun clearConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    /** Ends the session on a timeout and tells the IDE, so its popup does not keep showing this device. */
    private suspend fun endAfterTimeout(reason: EndReason, notificationStatus: SessionStatus) {
        connectionTimeoutJob = null
        stopQuietly()
        beginTeardown()
        cancelConnect()
        status.update { it.copy(pendingSecurityTicket = null) }
        end(reason, notificationStatus)
    }

    /** Tells the IDE the session ended over the open channel, so its popup stops showing this device. */
    suspend fun stopQuietly() {
        val channel = core.channel.value ?: return
        runCatching {
            withTimeoutOrNull(SESSION_STOP_NOTIFY_TIMEOUT_MILLIS.milliseconds) { channel.call(LinkCall.StopSession) }
        }
            .onFailure { Logger.w("Unable to tell the IDE the session ended", it) }
            .onSuccess { if (it == null) Logger.w("Telling the IDE the session ended timed out") }
    }

    fun showPairingStoppedAfterTooManyAttempts() = endPairing(EndReason.PairingStopped)

    fun showPairingWordsExpired() = endPairing(EndReason.PairingWordsExpired)

    private fun endPairing(reason: EndReason) {
        beginTeardown()
        status.update { it.copy(pendingSecurityTicket = null) }
        end(reason, SessionStatus.PairingFailed)
    }

    /** Stops everything without choosing an end reason; the caller publishes one. */
    fun cancelForTeardown() {
        beginTeardown()
        cancelConnect()
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_DELAY_MILLIS = 1_000L
        const val CONNECTION_TIMEOUT_MILLIS = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS
        const val SESSION_STOP_NOTIFY_TIMEOUT_MILLIS = 1_500L
    }
}
