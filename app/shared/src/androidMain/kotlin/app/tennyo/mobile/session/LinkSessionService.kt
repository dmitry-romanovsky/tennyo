package app.tennyo.mobile.session

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.endsSession
import app.tennyo.mobile.link.isDeviceNotApproved
import app.tennyo.mobile.link.session.AndroidSecureCredentialStorage
import app.tennyo.mobile.link.session.SessionCredentialStore
import app.tennyo.mobile.link.session.SessionStatus
import app.tennyo.mobile.link.session.StoredSessionReadResult
import app.tennyo.mobile.link.session.toLinkTicket
import app.tennyo.mobile.link.transport.LinkChannels
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class LinkSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications: SessionNotifications by lazy { SessionNotifications(this) }
    private val sessionStore: SessionCredentialStore by lazy {
        SessionCredentialStore(AndroidSecureCredentialStorage(this))
    }
    private val channels: LinkChannels by lazy { GlobalContext.get().get() }
    private val keepAlive: IdleKeepAlive by lazy {
        IdleKeepAlive(
            scope = serviceScope,
            sessionStore = sessionStore,
            ping = ::pingHost,
            onStatus = ::applyKeepAliveStatus,
            onTerminalFailure = ::endKeepAliveAfterTerminalFailure,
            onTimedOut = ::endKeepAliveAfterTimeout,
        )
    }

    private val destroyed = AtomicBoolean(false)
    private var currentArtifact: String = ""
    private var projectName: String = ""
    private var status: SessionStatus = SessionStatus.Connecting
    private var startedAtEpochMillis: Long = System.currentTimeMillis()
    private var ended: Boolean = false

    override fun onCreate() {
        super.onCreate()
        activeServiceInstances.incrementAndGet()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startPolicy = startPolicyForAction(intent?.action)
        if (startPolicy.startsForeground) {
            showNotification()
        }
        val requestedSessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (shouldReloadArtifactForAction(intent?.action) && requestedSessionId.isNotBlank()) {
            currentArtifact = reloadStoredArtifact(requestedSessionId)
        }
        when (intent?.action) {
            ACTION_END -> endSession(startId)
            ACTION_START_IDLE_KEEPALIVE -> beginIdleKeepAlive(intent, startId)
            ACTION_STOP_IDLE_KEEPALIVE -> stopIdleKeepAlive(requestedSessionId)
            null -> stopRestartedServiceWithoutIntent(startId)
            ACTION_UPDATE, ACTION_START -> {
                if (applySessionUpdate(intent, startId)) return START_NOT_STICKY
            }
        }
        return startPolicy.startResult
    }

    /** The stored ticket for [requestedSessionId], so a restarted service can pick the session back up. */
    private fun reloadStoredArtifact(requestedSessionId: String): String =
        kotlinx.coroutines.runBlocking {
            (sessionStore.readSecureActiveSession() as? StoredSessionReadResult.Found)
                ?.record
                ?.takeIf { it.sessionId == requestedSessionId }
                ?.artifact
                .orEmpty()
        }

    private fun endSession(startId: Int) {
        if (currentArtifact.isBlank()) {
            stopRestartedServiceWithoutIntent(startId)
            return
        }
        status = SessionStatus.EndingSession
        keepAlive.clear()
        showNotification()
        serviceScope.launch {
            try {
                clearStoredSession()
                endSessionRequests.tryEmit(currentArtifact)
                notifyHostSessionEndedSafely(
                    artifactRaw = currentArtifact,
                    failureMessage = "Unable to notify IDE companion about ended remote session",
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun beginIdleKeepAlive(intent: Intent, startId: Int) {
        if (currentArtifact.isBlank()) {
            stopRestartedServiceWithoutIntent(startId)
            return
        }
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty().ifBlank { projectName }
        status = intent.sessionStatus() ?: SessionStatus.Connected
        ended = false
        startedAtEpochMillis = intent.getLongExtra(EXTRA_STARTED_AT, startedAtEpochMillis)
        showNotification()
        keepAlive.start(artifactRaw = currentArtifact, startId = startId)
    }

    private fun stopIdleKeepAlive(requestedSessionId: String) {
        val matches = matchesIdleKeepAliveSession(
            activeSessionId = keepAlive.activeArtifact.toLinkTicket()?.sessionId,
            requestedSessionId = requestedSessionId,
        )
        if (!matches) return
        ended = false
        keepAlive.clear()
    }

    /** Whether the session ended with this update, so the service stops instead of staying in the foreground. */
    private fun applySessionUpdate(intent: Intent, startId: Int): Boolean {
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty().ifBlank { projectName }
        status = intent.sessionStatus() ?: status
        startedAtEpochMillis = intent.getLongExtra(EXTRA_STARTED_AT, startedAtEpochMillis)
        ended = intent.getBooleanExtra(EXTRA_ENDED, false)
        keepAlive.clear()
        showNotification()
        if (!ended) return false
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
        return true
    }

    override fun onDestroy() {
        destroyed.set(true)
        keepAlive.clear()
        activeServiceInstances.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Logger.w("Android stopped Tennyo remote idle keepalive after a foreground-service timeout")
        keepAlive.clear()
        status = SessionStatus.BackgroundPaused
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    /** Creates the channel if needed and (re)posts the ongoing notification the service runs in. */
    private fun showNotification() {
        if (destroyed.get()) return // a notification posted after onDestroy crashes
        try {
            notifications.createChannel()
            startForeground(
                SESSION_NOTIFICATION_ID,
                notifications.build(
                    SessionNotificationState(
                        projectName = projectName,
                        status = status,
                        startedAtEpochMillis = startedAtEpochMillis,
                        ended = ended,
                        sessionId = currentArtifact.toLinkTicket()?.sessionId,
                    ),
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Logger.w("Failed to show session notification", e)
        } catch (e: SecurityException) {
            Logger.w("Failed to show session notification", e)
        }
    }

    private fun stopRestartedServiceWithoutIntent(startId: Int) {
        Logger.w("Stopping Tennyo remote service restart without a session intent")
        keepAlive.clear()
        notifications.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    /** The keep-alive's status, shown only when it actually changes or when a reconnect just recovered. */
    private fun applyKeepAliveStatus(next: SessionStatus, force: Boolean) {
        if (!force && status == next) return
        status = next
        showNotification()
    }

    private suspend fun endKeepAliveAfterTerminalFailure(artifactRaw: String, startId: Int, message: String) {
        Logger.w("Ending the Tennyo remote session from idle keepalive: $message")
        clearStoredSession()
        endSessionRequests.tryEmit(artifactRaw)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private suspend fun endKeepAliveAfterTimeout(artifactRaw: String, startId: Int, cause: Throwable?) {
        cause?.let { Logger.w("Stopping Tennyo remote idle keepalive after ping timeout", it) }
            ?: Logger.w("Stopping Tennyo remote idle keepalive after ping timeout")
        status = SessionStatus.DisconnectedTimeout
        ended = true
        clearStoredSession()
        showNotification()
        notifyHostSessionEndedSafely(
            artifactRaw = artifactRaw,
            failureMessage = "Unable to notify IDE companion about timed out background remote session",
        )
        endSessionRequests.tryEmit(artifactRaw)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private suspend fun notifyHostSessionEndedSafely(
        artifactRaw: String,
        failureMessage: String,
    ) {
        val completed = runCatching {
            withTimeoutOrNull(END_NOTIFY_TIMEOUT_MILLIS.milliseconds) {
                notifyHostSessionEnded(artifactRaw)
            }
        }
        completed
            .onFailure { Logger.w(failureMessage, it) }
            .onSuccess { result ->
                if (result == null) {
                    Logger.w("$failureMessage: request timed out")
                }
            }
    }

    private suspend fun clearStoredSession() {
        sessionStore.clearSecureActiveSession()
    }

    /** One keep-alive ping: a stats call the host answers while the session is alive. */
    private suspend fun pingHost(artifactRaw: String): IdlePingResult {
        val ticket = artifactRaw.toLinkTicket()
            ?: return IdlePingResult.TerminalFailure("The active session ticket is no longer valid.")
        return runCatching { callOnChannel(ticket, LinkCall.Stats) }
            .fold(
                onSuccess = { IdlePingResult.Success },
                onFailure = { failure ->
                    if (failure.endsSession || failure.isDeviceNotApproved) {
                        IdlePingResult.TerminalFailure(failure.message ?: "The IDE session is no longer resumable.")
                    } else {
                        IdlePingResult.TransientFailure(failure)
                    }
                },
            )
    }

    private suspend fun notifyHostSessionEnded(artifactRaw: String) {
        val ticket = artifactRaw.toLinkTicket()
            ?: throw IllegalStateException("The active session ticket is no longer valid.")
        callOnChannel(ticket, LinkCall.StopSession)
    }

    /** Runs [call] on a channel opened for this one call, at the first address the ticket names. */
    private suspend fun callOnChannel(ticket: LinkTicket, call: LinkCall): LinkResult {
        val endpoint = ticket.endpointCandidates().firstOrNull()
            ?: throw LinkFault.Unreachable(LinkFault.UnreachableReason.NoEndpoints)
        return withContext(Dispatchers.IO) {
            withTimeout(BACKGROUND_CALL_TIMEOUT_MILLIS.milliseconds) {
                channels.call(ticket.withEndpoint(endpoint), call)
            }
        }
    }

    companion object {
        internal const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_PROJECT_NAME = "project_name"
        internal const val EXTRA_STATUS = "status"
        private const val EXTRA_STARTED_AT = "started_at"
        private const val EXTRA_ENDED = "ended"
        internal const val ACTION_START = "app.tennyo.mobile.session.action.START"
        internal const val ACTION_UPDATE = "app.tennyo.mobile.session.action.UPDATE"
        internal const val ACTION_START_IDLE_KEEPALIVE =
            "app.tennyo.mobile.session.action.START_IDLE_KEEPALIVE"
        internal const val ACTION_STOP_IDLE_KEEPALIVE = "app.tennyo.mobile.session.action.STOP_IDLE_KEEPALIVE"
        internal const val ACTION_END = "app.tennyo.mobile.session.action.END"
        private const val END_NOTIFY_TIMEOUT_MILLIS = 1_500L
        private const val BACKGROUND_CALL_TIMEOUT_MILLIS = 5_000L

        val endSessionRequests: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 1)

        internal fun startPolicyForAction(action: String?): SessionServiceStartPolicy =
            when (action) {
                null -> SessionServiceStartPolicy(
                    startResult = START_NOT_STICKY,
                    startsForeground = false,
                )

                ACTION_START_IDLE_KEEPALIVE,
                ACTION_END -> SessionServiceStartPolicy(startResult = START_NOT_STICKY)

                ACTION_STOP_IDLE_KEEPALIVE -> SessionServiceStartPolicy(
                    startResult = START_NOT_STICKY,
                    startsForeground = false,
                )

                else -> SessionServiceStartPolicy(startResult = START_STICKY)
            }

        internal fun commandDispatch(
            action: String,
            sessionId: String,
            serviceActive: Boolean,
        ): SessionServiceCommandDispatch = when (action) {
            ACTION_STOP_IDLE_KEEPALIVE -> if (sessionId.isBlank() || !serviceActive) {
                SessionServiceCommandDispatch.NoOp
            } else {
                SessionServiceCommandDispatch.StartService
            }

            else -> SessionServiceCommandDispatch.StartForegroundService
        }

        internal fun matchesIdleKeepAliveSession(
            activeSessionId: String?,
            requestedSessionId: String,
        ): Boolean = activeSessionId != null && activeSessionId == requestedSessionId

        internal fun shouldReloadArtifactForAction(action: String?): Boolean =
            action != null && action != ACTION_STOP_IDLE_KEEPALIVE

        internal fun idleKeepAliveTimedOut(
            lastSuccessfulPingAtEpochMillis: Long,
            nowEpochMillis: Long,
            timeoutMillis: Long,
        ): Boolean =
            lastSuccessfulPingAtEpochMillis > 0L &&
                    timeoutMillis > 0L &&
                    nowEpochMillis - lastSuccessfulPingAtEpochMillis > timeoutMillis

        internal fun idleKeepAliveDelayBeforeTimeout(
            lastSuccessfulPingAtEpochMillis: Long,
            nowEpochMillis: Long,
            retryDelayMillis: Long,
            timeoutMillis: Long,
        ): Long {
            if (lastSuccessfulPingAtEpochMillis <= 0L || timeoutMillis <= 0L) return retryDelayMillis
            val timeoutAtEpochMillis = lastSuccessfulPingAtEpochMillis + timeoutMillis
            val remainingBeforeTimeout = (timeoutAtEpochMillis - nowEpochMillis).coerceAtLeast(0L)
            return minOf(retryDelayMillis, remainingBeforeTimeout)
        }

        internal fun idleKeepAliveNotificationStateAfterPing(
            pingSucceeded: Boolean,
            lastSuccessfulPingAtEpochMillis: Long,
            nowEpochMillis: Long,
            timeoutMillis: Long,
        ): IdleKeepAliveNotificationState =
            when {
                pingSucceeded -> IdleKeepAliveNotificationState.BackgroundSync
                idleKeepAliveTimedOut(
                    lastSuccessfulPingAtEpochMillis = lastSuccessfulPingAtEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                    timeoutMillis = timeoutMillis,
                ) -> IdleKeepAliveNotificationState.DisconnectedTimeout

                else -> IdleKeepAliveNotificationState.Reconnecting
            }

        fun start(
            context: Context,
            sessionId: String,
            projectName: String,
            status: SessionStatus,
            startedAtEpochMillis: Long,
        ) {
            val intent = Intent(context, LinkSessionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
                .putExtra(EXTRA_STATUS, status.name)
                .putExtra(EXTRA_STARTED_AT, startedAtEpochMillis)
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(
            context: Context,
            sessionId: String,
            projectName: String,
            status: SessionStatus,
            startedAtEpochMillis: Long,
        ) {
            val intent = Intent(context, LinkSessionService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
                .putExtra(EXTRA_STATUS, status.name)
                .putExtra(EXTRA_STARTED_AT, startedAtEpochMillis)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startIdleKeepAlive(
            context: Context,
            sessionId: String,
            projectName: String,
            status: SessionStatus,
            startedAtEpochMillis: Long,
        ) {
            val intent = Intent(context, LinkSessionService::class.java)
                .setAction(ACTION_START_IDLE_KEEPALIVE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_PROJECT_NAME, projectName)
                .putExtra(EXTRA_STATUS, status.name)
                .putExtra(EXTRA_STARTED_AT, startedAtEpochMillis)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopIdleKeepAlive(context: Context, sessionId: String) {
            val dispatch = commandDispatch(
                action = ACTION_STOP_IDLE_KEEPALIVE,
                sessionId = sessionId,
                serviceActive = activeServiceInstances.get() > 0,
            )
            if (dispatch == SessionServiceCommandDispatch.NoOp) return
            val intent = Intent(context, LinkSessionService::class.java)
                .setAction(ACTION_STOP_IDLE_KEEPALIVE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            context.startService(intent)
        }

        /** The notification's End action, run from the app: the service tells the host and ends the session. */
        fun end(context: Context) {
            context.startService(Intent(context, LinkSessionService::class.java).setAction(ACTION_END))
        }

        fun removeNotification(context: Context) {
            val intent = Intent(context, LinkSessionService::class.java)
            context.stopService(intent)
            (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(SESSION_NOTIFICATION_ID)
        }

        private val activeServiceInstances = AtomicInteger(0)
    }
}

internal data class SessionServiceStartPolicy(
    val startResult: Int,
    val startsForeground: Boolean = true,
)

internal enum class SessionServiceCommandDispatch {
    NoOp,
    StartService,
    StartForegroundService,
}

internal enum class IdleKeepAliveNotificationState {
    BackgroundSync,
    Reconnecting,
    DisconnectedTimeout,
}

private fun Intent.sessionStatus(): SessionStatus? =
    getStringExtra(LinkSessionService.EXTRA_STATUS)?.let { name ->
        SessionStatus.entries.firstOrNull { it.name == name }
    }
