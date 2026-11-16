package app.tennyo.host.intellij

import app.tennyo.contract.HostEditor
import app.tennyo.host.engine.agents.IdeAgents
import app.tennyo.host.engine.panes.HostProjectInfo
import app.tennyo.host.engine.panes.ProjectPanes
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project

/** The IntelliJ side of every surface a session reaches in [project]. */
internal fun projectPanes(project: Project): ProjectPanes =
    ProjectPanes(
        projectInfo = HostProjectInfo(
            id = project.locationHash,
            name = project.name,
            displayPath = shortenProjectHomePath(project.basePath.orEmpty()),
            hostVersion = ApplicationInfo.getInstance().fullVersion,
            editor = HostEditor.JETBRAINS,
        ),
        files = IntelliJFilesPort(project),
        editorTabs = IntelliJEditorTabsPort(project),
        launchFactory = IntelliJLaunchPortFactory(project),
        terminalFactory = IntelliJTerminalPortFactory.forProject(project),
        gitFactory = intelliJGitFactory(project),
        agents = IdeAgents(IntelliJAgentLauncher, ::intelliJAgentServers),
    )
