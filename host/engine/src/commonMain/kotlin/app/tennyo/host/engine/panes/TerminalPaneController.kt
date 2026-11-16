package app.tennyo.host.engine.panes

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.LinkPush
import app.tennyo.contract.TerminalFrame
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalSnapshotResponse
import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState
import app.tennyo.contract.selectedVisibleTerminalId
import app.tennyo.host.engine.platform.HostConcurrentMap
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.session.HostSession

private const val MAX_TERMINAL_FRAME_DELTA_CHARS = 16 * 1024

fun terminalFrameForSnapshot(
    terminalId: String,
    previous: TerminalSnapshot?,
    snapshot: TerminalSnapshot,
    styleRegistry: TerminalStyleRegistry,
    baseRevision: Long? = null,
    revision: Long? = null,
): TerminalFrame =
    styleRegistry.compactFrameForSnapshot(
        terminalId = terminalId,
        previous = previous,
        snapshot = snapshot,
        baseRevision = baseRevision,
        revision = revision,
        maxDeltaChars = MAX_TERMINAL_FRAME_DELTA_CHARS,
    )

private data class TerminalSnapshotUpdate(
    val previous: TerminalSnapshot?,
    val baseRevision: Long?,
    val revision: Long,
)

class TerminalPaneController(
    private val session: HostSession,
) {
    private val terminalLease = session.surfaces.terminalFactory.create(
        TerminalPortCallbacks(
            onTabsChanged = { emitTerminalTabs() },
            onFrame = { terminalId, snapshot -> emitTerminalFrame(terminalId, snapshot) },
            shouldReadFrame = { terminalId -> shouldReadTerminalFrame(terminalId) },
        )
    )
    private val terminalBridge = terminalLease?.bridge
    private val terminalSnapshots = HostConcurrentMap<String, TerminalSnapshot>()
    private val terminalSnapshotRevisions = HostConcurrentMap<String, Long>()
    private val terminalSnapshotLock = HostMonitor()
    private val terminalStyleRegistry = TerminalStyleRegistry()

    fun createTerminal(): TerminalTab? {
        val remoteTab = terminalBridge?.create() ?: return null
        remoteTab.snapshot?.let { snapshot -> recordTerminalSnapshot(remoteTab.id, snapshot) }
        val responseTab = remoteTab.snapshot?.let { snapshot ->
            remoteTab.copy(
                snapshot = terminalStyleRegistry.compactSnapshot(
                    snapshot,
                    includeAllDefinitions = false
                )
            )
        } ?: remoteTab
        emitTerminalTabs()
        session.emitStats()
        return responseTab
    }

    fun ensureTerminalOpen(): Boolean {
        val opened = terminalBridge?.ensureOpen() == true
        if (opened) {
            emitTerminalTabs()
        }
        return opened
    }

    fun writeTerminal(terminalId: String, text: String): Boolean {
        val accepted = terminalBridge?.write(terminalId, text) == true
        if (accepted) {
            session.stats.recordUploadBytes(text.encodeToByteArray().size.toLong())
            session.emitStats()
        }
        return accepted
    }

    fun closeTerminal(terminalId: String): Boolean {
        if (terminalBridge?.close(terminalId) != true) return false
        terminalSnapshots.remove(terminalId)
        terminalSnapshotRevisions.remove(terminalId)
        emitTerminalTabs()
        session.emitStats()
        return true
    }

    fun selectTerminal(terminalId: String): Boolean {
        if (terminalBridge?.select(terminalId) != true) return false
        emitTerminalTabs()
        return true
    }

    fun terminalSnapshot(
        terminalId: String,
        includeAllStyles: Boolean = false,
    ): TerminalSnapshotResponse? {
        val snapshot = terminalBridge?.snapshot(terminalId) ?: return null
        val update = recordTerminalSnapshot(terminalId, snapshot)
        return TerminalSnapshotResponse(
            terminalId = terminalId,
            snapshot = terminalStyleRegistry.compactSnapshot(snapshot, includeAllDefinitions = includeAllStyles),
            revision = update.revision,
        )
    }

    fun emitTerminalTabs() {
        if (!session.shouldEmitPanelMetadata(ActivePanels.TERMINAL)) return
        session.push(LinkPush.TerminalTabs(terminalTabsState(includeSnapshots = false)))
    }

    fun emitTerminalFrame(terminalId: String, snapshot: TerminalSnapshot) {
        LOG.debug {
            "Tennyo terminal frame emitted activePanel=${session.activeSurface.get()?.panel ?: "none"}"
        }
        session.push(LinkPush.TerminalOutput(terminalFrameForSnapshot(terminalId, snapshot)))
        session.emitStats()
    }

    fun emitSelectedTerminalFrame(terminalId: String?) {
        val selectedTerminalId = terminalId ?: terminalBridge?.selectedTerminalId() ?: return
        val snapshot = terminalBridge?.snapshot(selectedTerminalId) ?: return
        emitTerminalFrame(selectedTerminalId, snapshot)
    }

    fun emitTerminalTextFrame(terminalId: String, text: String, isError: Boolean = false) {
        session.push(LinkPush.TerminalOutput(TerminalFrame(terminalId = terminalId, text = text, isError = isError)))
        session.emitStats()
    }

    fun selectedTerminalId(): String? = terminalBridge?.selectedTerminalId()

    fun tabs(includeSnapshots: Boolean): List<TerminalTab> =
        terminalBridge?.tabs(includeSnapshots).orEmpty()

    private fun terminalFrameForSnapshot(
        terminalId: String,
        snapshot: TerminalSnapshot,
    ): TerminalFrame {
        val update = recordTerminalSnapshot(terminalId, snapshot)
        return terminalFrameForSnapshot(
            terminalId = terminalId,
            previous = update.previous,
            snapshot = snapshot,
            styleRegistry = terminalStyleRegistry,
            baseRevision = update.baseRevision,
            revision = update.revision,
        )
    }

    private fun recordTerminalSnapshot(
        terminalId: String,
        snapshot: TerminalSnapshot,
    ): TerminalSnapshotUpdate =
        terminalSnapshotLock.guarded {
            val previous = terminalSnapshots[terminalId]
            val previousRevision = terminalSnapshotRevisions[terminalId]
            val revision = if (previous == snapshot && previousRevision != null) {
                previousRevision
            } else {
                (previousRevision ?: 0L) + 1L
            }
            terminalSnapshots[terminalId] = snapshot
            terminalSnapshotRevisions[terminalId] = revision
            TerminalSnapshotUpdate(
                previous = previous,
                baseRevision = previousRevision,
                revision = revision,
            )
        }

    fun terminalTabsState(includeSnapshots: Boolean = true): TerminalTabsState {
        val tabs = terminalBridge?.tabs(includeSnapshots).orEmpty()
        val state = TerminalTabsState(
            tabs = tabs,
            selectedTerminalId = terminalBridge?.selectedTerminalId()?.takeIf { selected ->
                tabs.any { it.id == selected }
            },
        )
        if (!includeSnapshots) return state
        return state.copy(
            tabs = state.tabs.map { tab ->
                tab.snapshot?.let { snapshot ->
                    tab.copy(snapshot = terminalStyleRegistry.compactSnapshot(snapshot, includeAllDefinitions = false))
                } ?: tab
            }
        )
    }

    private fun shouldReadTerminalFrame(terminalId: String): Boolean {
        val surface = session.activeSurface.get() ?: return false
        return surface.selectedVisibleTerminalId() == terminalId
    }

    fun close() {
        terminalLease?.release()
    }

    private companion object {
        private val LOG = HostLogger("TerminalPaneController")
    }
}
