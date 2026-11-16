package app.tennyo.host.engine.rpc

import app.tennyo.contract.FileContent
import app.tennyo.contract.FileNode
import app.tennyo.contract.FileTreeResponse
import app.tennyo.contract.LinkLimits
import app.tennyo.host.engine.panes.FileSnapshot
import app.tennyo.host.engine.panes.IdeFilesPort

private const val MAX_TREE_DEPTH = 4
private const val MAX_CHILDREN_PER_DIRECTORY = 80
private const val MAX_DIRECTORY_ENTRIES_PER_TREE = 10_000

/**
 * Resolves a client path to an existing file or directory inside the project, or `null`. The path is joined onto the
 * resolved [root] and must stay inside it both before and after symbolic links are resolved, so neither `..` nor a
 * link pointing out of the project can reach another file.
 */
fun IdeFilesPort.resolveExistingProjectPath(root: String, pathParameter: String?): String? {
    val realRoot = realPath(root) ?: return null
    val candidate = resolve(realRoot, pathParameter.orEmpty())
    if (!candidate.isWithinProjectPath(realRoot)) return null
    return realPath(candidate)?.takeIf { it.isWithinProjectPath(realRoot) }
}

/** [resolveExistingProjectPath] against the project's own root. */
fun IdeFilesPort.resolveExistingProjectPath(pathParameter: String?): String? =
    rootPath()?.let { root -> resolveExistingProjectPath(root, pathParameter) }

/** [path] relative to [root] with `/` separators, `""` for the root itself, or `null` outside it. */
fun projectRelativePath(root: String, path: String): String? =
    when {
        path == root -> ""
        path.isWithinProjectPath(root) -> path.removePrefix(root.withTrailingSlash())
        else -> null
    }

/** The last segment of a `/`-separated path. */
fun String.projectPathName(): String = substringAfterLast('/')

private fun String.isWithinProjectPath(root: String): Boolean = this == root || startsWith(root.withTrailingSlash())

private fun String.withTrailingSlash(): String = if (endsWith('/')) this else "$this/"

fun IdeFilesPort.fileTree(projectName: String, pathParameter: String?): FileTreeResponse {
    val root = rootPath()
        ?: return FileTreeResponse(root = FileNode(path = "", name = projectName, isDirectory = true))
    val target = resolveExistingProjectPath(root, pathParameter) ?: root
    return FileTreeResponse(root = fileNode(root, target, 0, DirectoryEnumerationBudget()))
}

/** The file at the project-relative [requestedPath] for the mobile editor, or `null` when no such file exists. */
fun IdeFilesPort.readFileContent(requestedPath: String): FileContent? =
    withExistingFile(requestedPath) { root, path ->
        read(path, LinkLimits.MAX_FILE_TEXT_BYTES).toFileContent(root, path)
    }

/** Writes [text] to the existing file at [requestedPath] and describes the result, or `null` when there is none. */
fun IdeFilesPort.writeFileContent(requestedPath: String, text: String): FileContent? =
    withExistingFile(requestedPath) { root, path ->
        write(path, text, LinkLimits.MAX_FILE_TEXT_BYTES).toFileContent(root, path)
    }

private inline fun IdeFilesPort.withExistingFile(
    requestedPath: String,
    use: (root: String, path: String) -> FileContent,
): FileContent? {
    val root = rootPath() ?: return null
    val path = resolveExistingProjectPath(root, requestedPath)?.takeUnless { isDirectory(it) } ?: return null
    return use(root, path)
}

fun FileSnapshot.toFileContent(root: String, path: String): FileContent =
    FileContent(
        path = projectRelativePath(root, path).orEmpty(),
        name = path.projectPathName(),
        text = text,
        revision = revision,
        writable = writable,
        viewable = viewable,
        unavailableReason = unavailableReason,
    )

private fun IdeFilesPort.fileNode(
    root: String,
    path: String,
    depth: Int,
    budget: DirectoryEnumerationBudget,
    displayName: String? = null,
): FileNode {
    val isDirectory = isDirectory(path)
    val relative = projectRelativePath(root, path).orEmpty()
    val listedChildren = if (isDirectory && depth < MAX_TREE_DEPTH) {
        listProjectChildren(root, path, budget)
    } else {
        DirectoryChildren()
    }
    val children = if (isDirectory && depth < MAX_TREE_DEPTH) {
        listedChildren.paths
            .map { child ->
                val compactNode = compactPackageDirectory(root, path, child, budget)
                fileNode(root, compactNode.path, depth + 1, budget, compactNode.displayName)
            }
    } else {
        emptyList()
    }
    return FileNode(
        path = relative,
        name = displayName ?: path.projectPathName(),
        isDirectory = isDirectory,
        children = children,
        childrenTruncated = listedChildren.truncated,
    )
}

/**
 * Folds a single-child chain of package directories under a `java` or `kotlin` source root into one node named like
 * the package, the way the IDE's project view does.
 */
private fun IdeFilesPort.compactPackageDirectory(
    root: String,
    parent: String,
    child: String,
    budget: DirectoryEnumerationBudget,
): CompactFileNode {
    if (!isDirectory(child) ||
        !parent.projectPathName().isSourcePackageRootName() ||
        !child.projectPathName().isPackageSegment()
    ) {
        return CompactFileNode(child)
    }
    val segments = mutableListOf(child.projectPathName())
    var current = child
    while (true) {
        val next = soleChildPackage(root, current, budget) ?: break
        segments += next.projectPathName()
        current = next
    }
    return if (segments.size > 1) {
        CompactFileNode(current, segments.joinToString("."))
    } else {
        CompactFileNode(child)
    }
}

/** The one package directory [directory] contains, when that is all it contains and the tree still has budget. */
private fun IdeFilesPort.soleChildPackage(
    root: String,
    directory: String,
    budget: DirectoryEnumerationBudget,
): String? {
    val children = listProjectChildren(root, directory, budget)
    if (children.truncated || children.paths.size != 1) return null
    val next = children.paths.single().takeIf { isDirectory(it) } ?: return null
    return next.takeIf { it.projectPathName().isPackageSegment() }
}

/**
 * Lists a directory for the tree: every entry is confined to the project like a client path, at most
 * [MAX_CHILDREN_PER_DIRECTORY] are kept, and the whole tree inspects at most [MAX_DIRECTORY_ENTRIES_PER_TREE].
 */
private fun IdeFilesPort.listProjectChildren(
    root: String,
    directory: String,
    budget: DirectoryEnumerationBudget,
): DirectoryChildren {
    val children = ArrayList<String>(MAX_CHILDREN_PER_DIRECTORY)
    var truncated = false
    forEachChild(directory) { child ->
        if (!budget.tryInspect()) {
            truncated = true
            return@forEachChild false
        }
        val resolved = projectRelativePath(root, child)?.let { resolveExistingProjectPath(root, it) }
        if (resolved != null) {
            if (children.size < MAX_CHILDREN_PER_DIRECTORY) {
                children.add(resolved)
            } else {
                truncated = true
            }
        }
        true
    }
    return DirectoryChildren(
        paths = children
            .sortedBy { it.projectPathName().lowercase() }
            .take(MAX_CHILDREN_PER_DIRECTORY),
        truncated = truncated,
    )
}

private fun String.isSourcePackageRootName(): Boolean = this == "java" || this == "kotlin"

private fun String.isPackageSegment(): Boolean =
    isNotBlank() && first().let { it == '_' || it.isLetter() } && all { it == '_' || it.isLetterOrDigit() }

private data class CompactFileNode(
    val path: String,
    val displayName: String? = null,
)

private data class DirectoryChildren(
    val paths: List<String> = emptyList(),
    val truncated: Boolean = false,
)

private class DirectoryEnumerationBudget(
    private var remainingEntries: Int = MAX_DIRECTORY_ENTRIES_PER_TREE,
) {
    fun tryInspect(): Boolean = remainingEntries-- > 0
}

/** Whether the text's UTF-8 encoding exceeds [limit] bytes, stopping as soon as it does. */
fun String.utf8SizeExceeds(limit: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < length) {
        val char = this[index]
        val isPair = char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()
        bytes += when {
            isPair -> UTF8_SUPPLEMENTARY_BYTES
            char.code <= UTF8_ONE_BYTE_MAX -> 1
            char.code <= UTF8_TWO_BYTE_MAX -> 2
            else -> UTF8_BMP_MAX_BYTES
        }
        if (bytes > limit) return true
        index += if (isPair) 2 else 1
    }
    return false
}

private const val UTF8_ONE_BYTE_MAX = 0x7f
private const val UTF8_TWO_BYTE_MAX = 0x7ff
private const val UTF8_BMP_MAX_BYTES = 3
private const val UTF8_SUPPLEMENTARY_BYTES = 4
