package app.tennyo.host.vscode.panes

import app.tennyo.contract.EditorTabAction
import app.tennyo.host.engine.panes.EditorTabInfo
import app.tennyo.host.engine.panes.FileSnapshot
import app.tennyo.host.engine.panes.IdeEditorTabsPort
import app.tennyo.host.engine.panes.IdeFilesPort
import app.tennyo.host.vscode.api.Tab
import app.tennyo.host.vscode.api.Uri
import app.tennyo.host.vscode.api.VsCodeTabs
import app.tennyo.host.vscode.api.VsCodeWorkspace

/**
 * The editor's tab strip. VS Code's tab API reads synchronously and changes asynchronously, so an action the phone
 * asks for is submitted and not waited on: the editor's own tab-change notification is what tells the phone whether
 * it happened, which is the same thing that reports a tab the user opened by hand.
 *
 * Only text tabs are reported. A diff view, a notebook or a webview is a tab the mobile editor has nothing to show
 * for, and listing it would offer the phone a file it cannot open.
 */
class VsCodeEditorTabsPort(private val files: IdeFilesPort) : IdeEditorTabsPort {
    override val allowedActions: Set<EditorTabAction> = EditorTabAction.entries.toSet()

    override fun tabs(): List<EditorTabInfo> = VsCodeTabs.tabGroups.all
        .flatMap { group -> group.tabs.toList() }
        .mapNotNull { tab -> tab.textPath()?.let { EditorTabInfo(path = it, name = tab.label, writable = true) } }
        .distinctBy { it.path }

    override fun selectedPath(): String? = VsCodeTabs.tabGroups.activeTabGroup.activeTab?.textPath()

    override fun open(path: String): Boolean {
        VsCodeWorkspace.openTextDocument(Uri.file(path)).then(
            { document -> VsCodeTabs.showTextDocument(document).then({}, {}) },
            {},
        )
        return true
    }

    override fun close(path: String): Boolean {
        val open = VsCodeTabs.tabGroups.all
            .flatMap { group -> group.tabs.toList() }
            .filter { it.textPath() == path }
        if (open.isEmpty()) return false
        open.forEach { tab -> VsCodeTabs.tabGroups.close(tab).then({}, {}) }
        return true
    }

    override fun contentOf(path: String): FileSnapshot? =
        files.read(path, MAX_TAB_CONTENT_BYTES).takeIf { it.viewable }

    private companion object {
        /** The engine trims to its own limit; this only keeps a huge file from being read to be thrown away. */
        const val MAX_TAB_CONTENT_BYTES = 2 * 1024 * 1024
    }
}

/** The file this tab shows, or `null` when its input is not a text file. */
private fun Tab.textPath(): String? = (input?.uri as? Uri)?.linkPath()
