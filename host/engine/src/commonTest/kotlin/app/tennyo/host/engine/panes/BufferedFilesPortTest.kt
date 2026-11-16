package app.tennyo.host.engine.panes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the phone sees and writes when the desktop has the same file open with unsaved edits. */
class BufferedFilesPortTest {
    private val disk = FakeProjectFiles().file(PATH, "saved text").file(BINARY, "binary")
    private val buffers = FakeEditorBuffers()
    private val files = BufferedFilesPort(disk, buffers)

    @Test
    fun `a file with no unsaved edits reads straight from the disk`() {
        assertEquals("saved text", files.read(PATH, LIMIT).text)
        assertEquals(disk.read(PATH, LIMIT).revision, files.read(PATH, LIMIT).revision)
    }

    @Test
    fun `an unsaved buffer is what the phone is shown, with the editor's own revision`() {
        buffers.unsaved[PATH] = "typed but not saved"
        buffers.revisions[PATH] = "buffer-7"

        val snapshot = files.read(PATH, LIMIT)

        assertEquals("typed but not saved", snapshot.text)
        assertEquals("buffer-7", snapshot.revision)
        assertTrue(snapshot.writable)
    }

    @Test
    fun `a file the editor cannot show stays unshowable even with a buffer open`() {
        disk.unviewable += BINARY
        buffers.unsaved[BINARY] = "would be text"

        val snapshot = files.read(BINARY, LIMIT)

        assertFalse(snapshot.viewable)
        assertEquals("", snapshot.text)
    }

    @Test
    fun `a write to an open file goes through the editor rather than under it`() {
        buffers.unsaved[PATH] = "desktop edits"

        val written = files.write(PATH, "from the phone", LIMIT)

        assertEquals(listOf(PATH to "from the phone"), buffers.replaced)
        assertTrue(disk.writes.isEmpty(), "the phone's write went to the disk under an open buffer: ${disk.writes}")
        assertEquals("from the phone", written.text)
        assertNull(written.revision, "a revision was claimed before the editor reached it")
    }

    @Test
    fun `a write to a file the editor does not have open goes to the disk`() {
        val written = files.write(PATH, "from the phone", LIMIT)

        assertEquals(listOf(PATH to "from the phone"), disk.writes)
        assertTrue(buffers.replaced.isEmpty())
        assertEquals("from the phone", written.text)
    }

    @Test
    fun `a file that cannot be written is refused by the disk rather than routed to the editor`() {
        val missing = "/work/project/absent.kt"

        files.write(missing, "text", LIMIT)

        assertTrue(buffers.replaced.isEmpty(), "an unwritable file was routed to the editor")
    }

    private companion object {
        const val PATH = "/work/project/Main.kt"
        const val BINARY = "/work/project/image.bin"
        const val LIMIT = 64 * 1024
    }
}

private class FakeEditorBuffers : EditorBuffers {
    val unsaved = mutableMapOf<String, String>()
    val revisions = mutableMapOf<String, String>()
    val replaced = mutableListOf<Pair<String, String>>()

    override fun unsavedText(path: String): String? = unsaved[path]

    override fun revision(path: String): String? = revisions[path]

    override fun replaceText(path: String, text: String): Boolean {
        if (path !in unsaved) return false
        replaced += path to text
        unsaved[path] = text
        return true
    }
}
