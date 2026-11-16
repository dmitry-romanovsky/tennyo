package app.tennyo.mobile.link.transport

import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkTicket

expect fun rememberTransportPin(artifact: LinkTicket)

/** Whether [endpoint]'s host and port hold a pin. A host without one would be dialed under platform trust. */
expect fun isTransportEndpointPinned(endpoint: String): Boolean

/** The fingerprint [endpoint] is pinned to, or null; lets tests prove which certificate an address is held to. */
internal expect fun transportPinFingerprint(endpoint: String): String?

/** Pins [ticket] and refuses it, before any request, when its endpoint still has no pin. */
internal fun requirePinnedTransport(ticket: LinkTicket) {
    rememberTransportPin(ticket)
    // A blank fingerprint pins nothing, so whatever pin the address held before would otherwise pass.
    if (ticket.fingerprint.isBlank() || transportPinFingerprint(ticket.endpoint) != ticket.fingerprint) {
        throw LinkFault.Unreachable(LinkFault.UnreachableReason.UnpinnedAddress)
    }
}

/**
 * Pins [fingerprint] — the SPKI SHA-256 a probe observed — for [endpoint]'s host, so every later request to it is
 * checked against the certificate the probe actually saw.
 */
expect fun rememberObservedTransportPin(endpoint: String, fingerprint: String)

/**
 * The SPKI SHA-256 of the certificate [endpoint] presents, learned by completing a TLS handshake that accepts
 * whatever is offered; `null` when the host cannot be reached or presents nothing usable.
 *
 * This is the one place the app trusts a certificate it has never seen. It exists because a phone pairing by name
 * has no fingerprint to pin yet: it observes one here, pins it with [rememberObservedTransportPin], and everything
 * after that runs pinned. The permissive trust is scoped to this call — it is never installed on the shared client —
 * and the tapped words are what finally confirm that the pinned fingerprint is the one the host believes it has.
 */
expect suspend fun observeTransportFingerprint(endpoint: String): String?
