package app.tennyo.crypto

/** The protocol's keyed derivations. */
object SecurityDerivations {

    /**
     * The MAC key for a tapped selection: each tile's key, concatenated in tap order.
     *
     * Tile keys are not secret — anyone who reached the grid can derive all sixteen. The secret is *which* words and
     * *in what order*. Fixed-width keys make the concatenation unambiguous, so the word list need not be prefix-free.
     */
    fun pairingSelectionSecret(gridSeed: String, selectedWords: List<String>): ByteArray {
        val seed = gridSeed.encodeToByteArray()
        return selectedWords.fold(ByteArray(0)) { accumulated, word ->
            accumulated + hmacSha256(seed, "tennyo-pairing-tile$word".encodeToByteArray())
        }
    }

    /**
     * HKDF-SHA256 over the raw ECDH secret: extract salted with the server challenge so the key is fresh per attempt,
     * expand with the transcript hash so it is bound to this exact exchange.
     *
     * The pair material travels under this key rather than anything derived from the tapped words, so a recorded
     * exchange cannot be unwrapped by grinding the 43,680 possible selections offline.
     */
    fun pairMaterialKey(sharedSecret: ByteArray, serverChallenge: String, transcriptHash: String): ByteArray {
        val pseudoRandomKey = hmacSha256(serverChallenge.encodeToByteArray(), sharedSecret)
        val info = "tennyo-pair-material-key\n$transcriptHash".encodeToByteArray()
        return hmacSha256(pseudoRandomKey, info + HKDF_BLOCK_1)
    }

    /** HKDF-SHA256 of the per-stream command key, bound to the session token, stream, and approved device. */
    fun commandMacKey(
        commandKeySeed: ByteArray,
        sessionToken: String,
        streamId: String,
        approvedDeviceFingerprint: String,
    ): ByteArray {
        val inputKeyMaterial = commandKeySeed + sessionToken.encodeToByteArray()
        val salt = (streamId + approvedDeviceFingerprint).encodeToByteArray()
        val pseudoRandomKey = hmacSha256(salt, inputKeyMaterial)
        return hmacSha256(pseudoRandomKey, "tennyo-mobile-ws-command-key".encodeToByteArray() + HKDF_BLOCK_1)
            .copyOf(COMMAND_KEY_BYTES)
    }

/**
     * The encryption and MAC keys of a protected payload: HKDF-SHA256 (RFC 5869) of the response key, expanded to two
     * blocks so the keys are independent. The same response key must never encrypt under both.
     */
    fun protectedPayloadKeys(responseKey: ByteArray): ProtectedPayloadKeys {
        val pseudoRandomKey = hmacSha256(PROTECTED_PAYLOAD_SALT, responseKey)
        val first = hmacSha256(pseudoRandomKey, PROTECTED_PAYLOAD_INFO + HKDF_BLOCK_1)
        val second = hmacSha256(pseudoRandomKey, first + PROTECTED_PAYLOAD_INFO + HKDF_BLOCK_2)
        return ProtectedPayloadKeys(encryptionKey = first, macKey = second)
    }

    /** The CTR initial counter block for [nonce]: fresh per payload, since every payload carries a new nonce. */
    fun protectedPayloadIv(encryptionKey: ByteArray, nonce: String): ByteArray =
        hmacSha256(encryptionKey, "tennyo-protected-payload-iv\n$nonce".encodeToByteArray()).copyOf(AES_BLOCK_BYTES)

    // HKDF-Expand counter bytes; they are part of the construction.
    private val HKDF_BLOCK_1 = byteArrayOf(1)
    private val HKDF_BLOCK_2 = byteArrayOf(2)
    private val PROTECTED_PAYLOAD_SALT = "tennyo-protected-payload".encodeToByteArray()
    private val PROTECTED_PAYLOAD_INFO = "tennyo-protected-payload-keys".encodeToByteArray()
    private const val COMMAND_KEY_BYTES = 32
}

/** The independent keys a protected payload is encrypted and authenticated under. */
class ProtectedPayloadKeys(val encryptionKey: ByteArray, val macKey: ByteArray)
