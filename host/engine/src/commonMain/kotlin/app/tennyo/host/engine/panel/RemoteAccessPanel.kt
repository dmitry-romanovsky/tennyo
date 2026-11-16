package app.tennyo.host.engine.panel

import app.tennyo.host.engine.transport.OverlayNetwork

/**
 * The panel's Remote access section. [available] is false while nobody may discover this computer, because a phone
 * away from home reaches it only through discovery. [overlay] lists the private-network addresses the listener is
 * bound to now, all on [port], and is empty when Remote access is on but no such network is up.
 */
data class RemoteAccessPanel(
    val enabled: Boolean,
    val available: Boolean,
    val port: Int?,
    val overlay: List<OverlayAddress>,
)

data class OverlayAddress(val network: OverlayNetwork, val address: String)
