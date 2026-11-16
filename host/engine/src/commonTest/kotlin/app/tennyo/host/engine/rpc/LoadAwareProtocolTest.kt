package app.tennyo.host.engine.rpc

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.ActiveSurface
import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.LaunchConfiguration
import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.contract.LaunchOutputSnapshot
import app.tennyo.contract.LaunchTab
import app.tennyo.contract.LaunchTabsState
import app.tennyo.contract.LinkJson
import app.tennyo.contract.ProjectChoice
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalSnapshotResponse
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleRun
import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState
import app.tennyo.contract.allowsMetadataPanel
import app.tennyo.contract.selectedVisibleTerminalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoadAwareProtocolTest {
    @Test
    fun `launch tabs metadata omits output text`() {
        val payload = linkJson(
            LaunchTabsState(
                tabs = listOf(
                    LaunchTab(
                        id = "launch-1",
                        configurationId = "config",
                        title = "App",
                        isRunning = true,
                        isSelected = true,
                    )
                ),
                selectedLaunchTabId = "launch-1",
            )
        )

        assertEquals(
            """{"tabs":[{"id":"launch-1","configurationId":"config","title":"App","isRunning":true,"isSelected":true}],"selectedLaunchTabId":"launch-1"}""",
            payload,
        )
    }

    @Test
    fun `selected launch output response carries only requested output`() {
        val payload = linkJson(
            LaunchOutputSnapshot(
                tabId = "launch-1",
                configurationId = "config",
                text = "output",
                isRunning = false,
            )
        )

        assertEquals(
            """{"tabId":"launch-1","configurationId":"config","text":"output","isRunning":false}""",
            payload,
        )
    }

    @Test
    fun `launch configurations payload keeps nullable folder metadata`() {
        val payload = linkJson(
            LaunchConfigurationsState(
                configurations = listOf(
                    LaunchConfiguration(
                        id = "android-app",
                        name = "Android app",
                        type = "Android",
                        folder = "Mobile",
                        isRunning = true,
                    ),
                    LaunchConfiguration(
                        id = "lint",
                        name = "Lint",
                        type = "Gradle",
                        folder = null,
                        isRunning = false,
                    ),
                ),
                selectedConfigurationId = "android-app",
            )
        )

        assertEquals(
            """{"configurations":[{"id":"android-app","name":"Android app","type":"Android","folder":"Mobile","isRunning":true},{"id":"lint","name":"Lint","type":"Gradle"}],"selectedConfigurationId":"android-app"}""",
            payload,
        )
    }

    @Test
    fun `selected terminal snapshot response carries only requested snapshot`() {
        val payload = linkJson(
            TerminalSnapshotResponse(
                terminalId = "terminal-1",
                snapshot = TerminalSnapshot(text = "prompt"),
                revision = 10L,
            )
        )

        assertEquals(
            """{"terminalId":"terminal-1","snapshot":{"text":"prompt"},"revision":10}""",
            payload,
        )
    }

    @Test
    fun `selected terminal snapshot can carry compact style cache`() {
        val payload = linkJson(
            TerminalSnapshotResponse(
                terminalId = "terminal-1",
                snapshot = TerminalSnapshot(
                    text = "FAILED\n",
                    styleEpoch = 3L,
                    styles = mapOf(19 to TerminalStyleDefinition(foreground = "#FF5555", bold = true)),
                    styleRuns = listOf(TerminalStyleRun(styleId = 19, ranges = listOf(0, 6))),
                ),
                revision = 47L,
            )
        )

        assertEquals(
            """{"terminalId":"terminal-1","snapshot":{"text":"FAILED\n","styleEpoch":3,"styles":{"19":{"foreground":"#FF5555","bold":true}},"styleRuns":[{"styleId":19,"ranges":[0,6]}]},"revision":47}""",
            payload,
        )
    }

    @Test
    fun `terminal tabs metadata omits terminal text`() {
        val payload = linkJson(
            TerminalTabsState(
                tabs = listOf(
                    TerminalTab(
                        id = "terminal-1",
                        title = "Terminal",
                        cwd = "/project",
                        text = "large terminal output",
                        isSelected = true,
                        snapshot = null,
                    )
                ),
                selectedTerminalId = "terminal-1",
            )
        )

        assertFalse(payload.contains("large terminal output"))
        assertEquals(
            """{"tabs":[{"id":"terminal-1","title":"Terminal","cwd":"/project","isSelected":true}],"selectedTerminalId":"terminal-1"}""",
            payload,
        )
    }

    @Test
    fun `active surface serializes as protocol state`() {
        val payload = linkJson(
            ActiveSurface(
                panel = "LAUNCH",
                selectedFilePath = "src/App.kt",
                selectedLaunchTabId = "launch-1",
                selectedTerminalId = "terminal-1",
            )
        )

        assertEquals(
            """{"panel":"LAUNCH","selectedFilePath":"src/App.kt","selectedLaunchTabId":"launch-1","selectedTerminalId":"terminal-1"}""",
            payload,
        )
    }

    @Test
    fun `auto start discover response serializes typed project choices`() {
        val payload = hostJson(
            DiscoverResponse(
                status = DiscoveryStatus.AVAILABLE,
                projectChoices = listOf(
                    ProjectChoice(
                        projectId = "project-a",
                        projectName = "Project A",
                        projectPath = "/tmp/project-a",
                    )
                ),
            )
        )

        assertEquals(
            """{"status":"available","challenge":null,"descriptor":null,"projectChoices":[{"projectId":"project-a","projectName":"Project A","projectPath":"/tmp/project-a"}],"blockedMessage":null}""",
            payload,
        )
    }

    @Test
    fun `active surface allows metadata only for active panel`() {
        val surface = ActiveSurface(
            panel = ActivePanels.TERMINAL,
            selectedTerminalId = "terminal-1",
        )
        val missingSurface: ActiveSurface? = null

        assertTrue(surface.allowsMetadataPanel(ActivePanels.TERMINAL))
        assertFalse(surface.allowsMetadataPanel(ActivePanels.LAUNCH))
        assertFalse(missingSurface.allowsMetadataPanel(ActivePanels.FILES))
    }

    @Test
    fun `terminal surface exposes its selected terminal for metadata and live frames`() {
        val terminalSurface = ActiveSurface(
            panel = ActivePanels.TERMINAL,
            selectedTerminalId = "terminal-1",
        )
        val missingTerminalSurface = ActiveSurface(
            panel = ActivePanels.TERMINAL,
        )

        assertEquals("terminal-1", terminalSurface.selectedVisibleTerminalId())
        assertNull(missingTerminalSurface.selectedVisibleTerminalId())
    }


    private inline fun <reified T> linkJson(value: T): String = LinkJson.encodeToString(value)
}
