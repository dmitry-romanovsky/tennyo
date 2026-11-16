package app.tennyo.host.engine.panes

import app.tennyo.contract.Capability
import app.tennyo.contract.HostEditor
import app.tennyo.host.engine.agents.IdeAgents

/** The project a session serves, as the host IDE describes it. */
data class HostProjectInfo(
    val id: String,
    val name: String,
    val displayPath: String,
    val hostVersion: String,
    val editor: HostEditor,
)

/**
 * Everything a session reaches in the host IDE. The files, editor-tab, and launch bridges are shared by the project's
 * sessions; terminals and Git keep per-session state, so each session creates its own through the factories.
 */
class ProjectPanes(
    val projectInfo: HostProjectInfo,
    val files: IdeFilesPort,
    val editorTabs: IdeEditorTabsPort,
    val launchFactory: IdeLaunchPortFactory,
    val terminalFactory: IdeTerminalPortFactory,
    val gitFactory: IdeGitPortFactory,
    /** The ACP agents the user configured; `null` for a host that runs none. */
    val agents: IdeAgents? = null,
) {
    /**
     * The launch port for this project, created lazily from the factory. Multiple sessions share the same
     * instance through this property.
     */
    val launch: IdeLaunchPort? by lazy { launchFactory.create()?.bridge }

    /** What sessions of this project can offer; every surface but the files depends on what the IDE exposes. */
    fun capabilities(): Set<Capability> = buildSet {
        add(Capability.FILES)
        if (launchFactory.isAvailable()) add(Capability.LAUNCH_CONFIGURATIONS)
        if (terminalFactory.isAvailable()) add(Capability.TERMINAL)
        if (gitFactory.isAvailable()) add(Capability.GIT)
        if (agents?.hasAny() == true) add(Capability.CHAT)
    }
}
