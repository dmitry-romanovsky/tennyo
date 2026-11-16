package app.tennyo.host.vscode.panes

import app.tennyo.host.engine.panes.EditorBuffers
import app.tennyo.host.vscode.api.Range
import app.tennyo.host.vscode.api.TextDocument
import app.tennyo.host.vscode.api.VsCodeWorkspace
import app.tennyo.host.vscode.api.WorkspaceEdit

/**
 * The editor's unsaved buffers, which VS Code keeps outside the file system.
 *
 * `workspace.textDocuments` is one of the few synchronous reads the API offers, so the phone can be shown the file
 * as the desktop has it without waiting. Writing is not synchronous: [replaceText] submits the edit and answers that
 * the editor owns the file, and the document's own change notification carries the result back to the phone.
 */
class VsCodeEditorBuffers : EditorBuffers {
    override fun unsavedText(path: String): String? = documentAt(path)?.takeIf { it.isDirty }?.getText()

    override fun revision(path: String): String? = documentAt(path)?.version?.toString()

    override fun replaceText(path: String, text: String): Boolean {
        val document = documentAt(path) ?: return false
        val edit = WorkspaceEdit()
        edit.replace(document.uri, document.wholeRange(), text)
        VsCodeWorkspace.applyEdit(edit).then(
            { applied -> if (applied) document.save().then({}, {}) },
            {},
        )
        return true
    }

    /** The open document for [path], compared on the link's path form so the platform's separators do not matter. */
    private fun documentAt(path: String): TextDocument? =
        VsCodeWorkspace.textDocuments.firstOrNull { !it.isUntitled && it.uri.linkPath() == path }
}

private fun TextDocument.wholeRange(): Range = Range(positionAt(0), positionAt(getText().length))
