package app.tennyo.host.engine.panes

import app.tennyo.host.engine.platform.NodeFs
import app.tennyo.host.engine.platform.NodePath
import app.tennyo.host.engine.platform.asByteArray
import app.tennyo.host.engine.platform.jsOptions

/**
 * Project files on Node, for every host whose editor is not the JVM: the VS Code family and the Zed sidecar.
 *
 * Paths cross [IdeFilesPort] absolute, normalised and `/`-separated, which is what `path.resolve` produces
 * everywhere except Windows, where the separators are turned around. A host that also has open, unsaved buffers
 * layers them over this rather than replacing it: the disk is the same on both sides of that overlay.
 */
class NodeFilesPort(private val projectRoot: String?) : IdeFilesPort {
    override fun rootPath(): String? = projectRoot?.let(::realPath)

    override fun realPath(path: String): String? =
        runCatching { NodeFs.realpathSync(path) }.getOrNull()?.asLinkPath()

    override fun resolve(base: String, relative: String): String = NodePath.resolve(base, relative).asLinkPath()

    override fun isDirectory(path: String): Boolean =
        runCatching { NodeFs.statSync(path).isDirectory() }.getOrDefault(false)

    override fun forEachChild(directory: String, visit: (String) -> Boolean) {
        val names = NodeFs.readdirSync(directory)
        for (name in names) {
            if (!visit(resolve(directory, name))) break
        }
    }

    override fun read(path: String, byteLimit: Int): FileSnapshot {
        if (isDirectory(path)) return unavailable(DIRECTORY_REASON)
        val size = runCatching { NodeFs.statSync(path).size.toInt() }.getOrNull()
            ?: return unavailable(UNREADABLE_REASON)
        if (size > byteLimit) return unavailable(OVERSIZED_REASON)
        val bytes = runCatching { NodeFs.readFileSync(path).asByteArray() }.getOrNull()
            ?: return unavailable(UNREADABLE_REASON)
        if (bytes.looksBinary()) return unavailable(BINARY_REASON)
        return FileSnapshot(
            text = bytes.decodeToString(),
            revision = revisionOf(path),
            writable = isWritable(path),
        )
    }

    override fun write(path: String, text: String, byteLimit: Int): FileSnapshot {
        if (isDirectory(path)) return unavailable(DIRECTORY_REASON)
        val written = runCatching { NodeFs.writeFileSync(path, text, jsOptions("encoding" to ENCODING)) }
        if (written.isFailure) return unavailable(UNWRITABLE_REASON)
        return read(path, byteLimit)
    }

    /** The modification time, which is what changes whenever the file does. */
    private fun revisionOf(path: String): String? =
        runCatching { NodeFs.statSync(path).mtimeMs.toString() }.getOrNull()

    private fun isWritable(path: String): Boolean =
        runCatching { NodeFs.accessSync(path, NodeFs.constants.writeOk) }.isSuccess

    private fun unavailable(reason: String): FileSnapshot =
        FileSnapshot(text = "", revision = null, writable = false, viewable = false, unavailableReason = reason)

    private companion object {
        const val ENCODING = "utf8"
        const val DIRECTORY_REASON = "Directories can't be shown in the mobile editor."
        const val BINARY_REASON = "This file is binary and can't be shown on mobile."
        const val OVERSIZED_REASON = "This file exceeds the mobile viewing limit."
        const val UNREADABLE_REASON = "This content can't be shown on mobile."
        const val UNWRITABLE_REASON = "This file can't be written from mobile."
    }
}

/**
 * Whether the bytes are something the mobile editor cannot show. IntelliJ asks its file-type registry; Node has no
 * such registry, so this asks the content: a NUL byte, or bytes that are not valid UTF-8, means not text. The whole
 * file is checked rather than a prefix, which the read limit already keeps small.
 *
 * As a content heuristic it calls a UTF-16 or latin-1 file binary, and a text file that really contains U+FFFD with
 * it. Both are the safe direction to be wrong in.
 */
private fun ByteArray.looksBinary(): Boolean =
    any { it == 0.toByte() } || REPLACEMENT_CHARACTER in decodeToString()

private const val REPLACEMENT_CHARACTER = '�'
