package app.tennyo.host.engine.panes

import app.tennyo.contract.ActivePanels
import app.tennyo.contract.EditorTab
import app.tennyo.contract.EditorTabAction
import app.tennyo.contract.EditorTabsState
import app.tennyo.contract.FileContent
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkPush
import app.tennyo.host.engine.rpc.CallFault
import app.tennyo.host.engine.rpc.projectPathName
import app.tennyo.host.engine.rpc.projectRelativePath
import app.tennyo.host.engine.rpc.resolveExistingProjectPath
import app.tennyo.host.engine.session.HostSession

class EditorPaneController(
    private val session: HostSession,
) {
    private val files get() = session.surfaces.files
    private val editorTabs get() = session.surfaces.editorTabs

    fun openEditor(path: String): Boolean {
        val opened = files.resolveExistingProjectPath(path)?.let(editorTabs::open) == true
        if (opened) emitEditorTabs()
        return opened
    }

    fun selectEditor(path: String): Boolean = openEditor(path)

    fun closeEditor(path: String): Boolean {
        val closed = files.resolveExistingProjectPath(path)?.let(editorTabs::close) == true
        if (closed) emitEditorTabs()
        return closed
    }

    /** Runs [action] on the tab at [path], faulting when this host does not advertise the action. */
    fun performTabAction(action: EditorTabAction, path: String): Boolean {
        val allowed = editorTabsState().allowedActions
        if (action !in allowed) {
            throw CallFault(
                LinkFaultCode.UNSUPPORTED,
                "Editor tab action $action is unsupported here; this host allows ${allowed.joinToString()}.",
            )
        }
        return when (action) {
            EditorTabAction.OPEN -> openEditor(path)
            EditorTabAction.SELECT -> selectEditor(path)
            EditorTabAction.CLOSE -> closeEditor(path)
        }
    }

    fun emitEditorTabs() {
        if (!session.shouldEmitPanelMetadata(ActivePanels.FILES)) return
        session.push(LinkPush.EditorTabs(editorTabsState()))
    }

    /** Sends the content of the file at [path] when it is the open, selected file of the active files panel. */
    fun emitFileContent(path: String) {
        val root = files.rootPath() ?: return
        val relativePath = projectRelativePath(root, path) ?: return
        val surface = session.activeSurface.get() ?: return
        if (surface.panel != ActivePanels.FILES || surface.selectedFilePath != relativePath) {
            return
        }
        if (relativePath !in editorTabs().map { it.path }.toSet()) return
        val snapshot = editorTabs.contentOf(path) ?: return
        session.push(
            LinkPush.FileContentChanged(
                FileContent(
                    path = relativePath,
                    name = path.projectPathName(),
                    text = snapshot.text,
                    revision = snapshot.revision,
                    writable = snapshot.writable,
                    viewable = snapshot.viewable,
                    unavailableReason = snapshot.unavailableReason,
                ),
            ),
        )
    }

    fun emitOpenFileContents() {
        editorTabs.tabs().forEach { tab -> emitFileContent(tab.path) }
    }

    private fun editorTabs(): List<EditorTab> {
        val root = files.rootPath() ?: return emptyList()
        return editorTabs.tabs().mapNotNull { tab ->
            projectRelativePath(root, tab.path)?.let { relativePath ->
                EditorTab(path = relativePath, name = tab.name, writable = tab.writable)
            }
        }
    }

    fun editorTabsState(): EditorTabsState = EditorTabsState(
        tabs = editorTabs(),
        selectedPath = selectedEditorPath(),
        allowedActions = editorTabs.allowedActions,
    )

    private fun selectedEditorPath(): String? {
        val root = files.rootPath() ?: return null
        return editorTabs.selectedPath()?.let { projectRelativePath(root, it) }
    }
}
