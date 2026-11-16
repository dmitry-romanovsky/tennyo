package app.tennyo.host.engine.panel

import app.tennyo.host.engine.pairing.RememberedPairSummary

/**
 * What a host panel can do, whichever editor draws it.
 *
 * An adapter implements this over its own session service; the panel itself has no logic of its own and only
 * renders [state] and calls these back. That is what lets the IntelliJ popup and the VS Code one stay the same
 * panel rather than two that resemble each other.
 */
interface HostPanelModel {
    val state: HostPanelState

    /** Opens the two-minute window, starting the session it belongs to. */
    fun openPairingWindow()

    /** Restarts the open window's deadline, keeping its name, id and token. */
    fun extendPairingWindow()

    fun setVisibility(visibility: HostVisibility)

    /** Whether phones away from the local network may connect, and where they would reach this computer. */
    val remoteAccess: RemoteAccessPanel

    fun setRemoteAccess(enabled: Boolean)

    /** Accepts the pending connection request, which moves the panel to its words state. */
    fun acceptRequest()

    fun rejectRequest()

    fun endSession()

    /** The phones this host remembers, most recently used first. */
    fun knownDevices(): List<RememberedPairSummary>

    /** Forgets the phone [pairId] names; it has to pair again to connect. */
    fun forgetDevice(pairId: String)
}
