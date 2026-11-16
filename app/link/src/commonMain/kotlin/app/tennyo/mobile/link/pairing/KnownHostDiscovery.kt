package app.tennyo.mobile.link.pairing

import app.tennyo.contract.DiscoverResponse
import app.tennyo.contract.DiscoveryChallenge
import app.tennyo.contract.DiscoveryStatus
import app.tennyo.mobile.link.LinkTicket

/** The outcome of reconnecting to a known IDE: a fresh ticket, or why the IDE would not issue one. */
sealed interface DiscoveryConnectResult {
    data class Success(val artifact: LinkTicket) : DiscoveryConnectResult
    data class Failed(val status: DiscoveryStatus, val blockedMessage: String?) : DiscoveryConnectResult
}

/** How a known host answered: found by DNS-SD here, or dialed at an address stored from an earlier ticket. */
enum class KnownHostReach { NEARBY, AWAY }

/** A known host's answer to a discover probe, the way the phone reached it, and the address that answered. */
data class KnownHostAnswer(val response: DiscoverResponse, val reach: KnownHostReach, val endpoint: String)

/** How a typed address answered a pinned probe. */
enum class AddressCheck {
    /** The host answered and is willing to take this device, or is blocking discovery. */
    REACHED,

    /** Something answered, but it will not take this device: another host, a revoked pair, or unavailable. */
    REFUSED,

    /** Nothing answered in time. A forwarded address often cannot be reached from inside the home network. */
    NO_ANSWER,
}

/** The known IDEs Home offers again, and reconnecting to them over local discovery. */
interface KnownHostDiscovery {
    fun knownHostTargets(): List<KnownHostTarget>

    /**
     * Asks each of [targets] whether it will take this device, over the hosts in [services].
     *
     * The browse is the caller's: the pairing screen browses once a pass and probes both its lists from that one
     * result. A browse of its own here would be a second DNS-SD session for the same service type moments after the
     * first, which Android answers with nothing — and a known host that answers nothing is never seen.
     *
     * A host [services] does not list is dialed at its stored addresses only when [dialStored] is true, so the
     * caller decides how often a pass leaves the local network.
     */
    suspend fun discoverCandidates(
        targets: List<KnownHostTarget>,
        services: List<LocalNetworkServiceAdvertisement>,
        dialStored: Boolean,
    ): Map<String, KnownHostAnswer?>

    /** The tap path: browses for itself and always dials the stored addresses when DNS-SD misses. */
    suspend fun discoverCandidate(target: KnownHostTarget): KnownHostAnswer?
    suspend fun forgetKnownHostTarget(companionId: String)

    /** Keeps [endpoint], already parsed, among the target's typed addresses. */
    suspend fun addAddress(companionId: String, endpoint: String)
    suspend fun removeAddress(companionId: String, endpoint: String)

    /** Sends one probe to [endpoint], pinned to the target's known fingerprint. */
    suspend fun checkAddress(target: KnownHostTarget, endpoint: String): AddressCheck
    suspend fun startDiscoverySession(
        target: KnownHostTarget,
        challenge: DiscoveryChallenge,
        /** The address that answered the discover probe; the connect goes to the same one. */
        endpoint: String,
        projectId: String? = null,
    ): DiscoveryConnectResult
}
