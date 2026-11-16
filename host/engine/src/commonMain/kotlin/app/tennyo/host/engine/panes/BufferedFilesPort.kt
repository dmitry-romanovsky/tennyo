package app.tennyo.host.engine.panes

/**
 * The open, unsaved buffers of a host whose editor keeps them outside the file system.
 *
 * IntelliJ has no need of this: its VFS hands out the open document's text already. VS Code and Zed do — their file
 * APIs read the disk, so a phone would otherwise be shown a stale file whenever the desktop has unsaved edits.
 */
interface EditorBuffers {
    /** The buffer's text when the editor holds unsaved edits for [path], and `null` when the disk is current. */
    fun unsavedText(path: String): String?

    /** Something that changes whenever the buffer does, when the editor can say; `null` when it cannot. */
    fun revision(path: String): String? = null

    /**
     * Asks the editor to replace the buffer's text and save it, answering whether it has a buffer to replace. The
     * editor may finish after this returns: a host whose edit API is asynchronous submits the edit and answers
     * `true`, and its own change notification carries the authoritative state to the phone.
     */
    fun replaceText(path: String, text: String): Boolean
}

/**
 * [delegate] with the host's unsaved buffers laid over it.
 *
 * A read prefers the buffer, so the phone sees the file as the desktop shows it. A write goes through the editor
 * when it has the file open, so unsaved desktop edits are replaced rather than silently overwritten underneath, and
 * falls to the disk otherwise. When the editor takes the write its revision is reported as unknown: the edit may
 * still be in flight, and claiming a revision the editor has not reached would defeat the conflict check the phone
 * runs against the next push.
 */
class BufferedFilesPort(
    private val delegate: IdeFilesPort,
    private val buffers: EditorBuffers,
) : IdeFilesPort by delegate {
    override fun read(path: String, byteLimit: Int): FileSnapshot {
        val snapshot = delegate.read(path, byteLimit)
        val unsaved = buffers.unsavedText(path) ?: return snapshot
        // A file the editor cannot show stays unshowable; an open buffer does not make a binary file readable.
        if (!snapshot.viewable) return snapshot
        return snapshot.copy(text = unsaved, revision = buffers.revision(path) ?: snapshot.revision)
    }

    override fun write(path: String, text: String, byteLimit: Int): FileSnapshot {
        val snapshot = delegate.read(path, byteLimit)
        if (!snapshot.writable || !snapshot.viewable) return delegate.write(path, text, byteLimit)
        if (!buffers.replaceText(path, text)) return delegate.write(path, text, byteLimit)
        return snapshot.copy(text = text, revision = null)
    }
}
