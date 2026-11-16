package app.tennyo.mobile.link

import app.tennyo.contract.Capability
import app.tennyo.contract.HostEditor
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkResult
import app.tennyo.contract.SessionStats
import app.tennyo.mobile.link.pane.PaneLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Runs calls on the host of the current session. What pane clients depend on. */
interface LinkCalls {
    /**
     * Runs [call] over the session's channel. Every failure surfaces as a [LinkFault]; a call made while no channel is
     * open fails with [LinkFault.StreamClosed].
     */
    suspend fun call(call: LinkCall): LinkResult
}

/** [call], with the result the call is defined to answer; an unexpected result is a protocol failure. */
suspend inline fun <reified R : LinkResult> LinkCalls.request(call: LinkCall): R =
    when (val result = call(call)) {
        is R -> result
        else -> throw LinkFault.Refused(
            status = 0,
            code = "unexpected_result",
            message = "The computer answered ${call::class.simpleName} with ${result::class.simpleName}.",
        )
    }

/**
 * The phone's connection to one IDE session, from the first hello through pairing, live sync, reconnects and
 * background keep-alive to its end. One exists per active ticket; the workspace screen attaches to it while shown.
 */
interface LinkSession : PaneLink {
    val state: StateFlow<LinkState>

    /** The ticket in use: the one the session was opened with, re-issued once the host confirms an endpoint. */
    override val ticket: StateFlow<LinkTicket?>

    override val project: StateFlow<LinkProject>

    val stats: StateFlow<SessionStats>

    /**
     * What the host pushes for the workspace, in order; the session consumes the ones about itself (stats, session
     * end) and forwards the rest. Each must be [acknowledge]d once applied.
     */
    val pushes: Flow<LinkFrame.Push>

    /**
     * Records [push] as applied, so a reconnect resumes after it. A push whose handling failed is left
     * unacknowledged and the host sends it again on the next reconnect.
     */
    fun acknowledge(push: LinkFrame.Push)

    /** Starts the session, or brings an already started one back to the foreground for [attachment]. */
    fun attach(attachment: LinkAttachment)

    /** The workspace left the screen. Live sync stops at once; the session stays resumable in the background. */
    fun detach()

    fun retry()

    fun end()

    fun togglePairingWord(word: String)

    fun clearPairingWords()

    fun confirmPairing()

    fun refreshStats()

    fun setStatsVisible(visible: Boolean)

    /**
     * Tells the host what is on screen so it pushes only that. Repeats are dropped unless [force] is set; the
     * workspace forces one whenever sync becomes ready.
     */
    fun showSurface(surface: ActiveSurface, force: Boolean = false)

    fun onForeground()

    fun onBackground()
}

/** The workspace's side of an attached session. */
interface LinkAttachment {
    /**
     * Loads what the visible panes need. Runs after every (re)connect the host could not resume, and completes before
     * pushes resume, so no push is applied to a snapshot older than itself.
     */
    suspend fun loadSnapshot(capabilities: Set<Capability>)

    /**
     * Sync stops because the connection failed or ended, or — [forBackground] — because the app went to the background,
     * in which case pending edits and resizes are dropped too.
     */
    fun suspendSync(forBackground: Boolean)
}

data class LinkProject(
    val name: String = "",
    val path: String = "",
    val capabilities: Set<Capability> = emptySet(),
    val editor: HostEditor? = null,
)

/** What the workspace shows, as the host's [LinkCall.ShowPane] carries it. */
data class ActiveSurface(
    val panel: String,
    val selectedFilePath: String? = null,
    val selectedLaunchTabId: String? = null,
    val selectedTerminalId: String? = null,
)
