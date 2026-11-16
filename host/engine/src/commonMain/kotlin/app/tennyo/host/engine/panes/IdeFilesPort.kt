package app.tennyo.host.engine.panes

import app.tennyo.contract.EditorTabAction

/**
 * File access for one project. Every path crossing this interface is absolute, normalised, and `/`-separated. The
 * core resolves each client path against [rootPath] and confines it to the project before a bridge call sees it.
 */
interface IdeFilesPort {
    /** The project directory with symbolic links resolved, or `null` when the project has none. */
    fun rootPath(): String?

    /** [path] with symbolic links resolved, or `null` when nothing exists there. */
    fun realPath(path: String): String?

    /**
     * Joins [relative] onto [base] the way the platform's path API does, folding `.` and `..` without touching the
     * disk. An absolute [relative] replaces [base]. Input the platform cannot parse as a path throws.
     */
    fun resolve(base: String, relative: String): String

    fun isDirectory(path: String): Boolean

    /**
     * Visits the entries of [directory] in listing order until [visit] returns `false`, so a huge directory is never
     * enumerated past what the caller needs. Failing to read the directory throws.
     */
    fun forEachChild(directory: String, visit: (String) -> Boolean)

    /**
     * The file as the mobile editor shows it: the open document's text when there is one, including unsaved edits.
     * A binary, directory, oversized, or undecodable file comes back unviewable with the reason.
     */
    fun read(path: String, byteLimit: Int): FileSnapshot

    /**
     * Replaces the file's text, through the open document when there is one, and returns the fresh [read]. A file the
     * editor can't show is returned unviewable and left untouched.
     */
    fun write(path: String, text: String, byteLimit: Int): FileSnapshot
}

/** One file's content for the mobile editor. [revision] changes whenever the text does, when the IDE can tell. */
data class FileSnapshot(
    val text: String,
    val revision: String?,
    val writable: Boolean,
    val viewable: Boolean = true,
    val unavailableReason: String? = null,
)

/** The IDE's open editor tabs. Paths are absolute and normalised but not resolved through symbolic links. */
interface IdeEditorTabsPort {
    /**
     * The tab actions this IDE lets the phone drive. IntelliJ and the VS Code family drive all three; a host that
     * cannot, say, close a tab narrows the set and the engine faults the call instead of failing it silently.
     */
    val allowedActions: Set<EditorTabAction> get() = EditorTabAction.entries.toSet()

    fun tabs(): List<EditorTabInfo>

    fun selectedPath(): String?

    /** Opens and focuses the file at [path]; `false` when the IDE cannot find it. */
    fun open(path: String): Boolean

    /** Closes the file's tabs; `false` when the IDE cannot find it. */
    fun close(path: String): Boolean

    /**
     * The open editor's view of the file: document text when loaded, the file's text otherwise, or `null` when the
     * IDE no longer knows the file.
     */
    fun contentOf(path: String): FileSnapshot?
}

data class EditorTabInfo(val path: String, val name: String, val writable: Boolean)
