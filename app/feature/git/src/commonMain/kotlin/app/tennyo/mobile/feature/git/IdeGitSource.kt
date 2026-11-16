package app.tennyo.mobile.feature.git

import app.tennyo.contract.GitBranchesResponse
import app.tennyo.contract.GitCheckoutRequest
import app.tennyo.contract.GitCommitDetailResponse
import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitDiffResponse
import app.tennyo.contract.GitHistoryResponse
import app.tennyo.contract.GitOperationOutcome
import app.tennyo.contract.GitProjectInfoResponse
import app.tennyo.contract.GitPushPreviewResponse
import app.tennyo.contract.GitPushRequest
import app.tennyo.contract.GitRepositorySummary
import app.tennyo.contract.GitRollbackRequest
import app.tennyo.contract.GitStatusResponse
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.LinkCalls
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.RequestBackoff
import app.tennyo.mobile.link.pane.nowEpochMillis
import app.tennyo.mobile.link.request
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import app.tennyo.contract.GitAction as GitActionDto
import app.tennyo.contract.GitBranch as GitBranchDto
import app.tennyo.contract.GitChange as GitChangeDto
import app.tennyo.contract.GitChangeKind as GitChangeKindDto
import app.tennyo.contract.GitChangeList as GitChangeListDto
import app.tennyo.contract.GitCommitFile as GitCommitFileDto
import app.tennyo.contract.GitCommitRef as GitCommitRefDto
import app.tennyo.contract.GitCommitSummary as GitCommitSummaryDto
import app.tennyo.contract.GitOperationResult as GitOperationResultDto
import app.tennyo.contract.GitProjectAction as GitProjectActionDto
import app.tennyo.contract.GitRefKind as GitRefKindDto
import app.tennyo.contract.GitRemote as GitRemoteDto

/**
 * The host editor's Git over the link: every action the local workspace offers, run by the computer's own Git.
 * Contract DTOs stop here: every answer becomes the pane's own model, and every host
 * error code a [GitFault]. Status refreshes the pane starts on its own back off while the host rate-limits them.
 */
class IdeGitSource(private val link: LinkCalls) : GitRepositorySource {
    private val statusBackoff = RequestBackoff<Unit>()

    // Credentials and the commit author belong to the computer's own Git, so the phone never asks for them.
    override val capabilities: GitSourceCapabilities = GitSourceCapabilities(
        fetch = true,
        pull = true,
        createBranch = true,
        projectSettings = true,
    )

    override fun automaticRefreshAllowed(): Boolean = statusBackoff.shouldRun(Unit, nowEpochMillis())

    override suspend fun status(repositoryId: String?): GitStatus =
        read<LinkResult.GitStatus>(LinkCall.GitStatus(repositoryId), onFailure = ::recordStatusFailure)
            .also { statusBackoff.onRequestSuccess(Unit) }
            .status
            .toModel()

    private fun recordStatusFailure(failure: Throwable) {
        if (statusBackoff.onRequestFailure(Unit, failure, nowEpochMillis())) {
            Logger.w("Unable to refresh Git overview; backing off after rate limit", failure)
        } else {
            Logger.w("Unable to refresh Git overview", failure)
        }
    }

    override suspend fun diff(repositoryId: String, changeId: String): GitDiff =
        read<LinkResult.GitDiff>(LinkCall.GitDiff(repositoryId, changeId)).diff.toModel()

    override suspend fun commitFileDiff(repositoryId: String, commitId: String, fileId: String): GitDiff =
        read<LinkResult.GitDiff>(LinkCall.GitCommitFileDiff(repositoryId, commitId, fileId)).diff.toModel()

    override suspend fun history(repositoryId: String, cursor: String?, limit: Int): GitHistoryPage =
        read<LinkResult.GitHistory>(LinkCall.GitHistory(repositoryId, cursor, limit)).history.toModel()

    override suspend fun commitDetail(repositoryId: String, commitId: String): GitCommitDetails =
        read<LinkResult.GitCommitDetail>(LinkCall.GitCommitDetail(repositoryId, commitId)).detail.toModel()

    override suspend fun branches(repositoryId: String): GitBranches =
        read<LinkResult.GitBranches>(LinkCall.GitBranches(repositoryId)).branches.toModel()

    override suspend fun pushPreview(repositoryId: String): GitPushPreview =
        read<LinkResult.GitPushPreview>(LinkCall.GitPushPreview(repositoryId)).preview.toModel()

    override suspend fun commit(
        repositoryId: String,
        selection: GitCommitSelection,
        message: String,
    ): GitOperationResult =
        operation(
            LinkCall.GitCommit(
                GitCommitRequest(repositoryId, selection.changeIds, message, selection.expectedRevision),
            ),
        )

    override suspend fun rollback(repositoryId: String, selection: GitCommitSelection): GitOperationResult =
        operation(
            LinkCall.GitRollback(GitRollbackRequest(repositoryId, selection.changeIds, selection.expectedRevision)),
        )

    override suspend fun checkout(repositoryId: String, target: GitCheckoutTarget): GitOperationResult =
        operation(LinkCall.GitCheckout(GitCheckoutRequest(repositoryId, target.branchId, target.expectedRevision)))

    override suspend fun deleteBranch(repositoryId: String, branchId: String, expectedRevision: Long) =
        operation(LinkCall.GitDeleteBranch(repositoryId, branchId, expectedRevision))

    override suspend fun push(repositoryId: String, pushTargetId: String, previewRevision: Long): GitOperationResult =
        operation(LinkCall.GitPush(GitPushRequest(repositoryId, pushTargetId, previewRevision)))

    override suspend fun fetch(repositoryId: String): GitOperationResult = operation(LinkCall.GitFetch(repositoryId))

    override suspend fun pull(repositoryId: String): GitOperationResult = operation(LinkCall.GitPull(repositoryId))

    override suspend fun merge(repositoryId: String): GitOperationResult = operation(LinkCall.GitMerge(repositoryId))

    override suspend fun createBranch(repositoryId: String, name: String): GitOperationResult =
        operation(LinkCall.GitCreateBranch(repositoryId, name))

    override suspend fun projectInfo(repositoryId: String): GitProjectInfo =
        read<LinkResult.GitProjectInfo>(LinkCall.GitProjectInfo(repositoryId)).info.toModel()

    override suspend fun saveRemote(
        repositoryId: String,
        previousName: String?,
        remote: GitRemote,
    ): GitOperationResult =
        operation(LinkCall.GitSaveRemote(repositoryId, previousName, GitRemoteDto(remote.name, remote.url)))

    override suspend fun removeRemote(repositoryId: String, name: String): GitOperationResult =
        operation(LinkCall.GitRemoveRemote(repositoryId, name))

    override suspend fun runProjectAction(repositoryId: String, action: GitProjectAction): GitOperationResult =
        operation(
            LinkCall.GitRunProjectAction(
                repositoryId,
                when (action) {
                    GitProjectAction.STASH -> GitProjectActionDto.STASH
                    GitProjectAction.APPLY_STASH -> GitProjectActionDto.APPLY_STASH
                    GitProjectAction.CLEAN_UP -> GitProjectActionDto.CLEAN_UP
                    GitProjectAction.RESET_HEAD -> GitProjectActionDto.RESET_HEAD
                },
            ),
        )

    /** Runs a mutation; outcomes the pane answers with a prompt or its own words arrive as their [GitFault]. */
    private suspend fun operation(call: LinkCall): GitOperationResult {
        val result = read<LinkResult.GitOperation>(call).result
        throw when (result.outcome) {
            GitOperationOutcome.NON_FAST_FORWARD -> GitFault.NonFastForward
            GitOperationOutcome.CONFLICTS -> GitFault.Conflicts(emptyList())
            GitOperationOutcome.IDENTITY_MISSING -> GitFault.IdentityMissing
            else -> return result.toModel()
        }
    }

    /** Runs [call], turning the host's Git faults into [GitFault]s once [onFailure] saw the original. */
    private suspend inline fun <reified R : LinkResult> read(
        call: LinkCall,
        onFailure: (Throwable) -> Unit = {},
    ): R =
        try {
            link.request<R>(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            onFailure(failure)
            throw GitFault.Rejected((failure as? LinkFault.Host)?.gitRejection() ?: GitRejection.UNEXPECTED)
        }
}

private fun LinkFault.Host.gitRejection(): GitRejection = when (linkFault) {
    LinkFaultCode.GIT_TARGET_STALE -> GitRejection.STALE_TARGET
    LinkFaultCode.GIT_CURSOR_INVALID -> GitRejection.INVALID_CURSOR
    LinkFaultCode.GIT_MISSING_UPSTREAM -> GitRejection.MISSING_UPSTREAM
    LinkFaultCode.GIT_PLATFORM_FAILURE -> GitRejection.PLATFORM_FAILURE
    LinkFaultCode.BUSY -> GitRejection.BUSY
    LinkFaultCode.UNSUPPORTED -> GitRejection.UNSUPPORTED_TARGET
    LinkFaultCode.TARGET_MISSING -> GitRejection.STALE_TARGET
    else -> GitRejection.UNEXPECTED
}

private fun GitStatusResponse.toModel() = GitStatus(
    repositories = repositories.map { it.toModel() },
    selectedRepositoryId = selectedRepositoryId,
    revision = revision,
)

private fun GitRepositorySummary.toModel() = GitRepository(
    id = id,
    rootName = rootName,
    projectPath = projectPath,
    currentBranch = currentBranch,
    upstreamBranch = upstreamBranch,
    aheadCount = aheadCount,
    behindCount = behindCount,
    isDetachedHead = isDetachedHead,
    availableActions = availableActions.toModel(),
    changeLists = changeLists.map { it.toModel() },
    changesTruncated = changesTruncated,
    commitCapability = commitCapability?.let { GitCommitLimits(it.maxSelectedChangeCount, it.maxMessageLength) },
)

private fun GitChangeListDto.toModel() = GitChangeList(id = id, name = name, changes = changes.map { it.toModel() })

private fun GitChangeDto.toModel() = GitChange(
    id = id,
    kind = kind.toModel(),
    beforePath = beforePath,
    afterPath = afterPath,
    fileName = fileName,
    parentPath = parentPath,
    addedLines = addedLines,
    deletedLines = deletedLines,
    availableActions = availableActions.toModel(),
)

private fun GitChangeKindDto.toModel(): GitChangeKind = when (this) {
    GitChangeKindDto.ADDED -> GitChangeKind.ADDED
    GitChangeKindDto.MODIFIED -> GitChangeKind.MODIFIED
    GitChangeKindDto.RENAMED -> GitChangeKind.RENAMED
    GitChangeKindDto.DELETED -> GitChangeKind.DELETED
    GitChangeKindDto.MOVED -> GitChangeKind.MOVED
    GitChangeKindDto.UNTRACKED -> GitChangeKind.UNTRACKED
    GitChangeKindDto.CONFLICTED -> GitChangeKind.CONFLICTED
}

private fun Set<GitActionDto>.toModel(): Set<GitAction> = mapTo(mutableSetOf()) { action ->
    when (action) {
        GitActionDto.REFRESH -> GitAction.REFRESH
        GitActionDto.VIEW_DIFF -> GitAction.VIEW_DIFF
        GitActionDto.VIEW_HISTORY -> GitAction.VIEW_HISTORY
        GitActionDto.VIEW_BRANCHES -> GitAction.VIEW_BRANCHES
        GitActionDto.PUSH -> GitAction.PUSH
        GitActionDto.CHECKOUT -> GitAction.CHECKOUT
        GitActionDto.ROLLBACK -> GitAction.ROLLBACK
        GitActionDto.DELETE -> GitAction.DELETE
    }
}

private fun GitProjectInfoResponse.toModel() = GitProjectInfo(
    location = location,
    branch = branch,
    head = head?.toModel(),
    identity = identityName?.let { name -> identityEmail?.let { email -> GitIdentity(name, email) } },
    remotes = remotes.map { GitRemote(it.name, it.url) },
    stashCount = stashCount,
    tagCount = tagCount,
)

private fun GitDiffResponse.toModel() = GitDiff(
    repositoryId = repositoryId,
    changeId = changeId,
    beforePath = beforePath,
    afterPath = afterPath,
    beforeRevision = beforeRevision,
    afterRevision = afterRevision,
    beforeContent = beforeContent,
    afterContent = afterContent,
    isBinary = isBinary,
    unavailableReason = unavailableReason,
    addedLines = addedLines,
    deletedLines = deletedLines,
)

private fun GitBranchesResponse.toModel() = GitBranches(
    repositoryId = repositoryId,
    revision = revision,
    isDetachedHead = isDetachedHead,
    localBranches = localBranches.map { it.toModel() },
    remoteBranches = remoteBranches.map { it.toModel() },
    localBranchesTruncated = localBranchesTruncated,
    remoteBranchesTruncated = remoteBranchesTruncated,
)

private fun GitBranchDto.toModel() = GitBranch(
    id = id,
    fullName = fullName,
    displayName = displayName,
    isCurrent = isCurrent,
    trackedUpstream = trackedUpstream,
    remoteName = remoteName,
    upstreamId = upstreamId,
    aheadCount = aheadCount,
    behindCount = behindCount,
    availableActions = availableActions.toModel(),
)

private fun GitCommitRefDto.toModel() = GitCommitRef(
    id = id,
    fullName = fullName,
    displayName = displayName,
    kind = when (kind) {
        GitRefKindDto.LOCAL_BRANCH -> GitRefKind.LOCAL_BRANCH
        GitRefKindDto.REMOTE_BRANCH -> GitRefKind.REMOTE_BRANCH
    },
)

private fun GitCommitSummaryDto.toModel() = GitCommitSummary(
    id = id,
    shortId = shortId,
    subject = subject,
    authorName = authorName,
    authoredAtEpochMillis = authoredAtEpochMillis,
    committerName = committerName,
    committedAtEpochMillis = committedAtEpochMillis,
    parentIds = parentIds,
    refs = refs.map { it.toModel() },
)

private fun GitHistoryResponse.toModel() = GitHistoryPage(
    repositoryId = repositoryId,
    revision = revision,
    commits = commits.map { it.toModel() },
    refsTruncated = refsTruncated,
    nextCursor = nextCursor,
)

private fun GitCommitFileDto.toModel() = GitCommitFile(
    id = id,
    kind = kind.toModel(),
    beforePath = beforePath,
    afterPath = afterPath,
    availableActions = availableActions.toModel(),
)

private fun GitCommitDetailResponse.toModel() = GitCommitDetails(
    repositoryId = repositoryId,
    revision = revision,
    commit = with(commit) {
        GitCommitDetail(
            id = id,
            shortId = shortId,
            subject = subject,
            fullMessage = fullMessage,
            authorName = authorName,
            authorEmail = authorEmail,
            authoredAtEpochMillis = authoredAtEpochMillis,
            committerName = committerName,
            committerEmail = committerEmail,
            committedAtEpochMillis = committedAtEpochMillis,
            parentIds = parentIds,
            refs = refs.map { it.toModel() },
        )
    },
    files = files.map { it.toModel() },
    filesTruncated = filesTruncated,
)

private fun GitPushPreviewResponse.toModel() = GitPushPreview(
    repositoryId = repositoryId,
    revision = revision,
    pushTargetId = pushTargetId,
    localBranch = localBranch,
    upstreamBranch = upstreamBranch,
    remoteName = remoteName,
    remoteDisplayUrl = remoteDisplayUrl,
    commits = commits.map { it.toModel() },
    commitsTruncated = commitsTruncated,
)

private fun GitOperationResultDto.toModel() = GitOperationResult(
    repositoryId = repositoryId,
    outcome = when (outcome) {
        GitOperationOutcome.SUCCESS -> GitOutcome.SUCCESS
        GitOperationOutcome.CANCELLED -> GitOutcome.CANCELLED
        GitOperationOutcome.REJECTED -> GitOutcome.REJECTED
        GitOperationOutcome.BUSY -> GitOutcome.BUSY
        GitOperationOutcome.MISSING_UPSTREAM -> GitOutcome.MISSING_UPSTREAM
        GitOperationOutcome.STALE_TARGET -> GitOutcome.STALE_TARGET
        GitOperationOutcome.UNSUPPORTED -> GitOutcome.UNSUPPORTED
        GitOperationOutcome.PLATFORM_FAILURE -> GitOutcome.PLATFORM_FAILURE
        GitOperationOutcome.AUTH_FAILED -> GitOutcome.AUTH_FAILED
        // Answered as faults by [IdeGitSource.operation] before they get here.
        GitOperationOutcome.NON_FAST_FORWARD, GitOperationOutcome.CONFLICTS, GitOperationOutcome.IDENTITY_MISSING ->
            GitOutcome.REJECTED
    },
    revision = revision,
)
