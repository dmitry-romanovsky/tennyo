package app.tennyo.mobile.link.pairing

import app.tennyo.contract.DiscoverRequest
import app.tennyo.contract.LinkJson
import app.tennyo.contract.OfferResponse
import app.tennyo.contract.PairingOffer
import app.tennyo.contract.PairingOfferStatus
import app.tennyo.crypto.HostShortId
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.discoveryDebug
import app.tennyo.mobile.link.runCatchingCancellable
import app.tennyo.mobile.link.transport.LinkHttp
import app.tennyo.mobile.link.transport.observeTransportFingerprint
import app.tennyo.mobile.link.transport.rememberObservedTransportPin

/**
 * A host found in pairing mode, with the fingerprint this device observed for it. Everything after the user picks
 * this target runs pinned to [fingerprint]; the tapped words then confirm that the pinned fingerprint is the one
 * the host believes it has.
 */
data class PairingTarget(
    val endpoint: String,
    val pairingSessionId: String,
    val pairingToken: String,
    val fingerprint: String,
    val hostShortId: String,
    val hostName: String,
    val hostLabel: String,
    val expiresAtEpochMillis: Long,
) {
    /** [hostShortId] grouped as the user compares it against the desktop: "abcd 1234 ef56". */
    val displayShortId: String get() = HostShortId.display(hostShortId)

    /**
     * The session handle the pairing steps travel under: the window's own credential in place of a session token.
     * The host matches it as a [app.tennyo.contract.PairingOffer]'s pairing token and lets it reach claim and
     * confirm only, and every transcript binds it in the session token's place.
     */
    fun toProvisionalTicket(): LinkTicket = LinkTicket(
        endpoint = endpoint,
        sessionId = pairingSessionId,
        token = pairingToken,
        fingerprint = fingerprint,
        projectName = "",
        projectPath = "",
        isPairingWindow = true,
    )
}

/** Why a probe did not produce a [PairingTarget]. Rows are driven by these, never by a message. */
sealed interface ProbeOutcome {
    data class Found(val target: PairingTarget) : ProbeOutcome

    /** The host answered, but no pairing window is open. */
    data object Closed : ProbeOutcome

    /** The host is refusing this device under its discovery lockout. */
    data object Blocked : ProbeOutcome

    /** No answer, no usable certificate, or an offer whose id did not match what the certificate says. */
    data object Unreachable : ProbeOutcome
}

/**
 * Asks one advertised host whether it is in pairing mode.
 *
 * The order matters: observe the certificate first, pin it, and only then send the offer request — so the offer
 * itself already travels pinned to the certificate that was observed. The offer's short id is checked against
 * `HostShortId.of(observedFingerprint)`, so a host cannot advertise an id it does not own; a mismatch is
 * [ProbeOutcome.Unreachable] rather than a distinct outcome, because from the phone's side it is simply not a host
 * it can pair with.
 */
class PairingProbe(
    private val http: LinkHttp,
    /** Observing the certificate is the one step that talks TLS directly; tests supply what was observed. */
    private val observeFingerprint: suspend (endpoint: String) -> String? = ::observeTransportFingerprint,
    private val pinFingerprint: (endpoint: String, fingerprint: String) -> Unit = ::rememberObservedTransportPin,
    private val offerFor: suspend (endpoint: String, fingerprint: String) -> OfferResponse = { endpoint, fingerprint ->
        http.requestOffer(endpoint, fingerprint)
    },
) {

    suspend fun probe(advertisement: LocalNetworkServiceAdvertisement): ProbeOutcome {
        val endpoint = "https://${advertisement.host}:${advertisement.port}"
        val fingerprint = observeFingerprint(endpoint)
            ?: return ProbeOutcome.Unreachable.also {
                discoveryDebug { "offer probe observed no certificate at $endpoint" }
            }
        pinFingerprint(endpoint, fingerprint)
        val response = runCatchingCancellable { offerFor(endpoint, fingerprint) }.getOrNull()
            ?: return ProbeOutcome.Unreachable
        return when (response.status) {
            PairingOfferStatus.CLOSED -> ProbeOutcome.Closed
            PairingOfferStatus.BLOCKED -> ProbeOutcome.Blocked
            PairingOfferStatus.OPEN -> response.offer?.let { target(endpoint, fingerprint, it) }
                ?: ProbeOutcome.Unreachable
        }
    }

    private fun target(endpoint: String, fingerprint: String, offer: PairingOffer): ProbeOutcome {
        if (offer.hostShortId != HostShortId.of(fingerprint)) {
            discoveryDebug { "offer probe refused $endpoint: its short id is not the certificate's" }
            return ProbeOutcome.Unreachable
        }
        return ProbeOutcome.Found(
            PairingTarget(
                endpoint = endpoint,
                pairingSessionId = offer.pairingSessionId,
                pairingToken = offer.pairingToken,
                fingerprint = fingerprint,
                hostShortId = offer.hostShortId,
                hostName = offer.hostName,
                hostLabel = offer.hostLabel,
                expiresAtEpochMillis = offer.expiresAtEpochMillis,
            ),
        )
    }
}

/** The offer request itself: pinned to [fingerprint], which the probe observed a moment earlier. */
private suspend fun LinkHttp.requestOffer(endpoint: String, fingerprint: String): OfferResponse {
    val ticket = LinkTicket(
        endpoint = endpoint,
        sessionId = "",
        token = "",
        fingerprint = fingerprint,
        projectName = "",
        projectPath = "",
    )
    return discover(ticket, DiscoverRequest.Offer) {
        LinkJson.decodeFromString(OfferResponse.serializer(), it)
    }
}
