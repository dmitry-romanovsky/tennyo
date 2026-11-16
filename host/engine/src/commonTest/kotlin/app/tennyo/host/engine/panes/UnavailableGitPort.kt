package app.tennyo.host.engine.panes

import app.tennyo.contract.GitBranchesResponse
import app.tennyo.contract.GitCheckoutRequest
import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitDiffResponse
import app.tennyo.contract.GitOperationOutcome
import app.tennyo.contract.GitOperationResult
import app.tennyo.contract.GitProjectAction
import app.tennyo.contract.GitProjectInfoResponse
import app.tennyo.contract.GitPushPreviewResponse
import app.tennyo.contract.GitPushRequest
import app.tennyo.contract.GitRemote
import app.tennyo.contract.GitRollbackRequest
import app.tennyo.contract.GitStatusResponse

/**
 * The Git surface of a host that has none. A session is always built with a Git bridge, so a host that cannot drive
 * Git supplies this and reports [IdeGitPortFactory.isAvailable] as `false`: the session then never advertises
 * `GIT`, and a call that arrives anyway is refused with a typed outcome rather than a crash.
 */
@Suppress("TooManyFunctions")
object UnavailableGitPort : IdeGitPort {
    override suspend fun status(selectedRepositoryId: String?, revision: Long): GitStatusResponse? = null

    override suspend fun pushPreview(repositoryId: String, revision: Long): GitPushPreviewResponse? = null

    override suspend fun diff(repositoryId: String, changeId: String): GitDiffResponse? = null

    override suspend fun branches(repositoryId: String, revision: Long): GitBranchesResponse? = null

    override suspend fun history(repositoryId: String, revision: Long, cursor: Int, limit: Int) =
        GitHistoryResult.MissingRepository

    override suspend fun commitDetail(repositoryId: String, revision: Long, commitId: String) =
        GitCommitDetailResult.MissingTarget

    override suspend fun commitDiff(repositoryId: String, commitId: String, fileId: String): GitDiffResponse? = null

    override suspend fun projectInfo(repositoryId: String): GitProjectInfoResponse? = null

    override suspend fun checkout(request: GitCheckoutRequest, revision: Long) = unsupported(request.repositoryId)

    override suspend fun push(request: GitPushRequest, revision: Long) = unsupported(request.repositoryId)

    override suspend fun commit(request: GitCommitRequest, revision: Long) = unsupported(request.repositoryId)

    override suspend fun rollback(request: GitRollbackRequest, revision: Long) = unsupported(request.repositoryId)

    override suspend fun fetch(repositoryId: String) = unsupported(repositoryId)

    override suspend fun pull(repositoryId: String) = unsupported(repositoryId)

    override suspend fun merge(repositoryId: String) = unsupported(repositoryId)

    override suspend fun createBranch(repositoryId: String, name: String) = unsupported(repositoryId)

    override suspend fun deleteBranch(repositoryId: String, branchId: String, expectedRevision: Long, revision: Long) =
        unsupported(repositoryId)

    override suspend fun saveRemote(repositoryId: String, previousName: String?, remote: GitRemote) =
        unsupported(repositoryId)

    override suspend fun removeRemote(repositoryId: String, name: String) = unsupported(repositoryId)

    override suspend fun runProjectAction(repositoryId: String, action: GitProjectAction) = unsupported(repositoryId)

    override fun close(reason: String) = Unit

    private fun unsupported(repositoryId: String) = GitOperationResult(repositoryId, GitOperationOutcome.UNSUPPORTED)
}

/**
 * The factories a host binds for the surfaces it does not drive. A session is built with all of them whatever the
 * host can do, so each one answers nothing and reports itself unavailable; the session then advertises only the
 * capabilities the host really has, and the app never offers a pane whose calls would fault.
 */
val UnavailableLaunchPortFactory: IdeLaunchPortFactory = object : IdeLaunchPortFactory {
    override fun create(): LaunchPortLease? = null

    override fun isAvailable(): Boolean = false
}

val UnavailableTerminalPortFactory: IdeTerminalPortFactory = object : IdeTerminalPortFactory {
    override fun create(callbacks: TerminalPortCallbacks): TerminalPortLease? = null

    override fun isAvailable(): Boolean = false
}
