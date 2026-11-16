package app.tennyo.mobile.link.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A workspace reopened over a live session (Home → Resume) builds new panes. The host resumes the channel from the
 * last push, which fills nothing in a pane that never loaded, so the new panes must load a snapshot anyway.
 */
class SessionCoreTest {
    private val core = SessionCore(CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun aResumedChannelLoadsNoSnapshotForPanesThatAlreadyHaveOne() {
        assertFalse(core.snapshotDue(hostRequires = false))
        assertTrue(core.snapshotDue(hostRequires = true))
    }

    @Test
    fun freshPanesLoadOneSnapshotEvenWhenTheHostResumes() {
        core.markAttachmentFresh()

        assertTrue(core.snapshotDue(hostRequires = false))
        assertFalse(core.snapshotDue(hostRequires = false))
    }
}
