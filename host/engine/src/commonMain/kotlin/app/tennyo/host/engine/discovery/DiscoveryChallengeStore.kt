package app.tennyo.host.engine.discovery

import app.tennyo.contract.DiscoveryChallenge
import app.tennyo.contract.ProjectChoice
import app.tennyo.crypto.secureRandomBytes
import app.tennyo.crypto.toBase64Url
import app.tennyo.host.engine.pairing.EphemeralKeyPair
import app.tennyo.host.engine.pairing.newEphemeralKeyPair
import app.tennyo.host.engine.platform.HostConcurrentMap
import app.tennyo.host.engine.platform.HostConcurrentSet
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.platform.debugShortId
import app.tennyo.host.engine.platform.discoveryDebug
import app.tennyo.host.engine.platform.hostRandomUuid
import app.tennyo.host.engine.platform.hostWallClockMillis

internal class DiscoveryChallengeStore {
    private val challenges = HostConcurrentMap<String, PendingDiscoveryChallenge>()

    fun issue(
        companionId: String,
        pairId: String,
        projectChoices: List<ProjectChoice>,
    ): DiscoveryChallenge {
        cleanup()
        val now = hostWallClockMillis()
        val challengeId = hostRandomUuid()
        val serverChallenge = secureRandomBytes(CHALLENGE_BYTES).toBase64Url()
        val connectToken = secureRandomBytes(CHALLENGE_BYTES).toBase64Url()
        val expiresAt = now + CHALLENGE_TTL_MILLIS
        val pending = PendingDiscoveryChallenge(
            companionId = companionId,
            pairId = pairId,
            challengeId = challengeId,
            serverChallenge = serverChallenge,
            connectToken = connectToken,
            ephemeralKeyPair = newEphemeralKeyPair(),
            expiresAtEpochMillis = expiresAt,
            projectChoices = projectChoices,
        )
        challenges[challengeId] = pending
        trim()
        LOG.discoveryDebug {
            "issued challenge companion=${companionId.debugShortId()} pair=${pairId.debugShortId()} " +
                "challenge=${challengeId.debugShortId()} projects=${projectChoices.size} ttlMs=$CHALLENGE_TTL_MILLIS"
        }
        return DiscoveryChallenge(
            challengeId = challengeId,
            serverChallenge = serverChallenge,
            connectToken = connectToken,
            companionEphemeralPublicKey = pending.ephemeralKeyPair.publicKeyBase64,
            expiresAtEpochMillis = expiresAt,
        )
    }

    fun find(companionId: String, pairId: String, challengeId: String): PendingDiscoveryChallenge? {
        cleanup()
        val result = challenges[challengeId]?.takeIf {
            it.companionId == companionId &&
                    it.pairId == pairId &&
                    it.expiresAtEpochMillis > hostWallClockMillis()
        }
        LOG.discoveryDebug {
            "lookup challenge companion=${companionId.debugShortId()} pair=${pairId.debugShortId()} " +
                "challenge=${challengeId.debugShortId()} found=${result != null}"
        }
        return result
    }

    fun consumeAttempt(challengeId: String, attemptId: String): Boolean {
        cleanup()
        val challenge = challenges[challengeId] ?: return false
        return challenge.attemptIds.add(attemptId).also { accepted ->
            LOG.discoveryDebug {
                "consume attempt challenge=${challengeId.debugShortId()} " +
                    "attempt=${attemptId.debugShortId()} accepted=$accepted"
            }
        }
    }

    private fun cleanup(nowEpochMillis: Long = hostWallClockMillis()) {
        val expired = challenges.entries().filter { (_, value) -> value.expiresAtEpochMillis <= nowEpochMillis }
        expired.forEach { (key, value) -> challenges.remove(key, value) }
        val expiredIds = expired.map { it.first }
        if (expiredIds.isNotEmpty()) {
            LOG.discoveryDebug {
                "cleaned expired challenges count=${expiredIds.size} " +
                    "ids=${expiredIds.joinToString { it.debugShortId() }}"
            }
        }
    }

    private fun trim() {
        while (challenges.size > MAX_CHALLENGES) {
            val oldestKey = challenges.entries().minByOrNull { it.second.expiresAtEpochMillis }?.first ?: return
            challenges.remove(oldestKey)
            LOG.discoveryDebug {
                "trimmed oldest challenge challenge=${oldestKey.debugShortId()} max=$MAX_CHALLENGES"
            }
        }
    }

    data class PendingDiscoveryChallenge(
        val companionId: String,
        val pairId: String,
        val challengeId: String,
        val serverChallenge: String,
        val connectToken: String,
        /** Fresh per challenge, so a recorded connect cannot be unwrapped from a leaked pairSecret later. */
        val ephemeralKeyPair: EphemeralKeyPair,
        val expiresAtEpochMillis: Long,
        val projectChoices: List<ProjectChoice>,
        val attemptIds: HostConcurrentSet<String> = HostConcurrentSet(),
    )

    internal companion object {
        const val CHALLENGE_TTL_MILLIS = 30_000L
        private const val MAX_CHALLENGES = 64
        private const val CHALLENGE_BYTES = 24
        private val LOG = HostLogger("DiscoveryChallengeStore")
    }
}

fun selectDiscoveryProjectId(
    requestedProjectId: String?,
    projectChoices: List<ProjectChoice>,
): String? {
    val normalizedRequestedProjectId = requestedProjectId?.trim()?.takeIf { it.isNotBlank() }
    return when {
        normalizedRequestedProjectId == null && projectChoices.size == 1 ->
            projectChoices.single().projectId

        normalizedRequestedProjectId == null ->
            null

        projectChoices.any { choice -> choice.projectId == normalizedRequestedProjectId } ->
            normalizedRequestedProjectId

        else -> null
    }
}
