package app.tennyo.host.engine.session

import app.tennyo.host.engine.pairing.RememberedPairStore
import app.tennyo.host.engine.transport.OverlayNetwork
import kotlin.concurrent.atomics.AtomicBoolean

/** What a [SessionHost] needs from the IDE that runs it. */
interface HostPlatform {
    /** Asks the user to accept or reject a pending connection request. */
    fun showConnectionRequest(sessionId: String, request: ConnectionRequest)

    fun expireConnectionRequest(sessionId: String, requestId: String)

    fun expireConnectionRequests(sessionId: String)

    /** Something the IDE shows about sessions changed, such as a connected device or a pending request. */
    fun onSessionStateChanged()

    /** Whether this project has a running session changed. */
    fun onSessionsActiveChanged(active: Boolean)

    /** Holds the shared listener open until the lease is released, and says where it listens. */
    fun acquireListener(ownerKind: String): ListenerLease

    /** Where the shared listener answers right now, or `null` while it is not up. */
    fun listenerEndpoints(): HostEndpoints?

    /** Whether callers away from the local network may reach this computer. */
    fun isRemoteAccessEnabled(): Boolean

    val pairStore: RememberedPairStore

    fun companionId(): String

    fun isDiscoveryEnabled(): Boolean

    /** The IDE's name as the mobile app shows it. */
    fun hostLabel(): String

    /** Runs [block] off the calling thread, for work that must not delay the caller. */
    fun executeInBackground(block: () -> Unit)

    /** Whether the caller is on the IDE's UI thread, where blocking work is forbidden. */
    fun isUiThread(): Boolean

    /** Fails when called on the IDE's UI thread. */
    fun assertBackgroundThread()
}

/**
 * One holder's claim on the shared listener; [release] is idempotent. [endpoints] is read each time it is needed: the
 * listener's addresses change while a lease is held, as a network comes up or Remote access is switched.
 */
class ListenerLease(val endpoints: () -> HostEndpoints, private val onRelease: () -> Unit) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) onRelease()
    }
}

/**
 * Where the listener answers. [primary] and [alternates] are LAN addresses and the only ones DNS-SD advertises;
 * [overlay] holds the private-network addresses, which a ticket carries but discovery never announces.
 */
data class HostEndpoints(
    val primary: String,
    val alternates: List<String>,
    val transportFingerprint: String,
    val port: Int,
    val overlay: List<OverlayEndpoint> = emptyList(),
)

data class OverlayEndpoint(val network: OverlayNetwork, val address: String, val url: String)

/** What a ticket offers beyond the primary address: the other LAN addresses, then the overlay ones. */
internal fun HostEndpoints.ticketAlternates(): List<String> = alternates + overlay.map(OverlayEndpoint::url)
