package app.tennyo.host.vscode.session

import app.tennyo.host.engine.pairing.RememberedPairStore
import app.tennyo.host.engine.platform.HostLogger
import app.tennyo.host.engine.session.ConnectionRequest
import app.tennyo.host.engine.session.HostEndpoints
import app.tennyo.host.engine.session.HostPlatform
import app.tennyo.host.engine.session.ListenerLease
import app.tennyo.host.engine.transport.NodeLinkServer

/**
 * How the protocol engine reaches VS Code: the shared listener, the remembered pairs, and the editor's idea of a
 * background thread.
 *
 * Node runs the extension on one thread, so there is no UI thread to keep clear and nothing to hand off to; what
 * [executeInBackground] must still promise is that the caller is not blocked, which a microtask gives it.
 */
class VsCodeHostPlatform(
    private val listener: NodeLinkServer,
    private val companionId: String,
    private val hostLabel: String,
    private val onStateChanged: () -> Unit,
    private val onSessionsEnded: () -> Unit,
    private val discoveryEnabled: () -> Boolean,
    private val remoteAccessEnabled: () -> Boolean,
    override val pairStore: RememberedPairStore,
) : HostPlatform {
    /**
     * The listener is bound during activation, so a session can take a lease without waiting. A session started
     * before the bind finished is refused rather than handed endpoints no phone can reach.
     */
    override fun acquireListener(ownerKind: String): ListenerLease {
        val lease = listener.acquireIfListening(ownerKind)
            ?: error("The Tennyo listener is not bound yet.")
        return ListenerLease(lease.endpoints, lease::release)
    }

    override fun listenerEndpoints(): HostEndpoints? = listener.endpoints()

    override fun isRemoteAccessEnabled(): Boolean = remoteAccessEnabled()

    override fun companionId(): String = companionId

    override fun hostLabel(): String = hostLabel

    override fun isDiscoveryEnabled(): Boolean = discoveryEnabled()

    override fun showConnectionRequest(sessionId: String, request: ConnectionRequest) = onStateChanged()

    override fun expireConnectionRequest(sessionId: String, requestId: String) = onStateChanged()

    override fun expireConnectionRequests(sessionId: String) = onStateChanged()

    override fun onSessionStateChanged() = onStateChanged()

    override fun onSessionsActiveChanged(active: Boolean) = if (active) onStateChanged() else onSessionsEnded()

    // A failure here has no caller left to see it, so it is logged rather than lost.
    override fun executeInBackground(block: () -> Unit) {
        queueMicrotask { runCatching(block).onFailure { LOG.warn("Tennyo background work failed: ${it.message}") } }
    }

    /** Node has one thread and no UI thread, so no call is ever on a thread it must leave. */
    override fun isUiThread(): Boolean = false

    override fun assertBackgroundThread() = Unit

    private companion object {
        val LOG = HostLogger("VsCodeHostPlatform")
    }
}

private external fun queueMicrotask(callback: () -> Unit)
