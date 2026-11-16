package app.tennyo.host.engine.platform

import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.decodeBase64Url
import app.tennyo.crypto.verifyEcdsaP256Sha256

/**
 * A startup check that the platform crypto provider speaks the wire encodings: it verifies a signature the JVM
 * provider produced and agrees on an ECDH secret from both sides. A host that fails it cannot pair with the app.
 */
object EngineSelfCheck {
    /** `null` when the core is ready, otherwise the reason it is not. */
    fun run(): String? =
        runCatching {
            when {
                !verifyEcdsaP256Sha256(VECTOR_SPKI, VECTOR_MESSAGE, VECTOR_SIGNATURE) ->
                    "the ECDSA P-256 reference signature did not verify"

                !ecdhRoundTripAgrees() -> "the ECDH P-256 round trip disagreed"
                else -> null
            }
        }.getOrElse { failure -> "${failure::class.simpleName}: ${failure.message}" }

    private fun ecdhRoundTripAgrees(): Boolean {
        EphemeralKeyAgreement().use { first ->
            EphemeralKeyAgreement().use { second ->
                val firstSecret = first.sharedSecret(second.publicKeySpki) ?: return false
                val secondSecret = second.sharedSecret(first.publicKeySpki)
                return secondSecret != null && firstSecret.contentEquals(secondSecret)
            }
        }
    }

    private val VECTOR_MESSAGE = "tennyo-cross-target-vector".encodeToByteArray()
    private val VECTOR_SPKI = (
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEK2nsjwfqn6f8WLpnolSLqz2elrFYt6_fvn-uLRbqg7cyF9nuxdqLbtGhDe87" +
            "o93aYTsTmkkMANB0Kd5x4oAnlw"
        ).decodeBase64Url()
    private val VECTOR_SIGNATURE = (
        "MEUCIQDQQY112UVCjNapnngfYYD1EZUXXvJA3oQm2PUWsg1egQIgXzRm7R9BQsLUSaD5ZQCDAeZ2_eDLDAqt2Uwd" +
            "pXv_FTc"
        ).decodeBase64Url()
}
