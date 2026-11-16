package app.tennyo.mobile.link.pane

import app.tennyo.mobile.link.LinkCalls
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkTicket
import kotlinx.coroutines.flow.StateFlow

/** The part of a link session a pane talks to: its calls, the session's ticket, and what the host can serve. */
interface PaneLink : LinkCalls {
    /** The ticket in use; `null` before the session has one, when panes have nothing to ask. */
    val ticket: StateFlow<LinkTicket?>

    val project: StateFlow<LinkProject>
}
