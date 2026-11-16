package app.tennyo.mobile.link.pairing

import app.tennyo.contract.HandshakeResponse
import app.tennyo.contract.KnownHostDescriptor
import app.tennyo.contract.SessionResponse
import app.tennyo.contract.SessionTimeouts
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.debugQuotedOrNone
import app.tennyo.mobile.link.debugShortId
import app.tennyo.mobile.link.discoveryDebug
import app.tennyo.mobile.link.pane.nowEpochMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The IDEs this phone has connected to, remembered so Home can offer them again over local discovery. A target
 * nobody used for [SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS] is forgotten together with its remembered pair.
 */
class KnownHosts(
    private val store: KnownHostTargetStore,
    private val rememberedPairs: RememberedPairStore,
) {
    /** Every read-modify-write of a target, so a discovery pass cannot drop an address the user just added. */
    private val writes = Mutex()

    fun list(): List<KnownHostTarget> {
        val (stale, current) = store.list().partition(::isStale)
        stale.forEach { target ->
            discoveryDebug {
                "forgetting stale known target companion=${target.companionId.debugShortId()} " +
                    "lastUsedAt=${target.lastUsedAtEpochMillis}"
            }
            store.remove(target.companionId)
            rememberedPairs.removeByCompanionId(target.companionId)
        }
        discoveryDebug {
            val missingPairs = current.filter { rememberedPairs.load(it.discoveryTicket(PRESENCE_CHECK_TOKEN)) == null }
            "known targets snapshot count=${current.size} " +
                "companions=${current.joinToString { it.companionId.debugShortId() }} " +
                "missingPairs=${missingPairs.size} " +
                "missingPairCompanions=${missingPairs.joinToString { it.companionId.debugShortId() }}"
        }
        return current
    }

    fun remove(companionId: String) = store.remove(companionId)

    fun get(companionId: String): KnownHostTarget? = store.get(companionId)

    /** Keeps [endpoint] among the target's typed addresses; past [MAX_ADDED_ENDPOINTS] the oldest one makes room. */
    suspend fun addEndpoint(companionId: String, endpoint: String) = writes.withLock {
        val target = store.get(companionId) ?: return@withLock
        val added = (target.addedEndpoints - endpoint + endpoint).takeLast(MAX_ADDED_ENDPOINTS)
        store.save(target.copy(addedEndpoints = added))
    }

    /** Drops a typed address, and with it the memory of having last answered there, so it is dialed no more. */
    suspend fun removeEndpoint(companionId: String, endpoint: String) = writes.withLock {
        val target = store.get(companionId) ?: return@withLock
        store.save(
            target.copy(
                addedEndpoints = target.addedEndpoints - endpoint,
                lastResolvedHost = target.lastResolvedHost.takeUnless { it == endpoint },
            ),
        )
    }

    suspend fun updateFromDescriptor(target: KnownHostTarget, descriptor: KnownHostDescriptor, host: String) {
        writes.withLock {
            store.updateFromDescriptor(
                descriptor = descriptor,
                transportFingerprint = target.discoveryTransportFingerprint,
                resolvedHost = host,
            )
        }
        discoveryDebug {
            "discover descriptor updated companion=${descriptor.companionId.debugShortId()} " +
                "label=${descriptor.companionLabel.debugQuotedOrNone()}"
        }
    }

    suspend fun remember(ticket: LinkTicket, handshake: HandshakeResponse) {
        remember(ticket, handshake.companionId, handshake.hostVersion)
    }

    /** A claimed session names the host machine, which becomes the target's label. */
    suspend fun remember(ticket: LinkTicket, session: SessionResponse) {
        val companionId = session.companionId ?: return
        discoveryDebug {
            "remember known target from claim companion=${companionId.debugShortId()} " +
                "lastProject=${session.projectName.debugQuotedOrNone()} " +
                "host=${session.stats.hostName.debugQuotedOrNone()}"
        }
        writes.withLock {
            val existing = store.get(companionId)
            store.save(
                KnownHostTarget(
                    companionId = companionId,
                    companionLabel = session.stats.hostName.ifBlank {
                        existing?.companionLabel ?: DEFAULT_KNOWN_HOST_LABEL
                    },
                    ticketEndpoints = ticket.hostEndpoints(existing),
                    addedEndpoints = existing?.addedEndpoints.orEmpty(),
                    lastProjectName = session.projectName.takeIf { it.isNotBlank() },
                    hostVersion = session.hostVersion.orEmpty(),
                    lastUsedAtEpochMillis = nowEpochMillis(),
                    discoveryTransportFingerprint = ticket.fingerprint,
                    lastResolvedHost = ticket.endpoint,
                    lastKnownNetworkLabel = session.stats.hostName,
                ),
            )
        }
    }

    suspend fun remember(ticket: LinkTicket, companionId: String?, hostVersion: String?) {
        val stableCompanionId = companionId ?: return
        writes.withLock {
            val existing = store.get(stableCompanionId)
            discoveryDebug {
                "remember known target companion=${stableCompanionId.debugShortId()} existing=${existing != null} " +
                    "endpoint=${ticket.endpoint.debugQuotedOrNone()} hostVersion=${hostVersion.debugQuotedOrNone()}"
            }
            store.save(
                KnownHostTarget(
                    companionId = stableCompanionId,
                    companionLabel = existing?.companionLabel ?: DEFAULT_KNOWN_HOST_LABEL,
                    lastProjectName = ticket.projectName.takeIf { it.isNotBlank() } ?: existing?.lastProjectName,
                    hostVersion = hostVersion ?: existing?.hostVersion.orEmpty(),
                    lastUsedAtEpochMillis = nowEpochMillis(),
                    discoveryTransportFingerprint = ticket.fingerprint,
                    lastResolvedHost = ticket.endpoint,
                    lastKnownNetworkLabel = existing?.lastKnownNetworkLabel,
                    ticketEndpoints = ticket.hostEndpoints(existing),
                    addedEndpoints = existing?.addedEndpoints.orEmpty(),
                ),
            )
        }
    }

    /** What the host said about itself: the ticket's addresses, never one the user typed. */
    private fun LinkTicket.hostEndpoints(existing: KnownHostTarget?): List<String> =
        endpointCandidates() - existing?.addedEndpoints.orEmpty().toSet()

    private fun isStale(target: KnownHostTarget): Boolean =
        nowEpochMillis() - target.lastUsedAtEpochMillis >= SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS

    private companion object {
        const val PRESENCE_CHECK_TOKEN = "remembered-pair-presence-check"
    }
}

const val DEFAULT_KNOWN_HOST_LABEL = "Computer"

/**
 * A ticket-shaped key for [target] that addresses its remembered pair and, with [endpoint], its discovery endpoint.
 * [token] is a placeholder naming the purpose; discovery requests are unsigned or proven by the pair secret.
 */
internal fun KnownHostTarget.discoveryTicket(token: String, endpoint: String = lastResolvedHost.orEmpty()): LinkTicket =
    LinkTicket(
        endpoint = endpoint,
        sessionId = companionId,
        token = token,
        fingerprint = discoveryTransportFingerprint,
        projectName = lastProjectName.orEmpty(),
        projectPath = "",
        companionId = companionId,
        hostVersion = hostVersion,
    )
