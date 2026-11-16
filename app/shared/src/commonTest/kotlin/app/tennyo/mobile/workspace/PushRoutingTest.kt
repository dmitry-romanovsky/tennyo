package app.tennyo.mobile.workspace

import app.tennyo.contract.LaunchConfigurationsState
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkPush
import app.tennyo.mobile.link.pane.SyncPass
import app.tennyo.mobile.link.pane.WorkspacePane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The checkpoint invariant: the workspace acknowledges a push — which lets the session checkpoint past it — only when
 * every pane applied it. A pane that throws leaves it unacknowledged, so the host sends it again on reconnect.
 */
class PushRoutingTest {
    private val frame = LinkFrame.Push(
        seq = 42,
        push = LinkPush.LaunchConfigurations(LaunchConfigurationsState(emptyList())),
    )

    @Test
    fun aPushEveryPaneAppliedIsAcknowledged() {
        val panes = listOf(RecordingPane(), RecordingPane())

        assertTrue(panes.applyAll(frame))
        assertEquals(listOf(listOf(frame.push), listOf(frame.push)), panes.map { it.applied })
    }

    @Test
    fun aPaneFailureLeavesThePushUnacknowledged() {
        assertFalse(listOf(RecordingPane(), RecordingPane(fails = true)).applyAll(frame))
    }

    private class RecordingPane(private val fails: Boolean = false) : WorkspacePane {
        val applied = mutableListOf<LinkPush>()

        override suspend fun loadSnapshot(shown: Boolean) = Unit

        override suspend fun sync(pass: SyncPass) = Unit

        override fun apply(push: LinkPush) {
            check(!fails) { "simulated handler failure" }
            applied += push
        }
    }
}
