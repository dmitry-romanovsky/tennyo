package app.tennyo.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a host in pairing mode tells a phone that has never spoken to it before.
 *
 * It says as little as it can: enough to render a row the user can recognise and to reach the claim step, and
 * nothing about the project. The project name, its path and the host's capabilities are answered only after the
 * user approves the device on the desktop.
 *
 * [pairingToken] is scoped to the open window and is accepted for claim and confirm alone — never for `hello`,
 * discovery connect or the channel. The real session token stays on the host until approval, and then travels
 * sealed under the pairing key.
 */
@Serializable
data class PairingOffer(
    val pairingSessionId: String,
    val pairingToken: String,
    /** A label drawn for this window, e.g. "Amber Harbour". Two hosts may draw the same one. */
    val hostName: String,
    /** Derived from the host's transport fingerprint; the phone checks it against the certificate it observed. */
    val hostShortId: String,
    val hostLabel: String,
    val expiresAtEpochMillis: Long,
    /** Required, not defaulted: kotlinx omits defaults, and a missing version must not read as the current one. */
    val securityVersion: Int,
)

@Serializable
data class OfferResponse(
    val status: PairingOfferStatus,
    val offer: PairingOffer? = null,
)

@Serializable
enum class PairingOfferStatus {
    /** A pairing window is open and [OfferResponse.offer] carries it. */
    @SerialName("open")
    OPEN,

    /** The host is reachable but not in pairing mode. */
    @SerialName("closed")
    CLOSED,

    /** The host is refusing this caller under its discovery lockout. */
    @SerialName("blocked")
    BLOCKED,
}
