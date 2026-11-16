package app.tennyo.mobile.feature.terminal

import app.tennyo.contract.TerminalFrame
import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalStyleDefinition
import app.tennyo.contract.TerminalStyleRun
import app.tennyo.contract.TerminalStyleSpan
import app.tennyo.contract.TerminalTab
import app.tennyo.contract.TerminalTabsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalStateTest {
    @Test
    fun terminalFrameForKnownTabIsIgnoredWhileThePaneIsHidden() {
        val state = TerminalState(
            tabs = listOf(terminalTab(id = "terminal", text = "before")),
            selectedTerminalId = "terminal",
        )

        assertFalse(state.shouldApplyFrame("terminal", shown = false))
        assertFalse(state.shouldApplyFrame("different-terminal", shown = true))
        assertTrue(state.shouldApplyFrame("terminal", shown = true))
    }

    @Test
    fun terminalPaneAcceptsInitialFrameBeforeTabMetadataArrives() {
        assertTrue(TerminalState().shouldApplyFrame("terminal", shown = true))
    }

    @Test
    fun terminalTabsUpdateDoesNotOverwriteFrameOutput() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ls\nREADME.md")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ls\nREADME.md")),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(terminalTab(title = "IDE Terminal", text = "")),
                selectedTerminalId = "terminal",
            )
        )

        assertEquals("IDE Terminal", updated.tabs.single().title)
        assertEquals("prompt> ls\nREADME.md", updated.tabs.single().text)
        assertEquals("prompt> ls\nREADME.md", updated.tabs.single().snapshot?.text)
    }

    @Test
    fun terminalFrameCanClearOutput() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "before clear")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "before clear")),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                text = "",
                snapshot = TerminalSnapshot(text = ""),
            )
        )

        assertEquals("", updated.tabs.single().text)
        assertEquals("", updated.tabs.single().snapshot?.text)
    }

    @Test
    fun terminalTabsUpdateUsesBackendSelectionAndKeepsTabOutputIndependent() {
        val state = TerminalState(
            tabs = listOf(
                terminalTab(id = "first", text = "first output"),
                terminalTab(id = "second", text = "second output"),
            ),
            snapshots = mapOf(
                "first" to TerminalSnapshot(text = "first output"),
                "second" to TerminalSnapshot(text = "second output"),
            ),
            selectedTerminalId = "first",
        )

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(
                    terminalTab(id = "first", title = "First renamed", text = ""),
                    terminalTab(id = "second", title = "Second renamed", text = ""),
                ),
                selectedTerminalId = "second",
            )
        )

        assertEquals("second", updated.selectedTerminalId)
        assertEquals("second output", updated.selectedTerminalSnapshot().text)
        assertEquals("First renamed", updated.tabs[0].title)
        assertEquals("first output", updated.tabs[0].text)
    }

    @Test
    fun newTerminalTabAcceptsInitialSnapshotFromTabsState() {
        val state = TerminalState()

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(terminalTab(text = "initial prompt")),
                selectedTerminalId = "terminal",
            )
        )

        assertEquals("initial prompt", updated.selectedTerminalSnapshot().text)
    }

    @Test
    fun metadataOnlyTerminalTabHasNoSnapshotUntilFrameArrives() {
        val state = TerminalState()

        val updated = state.applyTerminalTabsState(
            TerminalTabsState(
                tabs = listOf(terminalTab(text = "", snapshot = null)),
                selectedTerminalId = "terminal",
            )
        )

        assertEquals("", updated.selectedTerminalSnapshot().text)
        assertNull(updated.tabs.single().snapshot)
    }

    @Test
    fun terminalFrameSnapshotIsRenderingSourceEvenAfterMetadataRefresh() {
        val state = TerminalState()
            .applyTerminalTabsState(
                TerminalTabsState(
                    tabs = listOf(terminalTab(text = "", snapshot = null)),
                    selectedTerminalId = "terminal",
                )
            )
            .applyTerminalFrameState(
                TerminalFrame(
                    terminalId = "terminal",
                    text = "",
                    snapshot = TerminalSnapshot(
                        text = "prompt> echo hi\nhi",
                        spans = listOf(TerminalStyleSpan(0, 6, foreground = "#23D18B")),
                    ),
                )
            )
            .applyTerminalTabsState(
                TerminalTabsState(
                    tabs = listOf(terminalTab(title = "IDE Terminal", text = "", snapshot = null)),
                    selectedTerminalId = "terminal",
                )
            )

        assertEquals("prompt> echo hi\nhi", state.selectedTerminalSnapshot().text)
        assertEquals("#23D18B", state.selectedTerminalSnapshot().spans.single().foreground)
    }

    @Test
    fun terminalSnapshotRefreshUpdatesExistingOutputAndDecorations() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "old prompt")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "old prompt")),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                text = "",
                snapshot = TerminalSnapshot(
                    text = "new prompt",
                    spans = listOf(
                        TerminalStyleSpan(
                            0,
                            3,
                            foreground = "#569CD6",
                            background = "#1E1E1E"
                        )
                    ),
                ),
            )
        )

        assertEquals("new prompt", updated.selectedTerminalSnapshot().text)
        assertEquals("#569CD6", updated.selectedTerminalSnapshot().spans.single().foreground)
        assertEquals("#1E1E1E", updated.selectedTerminalSnapshot().spans.single().background)
    }

    @Test
    fun terminalSnapshotRefreshCanClearExistingOutput() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "old prompt")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "old prompt")),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                text = "",
                snapshot = TerminalSnapshot(text = ""),
            )
        )

        assertEquals("", updated.selectedTerminalSnapshot().text)
        assertEquals("", updated.tabs.single().text)
    }

    @Test
    fun frameForVisibleEmptyTerminalBecomesSelected() {
        val state = TerminalState(
            tabs = listOf(
                terminalTab(id = "stale", text = "", snapshot = null),
                terminalTab(id = "active", text = "", snapshot = null),
            ),
            selectedTerminalId = "stale",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "active",
                text = "",
                snapshot = TerminalSnapshot(text = "new output"),
            )
        )

        assertEquals("active", updated.selectedTerminalId)
        assertEquals("new output", updated.selectedTerminalSnapshot().text)
    }

    @Test
    fun terminalAppendFrameExtendsExistingSnapshot() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ")),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                text = "ls\r\nREADME.md",
            )
        )

        assertEquals("prompt> ls\nREADME.md", updated.tabs.single().text)
        assertEquals("prompt> ls\nREADME.md", updated.selectedTerminalSnapshot().text)
    }

    @Test
    fun compactTerminalAppendFrameAppliesMatchingRevisionAndStyles() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ")),
            snapshotRevisions = mapOf("terminal" to 1L),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                baseRevision = 1L,
                revision = 2L,
                styleEpoch = 7L,
                append = "ls\n",
                cursorOffset = 11,
                styles = mapOf(12 to TerminalStyleDefinition(foreground = "#569CD6", bold = true)),
                styleRuns = listOf(TerminalStyleRun(styleId = 12, ranges = listOf(0, 2))),
            )
        )

        assertEquals("prompt> ls\n", updated.selectedTerminalSnapshot().text)
        assertEquals(2L, updated.snapshotRevisions["terminal"])
        assertEquals(11, updated.selectedTerminalSnapshot().cursorOffset)
        assertEquals("#569CD6", updated.selectedTerminalSnapshot().spans.single().foreground)
        assertEquals(true, updated.selectedTerminalSnapshot().spans.single().bold)
    }

    @Test
    fun terminalFrameAppliesHiddenCursorVisibilityWithoutChangingText() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ", cursorVisible = true)),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                cursorVisible = false,
            )
        )

        assertEquals("prompt> ", updated.selectedTerminalSnapshot().text)
        assertEquals(false, updated.selectedTerminalSnapshot().cursorVisible)
    }

    @Test
    fun terminalFrameAppliesVisibleCursorVisibilityWithoutChangingText() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ", cursorVisible = false)),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                cursorVisible = true,
            )
        )

        assertEquals("prompt> ", updated.selectedTerminalSnapshot().text)
        assertEquals(true, updated.selectedTerminalSnapshot().cursorVisible)
    }

    @Test
    fun compactTerminalAppendFrameWithRevisionGapRequestsRecovery() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ")),
            snapshotRevisions = mapOf("terminal" to 1L),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                baseRevision = 3L,
                revision = 4L,
                styleEpoch = 7L,
                append = "pwd\n",
            )
        )

        assertEquals("prompt> ", updated.selectedTerminalSnapshot().text)
        assertEquals(setOf("terminal"), updated.styleRecoveryTerminalIds)
        assertEquals(1L, updated.snapshotRevisions["terminal"])
    }

    @Test
    fun compactTerminalAppendFrameShiftsStylesWhenWindowSlides() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "abcdef")),
            snapshots = mapOf(
                "terminal" to TerminalSnapshot(
                    text = "abcdef",
                    spans = listOf(TerminalStyleSpan(2, 4, foreground = "#23D18B")),
                )
            ),
            snapshotRevisions = mapOf("terminal" to 5L),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                baseRevision = 5L,
                revision = 6L,
                styleEpoch = 1L,
                dropPrefixChars = 2,
                append = "gh",
            )
        )

        assertEquals("cdefgh", updated.selectedTerminalSnapshot().text)
        assertEquals(
            TerminalStyleSpan(0, 2, foreground = "#23D18B"),
            updated.selectedTerminalSnapshot().spans.single()
        )
    }

    @Test
    fun compactTerminalAppendFrameWithUnknownStyleRequestsAllStylesRecovery() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ")),
            snapshotRevisions = mapOf("terminal" to 1L),
            styleEpoch = 4L,
            styleDefinitions = emptyMap(),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                baseRevision = 1L,
                revision = 2L,
                styleEpoch = 4L,
                append = "FAILED\n",
                styleRuns = listOf(TerminalStyleRun(styleId = 19, ranges = listOf(0, 6))),
            )
        )

        assertEquals("prompt> ", updated.selectedTerminalSnapshot().text)
        assertEquals(setOf("terminal"), updated.styleRecoveryTerminalIds)
        assertEquals(4L, updated.styleEpoch)
    }

    @Test
    fun compactTerminalStyleEpochChangeClearsCachedStylesAndRequestsRecovery() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ")),
            snapshotRevisions = mapOf("terminal" to 1L),
            styleEpoch = 1L,
            styleDefinitions = mapOf(12 to TerminalStyleDefinition(foreground = "#23D18B")),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                baseRevision = 1L,
                revision = 2L,
                styleEpoch = 2L,
                append = "ls\n",
                styles = mapOf(12 to TerminalStyleDefinition(foreground = "#569CD6")),
                styleRuns = listOf(TerminalStyleRun(styleId = 12, ranges = listOf(0, 2))),
            )
        )

        assertEquals("prompt> ", updated.selectedTerminalSnapshot().text)
        assertEquals(2L, updated.styleEpoch)
        assertEquals(emptyMap<Int, TerminalStyleDefinition>(), updated.styleDefinitions)
        assertEquals(setOf("terminal"), updated.styleRecoveryTerminalIds)
    }

    @Test
    fun compactTerminalSnapshotAppliesAfterStyleEpochRecovery() {
        val state = TerminalState(
            tabs = listOf(terminalTab(text = "prompt> ")),
            snapshots = mapOf("terminal" to TerminalSnapshot(text = "prompt> ")),
            snapshotRevisions = mapOf("terminal" to 1L),
            styleEpoch = 1L,
            styleDefinitions = mapOf(12 to TerminalStyleDefinition(foreground = "#23D18B")),
            styleRecoveryTerminalIds = setOf("terminal"),
            selectedTerminalId = "terminal",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "terminal",
                revision = 2L,
                snapshot = TerminalSnapshot(
                    text = "FAILED\n",
                    styleEpoch = 2L,
                    styles = mapOf(19 to TerminalStyleDefinition(foreground = "#FF5555", bold = true)),
                    styleRuns = listOf(TerminalStyleRun(styleId = 19, ranges = listOf(0, 6))),
                ),
            )
        )

        assertEquals("FAILED\n", updated.selectedTerminalSnapshot().text)
        assertEquals(2L, updated.styleEpoch)
        assertEquals(setOf(19), updated.styleDefinitions.keys)
        assertEquals(emptySet<String>(), updated.styleRecoveryTerminalIds)
        assertEquals("#FF5555", updated.selectedTerminalSnapshot().spans.single().foreground)
    }

    @Test
    fun errorFrameForUnknownTerminalDoesNotCreatePhantomTab() {
        val state = TerminalState(
            tabs = listOf(terminalTab(id = "active", text = "prompt")),
            snapshots = mapOf("active" to TerminalSnapshot(text = "prompt")),
            selectedTerminalId = "active",
        )

        val updated = state.applyTerminalFrameState(
            TerminalFrame(
                terminalId = "stale",
                text = "Terminal is not running.\n",
                isError = true,
            )
        )

        assertEquals(listOf("active"), updated.tabs.map { it.id })
        assertEquals("promptTerminal is not running.\n", updated.selectedTerminalSnapshot().text)
    }

    @Test
    fun removeTerminalKeepsAdjacentSelectionWhenClosingSelectedTab() {
        val state = TerminalState(
            tabs = listOf(
                terminalTab(id = "first"),
                terminalTab(id = "second"),
                terminalTab(id = "third"),
            ),
            snapshots = mapOf(
                "first" to TerminalSnapshot(text = "first"),
                "second" to TerminalSnapshot(text = "second"),
                "third" to TerminalSnapshot(text = "third"),
            ),
            selectedTerminalId = "second",
        )

        val updated = state.removeTerminal("second", keepAdjacentSelectionPending = true)

        assertEquals(listOf("first", "third"), updated.tabs.map { it.id })
        assertEquals("third", updated.selectedTerminalId)
        assertEquals("third", updated.pendingSelectionId)
    }

    @Test
    fun removeTerminalKeepsExistingSelectionWhenClosingDifferentTab() {
        val state = TerminalState(
            tabs = listOf(
                terminalTab(id = "first"),
                terminalTab(id = "second"),
                terminalTab(id = "third"),
            ),
            snapshots = mapOf(
                "first" to TerminalSnapshot(text = "first"),
                "second" to TerminalSnapshot(text = "second"),
                "third" to TerminalSnapshot(text = "third"),
            ),
            snapshotRevisions = mapOf("second" to 2L),
            styleRecoveryTerminalIds = setOf("second"),
            selectedTerminalId = "third",
            pendingSelectionId = "third",
        )

        val updated = state.removeTerminal("second")

        assertEquals(listOf("first", "third"), updated.tabs.map { it.id })
        assertEquals("third", updated.selectedTerminalId)
        assertEquals("third", updated.pendingSelectionId)
        assertNull(updated.snapshots["second"])
        assertNull(updated.snapshotRevisions["second"])
        assertEquals(emptySet<String>(), updated.styleRecoveryTerminalIds)
    }

    private fun terminalTab(
        id: String = "terminal",
        title: String = "Terminal",
        text: String = "",
        snapshot: TerminalSnapshot? = TerminalSnapshot(
            text = text,
            spans = listOf(
                TerminalStyleSpan(
                    start = 0,
                    end = text.length,
                    foreground = "#E5E5E5",
                )
            ),
        ),
    ): TerminalTab =
        TerminalTab(
            id = id,
            title = title,
            cwd = "/project",
            text = text,
            isSelected = false,
            snapshot = snapshot,
        )
}
