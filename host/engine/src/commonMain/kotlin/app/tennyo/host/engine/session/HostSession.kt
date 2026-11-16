package app.tennyo.host.engine.session

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.ActiveSurface
import app.tennyo.contract.Capability
import app.tennyo.contract.ChatState
import app.tennyo.contract.LinkPush
import app.tennyo.contract.SessionEndReason
import app.tennyo.contract.SessionTimeouts
import app.tennyo.contract.allowsMetadataPanel
import app.tennyo.contract.selectedVisibleTerminalId
import app.tennyo.host.engine.agents.AgentServer
import app.tennyo.host.engine.agents.ChatBackend
import app.tennyo.host.engine.agents.ChatController
import app.tennyo.host.engine.agents.ChatRouter
import app.tennyo.host.engine.agents.IdeAgents
import app.tennyo.host.engine.panes.EditorPaneController
import app.tennyo.host.engine.panes.GitPaneController
import app.tennyo.host.engine.panes.LaunchPaneController
import app.tennyo.host.engine.panes.ProjectPanes
import app.tennyo.host.engine.panes.TerminalPaneController
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.hostWallClockMillis
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.atomics.AtomicLong

/**
 * One session of one project: its ticket, deadlines and capabilities, the pane shown on the phone, and a
 * controller per pane. The calls a channel carries reach the controllers through `rpc`; what they push goes out
 * through [owner].
 */
class HostSession(
    val artifact: SessionTicket,
    val owner: SessionHost,
    val parentScope: CoroutineScope,
    private val listenerLease: ListenerLease? = null,
    val wallClockMillis: () -> Long = ::hostWallClockMillis,
) {
    /**
     * The ticket as it is served now. The listener's addresses move while the session lives, so the ticket a phone
     * receives is rebuilt from the lease; [artifact] stays the identity the session was started with.
     */
    fun currentTicket(): SessionTicket {
        val endpoints = runCatching { listenerLease?.endpoints?.invoke() }.getOrNull() ?: return artifact
        return artifact.copy(endpoint = endpoints.primary, alternateEndpoints = endpoints.ticketAlternates())
    }

    val surfaces: ProjectPanes get() = owner.surfaces
    val startedAtEpochMillis: Long = wallClockMillis()
    val expiresAtEpochMillis: Long = startedAtEpochMillis + SessionTimeouts.ABSOLUTE_SESSION_TIMEOUT_MILLIS
    private val lastObservedEpochMillis = AtomicLong(startedAtEpochMillis)
    val activeSurface = ActivePaneTracker()

    val sessionScope = CoroutineScope(
        parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineName("Tennyo session ${artifact.sessionId}"),
    )

    val auth = SessionAuthController(this)
    val stats = SessionStatsController(this)
    val git = GitPaneController(this)
    val editor = EditorPaneController(this)
    val launch = LaunchPaneController(this)
    val terminal = TerminalPaneController(this)
    val chat: ChatBackend? = surfaces.agents?.let(::chatRouter)

    /** The ACP agents this host launches first, then every other agent source it has. */
    private fun chatRouter(agents: IdeAgents): ChatBackend {
        val acpServers: List<AgentServer?> = agents.servers().ifEmpty { listOf(null) }
        val acp = acpServers.map { server ->
            { publish: ChatPublisher ->
                ChatController(
                    agents = IdeAgents(agents.launcher, servers = { server?.let(::listOf).orEmpty() }),
                    files = surfaces.files,
                    scope = sessionScope,
                    publish = publish,
                )
            }
        }
        val others = agents.sources.map { source ->
            { publish: ChatPublisher -> source.backend(sessionScope, publish) }
        }
        return ChatRouter(publish = { push(LinkPush.Chat(it)) }, backends = acp + others)
    }

    fun hasApprovedDeviceTimedOut(nowEpochMillis: Long = monotonicNow()): Boolean =
        auth.hasApprovedDeviceTimedOut(nowEpochMillis)

    fun expirationReason(nowEpochMillis: Long = monotonicNow()): SessionEndReason? {
        val effectiveNow = monotonicNow(nowEpochMillis)
        return absoluteExpirationReason(effectiveNow) ?: nonAbsoluteExpirationReason(effectiveNow)
    }

    fun absoluteExpirationReason(nowEpochMillis: Long = monotonicNow()): SessionEndReason? =
        if (monotonicNow(nowEpochMillis) >= expiresAtEpochMillis) SessionEndReason.AbsoluteExpired else null

    fun nonAbsoluteExpirationReason(nowEpochMillis: Long = monotonicNow()): SessionEndReason? =
        auth.nonAbsoluteExpirationReason(monotonicNow(nowEpochMillis))

    /** Keeps server-clock rollback from extending a security deadline. */
    fun monotonicNow(candidateEpochMillis: Long = wallClockMillis()): Long {
        while (true) {
            val observed = lastObservedEpochMillis.load()
            if (candidateEpochMillis <= observed) return observed
            if (lastObservedEpochMillis.compareAndSet(observed, candidateEpochMillis)) return candidateEpochMillis
        }
    }

    /** Sends [push] to the session's channel, if one is open, and keeps it for a client that resumes later. */
    fun push(push: LinkPush) {
        owner.push(artifact.sessionId, push)
    }

    fun emitStats() = stats.emitStats()

    fun capabilities(): Set<Capability> = artifact.capabilities

    fun hasCapability(capability: Capability): Boolean = capability in capabilities()

    fun emitCapabilities() = push(LinkPush.Capabilities(capabilities()))

    fun shouldEmitPanelMetadata(panel: String): Boolean = activeSurface.get().allowsMetadataPanel(panel)

    /** After a channel opens: the state a client may have missed, for whatever it shows. */
    fun emitConvergenceSnapshot() {
        emitCapabilities()
        emitActiveSurfaceMetadata()
        emitActiveSurfaceSelectedSnapshot()
        emitStats()
    }

    /** Applies a shown pane queued by [ActivePaneTracker.queue], and pushes what that pane now needs. */
    fun showQueuedSurface(surface: ActiveSurface) {
        if (!activeSurface.applyQueued(surface)) return
        LOG.debug {
            "Tennyo shown pane updated panel=${surface.panel} terminal=${surface.selectedTerminalId != null}"
        }
        emitActiveSurfaceMetadata()
        emitActiveSurfaceSelectedSnapshot()
    }

    private fun emitActiveSurfaceMetadata() {
        when (activeSurface.get()?.panel) {
            ActivePanels.FILES -> editor.emitEditorTabs()
            ActivePanels.LAUNCH -> {
                launch.emitLaunchConfigurations()
                launch.emitLaunchTabs()
            }

            ActivePanels.TERMINAL -> terminal.emitTerminalTabs()
            ActivePanels.GIT -> git.emitGitInvalidated()
        }
    }

    private fun emitActiveSurfaceSelectedSnapshot() {
        val surface = activeSurface.get() ?: return
        when (surface.panel) {
            ActivePanels.FILES -> editor.emitOpenFileContents()
            ActivePanels.TERMINAL -> terminal.emitSelectedTerminalFrame(surface.selectedVisibleTerminalId())
        }
    }

    /**
     * Closes the controllers newest first, as the IDE disposer did, then releases the listener and the scope. Like the
     * disposer, a failing step is logged and the rest still launch, so the lease and the scope are never leaked.
     */
    fun close() {
        listOf(
            { chat?.close() },
            terminal::close,
            launch::close,
            git::close,
            stats::close,
            auth::close,
            { listenerLease?.release() },
        ).forEach { step ->
            runCatching(step).onFailure { LOG.warn("Unable to close part of a Tennyo session", it) }
        }
        sessionScope.cancel()
    }

    private companion object {
        private val LOG = HostLogger("HostSession")
    }
}

private typealias ChatPublisher = (ChatState) -> Unit
