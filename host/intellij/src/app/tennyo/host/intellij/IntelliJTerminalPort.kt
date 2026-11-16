package app.tennyo.host.intellij

import app.tennyo.contract.TerminalSnapshot
import app.tennyo.contract.TerminalTab
import app.tennyo.host.engine.panes.IdeTerminalPort
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * One session's terminals: the IDE Terminal tool window tabs. This class
 * owns their lifecycle (create, select, write, close) and the selection; [output] streams what they print.
 */
internal class IntelliJTerminalPort(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val onTabsChanged: () -> Unit,
    onFrame: (String, TerminalSnapshot) -> Unit,
    shouldReadFrame: (String) -> Boolean,
) : IdeTerminalPort {
    private val selectedTerminalId = AtomicReference<String?>()
    private val observedTerminalContentIds = ConcurrentHashMap.newKeySet<Int>()
    private val scheduler = TerminalTasksScheduler(parentDisposable)
    private val output = IntelliJTerminalOutput(project, scheduler, onFrame, shouldReadFrame, ::findViewOnEdt)

    @Volatile
    private var disposed = false

    init {
        Disposer.register(parentDisposable) {
            disposed = true
            output.clear()
        }
        scheduleTabsRefresh()
    }

    override fun tabs(includeSnapshots: Boolean): List<TerminalTab> =
        if (isDisposed()) {
            emptyList()
        } else {
            runCatching {
                invokeOnEdtAndWait {
                    val tabs = TerminalToolWindowTabsManager.getInstance(project).tabs
                    tabs.forEach(::observeTerminalTab)
                    updateSelectedTerminalFromIde(tabs)
                    ensureSelectedTerminal(tabs.map { it.remoteId })
                    tabs.map { it.toTerminalTab(includeSnapshot = includeSnapshots) }
                }
            }.getOrElse {
                LOG.warn("Unable to read IDE terminal tabs for Tennyo Companion session in ${project.name}", it)
                emptyList()
            }
        }

    override fun snapshot(terminalId: String): TerminalSnapshot? {
        if (isDisposed()) return null
        return withTerminalTabOnEdt(
            terminalId,
            defaultValue = null,
            onFailure = {
                LOG.warn(
                    "Unable to read optional IDE terminal snapshot $terminalId for Tennyo Companion session in ${project.name}",
                    it
                )
            },
        ) { terminal -> output.snapshotOf(terminal.view) }
    }

    override fun selectedTerminalId(): String? = selectedTerminalId.get().takeUnless { isDisposed() }

    override fun ensureOpen(): Boolean =
        !isDisposed() && runCatching {
            invokeOnEdtAndWait {
                val toolWindow = ToolWindowManager.getInstance(project)
                    .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
                    ?: return@invokeOnEdtAndWait false
                if (!toolWindow.isVisible) {
                    toolWindow.show()
                    scheduleTabsRefresh()
                }
                true
            }
        }.onFailure {
            LOG.warn("Unable to open IDE Terminal tool window for Tennyo Companion session in ${project.name}", it)
        }.getOrDefault(false)

    override fun create(): TerminalTab? {
        if (isDisposed()) return null
        ensureOpen()
        val root = project.basePath?.let { Path.of(it).normalize() }?.takeIf { Files.exists(it) } ?: return null
        val tab = runCatching {
            invokeOnEdtAndWait {
                TerminalToolWindowTabsManager.getInstance(project)
                    .createTabBuilder()
                    .workingDirectory(root.toString())
                    .tabName("Mobile")
                    .requestFocus(false)
                    .deferSessionStartUntilUiShown(false)
                    .createTab()
            }
        }.onFailure {
            LOG.warn("Unable to start IDE terminal tab for Tennyo Companion session in ${project.name}", it)
        }.getOrNull() ?: return null
        val terminalTab = invokeOnEdtAndWait {
            observeTerminalTab(tab)
            tab.toTerminalTab(includeSnapshot = true)
        }
        selectedTerminalId.set(terminalTab.id)
        return terminalTab
    }

    override fun write(terminalId: String, text: String): Boolean {
        if (isDisposed()) return false
        if (text.isEmpty()) return false
        val accepted = withTerminalTabOnEdt(
            terminalId,
            defaultValue = false,
            onFailure = {
                LOG.warn(
                    "Unable to write optional IDE terminal input to $terminalId for Tennyo Companion session in ${project.name}",
                    it
                )
            },
        ) { terminal ->
            observeTerminalTab(terminal)
            terminal.view.createSendTextBuilder()
                .send(text)
            true
        }
        if (accepted) {
            selectedTerminalId.set(terminalId)
            output.scheduleFrame(terminalId, delayMillis = WRITE_FRAME_FALLBACK_DELAY_MILLIS)
        }
        return accepted
    }

    override fun close(terminalId: String): Boolean {
        if (isDisposed()) return false
        val closed = withTerminalTabOnEdt(
            terminalId,
            defaultValue = false,
            onFailure = {
                LOG.warn(
                    "Unable to close optional IDE terminal tab $terminalId for Tennyo Companion session in ${project.name}",
                    it
                )
            },
        ) { terminal ->
            runCatching {
                terminal.view.createSendTextBuilder().send("")
            }.onFailure {
                LOG.warn(
                    "Unable to interrupt optional IDE terminal tab $terminalId before closing in ${project.name}",
                    it
                )
            }
            removeTerminalContentSilently(terminal)
            true
        }
        if (!closed) return false
        if (selectedTerminalId.get() == terminalId) {
            selectedTerminalId.set(tabs().firstOrNull()?.id)
        }
        return true
    }

    override fun select(terminalId: String): Boolean {
        if (isDisposed()) return false
        val selected = withTerminalTabOnEdt(
            terminalId,
            defaultValue = false,
            onFailure = {
                LOG.warn(
                    "Unable to select optional IDE terminal tab $terminalId for Tennyo Companion session in ${project.name}",
                    it
                )
            },
        ) { terminal ->
            terminal.content.manager?.setSelectedContent(terminal.content, false)
            observeTerminalTab(terminal)
            true
        }
        if (!selected) return false
        selectedTerminalId.set(terminalId)
        output.scheduleFrame(terminalId, delayMillis = SELECT_FRAME_DELAY_MILLIS)
        return true
    }

    private fun scheduleTabsRefresh() {
        scheduler.schedule(TABS_REFRESH_DELAY_MILLIS, ::refreshTerminalTabsLater)
    }

    private fun refreshTerminalTabsLater() {
        if (isDisposed()) return
        onTabsChanged()
        if (!isDisposed()) {
            scheduleTabsRefresh()
        }
    }

    private fun ensureSelectedTerminal(ids: List<String>) {
        if (selectedTerminalId.get() !in ids) {
            selectedTerminalId.set(ids.firstOrNull())
        }
    }

    private fun updateSelectedTerminalFromIde(tabs: List<TerminalToolWindowTab>) {
        val selectedTab = tabs.firstOrNull { it.content.manager?.selectedContent == it.content }
        if (selectedTab != null) {
            selectedTerminalId.set(selectedTab.remoteId)
        }
    }

    private fun findTerminalTabOnEdt(id: String): TerminalToolWindowTab? =
        TerminalToolWindowTabsManager.getInstance(project).tabs.firstOrNull { it.remoteId == id }

    /** The view a frame for [terminalId] renders from; an IDE tab found this way starts being watched. */
    private fun findViewOnEdt(terminalId: String): TerminalView? =
        findTerminalTabOnEdt(terminalId)?.also(::observeTerminalTab)?.view

    private fun <T> withTerminalTabOnEdt(
        id: String,
        defaultValue: T,
        onFailure: ((Throwable) -> Unit)? = null,
        action: (TerminalToolWindowTab) -> T,
    ): T {
        if (isDisposed()) return defaultValue
        return runCatching {
            invokeOnEdtAndWait {
                val terminal = findTerminalTabOnEdt(id) ?: return@invokeOnEdtAndWait defaultValue
                action(terminal)
            }
        }.onFailure { throwable ->
            onFailure?.invoke(throwable)
        }.getOrDefault(defaultValue)
    }

    private fun TerminalToolWindowTab.toTerminalTab(includeSnapshot: Boolean): TerminalTab {
        val snapshot = if (includeSnapshot) output.snapshotOf(view) else null
        val id = remoteId
        return TerminalTab(
            id = id,
            title = content.displayName.takeIf { it.isNotBlank() } ?: view.title.buildTitle(),
            cwd = view.getCurrentDirectory() ?: project.basePath.orEmpty(),
            text = snapshot?.text.orEmpty(),
            isSelected = selectedTerminalId.get() == id,
            snapshot = snapshot,
        )
    }

    private val TerminalToolWindowTab.remoteId: String
        get() = System.identityHashCode(content).toString()

    private fun observeTerminalTab(tab: TerminalToolWindowTab) {
        if (isDisposed()) return
        val contentId = System.identityHashCode(tab.content)
        if (observedTerminalContentIds.add(contentId)) {
            Disposer.register(tab.content) {
                observedTerminalContentIds.remove(contentId)
                output.forgetContent(contentId)
                if (selectedTerminalId.get() == contentId.toString()) {
                    selectedTerminalId.set(null)
                }
                onTabsChanged()
            }
        }
        output.observe(tab.remoteId, tab)
    }

    private fun isDisposed(): Boolean = disposed || scheduler.isDisposed()

    private fun removeTerminalContentSilently(terminal: TerminalToolWindowTab) {
        TerminalToolWindowTabsManager.getInstance(project).closeTab(terminal)
    }

    private companion object {
        private val LOG = Logger.getInstance(IntelliJTerminalPort::class.java)
        private const val TABS_REFRESH_DELAY_MILLIS = 1_000
        private const val SELECT_FRAME_DELAY_MILLIS = 20
        private const val WRITE_FRAME_FALLBACK_DELAY_MILLIS = 80
    }
}
