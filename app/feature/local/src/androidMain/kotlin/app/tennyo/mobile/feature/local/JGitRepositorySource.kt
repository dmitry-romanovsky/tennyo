package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitAction
import app.tennyo.mobile.feature.git.GitBranch
import app.tennyo.mobile.feature.git.GitBranches
import app.tennyo.mobile.feature.git.GitChange
import app.tennyo.mobile.feature.git.GitChangeKind
import app.tennyo.mobile.feature.git.GitChangeList
import app.tennyo.mobile.feature.git.GitCheckoutTarget
import app.tennyo.mobile.feature.git.GitCommitDetail
import app.tennyo.mobile.feature.git.GitCommitDetails
import app.tennyo.mobile.feature.git.GitCommitFile
import app.tennyo.mobile.feature.git.GitCommitLimits
import app.tennyo.mobile.feature.git.GitCommitRef
import app.tennyo.mobile.feature.git.GitCommitSelection
import app.tennyo.mobile.feature.git.GitCommitSummary
import app.tennyo.mobile.feature.git.GitCredentials
import app.tennyo.mobile.feature.git.GitDiff
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitHistoryPage
import app.tennyo.mobile.feature.git.GitIdentity
import app.tennyo.mobile.feature.git.GitOperationResult
import app.tennyo.mobile.feature.git.GitOutcome
import app.tennyo.mobile.feature.git.GitProjectAction
import app.tennyo.mobile.feature.git.GitProjectInfo
import app.tennyo.mobile.feature.git.GitPushPreview
import app.tennyo.mobile.feature.git.GitRefKind
import app.tennyo.mobile.feature.git.GitRejection
import app.tennyo.mobile.feature.git.GitRemote
import app.tennyo.mobile.feature.git.GitRepository
import app.tennyo.mobile.feature.git.GitRepositorySource
import app.tennyo.mobile.feature.git.GitSourceCapabilities
import app.tennyo.mobile.feature.git.GitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidRefNameException
import org.eclipse.jgit.api.errors.RefAlreadyExistsException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * The repository of one on-device workspace, read and changed with JGit. Transfers (fetch, pull's fetch, push) run
 * through [transfers] so they survive the app leaving the foreground; everything else runs here on [ioContext].
 */
@Suppress("TooManyFunctions")
class JGitRepositorySource(
    private val workspaceSlug: String,
    private val directory: File,
    private val identity: () -> GitIdentity?,
    private val saveIdentity: (GitIdentity) -> Unit,
    private val transfers: GitTransferScheduler,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : GitRepositorySource {
    private val revisions = AtomicLong()

    override val capabilities = GitSourceCapabilities(
        fetch = true,
        pull = true,
        createBranch = true,
        credentials = true,
        identity = true,
        projectSettings = true,
    )

    override suspend fun status(repositoryId: String?): GitStatus = read { repository ->
        val revision = revisions.incrementAndGet()
        val changes = workingTreeChanges(repository)
        val branch = repository.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }
            ?.let(Repository::shortenRefName)
        val tracking = branch?.let { BranchTrackingStatus.of(repository, it) }
        val hasRemote = repository.config.getSubsections("remote").isNotEmpty()
        GitStatus(
            repositories = listOf(
                GitRepository(
                    id = workspaceSlug,
                    rootName = directory.name,
                    currentBranch = branch,
                    upstreamBranch = tracking?.remoteTrackingBranch?.let(Repository::shortenRefName),
                    aheadCount = tracking?.aheadCount,
                    behindCount = tracking?.behindCount,
                    isDetachedHead = branch == null && repository.resolve(Constants.HEAD) != null,
                    availableActions = buildSet {
                        addAll(listOf(GitAction.REFRESH, GitAction.VIEW_HISTORY, GitAction.VIEW_BRANCHES))
                        if (hasRemote && tracking != null) add(GitAction.PUSH)
                    },
                    changeLists = listOf(GitChangeList(CHANGE_LIST_ID, CHANGE_LIST_ID, changes)),
                    commitCapability = GitCommitLimits(MAX_COMMIT_CHANGES, MAX_COMMIT_MESSAGE_LENGTH),
                ),
            ),
            selectedRepositoryId = workspaceSlug,
            revision = revision,
        )
    }

    override suspend fun diff(repositoryId: String, changeId: String): GitDiff = read { repository ->
        val scanned = scanWorkingTree(repository).firstOrNull { it.entry.fileId == changeId }
            ?: throw GitFault.Rejected(GitRejection.STALE_TARGET)
        val before = scanned.beforePath?.let { path -> headTree(repository)?.let { blobBytes(repository, it, path) } }
        val after = scanned.afterPath?.let { File(directory, it) }?.takeIf(File::isFile)?.readBytes()
        textDiff(workspaceSlug, changeId, scanned, before, after)
    }

    override suspend fun commit(repositoryId: String, selection: GitCommitSelection, message: String) =
        write { repository ->
            val author = identity() ?: repository.configuredIdentity() ?: throw GitFault.IdentityMissing
            val changes = workingTreeChanges(repository).associateBy(GitChange::id)
            val selected = selection.changeIds.map { changes[it] ?: throw GitFault.Rejected(GitRejection.STALE_TARGET) }
            val git = Git(repository)
            selected.forEach { change ->
                val leavesBeforePath = change.kind == GitChangeKind.DELETED || change.kind == GitChangeKind.RENAMED
                change.beforePath?.takeIf { leavesBeforePath }
                    ?.let { git.rm().setCached(true).addFilepattern(it).call() }
                change.afterPath?.let { git.add().addFilepattern(it).call() }
            }
            val command = git.commit()
                .setMessage(message)
                .setAuthor(author.name, author.email)
                .setCommitter(author.name, author.email)
            // A merge commit takes the whole index; outside a merge only the chosen paths are committed.
            if (repository.repositoryState != RepositoryState.MERGING_RESOLVED &&
                repository.repositoryState != RepositoryState.MERGING
            ) {
                selected.flatMap { listOfNotNull(it.beforePath, it.afterPath) }.distinct().forEach(command::setOnly)
            }
            command.call()
        }

    override suspend fun rollback(repositoryId: String, selection: GitCommitSelection) = write { repository ->
        val changes = workingTreeChanges(repository).associateBy(GitChange::id)
        val selected = selection.changeIds.map { changes[it] ?: throw GitFault.Rejected(GitRejection.STALE_TARGET) }
        rollBack(repository, directory, selected)
    }

    override suspend fun history(repositoryId: String, cursor: String?, limit: Int): GitHistoryPage =
        read { repository ->
        val skip = cursor?.let { it.toIntOrNull() ?: throw GitFault.Rejected(GitRejection.INVALID_CURSOR) } ?: 0
        val revision = revisions.get()
        if (repository.resolve(Constants.HEAD) == null) return@read GitHistoryPage(repositoryId, revision, emptyList())
        val refs = refsByCommit(repository)
        val commits = Git(repository).log().setSkip(skip).setMaxCount(limit + 1).call().toList()
        GitHistoryPage(
            repositoryId = repositoryId,
            revision = revision,
            commits = commits.take(limit).map { it.toSummary(refs[it.id].orEmpty()) },
            nextCursor = (skip + limit).toString().takeIf { commits.size > limit },
        )
    }

    override suspend fun commitDetail(repositoryId: String, commitId: String): GitCommitDetails = read { repository ->
        RevWalk(repository).use { walk ->
            val commit = walk.parseCommitOrStale(repository, commitId)
            val refs = refsByCommit(repository)[commit.id].orEmpty()
            GitCommitDetails(
                repositoryId = repositoryId,
                revision = revisions.get(),
                commit = GitCommitDetail(
                    id = commit.name,
                    shortId = commit.name.take(SHORT_COMMIT_ID_LENGTH),
                    subject = commit.shortMessage,
                    fullMessage = commit.fullMessage,
                    authorName = commit.authorIdent.name,
                    authorEmail = commit.authorIdent.emailAddress,
                    authoredAtEpochMillis = commit.authorIdent.whenAsInstant.toEpochMilli(),
                    committerName = commit.committerIdent.name,
                    committerEmail = commit.committerIdent.emailAddress,
                    committedAtEpochMillis = commit.committerIdent.whenAsInstant.toEpochMilli(),
                    parentIds = commit.parents.map { it.name },
                    refs = refs,
                ),
                files = scanCommit(repository, walk, commit).map { (entry) ->
                    GitCommitFile(
                        id = entry.fileId,
                        kind = entry.changeType.toKind(),
                        beforePath = entry.oldPath.takeUnless { entry.changeType == DiffEntry.ChangeType.ADD },
                        afterPath = entry.newPath.takeUnless { entry.changeType == DiffEntry.ChangeType.DELETE },
                    )
                },
            )
        }
    }

    override suspend fun commitFileDiff(repositoryId: String, commitId: String, fileId: String): GitDiff =
        read { repository ->
            RevWalk(repository).use { walk ->
                val commit = walk.parseCommitOrStale(repository, commitId)
                val scanned = scanCommit(repository, walk, commit).firstOrNull { it.entry.fileId == fileId }
                    ?: throw GitFault.Rejected(GitRejection.STALE_TARGET)
                val before = scanned.beforePath?.let { scanned.entry.oldId.bytes(repository) }
                val after = scanned.afterPath?.let { scanned.entry.newId.bytes(repository) }
                textDiff(repositoryId, fileId, scanned, before, after).copy(
                    beforeRevision = commit.parents.firstOrNull()?.name,
                    afterRevision = commit.name,
                )
            }
        }

    override suspend fun branches(repositoryId: String): GitBranches = read { repository ->
        val git = Git(repository)
        val current = repository.fullBranch
        val local = git.branchList().call().map { ref ->
            val name = Repository.shortenRefName(ref.name)
            val tracking = BranchTrackingStatus.of(repository, name)
            GitBranch(
                id = ref.name,
                fullName = ref.name,
                displayName = name,
                isCurrent = ref.name == current,
                trackedUpstream = tracking?.remoteTrackingBranch?.let(Repository::shortenRefName),
                upstreamId = tracking?.remoteTrackingBranch,
                aheadCount = tracking?.aheadCount,
                behindCount = tracking?.behindCount,
                availableActions = if (ref.name == current) emptySet() else setOf(GitAction.CHECKOUT, GitAction.DELETE),
            )
        }
        val remote = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
            .filterNot { it.name.endsWith("/${Constants.HEAD}") }
            .map { ref ->
                val name = Repository.shortenRefName(ref.name)
                GitBranch(
                    id = ref.name,
                    fullName = ref.name,
                    displayName = name,
                    remoteName = name.substringBefore('/'),
                    availableActions = setOf(GitAction.CHECKOUT),
                )
            }
        GitBranches(
            repositoryId = repositoryId,
            revision = revisions.get(),
            isDetachedHead = current != null && !current.startsWith(Constants.R_HEADS),
            localBranches = local,
            remoteBranches = remote,
        )
    }

    override suspend fun checkout(repositoryId: String, target: GitCheckoutTarget) = write { repository ->
        val command = Git(repository).checkout()
        if (target.branchId.startsWith(Constants.R_REMOTES)) {
            val remoteBranch = Repository.shortenRefName(target.branchId)
            val localName = remoteBranch.substringAfter('/')
            if (repository.findRef(Constants.R_HEADS + localName) == null) {
                command.setCreateBranch(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint(remoteBranch)
            }
            command.setName(localName)
        } else {
            repository.findRef(target.branchId) ?: throw GitFault.Rejected(GitRejection.STALE_TARGET)
            command.setName(Repository.shortenRefName(target.branchId))
        }
        command.callWithoutForcing()
    }

    override suspend fun createBranch(repositoryId: String, name: String) = write { repository ->
        try {
            Git(repository).branchCreate().setName(name).call()
        } catch (_: RefAlreadyExistsException) {
            throw GitFault.Rejected(GitRejection.REJECTED)
        } catch (_: InvalidRefNameException) {
            throw GitFault.Rejected(GitRejection.REJECTED)
        }
    }

    override suspend fun deleteBranch(repositoryId: String, branchId: String, expectedRevision: Long) =
        write { it.deleteLocalBranch(branchId, isStale = expectedRevision != revisions.get()) }

    override suspend fun projectInfo(repositoryId: String): GitProjectInfo = read { repository ->
        val head = repository.resolve(Constants.HEAD)
            ?.let { id -> RevWalk(repository).use { it.parseCommit(id) }.toSummary(emptyList()) }
        GitProjectInfo(
            location = directory.name,
            branch = repository.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }
                ?.let(Repository::shortenRefName),
            head = head,
            identity = repository.configuredIdentity() ?: identity(),
            remotes = repository.config.getSubsections("remote").sorted().map { name ->
                GitRemote(name, repository.config.getString("remote", name, "url").orEmpty())
            },
            stashCount = Git(repository).stashList().call().size,
            tagCount = repository.refDatabase.getRefsByPrefix(Constants.R_TAGS).size,
        )
    }

    /** Adds [remote], or renames and repoints [previousName]; a URL JGit cannot read is refused. */
    override suspend fun saveRemote(repositoryId: String, previousName: String?, remote: GitRemote) =
        write { repository ->
            val uri = runCatching { URIish(remote.url) }.getOrNull() ?: throw GitFault.Rejected(GitRejection.REJECTED)
            val git = Git(repository)
            val remotes = repository.config.getSubsections("remote")
            when {
                previousName == remote.name -> git.remoteSetUrl().setRemoteName(remote.name).setRemoteUri(uri).call()
                remote.name in remotes -> throw GitFault.Rejected(GitRejection.REJECTED)
                else -> {
                    // JGit has no rename; the old remote goes and the new one takes its place.
                    previousName?.let { git.remoteRemove().setRemoteName(it).call() }
                    git.remoteAdd().setName(remote.name).setUri(uri).call()
                }
            }
        }

    override suspend fun removeRemote(repositoryId: String, name: String) = write { repository ->
        Git(repository).remoteRemove().setRemoteName(name).call()
    }

    override suspend fun runProjectAction(repositoryId: String, action: GitProjectAction) = write { repository ->
        val git = Git(repository)
        when (action) {
            // Nothing to put aside is a refusal, not a silent success.
            GitProjectAction.STASH -> git.stashCreate().call() ?: throw GitFault.Rejected(GitRejection.REJECTED)
            GitProjectAction.APPLY_STASH -> {
                if (git.stashList().call().isEmpty()) throw GitFault.Rejected(GitRejection.REJECTED)
                git.stashApply().call()
                git.stashDrop().setStashRef(0).call()
            }

            GitProjectAction.CLEAN_UP -> git.gc().call()
            GitProjectAction.RESET_HEAD -> {
                repository.resolve(Constants.HEAD) ?: throw GitFault.Rejected(GitRejection.UNSUPPORTED_TARGET)
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
            }
        }
    }

    override suspend fun pushPreview(repositoryId: String): GitPushPreview = read { repository ->
        val branch = repository.branch?.takeIf { repository.fullBranch?.startsWith(Constants.R_HEADS) == true }
            ?: throw GitFault.Rejected(GitRejection.MISSING_UPSTREAM)
        val tracking = BranchTrackingStatus.of(repository, branch)
            ?: throw GitFault.Rejected(GitRejection.MISSING_UPSTREAM)
        val upstream = repository.resolve(tracking.remoteTrackingBranch)
        val head = repository.resolve(Constants.HEAD)
        val log = Git(repository).log()
        if (upstream != null) log.addRange(upstream, head) else log.add(head)
        val commits = log.setMaxCount(MAX_PUSH_PREVIEW_COMMITS + 1).call().toList()
        val remote = BranchConfig(repository.config, branch).remote ?: repository.remoteName()
        GitPushPreview(
            repositoryId = repositoryId,
            revision = revisions.get(),
            pushTargetId = tracking.remoteTrackingBranch,
            localBranch = branch,
            upstreamBranch = Repository.shortenRefName(tracking.remoteTrackingBranch),
            remoteName = remote,
            remoteDisplayUrl = displayUrl(repository.config.getString("remote", remote, "url").orEmpty()),
            commits = commits.take(MAX_PUSH_PREVIEW_COMMITS).map { it.toSummary(emptyList()) },
            commitsTruncated = commits.size > MAX_PUSH_PREVIEW_COMMITS,
        )
    }

    override suspend fun push(repositoryId: String, pushTargetId: String, previewRevision: Long): GitOperationResult {
        read { repository -> repository.remoteUrl() }
        return transfer(GitTransfer.Push(workspaceSlug))
    }

    override suspend fun fetch(repositoryId: String): GitOperationResult {
        read { repository -> repository.remoteUrl() }
        return transfer(GitTransfer.Fetch(workspaceSlug))
    }

    /** Fetches, then fast-forwards to the upstream branch; a diverged branch is [GitFault.NonFastForward]. */
    override suspend fun pull(repositoryId: String): GitOperationResult {
        val fetched = fetch(repositoryId)
        if (fetched.outcome != GitOutcome.SUCCESS) return fetched
        return write { repository ->
            val result = mergeUpstream(repository, MergeCommand.FastForwardMode.FF_ONLY, commit = true)
            if (result.mergeStatus == MergeResult.MergeStatus.ABORTED) throw GitFault.NonFastForward
            result.requireMerged()
        }
    }

    /** Merges the fetched upstream branch; conflicts are left in the working tree for the user to resolve. */
    override suspend fun merge(repositoryId: String): GitOperationResult = write { repository ->
        val author = identity() ?: repository.configuredIdentity() ?: throw GitFault.IdentityMissing
        val result = mergeUpstream(repository, MergeCommand.FastForwardMode.FF, commit = false)
        result.requireMerged()
        if (result.mergeStatus == MergeResult.MergeStatus.MERGED_NOT_COMMITTED) {
            Git(repository).commit()
                .setAuthor(author.name, author.email)
                .setCommitter(author.name, author.email)
                .setMessage(repository.readMergeCommitMsg() ?: "Merge ${result.mergedCommits.last().name}")
                .call()
        }
    }

    override suspend fun useCredentials(host: String, credentials: GitCredentials) {
        transfers.stageCredentials(host, credentials)
    }

    override suspend fun saveIdentity(identity: GitIdentity) {
        saveIdentity.invoke(identity)
    }

    private suspend fun transfer(transfer: GitTransfer): GitOperationResult {
        return when (val finished = transfers.start(transfer).first { it.isFinished }) {
            GitTransferState.Succeeded -> GitOperationResult(workspaceSlug, GitOutcome.SUCCESS, revisions.get())
            GitTransferState.Cancelled -> GitOperationResult(workspaceSlug, GitOutcome.CANCELLED, revisions.get())
            is GitTransferState.Failed -> throw finished.fault
            else -> error("Unfinished transfer state $finished")
        }
    }

    private fun mergeUpstream(
        repository: Repository,
        mode: MergeCommand.FastForwardMode,
        commit: Boolean,
    ): MergeResult {
        val branch = repository.branch ?: throw GitFault.Rejected(GitRejection.MISSING_UPSTREAM)
        val upstream = BranchTrackingStatus.of(repository, branch)?.remoteTrackingBranch
            ?.let(repository::findRef)
            ?: throw GitFault.Rejected(GitRejection.MISSING_UPSTREAM)
        return Git(repository).merge().include(upstream).setFastForward(mode).setCommit(commit).call()
    }

    /**
     * The working tree against `HEAD`: the paths Git's status reports, with renames detected between them and
     * conflicted paths marked as such.
     */
    private fun workingTreeChanges(repository: Repository): List<GitChange> {
        val status = Git(repository).status().call()
        return scanWorkingTree(repository, status).map { scanned ->
            val entry = scanned.entry
            val path = entry.fileId
            val kind = when {
                path in status.conflicting -> GitChangeKind.CONFLICTED
                entry.changeType == DiffEntry.ChangeType.ADD && path in status.untracked -> GitChangeKind.UNTRACKED
                else -> entry.changeType.toKind()
            }
            GitChange(
                id = path,
                kind = kind,
                beforePath = scanned.beforePath,
                afterPath = scanned.afterPath,
                fileName = path.substringAfterLast('/'),
                parentPath = path.substringBeforeLast('/', missingDelimiterValue = ""),
                addedLines = scanned.addedLines,
                deletedLines = scanned.deletedLines,
                availableActions = setOf(GitAction.VIEW_DIFF, GitAction.ROLLBACK),
            )
        }.sortedBy { it.id }
    }

    private fun refsByCommit(repository: Repository): Map<ObjectId, List<GitCommitRef>> =
        repository.refDatabase.refs
            .filter { it.name.startsWith(Constants.R_HEADS) || it.name.startsWith(Constants.R_REMOTES) }
            .mapNotNull { ref ->
                val peeled = repository.refDatabase.peel(ref)
                (peeled.peeledObjectId ?: peeled.objectId)?.let { it to ref }
            }
            .groupBy({ it.first }, { (_, ref) -> ref.toCommitRef() })

    private suspend fun <T> read(block: (Repository) -> T): T = withContext(ioContext) {
        openRepository(directory).use { repository ->
            try {
                block(repository)
            } catch (failure: GitAPIException) {
                throw GitFault.Rejected(GitRejection.PLATFORM_FAILURE).apply { initCause(failure) }
            }
        }
    }

    private suspend fun write(block: (Repository) -> Unit): GitOperationResult {
        read(block)
        return GitOperationResult(workspaceSlug, GitOutcome.SUCCESS, revisions.incrementAndGet())
    }

    /** The author set in this repository's own config; a device-wide Git config does not exist on a phone. */
    private fun Repository.configuredIdentity(): GitIdentity? {
        val local = FileBasedConfig(File(directory, "config"), fs).apply { load() }
        val name = local.getString("user", null, "name")?.takeIf(String::isNotBlank)
        val email = local.getString("user", null, "email")?.takeIf(String::isNotBlank)
        return if (name != null && email != null) GitIdentity(name, email) else null
    }

    private companion object {
        const val CHANGE_LIST_ID = "changes"
        const val MAX_COMMIT_CHANGES = 1_000
        const val MAX_COMMIT_MESSAGE_LENGTH = 16_000
        const val MAX_PUSH_PREVIEW_COMMITS = 100
    }
}

private const val SHORT_COMMIT_ID_LENGTH = 7

private fun CheckoutCommand.callWithoutForcing() {
    try {
        call()
    } catch (_: CheckoutConflictException) {
        throw GitFault.Rejected(GitRejection.REJECTED)
    }
}

private fun RevWalk.parseCommitOrStale(repository: Repository, commitId: String): RevCommit {
    val id = runCatching { repository.resolve(commitId) }.getOrNull()
        ?: throw GitFault.Rejected(GitRejection.STALE_TARGET)
    return parseCommit(id)
}

private fun RevCommit.toSummary(refs: List<GitCommitRef>) = GitCommitSummary(
    id = name,
    shortId = name.take(SHORT_COMMIT_ID_LENGTH),
    subject = shortMessage,
    authorName = authorIdent.name,
    authoredAtEpochMillis = authorIdent.whenAsInstant.toEpochMilli(),
    committerName = committerIdent.name,
    committedAtEpochMillis = committerIdent.whenAsInstant.toEpochMilli(),
    parentIds = parents.map { it.name },
    refs = refs,
)

private fun Ref.toCommitRef() = GitCommitRef(
    id = name,
    fullName = name,
    displayName = Repository.shortenRefName(name),
    kind = if (name.startsWith(Constants.R_HEADS)) GitRefKind.LOCAL_BRANCH else GitRefKind.REMOTE_BRANCH,
)

/** The remote URL without any user information, for showing where a push goes. */
private fun displayUrl(url: String): String =
    runCatching { URIish(url).setUser(null).setPass(null).toString() }.getOrDefault(url)

/** Forced: the phone asked the user to confirm. Only the local ref goes; a remote-tracking branch stays. */
private fun Repository.deleteLocalBranch(branchId: String, isStale: Boolean) {
    if (isStale || !branchId.startsWith(Constants.R_HEADS) || findRef(branchId) == null) {
        throw GitFault.Rejected(GitRejection.STALE_TARGET)
    }
    if (branchId == fullBranch) throw GitFault.Rejected(GitRejection.REJECTED)
    Git(this).branchDelete().setBranchNames(branchId).setForce(true).call()
}

private fun MergeResult.requireMerged() {
    when (mergeStatus) {
        MergeResult.MergeStatus.CONFLICTING -> throw GitFault.Conflicts(conflicts.orEmpty().keys.sorted())
        MergeResult.MergeStatus.FAILED, MergeResult.MergeStatus.CHECKOUT_CONFLICT ->
            throw GitFault.Rejected(GitRejection.REJECTED)

        else -> Unit
    }
}
