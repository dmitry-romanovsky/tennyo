package app.tennyo.host.engine.git

import app.tennyo.contract.GitChangeKind
import app.tennyo.contract.GitCommitSummary

/** One changed path: [beforePath] is absent for an added file, [afterPath] for a deleted one. */
internal data class StatusEntry(val kind: GitChangeKind, val beforePath: String?, val afterPath: String?) {
    val path: String get() = afterPath ?: beforePath.orEmpty()
}

/** The working tree as `git status --porcelain=v2 --branch` reports it; [headCommit] is `null` on an unborn branch. */
internal data class StatusSnapshot(
    val branch: String?,
    val headCommit: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val entries: List<StatusEntry>,
)

/** Reads `git status --porcelain=v2 -z --branch` output. */
internal fun parseStatus(output: String): StatusSnapshot {
    var branch: String? = null
    var headCommit: String? = null
    var upstream: String? = null
    var ahead = 0
    var behind = 0
    val entries = mutableListOf<StatusEntry>()
    val tokens = output.split(NUL).iterator()
    while (tokens.hasNext()) {
        val token = tokens.next()
        when {
            token.startsWith("# branch.oid ") ->
                headCommit = token.removePrefix("# branch.oid ").takeUnless { it == "(initial)" }
            token.startsWith("# branch.head ") ->
                branch = token.removePrefix("# branch.head ").takeUnless { it == "(detached)" }
            token.startsWith("# branch.upstream ") -> upstream = token.removePrefix("# branch.upstream ")
            token.startsWith("# branch.ab ") -> token.removePrefix("# branch.ab ").split(' ').let { (a, b) ->
                ahead = a.removePrefix("+").toIntOrNull() ?: 0
                behind = b.removePrefix("-").toIntOrNull() ?: 0
            }
            token.startsWith("1 ") -> {
                val fields = token.split(' ', limit = ORDINARY_FIELDS)
                val path = fields.last()
                entries += when {
                    'A' in fields[1] -> StatusEntry(GitChangeKind.ADDED, null, path)
                    'D' in fields[1] -> StatusEntry(GitChangeKind.DELETED, path, null)
                    else -> StatusEntry(GitChangeKind.MODIFIED, path, path)
                }
            }
            token.startsWith("2 ") -> {
                val fields = token.split(' ', limit = RENAMED_FIELDS)
                val original = if (tokens.hasNext()) tokens.next() else continue
                entries += movedEntry(fields[RENAMED_FIELDS - 2], original, fields.last())
            }
            token.startsWith("u ") -> {
                val path = token.split(' ', limit = UNMERGED_FIELDS).last()
                entries += StatusEntry(GitChangeKind.CONFLICTED, path, path)
            }
            token.startsWith("? ") -> entries += StatusEntry(GitChangeKind.UNTRACKED, null, token.removePrefix("? "))
        }
    }
    return StatusSnapshot(branch, headCommit, upstream, ahead, behind, entries)
}

/** Reads `git diff --numstat -z`: added and deleted lines by the path a change leaves, both `null` for binary files. */
internal fun parseNumstat(output: String): Map<String, Pair<Int?, Int?>> {
    val counts = mutableMapOf<String, Pair<Int?, Int?>>()
    val tokens = output.split(NUL)
    var index = 0
    while (index < tokens.size) {
        val fields = tokens[index++].split('	', limit = NUMSTAT_FIELDS)
        if (fields.size == NUMSTAT_FIELDS) {
            // A rename leaves its path empty and names the old and new paths in the next two tokens.
            val path = fields[2].ifEmpty { tokens.getOrNull(index + 1).also { index += 2 } }
            if (path != null) counts[path] = fields[0].toIntOrNull() to fields[1].toIntOrNull()
        }
    }
    return counts
}

/** Reads `git diff --name-status -z` and `git diff-tree --name-status -z`. */
internal fun parseNameStatus(output: String): List<StatusEntry> {
    val entries = mutableListOf<StatusEntry>()
    val tokens = output.split(NUL).filter(String::isNotEmpty)
    var index = 0
    while (index + 1 < tokens.size) {
        val status = tokens[index]
        val path = tokens[index + 1]
        val copiedOrRenamed = status.first() == 'R' || status.first() == 'C'
        index += if (copiedOrRenamed) PATHS_WITH_SOURCE else PATHS_WITHOUT_SOURCE
        entries += when {
            copiedOrRenamed -> movedEntry(status, path, tokens.getOrNull(index - 1) ?: break)
            status.first() == 'A' -> StatusEntry(GitChangeKind.ADDED, null, path)
            status.first() == 'D' -> StatusEntry(GitChangeKind.DELETED, path, null)
            status.first() == 'U' -> StatusEntry(GitChangeKind.CONFLICTED, path, path)
            else -> StatusEntry(GitChangeKind.MODIFIED, path, path)
        }
    }
    return entries
}

/** Reads `git log` records written with [COMMIT_SUMMARY_FORMAT]. */
internal fun parseCommitSummaries(output: String): List<GitCommitSummary> =
    output.split(RECORD_SEPARATOR).map { it.trimStart('\n') }.filter(String::isNotEmpty).mapNotNull { record ->
        val field = record.split(UNIT_SEPARATOR)
        if (field.size != COMMIT_SUMMARY_FIELDS) return@mapNotNull null
        GitCommitSummary(
            id = field[0],
            shortId = field[1],
            subject = field[2],
            authorName = field[3],
            authoredAtEpochMillis = field[4].epochMillis(),
            committerName = field[5],
            committedAtEpochMillis = field[6].epochMillis(),
            parentIds = field[7].split(' ').filter(String::isNotEmpty),
        )
    }

/** Reads `%(upstream:track,nobracket)` as ahead and behind counts; anything else, such as `gone`, is none. */
internal fun parseTrack(track: String): Pair<Int, Int> {
    val counts = track.split(", ").associate { it.substringBefore(' ') to it.substringAfter(' ').toIntOrNull() }
    return (counts["ahead"] ?: 0) to (counts["behind"] ?: 0)
}

internal fun String.epochMillis(): Long = (trim().toLongOrNull() ?: 0L) * MILLIS_PER_SECOND

/** A copy keeps its source, so the phone sees it as a new file; a rename is a move once the directory changes. */
private fun movedEntry(status: String, beforePath: String, afterPath: String): StatusEntry = when {
    status.startsWith('C') -> StatusEntry(GitChangeKind.ADDED, null, afterPath)
    beforePath.substringBeforeLast('/', "") == afterPath.substringBeforeLast('/', "") ->
        StatusEntry(GitChangeKind.RENAMED, beforePath, afterPath)
    else -> StatusEntry(GitChangeKind.MOVED, beforePath, afterPath)
}

/** The remote URL without user info, query or fragment; `null` for local paths and transports never shown. */
internal fun credentialFreeRemoteUrl(url: String): String? {
    // `<transport>::<address>` hands the address to a remote helper, never something to show.
    val candidate = url.trim().takeIf { it.isNotEmpty() && "::" !in it } ?: return null
    return if ("://" in candidate) credentialFreeUrl(candidate) else credentialFreeScpAddress(candidate)
}

private fun credentialFreeUrl(url: String): String? {
    val scheme = url.substringBefore("://").lowercase()
    val rest = url.substringAfter("://")
    val authority = rest.substringBefore('/').substringAfterLast('@')
    val path = rest.substringAfter('/', missingDelimiterValue = "").substringBefore('?').substringBefore('#')
    return "$scheme://$authority/$path".takeIf { scheme in SAFE_REMOTE_SCHEMES && authority.isNotEmpty() }
}

/** `user@host:path`, as scp and SSH remotes are often written. */
private fun credentialFreeScpAddress(address: String): String? {
    val host = address.substringBefore(':', missingDelimiterValue = "").substringAfterLast('@')
    val path = address.substringAfter(':', missingDelimiterValue = "")
    return "$host:$path".takeIf { host.isNotEmpty() && path.isNotEmpty() && '/' !in host }
}

internal const val COMMIT_SUMMARY_FORMAT = "--format=%H%x1f%h%x1f%s%x1f%an%x1f%at%x1f%cn%x1f%ct%x1f%P%x1e"
internal const val UNIT_SEPARATOR = '\u001f'
private const val RECORD_SEPARATOR = '\u001e'
private const val NUL = '\u0000'
private const val COMMIT_SUMMARY_FIELDS = 8
private const val ORDINARY_FIELDS = 9
private const val RENAMED_FIELDS = 10
private const val UNMERGED_FIELDS = 11
private const val NUMSTAT_FIELDS = 3
private const val PATHS_WITH_SOURCE = 3
private const val PATHS_WITHOUT_SOURCE = 2
private const val MILLIS_PER_SECOND = 1_000L
private val SAFE_REMOTE_SCHEMES = setOf("git", "http", "https", "ssh")
