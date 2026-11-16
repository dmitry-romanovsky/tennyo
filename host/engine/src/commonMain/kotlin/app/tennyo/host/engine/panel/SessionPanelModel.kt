package app.tennyo.host.engine.panel

import app.tennyo.contract.SessionTimeouts
import app.tennyo.crypto.HostShortId
import app.tennyo.host.engine.pairing.PAIRING_CONFIRMATION_MAX_ATTEMPTS
import app.tennyo.host.engine.pairing.RememberedPairSummary
import app.tennyo.host.engine.session.ConnectionRequest
import app.tennyo.host.engine.session.ConnectionRequestStatus
import app.tennyo.host.engine.session.HostSession
import app.tennyo.host.engine.session.SessionHost
import app.tennyo.host.engine.session.closePairingWindow

/**
 * The things a panel asks of its host that the protocol engine cannot do itself. Everything else the panel shows
 * is read from the session, so a host supplies only what is genuinely its own.
 */
interface HostPanelActions {
    /** Opens a pairing window, starting a session first when the host has none running. */
    fun openPairingWindow()

    /** Turns local-network discovery on or off, which is a setting the host owns. */
    fun setDiscoveryEnabled(enabled: Boolean)

    /** Turns Remote access on or off, which is a setting the host owns, and rebinds the listener to match. */
    fun setRemoteAccess(enabled: Boolean)
}

/**
 * The panel's six states, derived from one [SessionHost].
 *
 * Both editors' panels are this same projection: IntelliJ draws it in Compose and VS Code lists it in a quick pick,
 * but neither owns the mapping, so the two cannot drift into showing different things for the same session. Nothing
 * here is cached — every field is read when the panel asks, so the panel can never be a stale second copy of the
 * session's state.
 */
class SessionPanelModel(
    private val host: SessionHost,
    private val actions: HostPanelActions,
) : HostPanelModel {
    override val state: HostPanelState
        get() = host.activeSession()?.let(::sessionState) ?: HostPanelState.NoSession(
            visibility = visibility(),
            knownDeviceCount = knownDeviceCount(),
            reason = NoSessionReason.NOT_STARTED,
        )

    override fun openPairingWindow() = offMainThread(actions::openPairingWindow)

    override fun extendPairingWindow() {
        host.pairingWindow.extend()
    }

    override fun setVisibility(visibility: HostVisibility) {
        when (visibility) {
            // Nothing may reach this computer: discovery off, and any open window closed with it.
            HostVisibility.NO_ONE -> {
                host.closePairingWindow()
                offMainThread { actions.setDiscoveryEnabled(enabled = false) }
            }

            // Remembered devices only: discovery on, but no window for devices this host has never met.
            HostVisibility.KNOWN_DEVICES -> {
                host.closePairingWindow()
                offMainThread { actions.setDiscoveryEnabled(enabled = true) }
            }

            HostVisibility.ALL -> openPairingWindow()
        }
    }

    override val remoteAccess: RemoteAccessPanel
        get() {
            val endpoints = host.platform.listenerEndpoints()
            return RemoteAccessPanel(
                enabled = host.platform.isRemoteAccessEnabled(),
                // A phone away from home gets in through discovery; blocking discovery also turns it off.
                available = visibility() != HostVisibility.NO_ONE,
                port = endpoints?.port,
                overlay = endpoints?.overlay.orEmpty().map { OverlayAddress(it.network, it.address) },
            )
        }

    override fun setRemoteAccess(enabled: Boolean) = offMainThread { actions.setRemoteAccess(enabled) }

    override fun acceptRequest() = answerRequest(host::acceptConnectionRequest)

    override fun rejectRequest() = answerRequest(host::rejectConnectionRequest)

    override fun endSession() {
        host.activeSession()?.let { host.stopSession(it.artifact.sessionId, ENDED_FROM_PANEL) }
    }

    override fun knownDevices(): List<RememberedPairSummary> =
        host.rememberedPairs().sortedByDescending { it.lastUsedAtEpochMillis }

    override fun forgetDevice(pairId: String) {
        host.revokeRememberedPair(pairId)
    }

    // Each state answers on its own, in the order the pairing flow reaches them.
    @Suppress("ReturnCount")
    private fun sessionState(session: HostSession): HostPanelState {
        val sessionId = session.artifact.sessionId
        val visibility = visibility()
        val knownDevices = knownDeviceCount()
        if (host.isClientConnected(sessionId)) {
            return HostPanelState.Connected(
                visibility = visibility,
                knownDeviceCount = knownDevices,
                device = HostPanelDevice(
                    name = host.connectedDeviceName(sessionId).orEmpty(),
                    platform = "",
                    shortId = host.connectedDeviceId(sessionId)?.let(HostShortId::of).orEmpty(),
                    isNew = false,
                ),
                capabilities = session.artifact.capabilities,
            )
        }
        // Every request carries the session's words from the start; its status says which step the phone is on. An
        // accepted remembered-pair resume needs no words, so it shows neither card.
        val pending = pendingRequest()
        when (pending?.status) {
            ConnectionRequestStatus.PENDING -> return HostPanelState.Request(
                visibility = visibility,
                knownDeviceCount = knownDevices,
                device = pending.toPanelDevice(),
                expiresAtEpochMillis = pending.expiresAt(),
            )

            ConnectionRequestStatus.AWAITING_PAIRING -> return wordsState(visibility, knownDevices, pending, session)
            else -> Unit
        }
        val window = host.pairingWindow.current()
            ?: return HostPanelState.NoSession(
                visibility = visibility,
                knownDeviceCount = knownDevices,
                reason = NoSessionReason.NOT_STARTED,
            )
        return HostPanelState.Waiting(
            visibility = visibility,
            knownDeviceCount = knownDevices,
            hostName = window.hostName,
            hostShortId = window.hostShortId,
            expiresAtEpochMillis = window.expiresAtEpochMillis,
        )
    }

    /** The words, or the same words with one attempt fewer after a wrong order. */
    private fun wordsState(
        visibility: HostVisibility,
        knownDevices: Int,
        request: ConnectionRequest,
        session: HostSession,
    ): HostPanelState {
        val attemptsLeft = session.auth.pairingAttemptsLeft
        return if (attemptsLeft < PAIRING_CONFIRMATION_MAX_ATTEMPTS) {
            HostPanelState.WrongWords(visibility, knownDevices, request.pairingWords, attemptsLeft, request.expiresAt())
        } else {
            HostPanelState.Words(visibility, knownDevices, request.pairingWords, attemptsLeft, request.expiresAt())
        }
    }

    /** The panel's one standing question, read back from the two pieces of state that answer it. */
    private fun visibility(): HostVisibility = when {
        host.pairingWindow.current() != null -> HostVisibility.ALL
        host.platform.isDiscoveryEnabled() -> HostVisibility.KNOWN_DEVICES
        else -> HostVisibility.NO_ONE
    }

    private fun knownDeviceCount(): Int = host.rememberedPairs().size

    private fun pendingRequest(): ConnectionRequest? =
        host.activeSession()?.let { host.pendingConnectionRequests(it.artifact.sessionId).firstOrNull() }

    private fun answerRequest(answer: (String, String) -> Boolean) {
        val session = host.activeSession() ?: return
        val request = pendingRequest() ?: return
        answer(session.artifact.sessionId, request.id)
    }

    /** The panel is clicked on the editor's UI thread, and none of these actions may run there. */
    private fun offMainThread(action: () -> Unit) = host.platform.executeInBackground(action)

    private fun ConnectionRequest.toPanelDevice(): HostPanelDevice = HostPanelDevice(
        name = clientName,
        platform = clientVersion,
        shortId = HostShortId.of(deviceFingerprint),
        // A device that reaches a request is one this session has not approved; that is what pairing is for.
        isNew = true,
    )

    /** A request lives as long as the pending-approval window the session already enforces. */
    private fun ConnectionRequest.expiresAt(): Long =
        requestedAtEpochMillis + SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS

    private companion object {
        const val ENDED_FROM_PANEL = "Ended from the Tennyo panel."
    }
}
