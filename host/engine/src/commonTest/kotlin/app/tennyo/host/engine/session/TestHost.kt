package app.tennyo.host.engine.session

import app.tennyo.contract.Capability
import app.tennyo.contract.HostEditor
import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.host.engine.agents.IdeAgents
import app.tennyo.host.engine.pairing.InMemoryPairStore
import app.tennyo.host.engine.pairing.RememberedPairStore
import app.tennyo.host.engine.panes.EditorTabInfo
import app.tennyo.host.engine.panes.FakeProjectFiles
import app.tennyo.host.engine.panes.FileSnapshot
import app.tennyo.host.engine.panes.HostProjectInfo
import app.tennyo.host.engine.panes.IdeEditorTabsPort
import app.tennyo.host.engine.panes.IdeFilesPort
import app.tennyo.host.engine.panes.IdeGitPort
import app.tennyo.host.engine.panes.IdeGitPortFactory
import app.tennyo.host.engine.panes.IdeLaunchPort
import app.tennyo.host.engine.panes.IdeLaunchPortFactory
import app.tennyo.host.engine.panes.IdeTerminalPortFactory
import app.tennyo.host.engine.panes.LaunchPortLease
import app.tennyo.host.engine.panes.LaunchTabOutputSource
import app.tennyo.host.engine.panes.LaunchTabWatcher
import app.tennyo.host.engine.panes.LaunchTabsSnapshot
import app.tennyo.host.engine.panes.ProjectPanes
import app.tennyo.host.engine.panes.StoppedLaunch
import app.tennyo.host.engine.panes.TerminalPortCallbacks
import app.tennyo.host.engine.panes.UnavailableGitPort

/** A host platform that records what the core asks of it and runs background work at once. */
class FakeHostPlatform(
    override val pairStore: RememberedPairStore = InMemoryPairStore(),
    var discoveryEnabled: Boolean = false,
    private val deferBackground: Boolean = false,
    var remoteAccess: Boolean = false,
) : HostPlatform {
    /** What the listener answers with; a test changes it to model an address that appears after the session starts. */
    var endpoints = HostEndpoints("https://192.168.1.10:63342/api/tennyo", emptyList(), "fingerprint", port = 63342)
    val deferredWork = mutableListOf<() -> Unit>()
    val shownRequests = mutableListOf<Pair<String, ConnectionRequest>>()
    var stateChanges = 0
        private set
    var listenerReleases = 0
        private set

    override fun showConnectionRequest(sessionId: String, request: ConnectionRequest) {
        shownRequests += sessionId to request
    }

    override fun expireConnectionRequest(sessionId: String, requestId: String) = Unit

    override fun expireConnectionRequests(sessionId: String) = Unit

    override fun onSessionStateChanged() {
        stateChanges++
    }

    override fun onSessionsActiveChanged(active: Boolean) = Unit

    override fun acquireListener(ownerKind: String): ListenerLease =
        ListenerLease({ endpoints }) { listenerReleases++ }

    override fun listenerEndpoints(): HostEndpoints = endpoints

    override fun isRemoteAccessEnabled(): Boolean = remoteAccess

    override fun companionId(): String = "companion"

    override fun isDiscoveryEnabled(): Boolean = discoveryEnabled

    override fun hostLabel(): String = "Test IDE"

    override fun executeInBackground(block: () -> Unit) {
        if (deferBackground) deferredWork += block else block()
    }

    override fun isUiThread(): Boolean = false

    override fun assertBackgroundThread() = Unit
}

/** Surfaces with no IDE behind them: no editor tabs, launch tabs, terminal, or Git repositories. */
fun testPanes(
    files: IdeFilesPort = FakeProjectFiles(),
    git: IdeGitPort = UnavailableGitPort,
    editorTabs: IdeEditorTabsPort = NoEditorTabs,
    agents: IdeAgents? = null,
): ProjectPanes =
    ProjectPanes(
        projectInfo = HostProjectInfo(
            id = "project",
            name = "Tennyo",
            displayPath = "~/tennyo",
            hostVersion = "1.0",
            editor = HostEditor.VS_CODE,
        ),
        files = files,
        editorTabs = editorTabs,
        launchFactory = IdeLaunchPortFactory { LaunchPortLease(NoLaunch) {} },
        terminalFactory = object : IdeTerminalPortFactory {
            override fun create(callbacks: TerminalPortCallbacks) = null

            override fun isAvailable(): Boolean = false
        },
        gitFactory = object : IdeGitPortFactory {
            override fun create(onChangesChanged: () -> Unit): IdeGitPort = git
        },
        agents = agents,
    )

fun testArtifact(sessionId: String, capabilities: Set<Capability> = setOf(Capability.FILES)) =
    SessionTicket(
        endpoint = "https://127.0.0.1",
        sessionId = sessionId,
        token = "token",
        fingerprint = "fingerprint",
        projectName = "Tennyo",
        projectPath = "/work/tennyo",
        capabilities = capabilities,
    )

object NoEditorTabs : IdeEditorTabsPort {
    override fun tabs(): List<EditorTabInfo> = emptyList()

    override fun selectedPath(): String? = null

    override fun open(path: String): Boolean = false

    override fun close(path: String): Boolean = false

    override fun contentOf(path: String): FileSnapshot? = null
}

private object NoLaunch : IdeLaunchPort {
    override fun configurations(): LaunchConfigurationsState = LaunchConfigurationsState(emptyList(), null)

    override fun selectConfiguration(id: String?) = Unit

    override fun start(id: String): String? = null

    override fun stop(id: String): List<StoppedLaunch> = emptyList()

    override fun tabs(watcher: LaunchTabWatcher): LaunchTabsSnapshot = LaunchTabsSnapshot(emptyList(), null)

    override fun hasTab(id: String): Boolean = false

    override fun selectTab(id: String) = Unit

    override fun closeTab(id: String): Boolean = false

    override fun outputSource(id: String, maxChars: Int, watcher: LaunchTabWatcher): LaunchTabOutputSource? = null
}

