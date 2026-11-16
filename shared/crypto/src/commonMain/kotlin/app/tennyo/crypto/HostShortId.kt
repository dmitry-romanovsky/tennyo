package app.tennyo.crypto

/**
 * The short id both sides of a pairing show so the user can tell two hosts apart. It is derived from the host's
 * transport fingerprint, so a host cannot choose it and one impersonating another's display name shows a different
 * id. Nothing in the protocol trusts it: the tapped words are the proof, and they MAC a transcript that already
 * binds the fingerprint. The id is a label the user compares, and the phone checks that the id an offer carries
 * matches the certificate the probe actually observed.
 */
object HostShortId {

    private const val HEX_LENGTH = 12
    private const val GROUP_LENGTH = 4
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F

    /** The first [HEX_LENGTH] hex characters of the SHA-256 of [transportFingerprint], lowercase and ungrouped. */
    fun of(transportFingerprint: String): String {
        val digest = sha256(transportFingerprint.encodeToByteArray())
        return buildString(HEX_LENGTH) {
            for (index in 0 until HEX_LENGTH / 2) {
                val byte = digest[index].toInt() and BYTE_MASK
                append(HEX_DIGITS[byte ushr NIBBLE_BITS])
                append(HEX_DIGITS[byte and NIBBLE_MASK])
            }
        }
    }

    /** [id] grouped four by four — "abcd 1234 ef56" — the one form either side renders it in. */
    fun display(id: String): String = id.chunked(GROUP_LENGTH).joinToString(separator = " ")
}
