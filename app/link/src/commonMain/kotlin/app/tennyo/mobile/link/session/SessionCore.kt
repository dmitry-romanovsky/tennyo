package app.tennyo.mobile.link.session

import app.tennyo.contract.LinkFrame
import app.tennyo.contract.SessionStats
import app.tennyo.mobile.link.ConnectStep
import app.tennyo.mobile.link.LinkAttachment
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkState
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.transport.OpenLinkChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What every part of one session reads and publishes: its scope, the shown state, its channel, and the workspace. */
internal class SessionCore(val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<LinkState>(LinkState.Connecting(ConnectStep.Reaching))
    val state: StateFlow<LinkState> = mutableState.asStateFlow()

    /** The published state. [LinkState.Ended] is final: work that finishes late cannot revive the session. */
    var linkState: LinkState
        get() = mutableState.value
        set(next) {
            mutableState.update { current -> if (current is LinkState.Ended) current else next }
        }

    val ticket = MutableStateFlow<LinkTicket?>(null)
    val project = MutableStateFlow(LinkProject())
    val stats = MutableStateFlow(SessionStats())
    val pushes = MutableSharedFlow<LinkFrame.Push>()

    /** The open channel, or `null` while none is; every call goes through it. */
    val channel = MutableStateFlow<OpenLinkChannel?>(null)

    var attachment: LinkAttachment? = null

    /** A workspace attached to a session that already ran: its panes are new and hold nothing yet. */
    private var attachmentIsFresh = false

    fun markAttachmentFresh() {
        attachmentIsFresh = true
    }

    /**
     * Whether the panes must load a snapshot now: the host could not resume ([hostRequires]), or a fresh attachment
     * has panes that replayed pushes alone cannot fill. Answers `true` once per fresh attachment.
     */
    fun snapshotDue(hostRequires: Boolean): Boolean = (hostRequires || attachmentIsFresh).also {
        attachmentIsFresh = false
    }

    val projectName: String get() = project.value.name

    /** Moves [LinkState.Live] to [transform]'s result and leaves every other state alone. */
    fun updateLive(transform: (LinkState.Live) -> LinkState) {
        mutableState.update { current -> if (current is LinkState.Live) transform(current) else current }
    }

    fun updatePairing(transform: (LinkState.Pairing) -> LinkState) {
        mutableState.update { current -> if (current is LinkState.Pairing) transform(current) else current }
    }
}

/** Stamps the locally tracked session start and, when given, the latest ping onto a stats snapshot from the host. */
internal fun SessionStats.withLocalSessionStats(
    status: ConnectionStatus,
    previous: SessionStats,
    lastPingMillis: Long? = null,
): SessionStats =
    copy(
        sessionStartedAtEpochMillis = status.notificationStartedAtEpochMillis,
        lastPingMillis = lastPingMillis ?: previous.lastPingMillis,
    )
