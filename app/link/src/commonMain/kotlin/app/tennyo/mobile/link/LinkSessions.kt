package app.tennyo.mobile.link

import app.tennyo.mobile.link.session.LinkServices
import app.tennyo.mobile.link.session.RealLinkSession

/**
 * Holds the session for the active ticket. Opening the same session again — even through a newer rendering of its
 * ticket — returns the running one, so leaving and re-entering the workspace resumes instead of reconnecting from
 * scratch; opening another ticket, or reopening after the session ended, starts over.
 */
class LinkSessions(private val create: (ticket: LinkTicket) -> RealLinkSession) {
    private var current: RealLinkSession? = null

    constructor(services: LinkServices) : this({ ticket -> RealLinkSession(ticket, services) })

    fun open(ticket: LinkTicket): LinkSession {
        current?.takeIf { it.matches(ticket) && it.state.value !is LinkState.Ended }?.let { return it }
        current?.close()
        return create(ticket).also { current = it }
    }
}
