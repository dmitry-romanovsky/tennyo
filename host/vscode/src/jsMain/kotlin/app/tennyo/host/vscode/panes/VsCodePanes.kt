package app.tennyo.host.vscode.panes

import app.tennyo.contract.HostEditor
import app.tennyo.host.engine.agents.AgentServer
import app.tennyo.host.engine.agents.AhpChatSource
import app.tennyo.host.engine.agents.IdeAgents
import app.tennyo.host.engine.agents.NodeAgentLauncher
import app.tennyo.host.engine.agents.agentServersFromMap
import app.tennyo.host.engine.agents.nodeLocalAgentServers
import app.tennyo.host.engine.panes.BufferedFilesPort
import app.tennyo.host.engine.panes.HostProjectInfo
import app.tennyo.host.engine.panes.NodeFilesPort
import app.tennyo.host.engine.panes.ProjectPanes
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.vscode.agents.DevinAgents
import app.tennyo.host.vscode.agents.VsCodeAgentHost
import app.tennyo.host.vscode.api.VsCodeEnv
import app.tennyo.host.vscode.api.VsCodeWorkspace

/**
 * What a VS Code window offers a session: the project's files, with the editor's unsaved buffers over them, its
 * editor tabs, terminals, launch configurations and tasks, Git, the ACP agents the user configured or Devin switched
 * on, and the agents of VS Code's own agent host. [sessions] is the window's session host once it is built.
 */
fun vsCodeProjectPanes(
    hostVersion: String,
    agentHost: VsCodeAgentHost,
    devinAgents: DevinAgents,
    sessions: () -> SessionHost?,
): ProjectPanes? {
    val folder = VsCodeWorkspace.workspaceFolders?.firstOrNull() ?: return null
    val root = folder.uri.linkPath()
    val files = BufferedFilesPort(NodeFilesPort(root), VsCodeEditorBuffers())
    return ProjectPanes(
        projectInfo = HostProjectInfo(
            id = root,
            name = folder.name,
            displayPath = root,
            hostVersion = hostVersion,
            editor = VsCodeEnv.appName.hostEditor(),
        ),
        files = files,
        editorTabs = VsCodeEditorTabsPort(files),
        launchFactory = vsCodeLaunchFactory(sessions),
        terminalFactory = vsCodeTerminalFactory(root),
        gitFactory = vsCodeGitFactory(),
        agents = IdeAgents(
            launcher = NodeAgentLauncher,
            servers = {
                (nodeLocalAgentServers() + settingAgentServers() + devinAgents.servers()).distinctBy { it.id }
            },
            sources = listOf(AhpChatSource(root, agentHost::connected)),
        ),
    )
}

/** The `tennyo.acp.agentServers` setting: the same name → `{command, args, env}` map as JetBrains' `acp.json`. */
private fun settingAgentServers(): List<AgentServer> {
    val setting = VsCodeWorkspace.getConfiguration("tennyo").get("acp.agentServers") ?: return emptyList()
    return agentServersFromMap("vscode", JSON.stringify(setting))
}

/** The editor family of a VS Code fork, from its application name; an unrecognised fork behaves like VS Code. */
internal fun String.hostEditor(): HostEditor = when {
    startsWith("Cursor") -> HostEditor.CURSOR
    startsWith("Devin Desktop") -> HostEditor.DEVIN_DESKTOP
    else -> HostEditor.VS_CODE
}
