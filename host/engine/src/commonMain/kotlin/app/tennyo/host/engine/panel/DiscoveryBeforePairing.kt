package app.tennyo.host.engine.panel

import kotlin.concurrent.atomics.AtomicReference

/**
 * The discovery setting "All" overrode. A pairing window needs the LAN advertisement, so opening one turns discovery
 * on; when the session it started ends, the host puts back "No one" or "Known devices", whichever the user had.
 */
class DiscoveryBeforePairing {
    private val saved = AtomicReference<Boolean?>(null)

    /** Records [discoveryEnabled] unless an earlier window already did, so re-opening keeps the user's own choice. */
    fun remember(discoveryEnabled: Boolean) {
        saved.compareAndSet(null, discoveryEnabled)
    }

    /** The user picked a mode themselves; there is nothing left to restore. */
    fun forget() = saved.store(null)

    /** The saved setting, handed out once; `null` when pairing overrode nothing. */
    fun takeSaved(): Boolean? = saved.exchange(null)
}
