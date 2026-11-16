package app.tennyo.mobile.link.session

import app.tennyo.mobile.link.DegradedReason
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background-idle mode: once the app has been in the background for a while the event stream and ping loop stop and
 * the platform notification keeps the session alive on its own; coming back resumes foreground sync.
 */
@Suppress("LongParameterList")
internal class BackgroundIdle(
    private val core: SessionCore,
    private val status: ConnectionStatus,
    private val state: BackgroundIdleState,
    private val persistence: SessionPersistence,
    private val notification: NotificationBridge,
    private val pingPolicy: PingPolicy,
    private val detachSync: () -> Unit,
    private val connect: (resume: Boolean) -> Unit,
) {
    private var idleJob: Job? = null

    fun onForeground() {
        pingPolicy.visibility = AppVisibility.Foreground
        idleJob?.cancel()
        idleJob = null
        val shouldResume = state.cancel()
        notification.stopIdleKeepAlive()
        if (shouldResume && core.ticket.value != null && !status.sessionEndedByHost) connect(true)
    }

    /** Enters idle mode after a short debounce, so a quick app switch does not drop live sync. */
    fun schedule() {
        val ticket = prepare() ?: return
        if (idleJob?.isActive == true) return
        idleJob = core.scope.launch {
            delay(DEBOUNCE_MILLIS.milliseconds)
            enter(ticket)
        }
    }

    /** Enters idle mode now: the workspace is gone, so nothing on screen needs live sync. */
    fun enterNow() {
        val ticket = prepare() ?: return
        idleJob?.cancel()
        idleJob = null
        enter(ticket)
    }

    private fun prepare(): LinkTicket? {
        val ticket = core.ticket.value?.takeIf { keepsAliveInBackground() } ?: return null
        persistence.saveActiveSession(idleKeepAliveActive = false)
        state.request()
        notification.startIdleKeepAlive(core.projectName.ifBlank { ticket.projectName })
        return ticket
    }

    private fun enter(ticket: LinkTicket) {
        if (status.sessionEndedByHost || core.ticket.value?.sessionId != ticket.sessionId) return
        pingPolicy.visibility = AppVisibility.Background
        state.enter()
        persistence.saveActiveSession(idleKeepAliveActive = true)
        detachSync()
    }

    /** Only a session that is (or is getting) live is worth keeping; a failed or pairing one is not. */
    private fun keepsAliveInBackground(): Boolean {
        val current = core.state.value
        return !status.sessionEndedByHost && status.sessionConnectedInMemory &&
            (current is LinkState.Live || current == LinkState.Degraded(DegradedReason.Reconnecting))
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 5_000L
    }
}
