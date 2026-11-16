package app.tennyo.mobile.workspace

import app.tennyo.contract.Capability
import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSyncTest {
    @Test
    fun aVisibleChannelLossShowsAsReconnectingWhileTheSessionItselfHolds() {
        val state = WorkspaceState(
            sessionTicket = TEST_TICKET,
            connectionState = ConnectionState.CONNECTED,
            liveSyncState = LiveSyncState.RECONNECTING,
        )

        assertEquals(ConnectionState.RECONNECTING, state.displayConnectionState())
    }

    @Test
    fun aPaneFailureStaysOutOfTheConnectionStatus() {
        val connected = WorkspaceState(sessionTicket = TEST_TICKET, connectionState = ConnectionState.CONNECTED)
        val failed = reduce(connected, WorkspaceMsg.ActionFailed(UiText.Verbatim("Unable to create terminal")))

        assertNull(failed.issue)
        assertEquals(ConnectionMessageKind.CONNECTED, failed.connectionMessageKind())
        assertEquals(UiText.Verbatim("Unable to create terminal"), failed.actionFailure)
    }

    @Test
    fun aPaneRecoveringOrADismissalClearsOnlyThePaneFailure() {
        val lost = WorkspaceState(sessionTicket = TEST_TICKET, issue = WorkspaceIssue.Known.Lost)
        val failed = reduce(lost, WorkspaceMsg.ActionFailed(UiText.Verbatim("save failed")))

        listOf(WorkspaceMsg.ActionRecovered, WorkspaceMsg.ActionFailureDismissed).forEach { msg ->
            val cleared = reduce(failed, msg)
            assertNull(cleared.actionFailure)
            assertEquals(WorkspaceIssue.Known.Lost, cleared.issue)
        }
    }

    @Test
    fun aPaneOffScreenAtTheSnapshotTakesItsOwnOnceWhenFirstShown() {
        val loaded = reduce(WorkspaceState(sessionTicket = TEST_TICKET), WorkspaceMsg.SnapshotLoaded)
        assertTrue(WorkspacePaneKind.FILES in loaded.snapshotDue)
        assertFalse(WorkspacePaneKind.CHAT in loaded.snapshotDue)

        val shown = reduce(loaded, WorkspaceMsg.PaneSelected(WorkspacePaneKind.FILES))
        assertFalse(WorkspacePaneKind.FILES in shown.snapshotDue)
    }
}

internal val TEST_TICKET = LinkTicket(
    endpoint = "https://192.0.2.10:8443",
    sessionId = "session",
    token = "token",
    fingerprint = "fingerprint",
    projectName = "Tennyo",
    projectPath = "~/tennyo",
)
