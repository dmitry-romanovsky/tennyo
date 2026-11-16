package app.tennyo.mobile.link.transport

import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.contract.LaunchOutputSnapshot
import app.tennyo.contract.LaunchTab
import app.tennyo.contract.LaunchTabsState
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkJson
import app.tennyo.contract.ProjectChoice
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalSnapshotResponse
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleRun
import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LoadAwareProtocolTest {

    @Test
    fun showPaneSerializesAsALinkCall() {
        val payload = LinkJson.encodeToString(
            LinkCall.serializer(),
            LinkCall.ShowPane(
                panel = "TERMINAL",
                selectedFilePath = "src/App.kt",
                selectedLaunchTabId = "launch-1",
                selectedTerminalId = "terminal-1",
            ),
        )

        assertEquals(
            """{"op":"showPane","panel":"TERMINAL","selectedFilePath":"src/App.kt","selectedLaunchTabId":"launch-1",""" +
                """"selectedTerminalId":"terminal-1"}""",
            payload,
        )
    }

    @Test
    fun discoveryDiscoverResponseSerializesTypedProjectChoices() {
        val payload = linkJson(
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
            """{"status":"available","projectChoices":[{"projectId":"project-a","projectName":"Project A","projectPath":"/tmp/project-a"}]}""",
            payload,
        )
    }

    @Test
    fun runTabMetadataDoesNotSerializeOutputText() {
        val payload = linkJson(
            LaunchTabsState(
                tabs = listOf(
                    LaunchTab(
                        id = "launch-1",
                        configurationId = "config",
                        title = "App",
                    )
                ),
                selectedLaunchTabId = "launch-1",
            )
        )

        assertEquals(
            """{"tabs":[{"id":"launch-1","configurationId":"config","title":"App"}],"selectedLaunchTabId":"launch-1"}""",
            payload,
        )
    }

    @Test
    fun selectedLaunchOutputSnapshotSerializesContentSeparately() {
        val payload = linkJson(
            LaunchOutputSnapshot(
                tabId = "launch-1",
                configurationId = "config",
                text = "output",
                isRunning = true,
            )
        )

        assertEquals(
            """{"tabId":"launch-1","configurationId":"config","text":"output","isRunning":true}""",
            payload,
        )
    }

    @Test
    fun selectedTerminalSnapshotSerializesContentSeparately() {
        val payload = linkJson(
            TerminalSnapshotResponse(
                terminalId = "terminal-1",
                snapshot = TerminalSnapshot(text = "prompt"),
                revision = 8L,
            )
        )

        assertEquals(
            """{"terminalId":"terminal-1","snapshot":{"text":"prompt"},"revision":8}""",
            payload,
        )
    }

    @Test
    fun selectedTerminalSnapshotSerializesCompactStyleCache() {
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
    fun terminalTabMetadataDoesNotSerializeOutputText() {
        val payload = linkJson(
            TerminalTabsState(
                tabs = listOf(
                    TerminalTab(
                        id = "terminal-1",
                        title = "Terminal",
                        cwd = "/project",
                        text = "large terminal output",
                        isSelected = true,
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

    private inline fun <reified T> linkJson(value: T): String = LinkJson.encodeToString(value)
}
