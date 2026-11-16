package app.tennyo.crypto

/**
 * Compares secrets without an early exit, so response timing reveals nothing about how many leading bytes matched.
 * Like `java.security.MessageDigest.isEqual`, only the length difference short-circuits. Every proof, MAC, and
 * token comparison on either side of the link goes through here.
 */
fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var difference = 0
    for (index in left.indices) {
        difference = difference or (left[index].toInt() xor right[index].toInt())
    }
    return difference == 0
}

/** [constantTimeEquals] for the Base64url proofs and MACs the protocol compares; they are ASCII by construction. */
fun constantTimeEquals(left: String, right: String): Boolean =
    constantTimeEquals(left.encodeToByteArray(), right.encodeToByteArray())
