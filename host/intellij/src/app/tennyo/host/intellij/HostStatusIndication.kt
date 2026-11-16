package app.tennyo.host.intellij

import app.tennyo.host.engine.panel.HostPanelState
import app.tennyo.host.engine.panel.HostVisibility
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import javax.swing.Icon

/** What the companion's entry points — the status bar widget and the Tools menu action — show about this project. */
internal enum class HostStatusIndication(private val iconOf: () -> Icon, private val descriptionKey: String) {
    PENDING_REQUEST({ HostIcons.pending }, "action.host.ShowPopupAction.description.pending"),
    CONNECTED({ HostIcons.connected }, "action.host.ShowPopupAction.description.active"),
    WAITING({ HostIcons.waiting }, "action.host.ShowPopupAction.description.waiting"),
    DISCOVERING({ HostIcons.discovery }, "action.host.ShowPopupAction.description.discovery"),
    IDLE({ HostIcons.host }, "action.app.tennyo.host.ShowPopup.description"),
    ;

    val icon: Icon get() = iconOf()

    val description: String get() = HostBundle.message(descriptionKey)
}

/**
 * Read from the panel's own state, so the status bar and the panel can never disagree about where the pairing
 * flow is — both editors answer this question from the same six cases.
 */
internal fun hostStatusIndication(project: Project): HostStatusIndication =
    ideHostPanelModel(project.service<HostSessionService>()).state.toStatusIndication()

internal fun HostPanelState.toStatusIndication(): HostStatusIndication = when (this) {
    is HostPanelState.Connected -> HostStatusIndication.CONNECTED
    is HostPanelState.Request -> HostStatusIndication.PENDING_REQUEST
    is HostPanelState.Words, is HostPanelState.WrongWords -> HostStatusIndication.PENDING_REQUEST
    is HostPanelState.Waiting -> HostStatusIndication.WAITING
    is HostPanelState.NoSession ->
        if (visibility == HostVisibility.NO_ONE) HostStatusIndication.IDLE else HostStatusIndication.DISCOVERING
}
