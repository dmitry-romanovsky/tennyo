package app.tennyo.mobile.link.pairing

import app.tennyo.crypto.EphemeralKeyAgreement
import app.tennyo.crypto.LinkProofs
import app.tennyo.crypto.ProtectedPayloads
import app.tennyo.crypto.SecurityDerivations
import app.tennyo.crypto.SecurityTranscripts
import app.tennyo.crypto.sha256Url
import app.tennyo.crypto.sharedSecret
import app.tennyo.mobile.link.LinkTicket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything one first-time pairing attempt binds itself to. Grouped rather than threaded through as loose arguments
 * because it is a single concept: change any part of it and the proof, the derived key, and the attempt's identity
 * all change together. The layout lives in [SecurityTranscripts.pairingConnect], shared with the host.
 */
internal data class PairingAttempt(
    val artifact: LinkTicket,
    val route: String,
    val devicePublicKey: String,
    val deviceFingerprint: String,
    val attemptId: String,
    val serverChallenge: String,
    val clientEphemeralPublicKey: String,
    val companionEphemeralPublicKey: String,
) {
    fun transcript(): String =
        SecurityTranscripts.pairingConnect(
            sessionId = artifact.sessionId,
            sessionTokenHash = sha256Url(artifact.token),
            companionFingerprint = artifact.fingerprint,
            route = route,
            devicePublicKey = devicePublicKey,
            deviceFingerprint = deviceFingerprint,
            attemptId = attemptId,
            serverChallenge = serverChallenge,
            companionEphemeralPublicKey = companionEphemeralPublicKey,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
        )

    /** Binds the ECDH-derived pair-material key to this exact attempt. */
    fun transcriptHash(): String = sha256Url(transcript())

    /** The proof for the tapped [selectedWords] on the grid seeded by [gridSeed]. */
    fun proof(gridSeed: String, selectedWords: List<String>): String =
        LinkProofs.pairing(SecurityDerivations.pairingSelectionSecret(gridSeed, selectedWords), transcript())
}

/** Everything one remembered-pair resume binds itself to; see [SecurityTranscripts.rememberedPairResume]. */
internal data class RememberedPairAttempt(
    val artifact: LinkTicket,
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

    fun proof(pairSecret: String): String = LinkProofs.rememberedPair(pairSecret, transcript())
}

/** The discovery-connect twin of [RememberedPairAttempt]; the same binding rules apply. */
internal data class DiscoveryAttempt(
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

    fun proof(pairSecret: String): String = LinkProofs.rememberedPair(pairSecret, transcript())
}

/**
 * The key the host's protected response travels under: the ECDH secret with [companionEphemeralPublicKey], bound to
 * the attempt's [transcriptHash]. `null` when the host's key is malformed.
 */
internal fun EphemeralKeyAgreement.responseKey(
    companionEphemeralPublicKey: String,
    serverChallenge: String,
    transcriptHash: String,
): ByteArray? =
    sharedSecret(companionEphemeralPublicKey)?.let { sharedSecret ->
        SecurityDerivations.pairMaterialKey(sharedSecret, serverChallenge, transcriptHash)
    }

internal fun openPairMaterial(
    protectedPayload: String,
    responseKey: ByteArray,
): RememberedPairMaterial? =
    openProtected<RememberedPairMaterialPayload>(protectedPayload, responseKey)?.let { material ->
        RememberedPairMaterial(
            pairId = material.pairId,
            pairSecret = material.pairSecret,
            expiresAtEpochMillis = material.expiresAtEpochMillis,
            deviceLabel = material.deviceLabel,
            companionId = material.companionId,
        )
    }

internal fun openTicket(
    protectedPayload: String,
    responseKey: ByteArray,
): LinkTicket? =
    openProtected<ConnectionArtifactPayload>(protectedPayload, responseKey)?.let { artifact ->
        LinkTicket(
            endpoint = artifact.endpoint,
            sessionId = artifact.sessionId,
            token = artifact.token,
            fingerprint = artifact.fingerprint,
            projectName = artifact.projectName,
            projectPath = artifact.projectPath,
            alternateEndpoints = artifact.alternateEndpoints,
            companionId = artifact.companionId,
            hostVersion = artifact.hostVersion,
        )
    }

private val protectedPlaintextJson = Json { ignoreUnknownKeys = true }

private inline fun <reified T> openProtected(protectedPayload: String, responseKey: ByteArray): T? =
    ProtectedPayloads.open(protectedPayload, responseKey)?.let { plaintext ->
        runCatching { protectedPlaintextJson.decodeFromString<T>(plaintext.decodeToString()) }.getOrNull()
    }

@Serializable
private data class RememberedPairMaterialPayload(
    val pairId: String,
    val pairSecret: String,
    val expiresAtEpochMillis: Long,
    val deviceLabel: String,
    val companionId: String? = null,
)

@Serializable
private data class ConnectionArtifactPayload(
    val endpoint: String,
    val sessionId: String,
    val token: String,
    val fingerprint: String,
    val projectName: String,
    val projectPath: String,
    val alternateEndpoints: List<String> = emptyList(),
    val companionId: String? = null,
    val hostVersion: String? = null,
)
