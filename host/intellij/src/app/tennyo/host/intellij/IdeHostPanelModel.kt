package app.tennyo.host.intellij

import app.tennyo.host.engine.panel.HostPanelActions
import app.tennyo.host.engine.panel.HostPanelModel
import app.tennyo.host.engine.panel.SessionPanelModel

/**
 * This IDE's answers to the panel: opening a pairing window, which starts a session first, and the discovery and
 * Remote access settings, which live in the IDE's own configuration. The six states themselves are
 * [SessionPanelModel]'s, shared with the VS Code panel so the two cannot drift into showing different things for the
 * same session.
 */
internal fun ideHostPanelModel(service: HostSessionService): HostPanelModel =
    SessionPanelModel(service.host, IdeHostPanelActions(service))

private class IdeHostPanelActions(private val service: HostSessionService) : HostPanelActions {
    override fun openPairingWindow() {
        service.openPairingWindow()
    }

    override fun setDiscoveryEnabled(enabled: Boolean) {
        service.forgetModeBeforePairing()
        service.setDiscoveryEnabled(enabled)
    }

    override fun setRemoteAccess(enabled: Boolean) {
        service.setRemoteAccess(enabled)
    }
}
