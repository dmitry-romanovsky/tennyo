package app.tennyo.host.engine.pairing

import app.tennyo.contract.SessionTimeouts
import app.tennyo.crypto.LinkProofs
import app.tennyo.crypto.ProtectedPayloads
import app.tennyo.crypto.SecurityTranscripts
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.crypto.sha256Url
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.rpc.hostJson
import app.tennyo.host.engine.session.SessionTicket
import kotlinx.serialization.Serializable

private const val PAIR_MATERIAL_SECRET_BYTES = 32
private const val PAIR_MATERIAL_ID_BYTES = 24
private const val PROTECTED_PAYLOAD_NONCE_BYTES = 24
private const val MAX_PAIR_PROOF_CHARS = 256

@Serializable
data class RememberedPairMaterial(
    val pairId: String,
    val pairSecret: String,
    val expiresAtEpochMillis: Long,
    val deviceLabel: String,
    val companionId: String? = null,
)

@Serializable
data class RememberedPairRecord(
    val pairId: String,
    val pairSecret: String,
    val deviceLabel: String,
    val companionId: String? = null,
    val lastProjectName: String? = null,
    val lastHostVersion: String? = null,
    val deviceKeyHash: String,
    val createdAtEpochMillis: Long,
    val lastConfirmedAtEpochMillis: Long,
    val lastUsedAtEpochMillis: Long = createdAtEpochMillis,
    val lastKnownNetworkLabel: String? = null,
    val expiresAtEpochMillis: Long,
    /** Set on a pair that a rotation replaced but that stays usable briefly, in case the new one never arrived. */
    val supersededByPairId: String? = null,
)

data class RememberedPairSummary(
    val pairId: String,
    val deviceLabel: String,
    val companionId: String? = null,
    val lastProjectName: String? = null,
    val lastHostVersion: String? = null,
    val lastUsedAtEpochMillis: Long = 0L,
    val lastKnownNetworkLabel: String? = null,
    val expiresAtEpochMillis: Long,
)

interface RememberedPairStore {
    fun save(record: RememberedPairRecord)
    fun find(pairId: String, nowEpochMillis: Long = hostWallClockMillis()): RememberedPairRecord?
    fun replace(
        pairId: String,
        expectedPairSecret: String,
        replacement: RememberedPairRecord,
        nowEpochMillis: Long = hostWallClockMillis(),
    ): Boolean

    fun summaries(nowEpochMillis: Long = hostWallClockMillis()): List<RememberedPairSummary>
    fun revoke(pairId: String): Boolean
}

fun newRememberedPair(
    deviceLabel: String,
    deviceKeyHash: String,
    randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
    nowEpochMillis: Long = hostWallClockMillis(),
): Pair<RememberedPairMaterial, RememberedPairRecord> {
    val pairIdBytes = randomBytes(PAIR_MATERIAL_ID_BYTES)
    val pairSecretBytes = randomBytes(PAIR_MATERIAL_SECRET_BYTES)
    val expiresAt = nowEpochMillis + SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS
    val material = RememberedPairMaterial(
        pairId = pairIdBytes.toBase64Url(),
        pairSecret = pairSecretBytes.toBase64Url(),
        expiresAtEpochMillis = expiresAt,
        deviceLabel = deviceLabel.ifBlank { "Mobile device" },
        companionId = null,
    )
    val record = RememberedPairRecord(
        pairId = material.pairId,
        pairSecret = material.pairSecret,
        deviceLabel = material.deviceLabel,
        companionId = material.companionId,
        deviceKeyHash = deviceKeyHash,
        createdAtEpochMillis = nowEpochMillis,
        lastConfirmedAtEpochMillis = nowEpochMillis,
        lastUsedAtEpochMillis = nowEpochMillis,
        expiresAtEpochMillis = expiresAt,
    )
    return material to record
}

/** A remembered pair is usable only by the exact device key it was confirmed with. */
fun RememberedPairRecord.isBoundToDevice(
    deviceKeyHash: String,
): Boolean =
    deviceKeyHash.isNotBlank() && deviceKeyHash == this.deviceKeyHash

fun RememberedPairRecord.isSupersededBy(
    newRecord: RememberedPairRecord,
): Boolean {
    return pairId == newRecord.pairId || deviceKeyHash == newRecord.deviceKeyHash
}

/**
 * Everything one remembered-pair resume binds itself to.
 *
 * Both ephemeral public keys are in the transcript so `pairSecret` *authenticates* the ECDH exchange that carries the
 * rotated pair material rather than encrypting it. That is what stops a leaked `pairSecret` plus recorded transcripts
 * from unwinding every later rotation — and without the binding a relay could substitute its own key and the exchange
 * would prove nothing.
 */
data class RememberedPairAttempt(
    val artifact: SessionTicket,
    val route: String,
    val challengeId: String,
    val attemptId: String,
    val serverChallenge: String,
    val deviceFingerprint: String,
    val clientEphemeralPublicKey: String,
    val companionEphemeralPublicKey: String,
) {
    fun transcript(): String =
        SecurityTranscripts.rememberedPairResume(
            route = route,
            challengeId = challengeId,
            serverChallenge = serverChallenge,
            sessionId = artifact.sessionId,
            sessionTokenHash = sha256Url(artifact.token),
            companionFingerprint = artifact.fingerprint,
            deviceFingerprint = deviceFingerprint,
            attemptId = attemptId,
            companionEphemeralPublicKey = companionEphemeralPublicKey,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
        )

    fun transcriptHash(): String = sha256Url(transcript())
}

fun rememberedPairProof(
    pairSecret: String,
    attempt: RememberedPairAttempt,
): String =
    LinkProofs.rememberedPair(pairSecret, attempt.transcript())

/** The discovery-connect twin of [RememberedPairAttempt]; the same binding rules apply. */
data class DiscoveryAttempt(
    val companionId: String,
    val fingerprint: String,
    val route: String,
    val challengeId: String,
    val attemptId: String,
    val serverChallenge: String,
    val deviceFingerprint: String,
    val clientEphemeralPublicKey: String,
    val companionEphemeralPublicKey: String,
) {
    fun transcript(): String =
        SecurityTranscripts.discoveryConnect(
            route = route,
            companionId = companionId,
            companionFingerprint = fingerprint,
            deviceFingerprint = deviceFingerprint,
            challengeId = challengeId,
            serverChallenge = serverChallenge,
            attemptId = attemptId,
            companionEphemeralPublicKey = companionEphemeralPublicKey,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
        )

    fun transcriptHash(): String = sha256Url(transcript())
}

fun discoveryPairProof(
    pairSecret: String,
    attempt: DiscoveryAttempt,
): String =
    LinkProofs.rememberedPair(pairSecret, attempt.transcript())

fun protectPairMaterial(
    material: RememberedPairMaterial,
    responseKey: ByteArray,
    randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
): String = protect(hostJson(material), responseKey, randomBytes)

fun protectSessionTicket(
    artifact: SessionTicket,
    responseKey: ByteArray,
    randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
): String = protect(hostJson(artifact), responseKey, randomBytes)

private fun protect(json: String, responseKey: ByteArray, randomBytes: (Int) -> ByteArray): String =
    ProtectedPayloads.seal(
        plaintext = json.encodeToByteArray(),
        responseKey = responseKey,
        nonce = randomBytes(PROTECTED_PAYLOAD_NONCE_BYTES).toBase64Url(),
    )

fun rememberedPairProofMatches(
    record: RememberedPairRecord,
    attempt: RememberedPairAttempt,
    clientPairProof: String,
): Boolean {
    if (clientPairProof.length > MAX_PAIR_PROOF_CHARS) return false
    return LinkProofs.matches(rememberedPairProof(record.pairSecret, attempt), clientPairProof)
}

fun discoveryProofMatches(
    record: RememberedPairRecord,
    attempt: DiscoveryAttempt,
    clientPairProof: String,
): Boolean {
    if (clientPairProof.length > MAX_PAIR_PROOF_CHARS) return false
    return LinkProofs.matches(discoveryPairProof(record.pairSecret, attempt), clientPairProof)
}
