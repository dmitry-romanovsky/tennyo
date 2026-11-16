package app.tennyo.contract

import kotlinx.serialization.Serializable

@Serializable
data class FileNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val children: List<FileNode> = emptyList(),
    val childrenTruncated: Boolean = false,
)

@Serializable
data class FileTreeResponse(val root: FileNode)

@Serializable
data class FileContent(
    val path: String,
    val name: String,
    val text: String,
    val revision: String? = null,
    val writable: Boolean = true,
    val viewable: Boolean = true,
    val unavailableReason: String? = null,
)

@Serializable
data class EditorTab(
    val path: String,
    val name: String,
    val writable: Boolean = true,
)

/** What a host lets the phone do to an editor tab. A host advertises its subset in [EditorTabsState]. */
@Serializable
enum class EditorTabAction {
    OPEN,
    SELECT,
    CLOSE,
}

@Serializable
data class EditorTabsState(
    val tabs: List<EditorTab> = emptyList(),
    /** Null when the host has no tab selected, which a host with no open file reports on every state. */
    val selectedPath: String? = null,
    /** The actions this host accepts; a call for one outside the set is faulted, not silently dropped. */
    val allowedActions: Set<EditorTabAction> = EditorTabAction.entries.toSet(),
)

@Serializable
data class EditorTabActionRequest(val path: String, val action: EditorTabAction)

@Serializable
data class WriteFileRequest(
    val path: String,
    val text: String,
    val expectedRevision: String? = null,
)
