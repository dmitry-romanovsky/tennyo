package app.tennyo.host.engine.session

import app.tennyo.contract.PairingOffer
import app.tennyo.contract.ProtocolVersions
import app.tennyo.contract.SessionTimeouts
import app.tennyo.crypto.HostShortId
import app.tennyo.crypto.constantTimeEquals
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.pairing.HostDisplayName
import kotlin.concurrent.atomics.AtomicReference

/** One open pairing window: what the host answers offers with until the deadline passes or a device is approved. */
data class OpenPairingWindow(
    val pairingSessionId: String,
    val pairingToken: String,
    val hostName: String,
    val hostShortId: String,
    val hostLabel: String,
    val expiresAtEpochMillis: Long,
) {
    fun toOffer(): PairingOffer = PairingOffer(
        pairingSessionId = pairingSessionId,
        pairingToken = pairingToken,
        hostName = hostName,
        hostShortId = hostShortId,
        hostLabel = hostLabel,
        expiresAtEpochMillis = expiresAtEpochMillis,
        securityVersion = ProtocolVersions.SECURITY_VERSION,
    )
}

/**
 * Whether this host is open to devices it has never met, and under what name.
 *
 * Opening a window implies a running session: the window's deadline is the session's own pending-approval
 * timeout, so a window nobody pairs with ends with the session it belongs to. The token it hands out reaches only
 * the claim and confirm steps — [SessionCredential.PairingToken] is what carries that restriction through
 * [SessionHost.validate] — and closing the window clears it, so an approved or expired window leaves no credential
 * behind.
 *
 * The name is drawn fresh per window and never persisted; the short id is derived from the transport fingerprint,
 * so it is the host's to show but not the host's to choose.
 */
class PairingWindow(
    private val wallClockMillis: () -> Long,
    private val drawName: () -> String = HostDisplayName::next,
) {
    private val open = AtomicReference<OpenPairingWindow?>(null)

    /** The window if one is open and unexpired; `null` otherwise. Expiry is observed here, never scheduled. */
    fun current(): OpenPairingWindow? = open.load()?.takeIf { wallClockMillis() < it.expiresAtEpochMillis }

    /** Opens a window for [pairingSessionId], drawing a fresh name and token. Re-opening supersedes the previous. */
    fun open(pairingSessionId: String, transportFingerprint: String, hostLabel: String): OpenPairingWindow {
        val window = OpenPairingWindow(
            pairingSessionId = pairingSessionId,
            pairingToken = secureRandomBytes(PAIRING_TOKEN_BYTES).toBase64Url(),
            hostName = drawName(),
            hostShortId = HostShortId.of(transportFingerprint),
            hostLabel = hostLabel,
            expiresAtEpochMillis = wallClockMillis() + SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS,
        )
        open.store(window)
        return window
    }

    /** Restarts the deadline of the open window, keeping its name, id and token. `null` when none is open. */
    fun extend(): OpenPairingWindow? {
        val window = current() ?: return null
        val extended = window.copy(
            expiresAtEpochMillis = wallClockMillis() + SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS,
        )
        open.store(extended)
        return extended
    }

    /** Closes the window and drops its token, so no further request can authenticate with it. */
    fun close() {
        open.store(null)
    }

    /** Whether [token] is the open window's pairing token, compared without an early exit. */
    fun accepts(token: String): Boolean {
        val window = current() ?: return false
        return constantTimeEquals(window.pairingToken, token)
    }

    private companion object {
        const val PAIRING_TOKEN_BYTES = 32
    }
}

/**
 * Opens [SessionHost] to devices it has never met: starts the session the window belongs to, then draws the name
 * and token the offer carries. The window's deadline is the session's own pending-approval timeout, so a window
 * nobody pairs with ends with its session.
 */
fun SessionHost.openPairingWindow(): OpenPairingWindow {
    val artifact = startSession()
    return pairingWindow.open(
        pairingSessionId = artifact.sessionId,
        transportFingerprint = artifact.fingerprint,
        hostLabel = artifact.hostVersion,
    )
}

/**
 * Leaves "all": drops the window and the session it started, unless a device is already connected to that session.
 *
 * Closing the window alone is not enough. The session `openPairingWindow` started would stay behind with no window,
 * which reads as [hasClaimedSession] — a host in use — and a host in use stops advertising on the LAN. Visibility
 * would then say "known devices" while no known device could find it.
 */
fun SessionHost.closePairingWindow() {
    val unclaimedSessionId = activeSession()?.artifact?.sessionId?.takeUnless(::isClientConnected)
    pairingWindow.close()
    unclaimedSessionId?.let { stopSession(it, PAIRING_WINDOW_LEFT) }
}

private const val PAIRING_WINDOW_LEFT = "Visibility left \"all\"."

/**
 * Whether a session on this host is in use rather than waiting to be found. A session behind an open pairing window
 * is the latter: it must keep advertising on the LAN, because the advertisement is the only way a phone that has
 * never paired with this host can see it while visibility is "all".
 */
val SessionHost.hasClaimedSession: Boolean
    get() = sessionCount > 0 && pairingWindow.current() == null

/**
 * The live session [sessionId] names together with the credential that authenticated it: its session token, or the
 * pairing token of an open window on this host. Callers act on the credential — a [SessionCredential.PairingToken]
 * reaches only the claim and confirm steps.
 */
fun SessionHost.match(sessionId: String?, token: String?): SessionMatch? {
    if (sessionId.isNullOrBlank() || token.isNullOrBlank()) return null
    val bySessionToken = validate(sessionId, token)
        ?.let { SessionMatch(this, it, SessionCredential.SessionToken(token)) }
    return bySessionToken ?: matchOpenWindow(sessionId, token)
}

private fun SessionHost.matchOpenWindow(sessionId: String, token: String): SessionMatch? =
    pairingWindow.current()
        ?.takeIf { it.pairingSessionId == sessionId && pairingWindow.accepts(token) }
        ?.let { session(sessionId) }
        ?.takeIf { !it.hasApprovedDeviceTimedOut() }
        ?.let { SessionMatch(this, it, SessionCredential.PairingToken(token)) }
