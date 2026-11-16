package app.tennyo.mobile.feature.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use
import kotlin.coroutines.CoroutineContext

/** One file or directory in a workspace listing; [path] is relative to the workspace root, `/`-separated. */
data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
)

/**
 * The files of every workspace under [workspacesRoot]. Every operation goes through [resolve], so no caller can read
 * or write outside the workspace it names. Each workspace's installed Python packages sit beside, not inside, its
 * checkout, under [packagesRoot].
 */
class WorkspaceFiles(
    private val fileSystem: FileSystem,
    val workspacesRoot: Path,
    private val packagesRoot: Path,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) {
    fun root(slug: String): Path {
        if (!SLUG_PATTERN.matches(slug)) throw WorkspaceFault.PathRejected(slug)
        return workspacesRoot / slug
    }

    /** Where workspace [slug]'s installed packages live. */
    fun packages(slug: String): Path = packagesSibling(slug, "")

    /** Where downloaded wheels wait for the user's second confirmation. A slug has no dot, so this is never a slug. */
    fun packagesStaging(slug: String): Path = packagesSibling(slug, STAGING_SUFFIX)

    /** The consent record of the installed packages, or `null` when none are installed or it cannot be read. */
    suspend fun readPackagesRecord(slug: String): PackagesRecord? = withContext(ioContext) {
        val file = packages(slug) / PACKAGES_RECORD_FILE
        if (!fileSystem.exists(file)) return@withContext null
        try {
            RECORD_JSON.decodeFromString<PackagesRecord>(fileSystem.read(file) { readUtf8() })
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    /** Writes [record] into [stagingDir], from where the installer carries it into the packages directory. */
    suspend fun writePackagesRecord(stagingDir: Path, record: PackagesRecord): Unit = withContext(ioContext) {
        try {
            fileSystem.createDirectories(stagingDir)
            fileSystem.write(stagingDir / PACKAGES_RECORD_FILE) { writeUtf8(RECORD_JSON.encodeToString(record)) }
        } catch (_: IOException) {
            throw WorkspaceFault.Unwritable(PACKAGES_RECORD_FILE)
        }
    }

    /**
     * The file [relativePath] names inside workspace [slug]. Rejects absolute paths, `..` segments, and paths whose
     * existing part resolves (through symlinks) outside the workspace root.
     */
    fun resolve(slug: String, relativePath: String): Path {
        val root = root(slug)
        val segments = relativePath.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        val absolute = relativePath.startsWith('/') || relativePath.startsWith('\\') || ':' in relativePath
        val resolved = segments.fold(root) { path, segment -> path / segment }
        if (absolute || ".." in segments || leavesRoot(root, resolved)) throw WorkspaceFault.PathRejected(relativePath)
        return resolved
    }

    /** Whether the part of [path] that exists resolves, through symlinks, to somewhere outside [root]. */
    private fun leavesRoot(root: Path, path: Path): Boolean {
        val existing = generateSequence(path) { it.parent }
            .takeWhile { it.isWithin(root) }
            .firstOrNull { fileSystem.metadataOrNull(it) != null }
        val canonicalRoot = existing?.let { runCatching { fileSystem.canonicalize(root) }.getOrNull() }
        return canonicalRoot != null &&
                runCatching { !fileSystem.canonicalize(existing).isWithin(canonicalRoot) }.getOrDefault(true)
    }

    /** The entries of [directory], directories first, without Git's own metadata. */
    suspend fun list(slug: String, directory: String): List<WorkspaceEntry> = withContext(ioContext) {
        val path = resolve(slug, directory)
        val children = try {
            fileSystem.list(path)
        } catch (_: IOException) {
            throw WorkspaceFault.Unreadable(directory)
        }
        children.filter { it.name != GIT_DIRECTORY }
            .map { child ->
                WorkspaceEntry(
                    path = child.relativeTo(root(slug)).segments.joinToString("/"),
                    name = child.name,
                    isDirectory = fileSystem.metadataOrNull(child)?.isDirectory == true,
                )
            }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** The text of [relativePath]; a file over [maxBytes] is [WorkspaceFault.Unreadable] rather than read. */
    suspend fun readText(
        slug: String,
        relativePath: String,
        maxBytes: Long = Long.MAX_VALUE,
    ): String = withContext(ioContext) {
        val path = resolve(slug, relativePath)
        val metadata = fileSystem.metadataOrNull(path)
        if (metadata?.isRegularFile != true) throw WorkspaceFault.NotFound(relativePath)
        if ((metadata.size ?: 0L) > maxBytes) throw WorkspaceFault.Unreadable(relativePath)
        try {
            fileSystem.read(path) { readUtf8() }
        } catch (_: IOException) {
            throw WorkspaceFault.Unreadable(relativePath)
        }
    }

    suspend fun writeText(slug: String, relativePath: String, text: String): Unit = withContext(ioContext) {
        val path = resolve(slug, relativePath)
        try {
            path.parent?.let(fileSystem::createDirectories)
            fileSystem.write(path) { writeUtf8(text) }
        } catch (_: IOException) {
            throw WorkspaceFault.Unwritable(relativePath)
        }
    }

    /** Whether [relativePath] looks like text the editor can open: a known extension, or a sample without binary. */
    suspend fun isText(slug: String, relativePath: String): Boolean = withContext(ioContext) {
        val path = resolve(slug, relativePath)
        val extension = path.name.substringAfterLast('.', "").lowercase()
        when {
            extension in TEXT_EXTENSIONS -> true
            extension in BINARY_EXTENSIONS -> false
            else -> {
                val metadata = fileSystem.metadataOrNull(path)
                metadata?.isRegularFile == true && (metadata.size == 0L || looksLikeText(sample(path)))
            }
        }
    }

    /** The files a new or freshly opened workspace shows first: its readme and its entry point. */
    suspend fun initialFiles(slug: String): List<String> = withContext(ioContext) {
        val root = root(slug)
        val files = fileSystem.listRecursively(root)
            .filterNot { GIT_DIRECTORY in it.relativeTo(root).segments }
            .take(MAX_SCANNED_FILES)
            .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true }
            .toList()
        val readme = files.firstOrNull { it.name == "README.md" }
            ?: files.firstOrNull { it.name.startsWith("readme", ignoreCase = true) }
        (listOfNotNull(readme) + files.filter { it.name in ENTRY_FILE_NAMES })
            .map { it.relativeTo(root).segments.joinToString("/") }
    }

    /**
     * Creates an empty file, or a directory when [directory], at [relativePath]. An existing entry is left alone and
     * reported, so a name typed twice never truncates a file.
     */
    suspend fun createEntry(slug: String, relativePath: String, directory: Boolean): Unit = withContext(ioContext) {
        if (relativePath.isBlank()) throw WorkspaceFault.PathRejected(relativePath)
        val path = resolve(slug, relativePath)
        if (path == root(slug)) throw WorkspaceFault.PathRejected(relativePath)
        if (fileSystem.metadataOrNull(path) != null) throw WorkspaceFault.AlreadyExists(relativePath)
        try {
            if (directory) {
                fileSystem.createDirectories(path)
            } else {
                path.parent?.let(fileSystem::createDirectories)
                fileSystem.write(path, mustCreate = true) { }
            }
        } catch (_: IOException) {
            throw WorkspaceFault.Unwritable(relativePath)
        }
    }

    suspend fun create(slug: String): Unit = withContext(ioContext) {
        try {
            fileSystem.createDirectories(root(slug))
        } catch (_: IOException) {
            throw WorkspaceFault.Unwritable(slug)
        }
    }

    suspend fun delete(slug: String): Unit = withContext(ioContext) {
        try {
            listOf(
                root(slug),
                packages(slug),
                packagesStaging(slug),
                packagesSibling(slug, NEW_SUFFIX),
                packagesSibling(slug, OLD_SUFFIX),
            ).forEach { fileSystem.deleteRecursively(it, mustExist = false) }
        } catch (_: IOException) {
            throw WorkspaceFault.Unwritable(slug)
        }
    }

    private fun sample(path: Path): ByteArray = try {
        fileSystem.source(path).buffer().use { source ->
            source.request(SAMPLE_BYTES)
            source.buffer.readByteArray(minOf(SAMPLE_BYTES, source.buffer.size))
        }
    } catch (_: IOException) {
        ByteArray(0)
    }

    private fun packagesSibling(slug: String, suffix: String): Path = packagesRoot / (root(slug).name + suffix)

    private fun Path.isWithin(root: Path): Boolean =
        this == root || (this.root == root.root && segments.size > root.segments.size &&
                segments.subList(0, root.segments.size) == root.segments)
}

/** Text has no NUL byte, no known binary signature, and few control characters. */
internal fun looksLikeText(sample: ByteArray): Boolean {
    if (sample.isEmpty()) return true
    if (BINARY_SIGNATURES.any { signature -> sample.startsWith(signature) } || sample.any { it == 0.toByte() }) {
        return false
    }
    val suspicious = sample.count { byte ->
        val value = byte.toInt() and BYTE_MASK
        value < PRINTABLE_START && value != TAB && value != LINE_FEED && value != CARRIAGE_RETURN
    }
    return suspicious.toDouble() / sample.size < MAX_CONTROL_RATIO
}

private fun ByteArray.startsWith(signature: IntArray): Boolean =
    size >= signature.size && signature.indices.all { (this[it].toInt() and BYTE_MASK) == signature[it] }

private val SLUG_PATTERN = Regex("[a-z0-9][a-z0-9-]*")
private const val GIT_DIRECTORY = ".git"
private const val STAGING_SUFFIX = ".staging"

// The installer builds `<packages>.new`, then swaps it in and drops `<packages>.old`; a crash can leave either behind.
private const val NEW_SUFFIX = ".new"
private const val OLD_SUFFIX = ".old"
private val RECORD_JSON = Json { ignoreUnknownKeys = true }
private const val MAX_SCANNED_FILES = 2_000

/** The file a new project opens on, next to its readme. */
private val ENTRY_FILE_NAMES = setOf("Main.kt", "main.py", "__main__.py")
private const val SAMPLE_BYTES = 4_096L
private const val BYTE_MASK = 0xFF
private const val PRINTABLE_START = 0x20
private const val TAB = 0x09
private const val LINE_FEED = 0x0A
private const val CARRIAGE_RETURN = 0x0D
private const val MAX_CONTROL_RATIO = 0.30

private val TEXT_EXTENSIONS = setOf(
    "kt", "kts", "java", "groovy", "gradle", "properties", "sql",
    "json", "json5", "xml", "js", "ts", "jsx", "tsx", "css", "scss", "html",
    "sh", "bash", "zsh", "ps1", "bat", "cmd",
    "md", "txt", "yaml", "yml", "toml", "ini", "cfg", "conf", "env",
    "c", "h", "cpp", "hpp", "cc", "cxx", "m", "mm", "swift", "rs", "go", "py", "rb",
    "gitignore", "gitattributes", "editorconfig", "pro", "cmake", "mk", "dockerfile",
)

private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
    "mp3", "wav", "ogg", "mp4", "mkv", "avi", "mov",
    "zip", "rar", "7z", "gz", "tar",
    "apk", "jar", "aar", "dex", "class", "so", "dll", "exe", "bin",
    "ttf", "otf", "woff", "woff2",
    "pdf",
)

@Suppress("MagicNumber")
private val BINARY_SIGNATURES = listOf(
    intArrayOf(0x89, 0x50, 0x4E, 0x47), // PNG
    intArrayOf(0xFF, 0xD8, 0xFF), // JPEG
    intArrayOf(0x47, 0x49, 0x46, 0x38), // GIF
    intArrayOf(0x25, 0x50, 0x44, 0x46), // PDF
    intArrayOf(0x50, 0x4B, 0x03, 0x04), // ZIP
    intArrayOf(0x7F, 0x45, 0x4C, 0x46), // ELF
    intArrayOf(0x4D, 0x5A), // Windows executable
    intArrayOf(0xCA, 0xFE, 0xBA, 0xBE), // Java class
)
