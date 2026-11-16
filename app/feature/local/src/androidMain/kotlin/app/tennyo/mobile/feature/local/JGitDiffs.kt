package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitChangeKind
import app.tennyo.mobile.feature.git.GitDiff
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.io.DisabledOutputStream

/*
 * Diffs of a workspace repository, from JGit's DiffFormatter: the entries between two trees (renames detected) with
 * the edits of their hunks, and the text of both sides for the Git pane, capped per side.
 */

/** The working tree against `HEAD`, limited to the paths [status] reports as changed. */
internal fun scanWorkingTree(
    repository: Repository,
    status: Status = Git(repository).status().call(),
): List<ScannedChange> {
    val changed = status.uncommittedChanges + status.untracked
    if (changed.isEmpty()) return emptyList()
    return scan(repository, headTreeIterator(repository), FileTreeIterator(repository)) {
        pathFilter = PathFilterGroup.createFromStrings(changed)
    }
}

internal fun scanCommit(repository: Repository, walk: RevWalk, commit: RevCommit): List<ScannedChange> {
    val parent = commit.parents.firstOrNull()?.let(walk::parseCommit)
    val parentTree = parent?.let { treeIterator(repository, it) } ?: EmptyTreeIterator()
    return scan(repository, parentTree, treeIterator(repository, commit))
}

/** Diff entries between two trees with renames detected, each with the edits of its hunks. */
private fun scan(
    repository: Repository,
    before: AbstractTreeIterator,
    after: AbstractTreeIterator,
    configure: DiffFormatter.() -> Unit = {},
): List<ScannedChange> = DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
    formatter.setRepository(repository)
    formatter.setContext(DIFF_CONTEXT_LINES)
    formatter.setDetectRenames(true)
    formatter.configure()
    formatter.scan(before, after).map { entry ->
        val header = formatter.toFileHeader(entry)
        ScannedChange(entry, header.toEditList().takeIf { header.patchType == FileHeader.PatchType.UNIFIED })
    }
}

internal fun textDiff(
    repositoryId: String,
    changeId: String,
    scanned: ScannedChange,
    before: ByteArray?,
    after: ByteArray?,
): GitDiff {
    val base = GitDiff(
        repositoryId = repositoryId,
        changeId = changeId,
        beforePath = scanned.beforePath,
        afterPath = scanned.afterPath,
    )
    val sides = listOfNotNull(before, after)
    return when {
        sides.any(RawText::isBinary) -> base.copy(isBinary = true)
        sides.any { RawText(it).size() > MAX_DIFF_LINES } -> base.copy(tooLarge = true)
        else -> base.copy(
            beforeContent = before?.decodeToString(),
            afterContent = after?.decodeToString(),
            addedLines = scanned.addedLines,
            deletedLines = scanned.deletedLines,
        )
    }
}

/** A diff entry and the edits of its hunks; [edits] is `null` for binary content. */
internal data class ScannedChange(val entry: DiffEntry, val edits: List<Edit>?) {
    val beforePath: String? get() = entry.oldPath.takeUnless { entry.changeType == DiffEntry.ChangeType.ADD }
    val afterPath: String? get() = entry.newPath.takeUnless { entry.changeType == DiffEntry.ChangeType.DELETE }
    val addedLines: Int? get() = edits?.sumOf(Edit::getLengthB)
    val deletedLines: Int? get() = edits?.sumOf(Edit::getLengthA)
}

internal val DiffEntry.fileId: String
    get() = if (changeType == DiffEntry.ChangeType.DELETE) oldPath else newPath

internal fun DiffEntry.ChangeType.toKind(): GitChangeKind = when (this) {
    DiffEntry.ChangeType.ADD -> GitChangeKind.ADDED
    DiffEntry.ChangeType.MODIFY -> GitChangeKind.MODIFIED
    DiffEntry.ChangeType.DELETE -> GitChangeKind.DELETED
    DiffEntry.ChangeType.RENAME -> GitChangeKind.RENAMED
    DiffEntry.ChangeType.COPY -> GitChangeKind.ADDED
}

internal fun headTree(repository: Repository): ObjectId? = repository.resolve("${Constants.HEAD}^{tree}")

private fun headTreeIterator(repository: Repository): AbstractTreeIterator =
    headTree(repository)?.let { tree ->
        repository.newObjectReader().use { reader -> CanonicalTreeParser(null, reader, tree) }
    } ?: EmptyTreeIterator()

internal fun treeIterator(repository: Repository, commit: RevCommit): AbstractTreeIterator =
    repository.newObjectReader().use { reader -> CanonicalTreeParser(null, reader, commit.tree) }

internal fun blobBytes(repository: Repository, tree: ObjectId, path: String): ByteArray? =
    TreeWalk.forPath(repository, path, tree)?.use { walk ->
        repository.open(walk.getObjectId(0)).bytes
    }

internal fun AbbreviatedObjectId.bytes(repository: Repository): ByteArray? =
    toObjectId().takeUnless { it == ObjectId.zeroId() }?.let { repository.open(it).bytes }

private const val DIFF_CONTEXT_LINES = 3

/** The most lines per side a diff is shown for; larger files report [GitDiff.tooLarge]. */
private const val MAX_DIFF_LINES = 2_000
