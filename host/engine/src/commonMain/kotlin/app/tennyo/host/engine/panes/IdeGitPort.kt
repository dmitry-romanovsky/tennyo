package app.tennyo.host.engine.panes

import app.tennyo.contract.GitBranchesResponse
import app.tennyo.contract.GitCheckoutRequest
import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitDiffResponse
import app.tennyo.contract.GitOperationResult
import app.tennyo.contract.GitProjectAction
import app.tennyo.contract.GitProjectInfoResponse
import app.tennyo.contract.GitPushPreviewResponse
import app.tennyo.contract.GitPushRequest
import app.tennyo.contract.GitRemote
import app.tennyo.contract.GitRollbackRequest
import app.tennyo.contract.GitStatusResponse

/**
 * One session's view of the project's Git repositories. The core owns the revision counter and passes it in; the port
 * uses it for freshness checks and echoes it in responses. A read answers `null` when its target is gone.
 */
@Suppress("TooManyFunctions")
interface IdeGitPort {
    suspend fun status(selectedRepositoryId: String?, revision: Long): GitStatusResponse?

    suspend fun pushPreview(repositoryId: String, revision: Long): GitPushPreviewResponse?

    suspend fun diff(repositoryId: String, changeId: String): GitDiffResponse?

    suspend fun branches(repositoryId: String, revision: Long): GitBranchesResponse?

    suspend fun history(repositoryId: String, revision: Long, cursor: Int, limit: Int): GitHistoryResult

    suspend fun commitDetail(repositoryId: String, revision: Long, commitId: String): GitCommitDetailResult

    suspend fun commitDiff(repositoryId: String, commitId: String, fileId: String): GitDiffResponse?

    suspend fun projectInfo(repositoryId: String): GitProjectInfoResponse?

    suspend fun checkout(request: GitCheckoutRequest, revision: Long): GitOperationResult

    suspend fun push(request: GitPushRequest, revision: Long): GitOperationResult

    suspend fun commit(request: GitCommitRequest, revision: Long): GitOperationResult

    suspend fun rollback(request: GitRollbackRequest, revision: Long): GitOperationResult

    suspend fun fetch(repositoryId: String): GitOperationResult

    suspend fun pull(repositoryId: String): GitOperationResult

    suspend fun merge(repositoryId: String): GitOperationResult

    suspend fun createBranch(repositoryId: String, name: String): GitOperationResult

    /** Deletes the local branch [branchId] of the branch list at [expectedRevision] while the host is at [revision]. */
    suspend fun deleteBranch(
        repositoryId: String,
        branchId: String,
        expectedRevision: Long,
        revision: Long,
    ): GitOperationResult

    suspend fun saveRemote(repositoryId: String, previousName: String?, remote: GitRemote): GitOperationResult

    suspend fun removeRemote(repositoryId: String, name: String): GitOperationResult

    suspend fun runProjectAction(repositoryId: String, action: GitProjectAction): GitOperationResult

    /** Refuses later mutations; the Git processes still running end with the calls that started them. */
    fun close(reason: String)
}

/** Creates one session's Git port; [onChangesChanged] runs whenever files in the project may have changed. */
interface IdeGitPortFactory {
    fun create(onChangesChanged: () -> Unit): IdeGitPort

    /** Whether this host can drive Git at all; a host that cannot never advertises the capability. */
    fun isAvailable(): Boolean = true
}
