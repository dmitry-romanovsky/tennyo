package app.tennyo.host.engine.rpc

import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkResult
import app.tennyo.host.engine.panes.GitCommitDetailResult
import app.tennyo.host.engine.panes.GitHistoryResult
import app.tennyo.host.engine.session.HostSession

private const val MAX_HISTORY_PAGE = 100

internal suspend fun HostSession.gitStatus(call: LinkCall.GitStatus): LinkResult =
    LinkResult.GitStatus(git.gitStatus(call.repositoryId) ?: throw staleRepository())

internal suspend fun HostSession.gitDiff(call: LinkCall.GitDiff): LinkResult =
    LinkResult.GitDiff(git.gitDiff(call.repositoryId, call.changeId) ?: throw staleChange())

internal suspend fun HostSession.gitCommitFileDiff(call: LinkCall.GitCommitFileDiff): LinkResult =
    LinkResult.GitDiff(git.gitCommitDiff(call.repositoryId, call.commitId, call.fileId) ?: throw staleChange())

internal suspend fun HostSession.gitBranches(call: LinkCall.GitBranches): LinkResult =
    LinkResult.GitBranches(git.gitBranches(call.repositoryId) ?: throw staleRepository())

internal suspend fun HostSession.gitProjectInfo(call: LinkCall.GitProjectInfo): LinkResult =
    LinkResult.GitProjectInfo(git.gitProjectInfo(call.repositoryId) ?: throw staleRepository())

internal suspend fun HostSession.gitPushPreview(call: LinkCall.GitPushPreview): LinkResult =
    LinkResult.GitPushPreview(
        git.gitPushPreview(call.repositoryId)
            ?: throw CallFault(LinkFaultCode.GIT_MISSING_UPSTREAM, "The Git repository has no tracked upstream."),
    )

internal suspend fun HostSession.gitHistory(call: LinkCall.GitHistory): LinkResult {
    val offset = git.gitHistoryCursorOffset(call.repositoryId, call.cursor)
        ?: throw CallFault(LinkFaultCode.GIT_CURSOR_INVALID, "The Git history cursor is invalid.")
    val page = git.gitHistory(call.repositoryId, offset, call.limit.coerceIn(1, MAX_HISTORY_PAGE))
    return if (page is GitHistoryResult.Success) LinkResult.GitHistory(page.response) else throw page.fault()
}

internal suspend fun HostSession.gitCommitDetail(call: LinkCall.GitCommitDetail): LinkResult {
    val commitId = call.commitId.takeIf { it.isNotBlank() && '/' !in it } ?: throw staleCommit()
    val detail = git.gitCommitDetail(call.repositoryId, commitId)
    if (detail !is GitCommitDetailResult.Success) throw detail.fault()
    return LinkResult.GitCommitDetail(detail.response)
}

/** Why a history page could not be read; a successful one has no fault. */
private fun GitHistoryResult.fault(): CallFault =
    when (this) {
        GitHistoryResult.MissingRepository -> staleRepository()
        GitHistoryResult.PlatformFailure -> platformFailure("The computer could not load Git history.")
        is GitHistoryResult.Success -> error("A successful Git history has no fault.")
    }

/** Why a commit detail could not be read; a successful one has no fault. */
private fun GitCommitDetailResult.fault(): CallFault =
    when (this) {
        GitCommitDetailResult.MissingTarget -> staleCommit()
        GitCommitDetailResult.PlatformFailure -> platformFailure("The computer could not load Git commit details.")
        is GitCommitDetailResult.Success -> error("A successful Git commit detail has no fault.")
    }

private fun staleRepository(): CallFault =
    CallFault(LinkFaultCode.GIT_TARGET_STALE, "The requested Git repository is no longer present.")

private fun staleChange(): CallFault =
    CallFault(LinkFaultCode.GIT_TARGET_STALE, "The requested Git change is no longer present.")

private fun staleCommit(): CallFault =
    CallFault(LinkFaultCode.GIT_TARGET_STALE, "The requested Git commit is no longer present.")

private fun platformFailure(message: String): CallFault = CallFault(LinkFaultCode.GIT_PLATFORM_FAILURE, message)
