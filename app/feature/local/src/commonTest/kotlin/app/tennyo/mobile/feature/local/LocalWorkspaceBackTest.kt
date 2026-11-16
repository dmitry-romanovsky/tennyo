package app.tennyo.mobile.feature.local

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What back has to undo first in a local workspace: the file drawer, which switching panes also closes. */
class LocalWorkspaceBackTest {
    @Test
    fun `the drawer is state back can close and switching panes closes it`() {
        val open = reduce(LocalWorkspaceState("demo"), LocalWorkspaceMsg.NavigatorOpenChanged(true))
        assertTrue(open.navigatorOpen)

        assertFalse(reduce(open, LocalWorkspaceMsg.NavigatorOpenChanged(false)).navigatorOpen)
        assertFalse(reduce(open, LocalWorkspaceMsg.PaneSelected(LocalPane.Git)).navigatorOpen)
    }
}
