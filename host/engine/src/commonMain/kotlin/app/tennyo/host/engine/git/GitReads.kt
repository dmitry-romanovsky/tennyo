package app.tennyo.host.engine.git

import app.tennyo.contract.GIT_MAX_COMMIT_MESSAGE_LENGTH
import app.tennyo.contract.GIT_MAX_SELECTED_CHANGES
import app.tennyo.contract.GitAction
import app.tennyo.contract.GitBranch
import app.tennyo.contract.GitBranchesResponse
import app.tennyo.contract.GitChange
import app.tennyo.contract.GitChangeKind
import app.tennyo.contract.GitChangeList
import app.tennyo.contract.GitCommitCapability
import app.tennyo.contract.GitCommitDetail
import app.tennyo.contract.GitCommitDetailResponse
import app.tennyo.contract.GitCommitFile
import app.tennyo.contract.GitCommitRef
import app.tennyo.contract.GitDiffResponse
import app.tennyo.contract.GitHistoryResponse
import app.tennyo.contract.GitProjectInfoResponse
import app.tennyo.contract.GitPushPreviewResponse
import app.tennyo.contract.GitRefKind
import app.tennyo.contract.GitRemote
import app.tennyo.contract.GitRepositorySummary
import app.tennyo.contract.GitStatusResponse
import app.tennyo.host.engine.panes.GitCommitDetailResult
import app.tennyo.host.engine.panes.GitHistoryResult
import app.tennyo.host.engine.panes.gitHistoryCursor
import app.tennyo.host.engine.panes.gitStableId
import app.tennyo.host.engine.panes.isGitObjectId
import app.tennyo.host.engine.panes.resolveCountRepositoryId

/** Status, diffs, history, branches, push previews and project details, read with Git commands that change nothing. */
internal class GitReads(private val git: GitCommandLine) {
    suspend fun status(selectedRepositoryId: String?, revision: Long): GitStatusResponse? {
        val repositories = git.repositories()
        if (selectedRepositoryId != null && repositories.none { it.id == selectedRepositoryId }) return null
        return GitStatusResponse(
            repositories = repositories.map { summary(it) },
            selectedRepositoryId = resolveCountRepositoryId(
                selectedRepositoryId,
                repositories.map(GitRepo::id),
            ),
            revision = revision,
        )
    }

    /** The working tree of [repo] against `HEAD`, or `null` when Git could not say. */
    suspend fun snapshot(repo: GitRepo): StatusSnapshot? =
        git.run(repo, "status", "--porcelain=v2", "-z", "--branch", "--untracked-files=all")
            .takeIf(GitProcessResult::succeeded)
            ?.let { parseStatus(it.text) }
            ?.let { status -> status.copy(entries = status.entries.sortedBy(StatusEntry::path)) }

    /** The changes [changeIds] name, in that order, or `null` when any is gone. */
    suspend fun resolveChanges(repo: GitRepo, changeIds: List<String>): List<StatusEntry>? {
        val byId = snapshot(repo)?.entries?.associateBy { changeId(repo, it) } ?: return null
        return changeIds.map { id -> byId[id] ?: return null }
    }

    private suspend fun summary(repo: GitRepo): GitRepositorySummary {
        val status = snapshot(repo) ?: return GitRepositorySummary(repo.id, repo.name, repo.projectPath)
        val counts = if (status.headCommit == null) {
            emptyMap()
        } else {
            git.run(repo, "diff", "HEAD", "--numstat", "-z", "-M").takeIf(GitProcessResult::succeeded)
                ?.let { parseNumstat(it.text) }.orEmpty()
        }
        val changes = status.entries.take(MAX_CHANGES_PER_REPOSITORY).map { entry ->
            GitChange(
                id = changeId(repo, entry),
                kind = entry.kind,
                beforePath = entry.beforePath,
                afterPath = entry.afterPath,
                fileName = entry.path.substringAfterLast('/'),
                parentPath = entry.path.substringBeforeLast('/', ""),
                addedLines = counts[entry.path]?.first,
                deletedLines = counts[entry.path]?.second,
                availableActions = setOf(GitAction.VIEW_DIFF, GitAction.ROLLBACK),
            )
        }
        return GitRepositorySummary(
            id = repo.id,
            rootName = repo.name,
            projectPath = repo.projectPath,
            currentBranch = status.branch,
            upstreamBranch = status.upstream,
            aheadCount = status.ahead,
            behindCount = status.behind,
            isDetachedHead = status.branch == null && status.headCommit != null,
            availableActions = buildSet {
                addAll(listOf(GitAction.REFRESH, GitAction.VIEW_HISTORY, GitAction.VIEW_BRANCHES))
                if (status.branch != null && status.upstream != null) add(GitAction.PUSH)
            },
            changeLists = listOf(GitChangeList(gitStableId(repo.id, "changes"), CHANGE_LIST_NAME, changes))
                .filter { it.changes.isNotEmpty() },
            changesTruncated = status.entries.size > MAX_CHANGES_PER_REPOSITORY,
            commitCapability = GitCommitCapability(GIT_MAX_SELECTED_CHANGES, GIT_MAX_COMMIT_MESSAGE_LENGTH),
        )
    }

    suspend fun diff(repositoryId: String, changeId: String): GitDiffResponse? {
        val repo = git.repository(repositoryId) ?: return null
        val status = snapshot(repo) ?: return null
        val entry = status.entries.firstOrNull { changeId(repo, it) == changeId } ?: return null
        val before = entry.beforePath
            ?.takeIf { status.headCommit != null && entry.kind != GitChangeKind.ADDED }
            ?.let { blob(repo, "HEAD", it) }
        val after = entry.afterPath?.let { git.readWorkingFile(repo, it) }
        return diffResponse(repositoryId, changeId, entry, status.headCommit, null, before, after)
    }

    suspend fun history(repositoryId: String, revision: Long, cursor: Int, limit: Int): GitHistoryResult {
        val repo = git.repository(repositoryId) ?: return GitHistoryResult.MissingRepository
        if (!hasCommits(repo)) return GitHistoryResult.Success(GitHistoryResponse(repositoryId, revision, emptyList()))
        val log = git.run(repo, "log", "--skip=$cursor", "-n${limit + 1}", COMMIT_SUMMARY_FORMAT, "HEAD", "--")
        if (!log.succeeded) return GitHistoryResult.PlatformFailure
        val commits = parseCommitSummaries(log.text)
        val refs = refs(repo)
        val page = commits.take(limit).map { it.copy(refs = refs.byCommit[it.id].orEmpty()) }
        return GitHistoryResult.Success(
            GitHistoryResponse(
                repositoryId = repositoryId,
                revision = revision,
                commits = page,
                refsTruncated = refs.truncated,
                nextCursor = (cursor + page.size).takeIf { commits.size > limit }
                    ?.let { gitHistoryCursor(repositoryId, revision, it) },
            ),
        )
    }

    suspend fun commitDetail(repositoryId: String, revision: Long, commitId: String): GitCommitDetailResult {
        val repo = git.repository(repositoryId) ?: return GitCommitDetailResult.MissingTarget
        if (!commitId.isGitObjectId()) return GitCommitDetailResult.MissingTarget
        val shown = git.run(repo, "show", "-s", "--format=$COMMIT_DETAIL_FORMAT", commitId, "--")
        if (!shown.succeeded) return GitCommitDetailResult.MissingTarget
        val field = shown.text.split(UNIT_SEPARATOR, limit = COMMIT_DETAIL_FIELDS)
        val parents = field[8].split(' ').filter(String::isNotEmpty)
        val files = commitFiles(repo, commitId, parents) ?: return GitCommitDetailResult.PlatformFailure
        val refs = refs(repo)
        return GitCommitDetailResult.Success(
            GitCommitDetailResponse(
                repositoryId = repositoryId,
                revision = revision,
                commit = GitCommitDetail(
                    id = field[0],
                    shortId = field[1],
                    subject = field[9].lineSequence().first(),
                    fullMessage = field[9].trimEnd(),
                    authorName = field[2],
                    authorEmail = field[3].takeIf(String::isNotBlank),
                    authoredAtEpochMillis = field[4].epochMillis(),
                    committerName = field[5],
                    committerEmail = field[6].takeIf(String::isNotBlank),
                    committedAtEpochMillis = field[7].epochMillis(),
                    parentIds = parents,
                    refs = refs.byCommit[field[0]].orEmpty(),
                ),
                files = files.take(MAX_COMMIT_FILES).map { entry ->
                    GitCommitFile(commitFileId(repo, commitId, entry), entry.kind, entry.beforePath, entry.afterPath)
                },
                filesTruncated = files.size > MAX_COMMIT_FILES,
            ),
        )
    }

    suspend fun commitDiff(repositoryId: String, commitId: String, fileId: String): GitDiffResponse? {
        val repo = git.repository(repositoryId) ?: return null
        if (!commitId.isGitObjectId()) return null
        val parent = git.run(repo, "rev-parse", "--verify", "-q", "$commitId^1").takeIf(GitProcessResult::succeeded)
            ?.text?.trim()
        val entry = commitFiles(repo, commitId, listOfNotNull(parent))
            ?.firstOrNull { commitFileId(repo, commitId, it) == fileId } ?: return null
        val before = entry.beforePath?.let { path -> parent?.let { blob(repo, it, path) } }
        val after = entry.afterPath?.let { blob(repo, commitId, it) }
        return diffResponse(repositoryId, fileId, entry, parent, commitId, before, after)
    }

    suspend fun branches(repositoryId: String, revision: Long): GitBranchesResponse? {
        val repo = git.repository(repositoryId) ?: return null
        val listed = git.run(
            repo,
            "for-each-ref",
            "--format=%(refname)%1f%(upstream:short)%1f%(HEAD)%1f%(symref)%1f%(upstream:track,nobracket)",
            "refs/heads",
            "refs/remotes",
        ).takeIf(GitProcessResult::succeeded) ?: return null
        val refs = listed.text.lines().filter(String::isNotBlank).map { it.split(UNIT_SEPARATOR) }
            .filter { it[3].isEmpty() }
        val local = refs.filter { it[0].startsWith(LOCAL_PREFIX) }.map { ref ->
            val name = ref[0].removePrefix(LOCAL_PREFIX)
            val upstream = ref[1].takeIf(String::isNotEmpty)
            val (ahead, behind) = parseTrack(ref[4])
            GitBranch(
                id = gitStableId(repositoryId, "local", name),
                fullName = name,
                displayName = name,
                isCurrent = ref[2] == "*",
                trackedUpstream = upstream,
                remoteName = upstream?.substringBefore('/'),
                upstreamId = upstream?.let { gitStableId(repositoryId, "remote", it) },
                aheadCount = upstream?.let { ahead },
                behindCount = upstream?.let { behind },
                availableActions = if (ref[2] == "*") emptySet() else setOf(GitAction.CHECKOUT, GitAction.DELETE),
            )
        }
        val remote = refs.filter { it[0].startsWith(REMOTE_PREFIX) }.map { ref ->
            val name = ref[0].removePrefix(REMOTE_PREFIX)
            GitBranch(
                id = gitStableId(repositoryId, "remote", name),
                fullName = name,
                displayName = name.substringAfter('/'),
                remoteName = name.substringBefore('/'),
            )
        }
        return GitBranchesResponse(
            repositoryId = repositoryId,
            revision = revision,
            isDetachedHead = local.none(GitBranch::isCurrent) && hasCommits(repo),
            localBranches = local.take(MAX_BRANCHES_PER_KIND),
            remoteBranches = remote.take(MAX_BRANCHES_PER_KIND),
            localBranchesTruncated = local.size > MAX_BRANCHES_PER_KIND,
            remoteBranchesTruncated = remote.size > MAX_BRANCHES_PER_KIND,
        )
    }

    suspend fun pushPreview(repositoryId: String, revision: Long): GitPushPreviewResponse? {
        val repo = git.repository(repositoryId) ?: return null
        val target = pushTarget(repo, revision) ?: return null
        val log = git.run(
            repo, "log", "-n${MAX_PUSH_PREVIEW_COMMITS + 1}", COMMIT_SUMMARY_FORMAT, "@{upstream}..HEAD", "--",
        ).takeIf(GitProcessResult::succeeded) ?: return null
        val commits = parseCommitSummaries(log.text)
        return GitPushPreviewResponse(
            repositoryId = repositoryId,
            revision = revision,
            pushTargetId = target.id,
            localBranch = target.localBranch,
            upstreamBranch = target.upstream,
            remoteName = target.remote,
            remoteDisplayUrl = target.remoteDisplayUrl,
            commits = commits.take(MAX_PUSH_PREVIEW_COMMITS),
            commitsTruncated = commits.size > MAX_PUSH_PREVIEW_COMMITS,
        )
    }

    /** Where a push of the current branch goes; `null` when it is detached, untracked, or its remote has no URL. */
    suspend fun pushTarget(repo: GitRepo, revision: Long): PushTarget? {
        val branch = git.run(repo, "symbolic-ref", "-q", "--short", "HEAD").takeIf(GitProcessResult::succeeded)
            ?.text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val tracking = git.run(
            repo,
            "for-each-ref",
            "--format=%(upstream:short)%1f%(upstream:remotename)%1f%(upstream:remoteref)",
            "refs/heads/$branch",
        ).takeIf(GitProcessResult::succeeded)?.text?.trimEnd('\n')?.split(UNIT_SEPARATOR) ?: return null
        val (upstream, remote, remoteRef) = tracking
            .takeIf { it.size == TRACKING_FIELDS && it.all(String::isNotEmpty) } ?: return null
        val url = git.run(repo, "remote", "get-url", "--push", remote).takeIf(GitProcessResult::succeeded)
            ?.text?.trim()?.let(::credentialFreeRemoteUrl) ?: return null
        return PushTarget(
            id = gitStableId(repo.id, branch, upstream, revision.toString()),
            localBranch = branch,
            upstream = upstream,
            remote = remote,
            remoteRef = remoteRef,
            remoteDisplayUrl = url,
        )
    }

    suspend fun projectInfo(repositoryId: String): GitProjectInfoResponse? {
        val repo = git.repository(repositoryId) ?: return null
        val branch = git.run(repo, "symbolic-ref", "-q", "--short", "HEAD").takeIf(GitProcessResult::succeeded)
            ?.text?.trim()
        val head = git.run(repo, "log", "-1", COMMIT_SUMMARY_FORMAT, "--").takeIf(GitProcessResult::succeeded)
            ?.let { parseCommitSummaries(it.text).firstOrNull() }
        return GitProjectInfoResponse(
            repositoryId = repositoryId,
            location = repo.root,
            branch = branch,
            head = head,
            identityName = config(repo, "user.name"),
            identityEmail = config(repo, "user.email"),
            remotes = remotes(repo),
            stashCount = git.run(repo, "stash", "list").lineCount(),
            tagCount = git.run(repo, "for-each-ref", "--format=x", "refs/tags").lineCount(),
        )
    }

    /** The remotes of [repo] with credential-free URLs, in name order. */
    suspend fun remotes(repo: GitRepo): List<GitRemote> =
        git.run(repo, "config", "--get-regexp", "^remote\\..*\\.url$").takeIf(GitProcessResult::succeeded)
            ?.text?.lines()?.filter(String::isNotBlank)
            ?.map { line ->
                val name = line.substringBefore(' ').removePrefix("remote.").removeSuffix(".url")
                val url = line.substringAfter(' ')
                GitRemote(name, credentialFreeRemoteUrl(url) ?: url.takeIf { "://" !in it }.orEmpty())
            }
            ?.sortedBy(GitRemote::name)
            .orEmpty()

    suspend fun hasCommits(repo: GitRepo): Boolean =
        git.run(repo, "rev-parse", "--verify", "-q", "HEAD").succeeded

    private suspend fun config(repo: GitRepo, key: String): String? =
        git.run(repo, "config", "--get", key).takeIf(GitProcessResult::succeeded)?.text?.trim()?.ifEmpty { null }

    private suspend fun commitFiles(repo: GitRepo, commitId: String, parents: List<String>): List<StatusEntry>? {
        val changed = if (parents.isEmpty()) {
            git.run(repo, "diff-tree", "-r", "-M", "--root", "--no-commit-id", "--name-status", "-z", commitId, "--")
        } else {
            git.run(repo, "diff", "-M", "--name-status", "-z", parents.first(), commitId, "--")
        }
        return changed.takeIf(GitProcessResult::succeeded)?.let { parseNameStatus(it.text) }
    }

    private suspend fun blob(repo: GitRepo, revision: String, path: String): ByteArray? =
        git.run(repo, "cat-file", "blob", "$revision:$path", outputLimit = MAX_CONTENT_BYTES)
            .takeIf { it.exitCode == 0 || it.truncated }?.stdout

    private suspend fun refs(repo: GitRepo): CommitRefs {
        val listed = git.run(
            repo,
            "for-each-ref",
            "--format=%(objectname)%1f%(refname)%1f%(symref)",
            "refs/heads",
            "refs/remotes",
        ).takeIf(GitProcessResult::succeeded) ?: return CommitRefs(emptyMap(), false)
        val refs = listed.text.lines().filter(String::isNotBlank).map { it.split(UNIT_SEPARATOR) }
            .filter { it[2].isEmpty() }
            .map { (commit, refName) ->
                val local = refName.startsWith(LOCAL_PREFIX)
                val name = refName.removePrefix(LOCAL_PREFIX).removePrefix(REMOTE_PREFIX)
                commit to GitCommitRef(
                    id = gitStableId(repo.id, if (local) "local" else "remote", name),
                    fullName = name,
                    displayName = if (local) name else name.substringAfter('/'),
                    kind = if (local) GitRefKind.LOCAL_BRANCH else GitRefKind.REMOTE_BRANCH,
                )
            }
        return CommitRefs(
            byCommit = refs.take(MAX_COMMIT_REFS).groupBy({ it.first }, { it.second }),
            truncated = refs.size > MAX_COMMIT_REFS,
        )
    }

    private fun diffResponse(
        repositoryId: String,
        changeId: String,
        entry: StatusEntry,
        beforeRevision: String?,
        afterRevision: String?,
        before: ByteArray?,
        after: ByteArray?,
    ): GitDiffResponse {
        val beforeContent = before.asDiffContent()
        val afterContent = after.asDiffContent()
        return GitDiffResponse(
            repositoryId = repositoryId,
            changeId = changeId,
            beforePath = entry.beforePath,
            afterPath = entry.afterPath,
            beforeRevision = entry.beforePath?.let { beforeRevision },
            afterRevision = entry.afterPath?.let { afterRevision },
            beforeContent = beforeContent.text,
            afterContent = afterContent.text,
            isBinary = beforeContent.binary || afterContent.binary,
            unavailableReason = if (beforeContent.tooLarge || afterContent.tooLarge) CONTENT_TOO_LARGE else null,
        )
    }

    private fun changeId(repo: GitRepo, entry: StatusEntry): String =
        gitStableId(repo.id, entry.kind.name, entry.beforePath.orEmpty(), entry.afterPath.orEmpty())

    private fun commitFileId(repo: GitRepo, commitId: String, entry: StatusEntry): String =
        gitStableId(repo.id, commitId, entry.beforePath.orEmpty(), entry.afterPath.orEmpty())

    private class CommitRefs(val byCommit: Map<String, List<GitCommitRef>>, val truncated: Boolean)
}

internal class PushTarget(
    val id: String,
    val localBranch: String,
    val upstream: String,
    val remote: String,
    val remoteRef: String,
    val remoteDisplayUrl: String,
)

private class DiffContent(val text: String?, val binary: Boolean = false, val tooLarge: Boolean = false)

private fun ByteArray?.asDiffContent(): DiffContent = when {
    this == null -> DiffContent(null)
    size > MAX_CONTENT_BYTES -> DiffContent(null, tooLarge = true)
    (0 until minOf(size, BINARY_PROBE_BYTES)).any { this[it] == 0.toByte() } -> DiffContent(null, binary = true)
    else -> decodeToString().let { text ->
        if (text.length > MAX_DIFF_CONTENT_CHARS) DiffContent(null, tooLarge = true) else DiffContent(text)
    }
}

private fun GitProcessResult.lineCount(): Int = if (succeeded) text.lines().count(String::isNotBlank) else 0

private const val COMMIT_DETAIL_FORMAT = "%H%x1f%h%x1f%an%x1f%ae%x1f%at%x1f%cn%x1f%ce%x1f%ct%x1f%P%x1f%B"
private const val COMMIT_DETAIL_FIELDS = 10
private const val TRACKING_FIELDS = 3
private const val LOCAL_PREFIX = "refs/heads/"
private const val REMOTE_PREFIX = "refs/remotes/"
private const val CHANGE_LIST_NAME = "Changes"
private const val CONTENT_TOO_LARGE = "Revision content exceeds the remote limit."
private const val MAX_CHANGES_PER_REPOSITORY = 500
private const val MAX_COMMIT_FILES = 500
private const val MAX_PUSH_PREVIEW_COMMITS = 100
private const val MAX_COMMIT_REFS = 500
private const val MAX_BRANCHES_PER_KIND = 500
private const val MAX_DIFF_CONTENT_CHARS = 1_000_000
private const val BINARY_PROBE_BYTES = 8_000
