package app.tennyo.mobile.link.session

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkPush
import app.tennyo.contract.LinkResult
import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.SessionStats
import app.tennyo.mobile.link.ActiveSurface
import app.tennyo.mobile.link.EndReason
import app.tennyo.mobile.link.LinkAttachment
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkSession
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pairing.FirstPairing
import app.tennyo.mobile.link.pairing.RememberedPairResume
import app.tennyo.mobile.link.pairing.SessionEndpoints
import app.tennyo.mobile.link.transport.LinkChannels
import app.tennyo.mobile.link.transport.LinkHttp
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** What a session needs from the rest of the link layer; one set is shared by every session the app opens. */
class LinkServices(
    val http: LinkHttp,
    val endpoints: SessionEndpoints,
    val firstPairing: FirstPairing,
    val rememberedPairResume: RememberedPairResume,
    val channels: LinkChannels,
    val credentials: SessionCredentialStore,
    val notification: SessionNotification,
)

/**
 * The session behind one ticket. It wires the parts — [Connector], [Reconnector], [ChannelAttachment], [PingLoop],
 * [BackgroundIdle] — around a shared [SessionCore], consumes the host pushes about itself and forwards the rest to the
 * workspace.
 */
class RealLinkSession(
    private val sessionTicket: LinkTicket,
    private val services: LinkServices,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : LinkSession {
    private val scope = CoroutineScope(SupervisorJob() + mainContext)
    private val core = SessionCore(scope)
    private val status = ConnectionStatus()
    private val signals = LateBoundLifecycleSignals()
    private val idleState = BackgroundIdleState()
    private val pingPolicy = PingPolicy()
    private val notification = NotificationBridge(core, services.notification, status)
    private val persistence = SessionPersistence(services.credentials, core, status)
    private val surfaceCommands = SurfaceCommands(core)
    private val pingLoop = PingLoop(core, status, persistence, idleState, pingPolicy, signals)
    private val channel = ChannelAttachment(
        core = core,
        status = status,
        channels = services.channels,
        backgroundIdle = idleState,
        surfaceCommands = surfaceCommands,
        notification = notification,
        signals = signals,
        onPush = ::dispatch,
    )
    private val connector = Connector(
        core = core,
        status = status,
        endpoints = services.endpoints,
        firstPairing = services.firstPairing,
        rememberedPairResume = services.rememberedPairResume,
        persistence = persistence,
        notification = notification,
        signals = signals,
        startLiveSync = { ticket ->
            channel.start(ticket)
            pingLoop.start(ticket)
        },
    )
    private val reconnector = Reconnector(
        core = core,
        status = status,
        backgroundIdle = idleState,
        persistence = persistence,
        notification = notification,
        connect = connector::connect,
        stopLiveSync = {
            channel.cancel()
            pingLoop.cancel()
        },
        cancelConnect = connector::cancel,
    )
    private val backgroundIdle = BackgroundIdle(
        core = core,
        status = status,
        state = idleState,
        persistence = persistence,
        notification = notification,
        pingPolicy = pingPolicy,
        detachSync = {
            channel.detachForBackground()
            pingLoop.cancel()
            core.attachment?.suspendSync(forBackground = true)
        },
        connect = connector::connect,
    )
    private var started = false

    /** Set once the user ends the session, so the host's confirming [LinkPush.SessionEnded] is not its own end. */
    private var endingByUser = false

    override val state: StateFlow<LinkState> = core.state
    override val ticket: StateFlow<LinkTicket?> = core.ticket.asStateFlow()
    override val project: StateFlow<LinkProject> = core.project.asStateFlow()
    override val stats: StateFlow<SessionStats> = core.stats.asStateFlow()
    override val pushes: Flow<LinkFrame.Push> = core.pushes.asSharedFlow()

    init {
        signals.wire(
            object : LifecycleSignals {
                override fun markConnectionHealthy(lastPingMillis: Long?, stats: SessionStats?) =
                    reconnector.markConnectionHealthy(lastPingMillis, stats)

                override fun markConnectionLost(retrying: Boolean, throwable: Throwable) =
                    reconnector.markConnectionLost(retrying, throwable)

                override fun scheduleReconnect(throwable: Throwable) = reconnector.scheduleReconnect(throwable)

                override fun handleConnectionFailure(throwable: Throwable) =
                    reconnector.handleConnectionFailure(throwable)

                override fun startConnectionTimeout() = reconnector.startConnectionTimeout()

                override fun clearConnectionTimeout() = reconnector.clearConnectionTimeout()

                override fun restartPairingConfirmationTimeout(challenge: PairingChallenge) =
                    reconnector.restartPairingConfirmationTimeout(challenge)

                override fun showPairingStoppedAfterTooManyAttempts() =
                    reconnector.showPairingStoppedAfterTooManyAttempts()

                override fun showPairingWordsExpired() = reconnector.showPairingWordsExpired()

                override fun connect(resume: Boolean) = connector.connect(resume)
            },
        )
        services.notification.endSessionRequests
            .onEach { endedArtifact -> if (matches(endedArtifact.toLinkTicket())) endFromNotification() }
            .launchIn(scope)
    }

    /** Whether [other] names this session, possibly through an earlier ticket for the same session. */
    fun matches(other: LinkTicket?): Boolean {
        if (other == null || other == sessionTicket) return true
        return other.namesSameSessionAs(core.ticket.value ?: sessionTicket)
    }

    private fun LinkTicket.namesSameSessionAs(other: LinkTicket): Boolean =
        sessionId == other.sessionId && token == other.token

    override suspend fun call(call: LinkCall): LinkResult {
        val channel = core.channel.value ?: throw LinkFault.StreamClosed(beforeLiveSync = true)
        return channel.call(call)
    }

    override fun attach(attachment: LinkAttachment) {
        core.attachment = attachment
        if (started) {
            // New panes over a session that already ran: a channel that stayed open never reopens to fill them, so
            // they load over it now; otherwise the next open loads them even when the host resumes.
            core.markAttachmentFresh()
            if (core.channel.value != null) scope.launch { channel.loadSnapshotIfDue() }
            onForeground()
        } else {
            started = true
            start()
        }
    }

    override fun detach() {
        core.attachment = null
        channel.cancelRetry()
        connector.cancel()
        reconnector.clearConnectionTimeout()
        backgroundIdle.enterNow()
    }

    private fun start() {
        scope.launch {
            val ticket = sessionTicket
            core.ticket.value = ticket
            persistence.restore(ticket)
            services.notification.stopIdleKeepAlive(ticket.sessionId)
            // The ticket names the project, but capabilities come from the hello alone.
            core.project.value = LinkProject(name = ticket.projectName, path = ticket.projectPath)
            core.stats.value = core.stats.value.copy(
                sessionStartedAtEpochMillis = status.notificationStartedAtEpochMillis,
            )
            notification.start(SessionStatus.CheckingReachability)
            connector.connect(resume = status.shouldResume)
        }
    }

    override fun retry() = connector.connect(resume = status.shouldResume)

    override fun end() {
        endingByUser = true
        scope.launch {
            notification.remove()
            // The stop travels on the live channel, and teardown cancels that channel: stopping first is what lets
            // the host hear it. A host that never hears it keeps the session claimed and stops advertising.
            reconnector.stopQuietly()
            reconnector.cancelForTeardown()
            persistence.clearCheckpoint()
            core.attachment?.suspendSync(forBackground = false)
            core.linkState = LinkState.Ended(EndReason.ByUser)
        }
    }

    private fun endFromNotification() {
        reconnector.cancelForTeardown()
        persistence.clearCheckpoint()
        core.attachment?.suspendSync(forBackground = false)
        core.linkState = LinkState.Ended(EndReason.FromNotification)
    }

    override fun togglePairingWord(word: String) = connector.togglePairingWord(word)

    override fun clearPairingWords() = connector.clearPairingWords()

    override fun confirmPairing() = connector.confirmPairing()

    override fun refreshStats() = pingLoop.refreshStats()

    override fun setStatsVisible(visible: Boolean) = pingLoop.setStatsVisible(visible)

    override fun showSurface(surface: ActiveSurface, force: Boolean) = surfaceCommands.show(surface, force)

    override fun onForeground() = backgroundIdle.onForeground()

    override fun onBackground() = backgroundIdle.schedule()

    /** Stops everything this session runs. It is unusable afterwards. */
    fun close() {
        scope.cancel()
    }

    override fun acknowledge(push: LinkFrame.Push) = persistence.checkpoint(push.seq)

    /**
     * Applies one host push. Pushes at or before the checkpoint were already applied and are dropped. The session
     * checkpoints the pushes about itself; the workspace acknowledges the ones it applies.
     */
    private suspend fun dispatch(frame: LinkFrame.Push) {
        if (isReplay(frame)) return
        when (val push = frame.push) {
            // The host confirms a stop this device asked for with the same push; that end is the user's.
            is LinkPush.SessionEnded -> handled(frame) {
                if (!endingByUser) reconnector.handleSessionExpired(push.reason)
            }
            is LinkPush.Stats -> handled(frame) {
                core.stats.value = push.stats.withLocalSessionStats(status, previous = core.stats.value)
            }

            is LinkPush.Capabilities -> {
                core.project.value = core.project.value.copy(capabilities = push.capabilities)
                core.pushes.emit(frame)
            }

            else -> core.pushes.emit(frame)
        }
    }

    private inline fun handled(frame: LinkFrame.Push, apply: () -> Unit) {
        runCatching { apply() }
            .onSuccess { persistence.checkpoint(frame.seq) }
            .onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                Logger.e("Applying push ${frame.seq} failed; it will not be checkpointed", throwable)
            }
    }

    private fun isReplay(frame: LinkFrame.Push): Boolean = frame.seq <= (status.lastPushSeq ?: 0L)
}
