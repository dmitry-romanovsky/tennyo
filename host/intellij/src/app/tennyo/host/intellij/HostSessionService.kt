package app.tennyo.host.intellij

import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.contract.ProjectChoice
import app.tennyo.contract.SessionEndReason
import app.tennyo.host.engine.discovery.DiscoveryProject
import app.tennyo.host.engine.pairing.RememberedPairRecord
import app.tennyo.host.engine.pairing.RememberedPairStore
import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.panel.DiscoveryBeforePairing
import app.tennyo.host.engine.platform.debugQuotedOrNone
import app.tennyo.host.engine.platform.debugShortId
import app.tennyo.host.engine.platform.debugShortIdOrNone
import app.tennyo.host.engine.session.ConnectionRequest
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.HostPlatform
import app.tennyo.host.engine.session.HostRegistry
import app.tennyo.host.engine.session.HostSession
import app.tennyo.host.engine.session.ListenerLease
import app.tennyo.host.engine.session.OpenPairingWindow
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.SessionTicket
import app.tennyo.host.engine.session.hasClaimedSession
import app.tennyo.host.engine.session.latestSuccessfulRemoteConnectionAt
import app.tennyo.host.engine.session.openPairingWindow
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentWithExecutorListener
import com.intellij.ide.ActivityTracker
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.ThreadingAssertions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The IntelliJ side of one project's companion. The protocol lives in [host]; this service builds it from IntelliJ
 * surfaces, feeds it the IDE's launch, editor, and document events, shows its prompts as notifications, and serves the
 * popup and discovery.
 */
@Service(Service.Level.PROJECT)
class HostSessionService(
    internal val project: Project,
    coroutineScope: CoroutineScope,
) : Disposable, DiscoveryProject {
    private var discoveryListenerLease: ServerLease? = null
    private val connectionRequestNotifications = ConcurrentHashMap<String, Notification>()
    private val pendingFileContentBroadcasts = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val runTabIdsByHandler = ConcurrentHashMap<ProcessHandler, String>()
    private val rememberedPairStore: RememberedPairStore =
        PasswordSafePairStore(globalRememberedPairAttributes())
    private val globalSettings: HostGlobalSettings = service()
    private val lifecycleLock = Any()
    private val discoveryBeforePairing = DiscoveryBeforePairing()

    override val host = SessionHost(
        surfaces = projectPanes(project),
        platform = IdeHostPlatform(),
        // Session work blocks on git processes and EDT hand-offs, so it runs on the IO pool rather than Default.
        scope = CoroutineScope(coroutineScope.coroutineContext + Dispatchers.IO),
    )

    val isStartingSession: Boolean get() = host.isStartingSession

    override val projectId: String get() = project.locationHash

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                val configurationId = env.runnerAndConfigurationSettings?.uniqueID ?: return
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        host.broadcastLaunchOutput(
                            configurationId,
                            event.text,
                            isRunning = !handler.isProcessTerminated,
                            tabId = runTabIdsByHandler[handler] ?: handler.remoteLaunchTabId()
                        )
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        val tabId = runTabIdsByHandler.remove(handler) ?: handler.remoteLaunchTabId()
                        host.broadcastLaunchOutput(
                            configurationId,
                            "\nProcess finished with exit code ${event.exitCode}\n",
                            isRunning = false,
                            tabId = tabId,
                        )
                        host.flushLaunchOutput(tabId)
                        host.broadcastLaunchTabs()
                    }
                })
            }

            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                val configurationId = env.runnerAndConfigurationSettings?.uniqueID ?: return
                val tabId = findLaunchTabId(handler)
                runTabIdsByHandler[handler] = tabId
                host.broadcastLaunchOutput(
                    configurationId,
                    "Process started: ${env.runProfile.name}\n",
                    isRunning = true,
                    tabId = tabId
                )
                host.broadcastLaunchTabs()
            }
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                host.broadcastEditorTabs()
                broadcastFileContent(file)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                host.broadcastEditorTabs()
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                host.broadcastEditorTabs()
                event.newFile?.let(::broadcastFileContent)
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                broadcastFileContentDebounced(file)
            }
        }, this)
        connection.subscribe(RunManagerListener.TOPIC, object : RunManagerListener {
            override fun runConfigurationSelected(settings: RunnerAndConfigurationSettings?) {
                host.broadcastLaunchConfigurations()
            }

            override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
                host.broadcastLaunchConfigurations()
            }

            override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) {
                host.broadcastLaunchConfigurations()
            }

            override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) {
                host.broadcastLaunchConfigurations()
            }
        })
        connection.subscribe(RunContentManager.TOPIC, object : RunContentWithExecutorListener {
            override fun contentSelected(descriptor: RunContentDescriptor?, executor: Executor) {
                descriptor?.remoteLaunchTabId()?.let(host::flushLaunchOutput)
                host.broadcastLaunchTabs()
            }

            override fun contentRemoved(descriptor: RunContentDescriptor?, executor: Executor) {
                descriptor?.remoteLaunchTabId()?.let(host::flushLaunchOutput)
                descriptor?.processHandler?.let(runTabIdsByHandler::remove)
                host.broadcastLaunchTabs()
            }
        })
        HostRegistry.register(host)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { refreshDiscoveryListener() }
                .onFailure { LOG.warn("Unable to refresh Tennyo Companion discovery listener", it) }
        }
        globalSettings.registerSessionService(project, this)
    }

    fun startSession(): SessionTicket = host.startSession()

    /**
     * Opens this host to devices it has never met: the window itself, plus the LAN advertisement a phone browses to
     * find it. Enabling discovery here is what makes "All" reachable — a window alone is invisible on the network.
     */
    fun openPairingWindow(): OpenPairingWindow {
        discoveryBeforePairing.remember(isDiscoveryEnabled())
        val window = host.openPairingWindow()
        setDiscoveryEnabled(enabled = true)
        return window
    }

    /** The user picked "No one" or "Known devices" themselves; ending the session must not undo that. */
    internal fun forgetModeBeforePairing() = discoveryBeforePairing.forget()

    fun activeSession(): HostSession? = host.activeSession()

    fun isClientConnected(sessionId: String): Boolean = host.isClientConnected(sessionId)

    fun connectedDeviceName(sessionId: String): String? = host.connectedDeviceName(sessionId)

    fun connectedDeviceId(sessionId: String): String? = host.connectedDeviceId(sessionId)

    fun stopSession(
        sessionId: String,
        reason: String,
        endReason: SessionEndReason? = null,
    ): Boolean = host.stopSession(sessionId, reason, endReason)

    fun pendingConnectionRequests(sessionId: String): List<ConnectionRequest> =
        host.pendingConnectionRequests(sessionId)

    fun acceptConnectionRequest(sessionId: String, requestId: String): Boolean =
        host.acceptConnectionRequest(sessionId, requestId)

    fun rejectConnectionRequest(sessionId: String, requestId: String): Boolean =
        host.rejectConnectionRequest(sessionId, requestId)

    fun validate(sessionId: String?, token: String?): HostSession? = host.validate(sessionId, token)

    internal fun companionId(): String = globalSettings.companionId()

    internal fun ideVersion(): String = ApplicationInfo.getInstance().fullVersion

    override fun discoveryEndpoints(): HostEndpoints = discoveryEndpointsInternal()

    override fun discoveryDescriptor(): KnownHostDescriptor =
        KnownHostDescriptor(
            companionId = companionId(),
            companionLabel = ideLabel(),
            projectName = project.name,
            hostVersion = ideVersion(),
            lastUsedAtEpochMillis = latestSuccessfulRemoteConnectionAt(
                companionId = companionId(),
                pairs = rememberedPairStore.summaries(),
            ),
            lastKnownNetworkLabel = shortenProjectHomePath(project.basePath ?: ""),
        )

    internal fun rememberedPairs(): List<RememberedPairSummary> = host.rememberedPairs()

    override fun isDiscoveryEnabled(): Boolean = globalSettings.isEnabled()

    override fun isDiscoveryAvailable(): Boolean =
        globalSettings.isOwner(project) &&
                globalSettings.isEnabled() &&
                !globalSettings.hasActiveSession()

    override fun discoveryBlockedMessage(): String? = globalSettings.blockedMessage()

    internal fun isDiscoveryBlockedBannerDismissed(): Boolean = globalSettings.blockedBannerDismissed()

    internal fun dismissDiscoveryBlockedBanner() {
        globalSettings.dismissBlockedBanner()
    }

    internal fun setDiscoveryEnabled(enabled: Boolean) {
        globalSettings.setEnabled(project, enabled)
        tennyoDiscoveryDebug(LOG) {
            "set discovery enabled=$enabled sessions=${host.sessionCount} starting=${host.isStartingSession} " +
                    "project=${project.name.debugQuotedOrNone()}"
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { refreshDiscoveryListener() }
                .onFailure { LOG.warn("Unable to update Tennyo Companion discovery listener", it) }
        }
    }

    /** Turns Remote access on or off and rebinds the listener at once, rather than at the next 30-second tick. */
    internal fun setRemoteAccess(enabled: Boolean) {
        globalSettings.setRemoteAccess(enabled)
        service<LinkServerService>().reconcile()
    }

    internal fun resetDiscoveryTrust() {
        globalSettings.resetDiscoveryTrust()
        tennyoDiscoveryDebug(LOG) {
            "reset discovery trust project=${project.name.debugQuotedOrNone()}"
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { refreshDiscoveryListener() }
                .onFailure { LOG.warn("Unable to reset Tennyo Companion discovery listener", it) }
        }
    }

    override fun recordDiscoverySecurityFailure(
        message: String,
        attackerSource: String?,
    ): Boolean =
        globalSettings.recordSecurityFailure(message = message, attackerSource = attackerSource).also { blocked ->
            tennyoDiscoveryDebug(LOG) {
                "recorded discovery security failure blocked=$blocked " +
                        "attacker=${attackerSource.debugQuotedOrNone()} message=${message.debugQuotedOrNone()}"
            }
            if (blocked) {
                refreshDiscoveryListener()
            }
        }

    internal fun revokeRememberedPair(pairId: String): Boolean = host.revokeRememberedPair(pairId)

    internal fun findRememberedPair(pairId: String): RememberedPairRecord? =
        host.findRememberedPair(pairId)

    internal fun replaceRememberedPair(
        pairId: String,
        expectedPairSecret: String,
        replacement: RememberedPairRecord,
    ): Boolean = host.replaceRememberedPair(pairId, expectedPairSecret, replacement)

    internal fun refreshDiscoveryListener() {
        tennyoDiscoveryDebug(LOG) {
            "refresh listener enabled=${globalSettings.isEnabled()} sessions=${host.sessionCount} " +
                    "starting=${host.isStartingSession} owner=${globalSettings.isOwner(project)} " +
                    "activeGlobalSession=${globalSettings.hasActiveSession()}"
        }
        LOG.info(
            "Tennyo Companion discovery listener refresh " +
                    "project=${project.name} locationHash=${project.locationHash} " +
                    "companionId=${globalSettings.currentCompanionIdOrNull().debugShortIdOrNone()} " +
                    "enabled=${globalSettings.isEnabled()}"
        )
        globalSettings.markSessionActive(project, host.hasClaimedSession)
        globalSettings.registerSessionService(project, this)
    }

    internal fun updateDiscoveryAdvertisement(shouldAdvertise: Boolean) {
        tennyoDiscoveryDebug(LOG) {
            "update discovery advertisement shouldAdvertise=$shouldAdvertise " +
                    "project=${project.name.debugQuotedOrNone()} sessions=${host.sessionCount} " +
                    "starting=${host.isStartingSession}"
        }
        if (shouldAdvertise) {
            val endpoints = discoveryEndpointsInternal()
            DnsSdAdvertisement.advertise(
                companionId = companionId(),
                endpoint = endpoints.primary,
                pairingWindowOpen = host.pairingWindow.current() != null,
            )
            return
        }
        releaseDiscoveryListener()
    }

    override fun discoveryProjectChoices(): List<ProjectChoice> =
        globalSettings.availableDiscoveryProjectChoices()

    private fun showConnectionRequestNotification(sessionId: String, request: ConnectionRequest) {
        val key = notificationKey(sessionId, request.id)
        connectionRequestNotifications[key]?.takeUnless { it.isExpired }?.let { return }
        val notification = Notification(
            HOST_NOTIFICATION_GROUP,
            HostBundle.message("notification.connection.request.title"),
            HostBundle.message(
                "notification.connection.request.content",
                request.clientName,
                project.name
            ),
            NotificationType.INFORMATION,
        )
        notification.addAction(object : AnAction(HostBundle.message("popup.button.accept")) {
            override fun actionPerformed(e: AnActionEvent) {
                acceptConnectionRequest(sessionId, request.id)
                expireConnectionRequestNotification(sessionId, request.id)
            }
        })
        notification.addAction(object : AnAction(HostBundle.message("popup.button.reject")) {
            override fun actionPerformed(e: AnActionEvent) {
                rejectConnectionRequest(sessionId, request.id)
                expireConnectionRequestNotification(sessionId, request.id)
            }
        })
        connectionRequestNotifications[key] = notification
        notification.notify(project)
    }

    private fun expireConnectionRequestNotification(sessionId: String, requestId: String) {
        connectionRequestNotifications.remove(notificationKey(sessionId, requestId))?.expire()
    }

    private fun expireConnectionRequestNotifications(sessionId: String) {
        val prefix = "$sessionId:"
        connectionRequestNotifications.keys
            .filter { it.startsWith(prefix) }
            .forEach { key -> connectionRequestNotifications.remove(key)?.expire() }
    }

    private fun notificationKey(sessionId: String, requestId: String): String = "$sessionId:$requestId"

    private fun findLaunchTabId(handler: ProcessHandler): String =
        runCatching {
            invokeOnEdtAndWait {
                RunContentManager.getInstanceIfCreated(project)
                    ?.allDescriptors
                    ?.firstOrNull { it.processHandler === handler }
                    ?.remoteLaunchTabId()
            }
        }.getOrNull() ?: handler.remoteLaunchTabId()

    private fun broadcastFileContent(file: VirtualFile) {
        file.companionNioPath()?.let(host::broadcastFileContent)
    }

    private fun broadcastFileContentDebounced(file: VirtualFile) {
        val key = file.url
        pendingFileContentBroadcasts.remove(key)?.cancel(false)
        val future = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                pendingFileContentBroadcasts.remove(key)
                broadcastFileContent(file)
            },
            FILE_CONTENT_BROADCAST_DELAY_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        pendingFileContentBroadcasts[key] = future
    }

    private fun discoveryEndpointsInternal(): HostEndpoints = synchronized(lifecycleLock) {
        val lease = discoveryListenerLease ?: service<LinkServerService>().acquire(
            preferredPort = globalSettings.preferredPort(),
            ownerKind = "discovery",
        ).also { discoveryListenerLease = it }
        lease.endpoints().also(::rememberPort)
    }

    /** The window's port survives restarts, so a phone's stored address keeps working. */
    private fun rememberPort(endpoints: HostEndpoints) {
        globalSettings.updatePreferredPort(endpoints.port)
        tennyoDiscoveryDebug(LOG) {
            "resolved endpoints primary=${endpoints.primary.debugQuotedOrNone()} " +
                    "alternates=${endpoints.alternates.size} overlay=${endpoints.overlay.size} " +
                    "fingerprint=${endpoints.transportFingerprint.debugShortId()}"
        }
    }

    private fun releaseDiscoveryListener() = synchronized(lifecycleLock) {
        discoveryListenerLease?.release()
        discoveryListenerLease = null
    }

    /** How the protocol host reaches IntelliJ: notifications, the toolbar, the shared listener, and threads. */
    private inner class IdeHostPlatform : HostPlatform {
        override fun showConnectionRequest(sessionId: String, request: ConnectionRequest) =
            showConnectionRequestNotification(sessionId, request)

        override fun expireConnectionRequest(sessionId: String, requestId: String) =
            expireConnectionRequestNotification(sessionId, requestId)

        override fun expireConnectionRequests(sessionId: String) = expireConnectionRequestNotifications(sessionId)

        override fun onSessionStateChanged() {
            ApplicationManager.getApplication().invokeLater {
                ActivityTracker.getInstance().inc()
                HostStatusBarWidget.refresh(project)
            }
        }

        override fun onSessionsActiveChanged(active: Boolean) {
            globalSettings.markSessionActive(project, active && host.hasClaimedSession)
            // The pairing session is over: put back the mode "All" overrode.
            if (!active) discoveryBeforePairing.takeSaved()?.let { setDiscoveryEnabled(it) }
        }

        override fun acquireListener(ownerKind: String): ListenerLease {
            val lease = service<LinkServerService>().acquire(
                preferredPort = globalSettings.preferredPort(),
                ownerKind = ownerKind,
            )
            return runCatching { ListenerLease(lease.endpoints, lease::release).also { rememberPort(it.endpoints()) } }
                .onFailure { lease.release() }
                .getOrThrow()
        }

        override fun listenerEndpoints(): HostEndpoints? = service<LinkServerService>().endpoints()

        override fun isRemoteAccessEnabled(): Boolean = globalSettings.isRemoteAccessEnabled()

        override val pairStore: RememberedPairStore get() = rememberedPairStore

        override fun companionId(): String = globalSettings.companionId()

        override fun isDiscoveryEnabled(): Boolean = globalSettings.isEnabled()

        override fun hostLabel(): String = ideLabel()

        override fun executeInBackground(block: () -> Unit) {
            AppExecutorUtil.getAppExecutorService().execute(block)
        }

        override fun isUiThread(): Boolean = ApplicationManager.getApplication().isDispatchThread

        override fun assertBackgroundThread() = ThreadingAssertions.assertBackgroundThread()
    }

    companion object {
        private val LOG = Logger.getInstance(HostSessionService::class.java)

        private const val FILE_CONTENT_BROADCAST_DELAY_MILLIS = 80L

        /** The `<notificationGroup>` id declared in plugin.xml. */
        private const val HOST_NOTIFICATION_GROUP = "TennyoHost"
    }

    override fun dispose() {
        globalSettings.unregisterSessionService(project)
        HostRegistry.unregister(host)
        pendingFileContentBroadcasts.values.forEach { it.cancel(false) }
        pendingFileContentBroadcasts.clear()
        runTabIdsByHandler.clear()
        host.close()
        releaseDiscoveryListener()
    }
}
