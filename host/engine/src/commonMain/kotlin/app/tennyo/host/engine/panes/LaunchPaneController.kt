package app.tennyo.host.engine.panes

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.LaunchCandidate
import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.contract.LaunchOutput
import app.tennyo.contract.LaunchOutputSnapshot
import app.tennyo.contract.LaunchTab
import app.tennyo.contract.LaunchTabsState
import app.tennyo.contract.LinkPush
import app.tennyo.host.engine.platform.HostConcurrentMap
import app.tennyo.host.engine.platform.HostConcurrentSet
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.session.HostSession
import app.tennyo.host.engine.session.launchTabTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

private const val MAX_LAUNCH_OUTPUT_CHARS = 80_000
private const val LAUNCH_OUTPUT_COALESCE_DELAY_MILLIS = 75L

class LaunchPaneController(
    private val session: HostSession,
) {
    private val launch get() = session.surfaces.launch

    private val selectedLaunchTabId = AtomicReference<String?>(null)
    private val observedLaunchContentIds = HostConcurrentSet<String>()
    private val launchOutputBuffers = HostConcurrentMap<String, LaunchOutputBuffer>()
    private val pendingLaunchOutput = LaunchOutputAccumulator(MAX_LAUNCH_OUTPUT_CHARS)
    private val pendingLaunchOutputLock = HostMonitor()
    private var launchOutputFlushJob: Job? = null

    /** Watches each launch tab once, and forgets its output and selection when the IDE disposes it. */
    private val launchTabWatcher = object : LaunchTabWatcher {
        override fun shouldWatch(tabId: String): Boolean = observedLaunchContentIds.add(tabId)

        override fun onTabClosed(tabId: String) {
            observedLaunchContentIds.remove(tabId)
            launchOutputBuffers.remove(tabId)
            if (selectedLaunchTabId.load() == tabId) {
                selectedLaunchTabId.store(null)
            }
            emitLaunchTabs()
        }
    }

    fun launchConfigurations(): LaunchConfigurationsState? = launch?.configurations()

    fun candidatesForFile(path: String): List<LaunchCandidate> = launch?.candidatesForFile(path).orEmpty()

    fun selectLaunchConfiguration(id: String?): LaunchConfigurationsState? {
        launch?.selectConfiguration(id)
        emitLaunchConfigurations()
        return launchConfigurations()
    }

    fun startLaunchConfiguration(id: String): Boolean {
        val name = launch?.start(id) ?: return false
        emitLaunchOutput(id, "Started $name\n", isRunning = true)
        return true
    }

    fun stopLaunchConfiguration(id: String): Boolean {
        val stopped = launch?.stop(id).orEmpty()
        stopped.forEach { stoppedLaunch ->
            emitLaunchOutput(
                id,
                "Stopped ${stoppedLaunch.displayName}\n",
                isRunning = false,
                tabId = stoppedLaunch.tabId,
            )
        }
        return stopped.isNotEmpty()
    }

    fun launchOutputSnapshot(launchTabId: String): LaunchOutputSnapshot? {
        val runPort = launch ?: return null
        val source = runPort.outputSource(launchTabId, MAX_LAUNCH_OUTPUT_CHARS, launchTabWatcher) ?: return null
        source.consoleText?.let { existingText ->
            launchOutputBuffers.computeIfAbsent(launchTabId) {
                LaunchOutputBuffer(MAX_LAUNCH_OUTPUT_CHARS)
            }.seedIfEmpty(existingText)
        }
        return LaunchOutputSnapshot(
            tabId = launchTabId,
            configurationId = source.configurationId,
            text = launchTabOutput(launchTabId),
            isRunning = source.isRunning,
        )
    }

    fun selectLaunchTab(launchTabId: String): Boolean {
        val runPort = launch ?: return false
        if (!runPort.hasTab(launchTabId)) return false
        flushLaunchOutput(launchTabId)
        selectedLaunchTabId.store(launchTabId)
        runPort.selectTab(launchTabId)
        emitLaunchTabs()
        return true
    }

    fun closeLaunchTab(launchTabId: String): Boolean {
        val runPort = launch ?: return false
        if (!runPort.closeTab(launchTabId)) return false
        if (selectedLaunchTabId.load() == launchTabId) {
            selectedLaunchTabId.store(null)
        }
        emitLaunchTabs()
        return true
    }

    fun emitLaunchTabs() {
        if (!session.shouldEmitPanelMetadata(ActivePanels.LAUNCH)) return
        launchTabsState()?.let { session.push(LinkPush.LaunchTabs(it)) }
    }

    fun emitLaunchConfigurations() {
        if (!session.shouldEmitPanelMetadata(ActivePanels.LAUNCH)) return
        launchConfigurations()?.let { session.push(LinkPush.LaunchConfigurations(it)) }
    }

    fun emitLaunchOutput(configurationId: String, text: String, isRunning: Boolean, tabId: String? = null) {
        if (text.isEmpty()) return
        tabId?.let { appendLaunchTabOutput(it, text) }
        if (!shouldEmitLaunchOutput()) return
        val outputTabId = tabId ?: return emitLaunchOutputNow(configurationId, text, isRunning, null)
        pendingLaunchOutputLock.guarded {
            pendingLaunchOutput.append(outputTabId, configurationId, text, isRunning)
            if (launchOutputFlushJob?.isActive != true) {
                launchOutputFlushJob = session.sessionScope.launch {
                    delay(LAUNCH_OUTPUT_COALESCE_DELAY_MILLIS.milliseconds)
                    flushLaunchOutput()
                }
            }
        }
    }

    fun flushLaunchOutput(tabId: String? = null) {
        session.owner.platform.assertBackgroundThread()
        val drained = pendingLaunchOutputLock.guarded {
            val entries = pendingLaunchOutput.drain(tabId)
            if (pendingLaunchOutput.isEmpty()) {
                launchOutputFlushJob?.cancel()
                launchOutputFlushJob = null
            }
            entries
        }
        drained.forEach { pending ->
            emitLaunchOutputNow(pending.configurationId, pending.text, pending.isRunning, pending.tabId)
        }
    }

    private fun shouldEmitLaunchOutput(): Boolean {
        val surface = session.activeSurface.get() ?: return false
        return surface.panel == ActivePanels.LAUNCH
    }

    private fun emitLaunchOutputNow(configurationId: String, text: String, isRunning: Boolean, tabId: String?) {
        session.push(
            LinkPush.LaunchOutputChanged(
                LaunchOutput(configurationId = configurationId, tabId = tabId, text = text, isRunning = isRunning),
            ),
        )
        session.stats.emitStatsDebounced()
    }

    fun launchTabsState(): LaunchTabsState? {
        val runPort = launch ?: return null
        return LaunchTabsState(
            tabs = launchTabs(runPort),
            selectedLaunchTabId = selectedLaunchTabId.load(),
        )
    }

    private fun launchTabs(runPort: IdeLaunchPort): List<LaunchTab> {
        val snapshot = runPort.tabs(launchTabWatcher) ?: return emptyList()
        snapshot.selectedTabId?.let { selectedLaunchTabId.store(it) }
        ensureSelectedLaunchTab(snapshot.tabs.map { it.id })
        return snapshot.tabs.map { tab ->
            LaunchTab(
                id = tab.id,
                configurationId = tab.configurationId,
                title = launchTabTitle(
                    displayName = tab.displayName,
                    runProfileName = tab.runProfileName,
                    configurationName = tab.configurationName,
                ),
                isRunning = tab.isRunning,
                isSelected = selectedLaunchTabId.load() == tab.id,
            )
        }
    }

    private fun ensureSelectedLaunchTab(ids: List<String>) {
        if (selectedLaunchTabId.load() !in ids) {
            selectedLaunchTabId.store(ids.firstOrNull())
        }
    }

    private fun launchTabOutput(id: String): String =
        launchOutputBuffers[id]?.snapshot()
            .orEmpty()
            .takeLast(MAX_LAUNCH_OUTPUT_CHARS)

    private fun appendLaunchTabOutput(id: String, text: String) {
        launchOutputBuffers.computeIfAbsent(id) { LaunchOutputBuffer(MAX_LAUNCH_OUTPUT_CHARS) }.append(text)
    }

    fun close() {
        if (!session.owner.platform.isUiThread()) {
            flushLaunchOutput()
        }
        pendingLaunchOutputLock.guarded {
            launchOutputFlushJob?.cancel()
            launchOutputFlushJob = null
            pendingLaunchOutput.clear()
        }
    }
}
