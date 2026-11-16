package app.tennyo.mobile.session

import app.tennyo.contract.SessionTimeouts
import app.tennyo.mobile.link.session.SessionCredentialStore
import app.tennyo.mobile.link.session.SessionStatus
import app.tennyo.mobile.link.session.toLinkTicket
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val DEBOUNCE_MILLIS = 5_000L
private const val INTERVAL_MILLIS = 15_000L
private const val MAX_BACKOFF_MILLIS = 60_000L

/** What one keep-alive ping came back with. */
internal sealed interface IdlePingResult {
    data object Success : IdlePingResult

    data class TransientFailure(val throwable: Throwable) : IdlePingResult

    data class TerminalFailure(val message: String) : IdlePingResult
}

/**
 * Keeps a backgrounded session warm: it pings the host on an interval, backs off while pings fail, and gives up
 * once the host's own idle timeout has passed, so the phone and the IDE end the session at the same moment.
 */
internal class IdleKeepAlive(
    private val scope: CoroutineScope,
    private val sessionStore: SessionCredentialStore,
    private val ping: suspend (artifactRaw: String) -> IdlePingResult,
    private val onStatus: (status: SessionStatus, force: Boolean) -> Unit,
    private val onTerminalFailure: suspend (artifactRaw: String, startId: Int, message: String) -> Unit,
    private val onTimedOut: suspend (artifactRaw: String, startId: Int, cause: Throwable?) -> Unit,
) {
    private var job: Job? = null
    private var artifactRaw: String = ""
    private var lastSuccessfulPingAtEpochMillis: Long = 0L
    private var timeoutMillis: Long = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS
    private var reconnecting: Boolean = false

    /** The session this keep-alive is running for, or `""` when it is not running. */
    val activeArtifact: String get() = artifactRaw

    fun start(artifactRaw: String, startId: Int) {
        if (artifactRaw.isBlank()) return
        if (job?.isActive == true && this.artifactRaw == artifactRaw) return
        clear()
        this.artifactRaw = artifactRaw
        lastSuccessfulPingAtEpochMillis = System.currentTimeMillis()
        timeoutMillis = SessionTimeouts.APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS
        job = scope.launch { run(artifactRaw, startId) }
    }

    fun clear() {
        job?.cancel()
        job = null
        artifactRaw = ""
        reconnecting = false
    }

    private suspend fun run(artifactRaw: String, startId: Int) {
        delay(DEBOUNCE_MILLIS.milliseconds)
        onStatus(statusFor(IdleKeepAliveNotificationState.BackgroundSync), true)
        var failureCount = 0
        var running = true
        while (running) {
            running = when (val result = ping(artifactRaw)) {
                IdlePingResult.Success -> {
                    failureCount = 0
                    recordSuccessfulPing(artifactRaw)
                    true
                }

                is IdlePingResult.TerminalFailure -> {
                    Logger.w("Stopping Tennyo remote idle keepalive: ${result.message}")
                    finish()
                    onTerminalFailure(artifactRaw, startId, result.message)
                    false
                }

                is IdlePingResult.TransientFailure -> {
                    failureCount += 1
                    handleTransientFailure(artifactRaw, startId, result.throwable)
                }
            }
            if (!running) break
            delay(retryDelayMillis(failureCount).milliseconds)
            if (failureCount > 0 && hasTimedOut()) {
                finish()
                onTimedOut(artifactRaw, startId, null)
                running = false
            }
        }
    }

    private suspend fun recordSuccessfulPing(artifactRaw: String) {
        val now = System.currentTimeMillis()
        artifactRaw.toLinkTicket()?.let { ticket -> sessionStore.recordSuccessfulPing(ticket.sessionId, now) }
        lastSuccessfulPingAtEpochMillis = now
        val nextStatus = statusFor(
            LinkSessionService.idleKeepAliveNotificationStateAfterPing(
                pingSucceeded = true,
                lastSuccessfulPingAtEpochMillis = lastSuccessfulPingAtEpochMillis,
                nowEpochMillis = now,
                timeoutMillis = timeoutMillis,
            ),
        )
        val wasReconnecting = reconnecting
        reconnecting = false
        onStatus(nextStatus, wasReconnecting)
    }

    /** Whether the loop carries on after a failed ping. */
    private suspend fun handleTransientFailure(artifactRaw: String, startId: Int, cause: Throwable): Boolean {
        reconnecting = true
        Logger.w("Unable to ping IDE companion from idle keepalive", cause)
        val state = LinkSessionService.idleKeepAliveNotificationStateAfterPing(
            pingSucceeded = false,
            lastSuccessfulPingAtEpochMillis = lastSuccessfulPingAtEpochMillis,
            nowEpochMillis = System.currentTimeMillis(),
            timeoutMillis = timeoutMillis,
        )
        return when (state) {
            IdleKeepAliveNotificationState.BackgroundSync -> true

            IdleKeepAliveNotificationState.Reconnecting -> {
                onStatus(statusFor(IdleKeepAliveNotificationState.Reconnecting), false)
                true
            }

            IdleKeepAliveNotificationState.DisconnectedTimeout -> {
                finish()
                onTimedOut(artifactRaw, startId, cause)
                false
            }
        }
    }

    private fun retryDelayMillis(failureCount: Int): Long {
        if (failureCount == 0) return INTERVAL_MILLIS
        val backoff = (INTERVAL_MILLIS * failureCount).coerceAtMost(MAX_BACKOFF_MILLIS)
        return LinkSessionService.idleKeepAliveDelayBeforeTimeout(
            lastSuccessfulPingAtEpochMillis = lastSuccessfulPingAtEpochMillis,
            nowEpochMillis = System.currentTimeMillis(),
            retryDelayMillis = backoff,
            timeoutMillis = timeoutMillis,
        )
    }

    private fun hasTimedOut(): Boolean =
        LinkSessionService.idleKeepAliveTimedOut(
            lastSuccessfulPingAtEpochMillis = lastSuccessfulPingAtEpochMillis,
            nowEpochMillis = System.currentTimeMillis(),
            timeoutMillis = timeoutMillis,
        )

    /** Drops the loop's own state before the service takes over; the coroutine is already on its way out. */
    private fun finish() {
        job = null
        artifactRaw = ""
        reconnecting = false
    }

    private fun statusFor(state: IdleKeepAliveNotificationState): SessionStatus =
        when (state) {
            IdleKeepAliveNotificationState.BackgroundSync -> SessionStatus.BackgroundSync
            IdleKeepAliveNotificationState.Reconnecting -> SessionStatus.Reconnecting
            IdleKeepAliveNotificationState.DisconnectedTimeout -> SessionStatus.DisconnectedTimeout
        }
}
