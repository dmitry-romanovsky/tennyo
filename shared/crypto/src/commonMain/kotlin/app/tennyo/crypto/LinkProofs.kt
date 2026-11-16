package app.tennyo.crypto

/**
 * The proofs each side computes over a transcript from [SecurityTranscripts]. The app sends them; the host recomputes
 * and compares with [matches], so both sides share one construction.
 */
object LinkProofs {
    /** First-time pairing: the transcript MAC'd with the tapped-word secret from [SecurityDerivations]. */
    fun pairing(selectionSecret: ByteArray, transcript: String): String = hmacSha256Url(selectionSecret, transcript)

    /** Remembered-pair resume and discovery connect: the transcript MAC'd with the stored pair secret. */
    fun rememberedPair(pairSecret: String, transcript: String): String =
        hmacSha256Url(pairSecret.encodeToByteArray(), transcript)

    /** One call on the channel, MAC'd with the per-stream command key. */
    fun call(commandKey: ByteArray, transcript: String): String = hmacSha256Url(commandKey, transcript)

    fun matches(expected: String, presented: String): Boolean = constantTimeEquals(expected, presented)
}
