package app.tennyo.host.engine.pairing

import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.publicKeyBase64
import app.tennyo.crypto.sharedSecret

/**
 * One session's ephemeral EC key pair for the pairing exchange.
 *
 * The pair material is encrypted under a key derived from this exchange rather than from the tapped words. The words
 * only *authenticate* the exchange — they MAC a transcript that covers both ephemeral public keys. That split is the
 * point: a recorded exchange can no longer be unwrapped by brute-forcing 43,680 selections offline, because the
 * payload key never depended on them.
 *
 * The private key never leaves the platform crypto provider and is dropped with the session.
 */
class EphemeralKeyPair(private val agreement: EphemeralKeyAgreement) {

    /** Base64url SPKI, the encoding the client parses. */
    val publicKeyBase64: String = agreement.publicKeyBase64

    /**
     * Derives the pair-material key from the client's ephemeral public key.
     *
     * [transcriptHash] binds the derivation to the same transcript the selection MAC covered, so a shared secret
     * agreed under one pairing attempt cannot be replayed into another. Returns `null` when the client's key is
     * malformed or off-curve.
     */
    fun pairMaterialKey(
        clientEphemeralPublicKey: String,
        serverChallenge: String,
        transcriptHash: String,
    ): ByteArray? {
        val sharedSecret = agreement.sharedSecret(clientEphemeralPublicKey) ?: return null
        return SecurityDerivations.pairMaterialKey(
            sharedSecret = sharedSecret,
            serverChallenge = serverChallenge,
            transcriptHash = transcriptHash,
        )
    }
}

fun newEphemeralKeyPair(): EphemeralKeyPair =
    EphemeralKeyPair(EphemeralKeyAgreement())
