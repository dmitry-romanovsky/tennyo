package app.tennyo.host.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent

/** The companion's entry point in the IDE status bar: the session state as an icon, the popup on click. */
internal class HostStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {
    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getIcon(): Icon = hostStatusIndication(project).icon

    override fun getTooltipText(): String = hostStatusIndication(project).description

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        showHostPopup(project, (event.component as? JComponent)?.let(PopupAnchor::StatusBar) ?: PopupAnchor.None)
    }

    companion object {
        const val WIDGET_ID: String = "app.tennyo.host.StatusBarWidget"

        /** Repaints the widget after the session state changed; runs on the EDT. */
        fun refresh(project: Project) {
            if (project.isDisposed) return
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(WIDGET_ID)
        }
    }
}

internal class HostStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = HostStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = HostBundle.message("status.bar.widget.name")

    override fun isAvailable(project: Project): Boolean = true

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = HostStatusBarWidget(project)
}
