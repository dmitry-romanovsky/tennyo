package app.tennyo.host.engine.panes

/**
 * An in-memory file system behind [IdeFilesPort]: directories, text files, and symbolic links, with paths
 * resolved the way a POSIX path API does.
 */
class FakeProjectFiles(private val root: String? = "/work/project") : IdeFilesPort {
    private val entries = linkedMapOf<String, Entry>("/" to Entry.Directory)
    val writes = mutableListOf<Pair<String, String>>()

    /** Paths the IDE would refuse to show, so a caller can set up the unviewable case. */
    val unviewable = mutableSetOf<String>()

    fun directory(path: String): FakeProjectFiles = apply { putWithParents(path, Entry.Directory) }

    fun file(path: String, text: String = ""): FakeProjectFiles = apply { putWithParents(path, Entry.File(text)) }

    fun link(path: String, target: String): FakeProjectFiles = apply { putWithParents(path, Entry.Link(target)) }

    override fun rootPath(): String? = root?.let(::realPath)

    override fun realPath(path: String): String? = realPath(path, depth = 0)

    override fun resolve(base: String, relative: String): String =
        normalize(if (relative.startsWith('/')) relative else "$base/$relative")

    override fun isDirectory(path: String): Boolean = realPath(path)?.let { entries[it] } == Entry.Directory

    override fun forEachChild(directory: String, visit: (String) -> Boolean) {
        val prefix = if (directory == "/") "/" else "$directory/"
        for (child in entries.keys.filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) && it != "/" }) {
            if (!visit(child)) break
        }
    }

    override fun read(path: String, byteLimit: Int): FileSnapshot {
        if (path in unviewable) {
            return FileSnapshot("", null, writable = false, viewable = false, unavailableReason = "unviewable")
        }
        return when (val entry = realPath(path)?.let { entries[it] }) {
            is Entry.File ->
                FileSnapshot(text = entry.text, revision = entry.text.hashCode().toString(), writable = true)

            else -> FileSnapshot("", null, writable = false, viewable = false, unavailableReason = "unreadable")
        }
    }

    override fun write(path: String, text: String, byteLimit: Int): FileSnapshot {
        writes += path to text
        realPath(path)?.let { entries[it] = Entry.File(text) }
        return read(path, byteLimit)
    }

    private fun realPath(path: String, depth: Int): String? {
        check(depth < MAX_LINK_DEPTH) { "Symbolic link loop at $path" }
        var resolved = "/"
        for (segment in normalize(path).split('/').filter { it.isNotEmpty() }) {
            val next = if (resolved == "/") "/$segment" else "$resolved/$segment"
            resolved = when (val entry = entries[next]) {
                null -> return null
                is Entry.Link -> realPath(resolve(resolved, entry.target), depth + 1) ?: return null
                else -> next
            }
        }
        return resolved
    }

    private fun putWithParents(path: String, entry: Entry) {
        val normalized = normalize(path)
        var parent = normalized.substringBeforeLast('/')
        while (parent.isNotEmpty() && parent !in entries) {
            entries[parent] = Entry.Directory
            parent = parent.substringBeforeLast('/')
        }
        entries[normalized] = entry
    }

    private sealed interface Entry {
        data object Directory : Entry
        data class File(val text: String) : Entry
        data class Link(val target: String) : Entry
    }

    private companion object {
        const val MAX_LINK_DEPTH = 16

        fun normalize(path: String): String {
            val segments = ArrayDeque<String>()
            path.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> segments.removeLastOrNull()
                    else -> segments.addLast(segment)
                }
            }
            return "/" + segments.joinToString("/")
        }
    }
}
