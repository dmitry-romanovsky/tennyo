package app.tennyo.host.intellij

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.ScreenUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.scale.JBUIScale
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.LocalComponent
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

class ShowPopupAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null
        val indication = if (project == null) HostStatusIndication.IDLE else hostStatusIndication(project)
        event.presentation.icon = indication.icon
        event.presentation.description = indication.description
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        if (project == null) {
            Messages.showWarningDialog(
                HostBundle.message("tennyo.companion.session.no.project"),
                HostBundle.message("tennyo.companion.session.dialog.title"),
            )
            return
        }
        showHostPopup(project, toolbarAnchor(event))
    }

    internal companion object {
        const val POPUP_WIDTH: Int = 400

        /** The design's frame height, so the first placement lands where the measured popup will stay. */
        const val POPUP_HEIGHT: Int = 480
    }
}

/** Where the popup hangs from: under a toolbar button, above a status bar widget, or nowhere in particular. */
internal sealed interface PopupAnchor {
    data class Toolbar(val component: JComponent) : PopupAnchor

    data class StatusBar(val component: JComponent) : PopupAnchor

    data object None : PopupAnchor
}

private val PopupAnchor.component: JComponent?
    get() = when (this) {
        is PopupAnchor.Toolbar -> component
        is PopupAnchor.StatusBar -> component
        PopupAnchor.None -> null
    }

@OptIn(ExperimentalJewelApi::class)
internal fun showHostPopup(project: Project, anchor: PopupAnchor) {
    var popupRef: JBPopup? = null
    var appliedSize: Dimension? = null

    val panel = JewelComposePanel(focusOnClickInside = true, config = {
        preferredSize = Dimension(ShowPopupAction.POPUP_WIDTH, ShowPopupAction.POPUP_HEIGHT)
    }) {
        val model = remember {
            RealPopupModel(project) {
                ActivityTracker.getInstance().inc()
                HostStatusBarWidget.refresh(project)
            }
        }
        val host = LocalComponent.current
        PopupContent(
            model = model,
            onClose = { popupRef?.cancel() },
            modifier = Modifier
                .width(ShowPopupAction.POPUP_WIDTH.dp)
                // The popup opens collapsed: the content measures freely and the host window follows it.
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .onSizeChanged { contentSize ->
                    val hostSize = hostSizeFor(host, contentSize)
                    // Resizing the popup relayouts the panel, which re-measures the content, which lands back
                    // here. At fractional display scaling the device-pixel round trip is off by one, so the two
                    // sizes would chase each other for as long as the popup is open.
                    if (hostSize == appliedSize) return@onSizeChanged
                    appliedSize = hostSize
                    SwingUtilities.invokeLater {
                        val popup = popupRef ?: return@invokeLater
                        // The popup can be cancelled — Close, a click outside, another window — between the
                        // measure and this runnable.
                        if (popup.isDisposed) return@invokeLater
                        host.preferredSize = hostSize
                        // Location before size: a status bar popup is pinned by its bottom edge, so growing it
                        // first would push it down across the status bar before it snapped back up.
                        anchor.popupLocation(hostSize.width, hostSize.height)
                            ?.let { popup.setLocation(it.screenPoint) }
                        popup.size = hostSize
                    }
                },
        )
    }

    val popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(panel, null)
        .setCancelOnOtherWindowOpen(true)
        .setCancelOnClickOutside(true)
        .setShowBorder(false)
        .setFocusable(true)
        .setRequestFocus(true)
        .createPopup()
    popupRef = popup
    (anchor.component as? ActionButton)?.let { PopupUtil.addToggledStateListener(popup, it.presentation) }

    val location = anchor.popupLocation(panel.preferredSize.width, panel.preferredSize.height)
    when {
        location != null -> {
            popup.setRequestFocus(true)
            popup.show(location)
        }

        anchor is PopupAnchor.None -> popup.showCenteredInCurrentWindow(project)
        else -> popup.showInFocusCenter()
    }
}

/** The measured content as the host's scaled coordinates, never taller than the screen the popup sits on. */
private fun hostSizeFor(host: JComponent, contentSize: IntSize): Dimension {
    val scale = JBUIScale.sysScale(host)
    val width = (contentSize.width / scale).roundToInt()
    val height = (contentSize.height / scale).roundToInt()
    // The content measures unbounded so the popup can follow it; without a ceiling a tall state opens a window
    // taller than the display, which fitToScreen can only move, not shrink.
    val screenHeight = host.takeIf { it.isShowing }?.let { ScreenUtil.getScreenRectangle(it).height }
    return Dimension(width, screenHeight?.let(height::coerceAtMost) ?: height)
}

internal fun toolbarAnchor(event: AnActionEvent): PopupAnchor {
    val presentationButton = event.presentation.getClientProperty(CustomComponentAction.COMPONENT_KEY) as? ActionButton
    if (presentationButton != null) return PopupAnchor.Toolbar(presentationButton)

    val eventComponent = event.inputEvent?.component ?: return PopupAnchor.None
    val button = eventComponent as? ActionButton
        ?: SwingUtilities.getAncestorOfClass(ActionButton::class.java, eventComponent) as? JComponent
    return button?.let(PopupAnchor::Toolbar) ?: PopupAnchor.None
}

/** Keeps the popup's near corner pinned to the anchor: below a toolbar button, above a status bar widget. */
private fun PopupAnchor.popupLocation(popupWidth: Int, popupHeight: Int): RelativePoint? {
    val component = component ?: return null
    if (!component.isShowing || component.width <= 0 || component.height <= 0) return null

    val corner = when (this) {
        is PopupAnchor.StatusBar -> Point(component.width, 0)
        else -> Point(component.width, component.height)
    }
    val anchorOnScreen = RelativePoint(component, corner).screenPoint
    // The popup's right edge lines up with the anchor's; it drops below a toolbar button and rises above a status
    // bar widget. Either corner can leave the display on a small screen or a secondary monitor, so the whole
    // rectangle is fitted to the screen the anchor is on rather than clamped against the anchor's own container.
    val bounds = Rectangle(
        anchorOnScreen.x - popupWidth,
        if (this is PopupAnchor.StatusBar) anchorOnScreen.y - popupHeight else anchorOnScreen.y,
        popupWidth,
        popupHeight,
    )
    ScreenUtil.fitToScreen(bounds)
    return RelativePoint.fromScreen(Point(bounds.x, bounds.y))
}
