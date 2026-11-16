package app.tennyo.host.intellij

import app.tennyo.host.engine.panes.IdeTerminalPort
import app.tennyo.host.engine.panes.IdeTerminalPortFactory
import app.tennyo.host.engine.panes.TerminalPortCallbacks
import app.tennyo.host.engine.panes.TerminalPortLease
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

internal object IntelliJTerminalPortFactory {

    /** The session-facing factory: each bridge gets its own disposable, released with the lease. */
    fun forProject(project: Project): IdeTerminalPortFactory = object : IdeTerminalPortFactory {
        override fun create(callbacks: TerminalPortCallbacks): TerminalPortLease? {
            val disposable = Disposer.newDisposable("Tennyo Companion terminal bridge")
            return create(project, disposable, callbacks)
                ?.let { bridge -> TerminalPortLease(bridge) { Disposer.dispose(disposable) } }
                ?: run {
                    Disposer.dispose(disposable)
                    null
                }
        }

        // The terminal module is a required dependency of the plugin, so a terminal is always there to bridge.
        override fun isAvailable(): Boolean = true
    }

    fun create(
        project: Project,
        parentDisposable: Disposable,
        callbacks: TerminalPortCallbacks,
    ): IdeTerminalPort? {
        return runCatching {
            IntelliJTerminalPort(
                project,
                parentDisposable,
                callbacks.onTabsChanged,
                callbacks.onFrame,
                callbacks.shouldReadFrame,
            )
        }.onFailure {
            LOG.warn("Unable to load IDE terminal integration for Tennyo Companion session in ${project.name}", it)
        }.getOrNull()
    }

    private val LOG = Logger.getInstance(IntelliJTerminalPortFactory::class.java)
}
