package app.tennyo.host.engine.pairing

import app.tennyo.contract.PairingChallenge
import app.tennyo.contract.PairingConfirmationStatus
import app.tennyo.crypto.LinkProofs
import app.tennyo.crypto.SecurityTranscripts
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.crypto.sha256Url
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import app.tennyo.host.engine.platform.hostWallClockMillis
import app.tennyo.host.engine.session.SessionTicket

private const val PAIRING_CHALLENGE_BYTES = 32
private const val MAX_PAIRING_PROOF_CHARS = 256
const val PAIRING_CONFIRMATION_MAX_ATTEMPTS = 3
const val PAIRING_CONFIRMATION_TTL_MILLIS = 2 * 60 * 1_000L

/**
 * Verifies a tapped word selection and, on success, derives the key the pair material travels under.
 *
 * The selection MACs the transcript; it never encrypts the payload. [pairMaterialKey] comes from the ephemeral ECDH
 * exchange instead, so recovering the pair material from a recorded exchange is no longer a matter of grinding the
 * 43,680 possible selections offline.
 */
class PairingVerifier(
    val secret: PairingSecret,
    val ephemeralKeyPair: EphemeralKeyPair = newEphemeralKeyPair(),
) {
    /** The words the popup shows, in the order the user must tap them. */
    val words: List<String> get() = secret.words

    fun verify(
        artifact: SessionTicket,
        route: String,
        devicePublicKey: String,
        deviceFingerprint: String,
        attemptId: String,
        serverChallenge: String,
        clientEphemeralPublicKey: String,
        clientPairingProof: String,
        sessionTokenHash: String = sha256Url(artifact.token),
    ): Boolean {
        val expected = pairingProof(
            selectionSecret = secret.expectedSelectionSecret(),
            artifact = artifact,
            sessionTokenHash = sessionTokenHash,
            route = route,
            devicePublicKey = devicePublicKey,
            deviceFingerprint = deviceFingerprint,
            attemptId = attemptId,
            serverChallenge = serverChallenge,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
            companionEphemeralPublicKey = ephemeralKeyPair.publicKeyBase64,
        )
        return LinkProofs.matches(expected, clientPairingProof)
    }

    fun pairMaterialKey(
        artifact: SessionTicket,
        route: String,
        devicePublicKey: String,
        deviceFingerprint: String,
        attemptId: String,
        serverChallenge: String,
        clientEphemeralPublicKey: String,
        sessionTokenHash: String = sha256Url(artifact.token),
    ): ByteArray? =
        ephemeralKeyPair.pairMaterialKey(
            clientEphemeralPublicKey = clientEphemeralPublicKey,
            serverChallenge = serverChallenge,
            transcriptHash = sha256Url(
                pairingTranscript(
                    artifact = artifact,
                    sessionTokenHash = sessionTokenHash,
                    route = route,
                    devicePublicKey = devicePublicKey,
                    deviceFingerprint = deviceFingerprint,
                    attemptId = attemptId,
                    serverChallenge = serverChallenge,
                    clientEphemeralPublicKey = clientEphemeralPublicKey,
                    companionEphemeralPublicKey = ephemeralKeyPair.publicKeyBase64,
                ),
            ),
        )
}

class PairingConfirmationState(
    val verifier: PairingVerifier,
    initialChallenge: PairingChallenge,
    initialFailedAttempts: Int = 0,
    val maxAttempts: Int = PAIRING_CONFIRMATION_MAX_ATTEMPTS,
    val expiresAtEpochMillis: Long,
    initialUsedAttemptIds: Set<String> = emptySet(),
    private val randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
) {
    private val monitor = HostMonitor()
    private val usedAttemptIds = initialUsedAttemptIds.toMutableSet()
    private var failedAttempts = initialFailedAttempts
    private var challenge = initialChallenge

    fun currentChallenge(nowEpochMillis: Long = hostWallClockMillis()): PairingChallenge? =
        monitor.guarded { currentChallengeLocked(nowEpochMillis) }

    /** Attempts the user has left, from [maxAttempts]; the panel shows this rather than a hardcoded number. */
    fun attemptsLeft(): Int = monitor.guarded { (maxAttempts - failedAttempts).coerceAtLeast(0) }

    private fun currentChallengeLocked(nowEpochMillis: Long): PairingChallenge? =
        challenge.takeIf { nowEpochMillis < expiresAtEpochMillis && nowEpochMillis < it.expiresAtEpochMillis }

    fun evaluate(
        artifact: SessionTicket,
        route: String,
        devicePublicKey: String,
        deviceFingerprint: String,
        challengeId: String,
        attemptId: String,
        clientEphemeralPublicKey: String,
        clientPairingProof: String,
        nowEpochMillis: Long = hostWallClockMillis(),
        sessionTokenHash: String = sha256Url(artifact.token),
    ): PairingConfirmationAttempt = monitor.guarded {
        evaluateLocked(
            artifact,
            route,
            devicePublicKey,
            deviceFingerprint,
            challengeId,
            attemptId,
            clientEphemeralPublicKey,
            clientPairingProof,
            nowEpochMillis,
            sessionTokenHash,
        )
    }

    // Every check answers the attempt on its own, so the verification below only runs on a well-formed attempt.
    @Suppress("LongParameterList", "ReturnCount")
    private fun evaluateLocked(
        artifact: SessionTicket,
        route: String,
        devicePublicKey: String,
        deviceFingerprint: String,
        challengeId: String,
        attemptId: String,
        clientEphemeralPublicKey: String,
        clientPairingProof: String,
        nowEpochMillis: Long,
        sessionTokenHash: String,
    ): PairingConfirmationAttempt {
        if (failedAttempts >= maxAttempts) {
            return PairingConfirmationAttempt(PairingConfirmationStatus.StoppedServerExceededMaximumAttempts)
        }
        val currentChallenge = currentChallengeLocked(nowEpochMillis)
            ?: return PairingConfirmationAttempt(
                status = PairingConfirmationStatus.StoppedServerExceededMaximumAttempts,
                expired = true,
            )
        if (attemptId.isBlank() || challengeId != currentChallenge.challengeId || !usedAttemptIds.add(attemptId)) {
            return PairingConfirmationAttempt(
                status = PairingConfirmationStatus.IncorrectSelection,
                pairingChallenge = currentChallenge,
            )
        }
        val selectionMatches =
            clientPairingProof.length <= MAX_PAIRING_PROOF_CHARS &&
                    verifier.verify(
                        artifact = artifact,
                        route = route,
                        devicePublicKey = devicePublicKey,
                        deviceFingerprint = deviceFingerprint,
                        attemptId = attemptId,
                        serverChallenge = currentChallenge.serverChallenge,
                        clientEphemeralPublicKey = clientEphemeralPublicKey,
                        clientPairingProof = clientPairingProof,
                        sessionTokenHash = sessionTokenHash,
                    )
        if (selectionMatches) {
            // A malformed or off-curve client key fails the exchange rather than the selection, but it is reported as
            // an ordinary failure: the caller must not learn that its taps were right.
            val pairMaterialKey = verifier.pairMaterialKey(
                artifact = artifact,
                route = route,
                devicePublicKey = devicePublicKey,
                deviceFingerprint = deviceFingerprint,
                attemptId = attemptId,
                serverChallenge = currentChallenge.serverChallenge,
                clientEphemeralPublicKey = clientEphemeralPublicKey,
                sessionTokenHash = sessionTokenHash,
            )
            if (pairMaterialKey != null) {
                return PairingConfirmationAttempt(
                    status = PairingConfirmationStatus.Success,
                    pairMaterialKey = pairMaterialKey,
                )
            }
        }

        failedAttempts += 1
        if (failedAttempts >= maxAttempts) {
            return PairingConfirmationAttempt(PairingConfirmationStatus.StoppedServerExceededMaximumAttempts)
        }
        challenge = newPairingChallenge(
            verifier = verifier,
            randomBytes = randomBytes,
            nowEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
        return PairingConfirmationAttempt(
            status = PairingConfirmationStatus.IncorrectSelection,
            pairingChallenge = challenge,
        )
    }

    fun consumePairResumeChallenge(
        challengeId: String,
        attemptId: String,
        nowEpochMillis: Long = hostWallClockMillis(),
    ): PairingChallenge? = monitor.guarded { consumePairResumeChallengeLocked(challengeId, attemptId, nowEpochMillis) }

    private fun consumePairResumeChallengeLocked(
        challengeId: String,
        attemptId: String,
        nowEpochMillis: Long,
    ): PairingChallenge? {
        if (failedAttempts >= maxAttempts) return null
        val currentChallenge = currentChallengeLocked(nowEpochMillis) ?: return null
        if (attemptId.isBlank() || challengeId != currentChallenge.challengeId || !usedAttemptIds.add(attemptId)) {
            return null
        }
        challenge = newPairingChallenge(
            verifier = verifier,
            randomBytes = randomBytes,
            nowEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
        return currentChallenge
    }
}

@Suppress("ArrayInDataClass")
data class PairingConfirmationAttempt(
    val status: PairingConfirmationStatus,
    val pairingChallenge: PairingChallenge? = null,
    val expired: Boolean = false,
    val pairMaterialKey: ByteArray? = null,
    val encryptedPairMaterial: String? = null,
    /** The session ticket sealed under [pairMaterialKey], for a device that paired by name and holds no ticket. */
    val encryptedArtifact: String? = null,
)

/**
 * Rotates the challenge identifiers while repeating the session's grid, seed, and ephemeral key. The secret words do
 * not change between attempts, so re-rolling the grid would buy nothing and only force the user to search it again.
 */
fun newPairingChallenge(
    verifier: PairingVerifier,
    randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
    nowEpochMillis: Long = hostWallClockMillis(),
    expiresAtEpochMillis: Long = nowEpochMillis + PAIRING_CONFIRMATION_TTL_MILLIS,
): PairingChallenge {
    val challengeBytes = randomBytes(PAIRING_CHALLENGE_BYTES)
    val idBytes = randomBytes(PAIRING_CHALLENGE_BYTES)
    return PairingChallenge(
        challengeId = idBytes.toBase64Url(),
        serverChallenge = challengeBytes.toBase64Url(),
        expiresAtEpochMillis = expiresAtEpochMillis,
        wordGrid = verifier.secret.wordGrid,
        gridSeed = verifier.secret.gridSeed,
        companionEphemeralPublicKey = verifier.ephemeralKeyPair.publicKeyBase64,
    )
}

fun pairingProof(
    selectionSecret: ByteArray,
    artifact: SessionTicket,
    route: String,
    devicePublicKey: String,
    deviceFingerprint: String,
    attemptId: String,
    serverChallenge: String,
    clientEphemeralPublicKey: String,
    companionEphemeralPublicKey: String,
    sessionTokenHash: String = sha256Url(artifact.token),
): String =
    LinkProofs.pairing(
        selectionSecret = selectionSecret,
        transcript = pairingTranscript(
            artifact = artifact,
            sessionTokenHash = sessionTokenHash,
            route = route,
            devicePublicKey = devicePublicKey,
            deviceFingerprint = deviceFingerprint,
            attemptId = attemptId,
            serverChallenge = serverChallenge,
            clientEphemeralPublicKey = clientEphemeralPublicKey,
            companionEphemeralPublicKey = companionEphemeralPublicKey,
        ),
    )

fun pairingTranscript(
    artifact: SessionTicket,
    route: String,
    sessionTokenHash: String = sha256Url(artifact.token),
    devicePublicKey: String,
    deviceFingerprint: String,
    attemptId: String,
    serverChallenge: String,
    clientEphemeralPublicKey: String,
    companionEphemeralPublicKey: String,
): String =
    SecurityTranscripts.pairingConnect(
        sessionId = artifact.sessionId,
        sessionTokenHash = sessionTokenHash,
        companionFingerprint = artifact.fingerprint,
        route = route,
        devicePublicKey = devicePublicKey,
        deviceFingerprint = deviceFingerprint,
        attemptId = attemptId,
        serverChallenge = serverChallenge,
        companionEphemeralPublicKey = companionEphemeralPublicKey,
        clientEphemeralPublicKey = clientEphemeralPublicKey,
    )
