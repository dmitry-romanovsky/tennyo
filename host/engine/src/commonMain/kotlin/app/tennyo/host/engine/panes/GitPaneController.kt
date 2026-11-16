package app.tennyo.host.engine.panes

import app.tennyo.contract.ActivePanels
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
import app.tennyo.contract.LinkPush
import app.tennyo.host.engine.session.HostSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

@Suppress("TooManyFunctions")
class GitPaneController(
    private val session: HostSession,
) {
    private val gitRevision = AtomicLong(0L)
    private val gitInvalidationPending = AtomicBoolean(false)
    private val gitBridge = session.surfaces.gitFactory.create(onChangesChanged = ::queueGitInvalidation)

    suspend fun gitStatus(repositoryId: String?): GitStatusResponse? =
        gitBridge.status(repositoryId, gitRevision.load())

    suspend fun gitPushPreview(repositoryId: String): GitPushPreviewResponse? =
        gitBridge.pushPreview(repositoryId, gitRevision.load())

    suspend fun gitDiff(repositoryId: String, changeId: String): GitDiffResponse? =
        gitBridge.diff(repositoryId, changeId)

    suspend fun gitBranches(repositoryId: String): GitBranchesResponse? =
        gitBridge.branches(repositoryId, gitRevision.load())

    suspend fun gitHistory(repositoryId: String, cursor: Int, limit: Int): GitHistoryResult =
        gitBridge.history(repositoryId, gitRevision.load(), cursor, limit)

    fun gitHistoryCursorOffset(repositoryId: String, cursor: String?): Int? =
        if (cursor == null) 0 else gitHistoryCursorOffset(repositoryId, gitRevision.load(), cursor)

    suspend fun gitCommitDetail(repositoryId: String, commitId: String): GitCommitDetailResult =
        gitBridge.commitDetail(repositoryId, gitRevision.load(), commitId)

    suspend fun gitCommitDiff(repositoryId: String, commitId: String, fileId: String): GitDiffResponse? =
        gitBridge.commitDiff(repositoryId, commitId, fileId)

    suspend fun gitProjectInfo(repositoryId: String): GitProjectInfoResponse? = gitBridge.projectInfo(repositoryId)

    suspend fun gitCheckout(request: GitCheckoutRequest): GitOperationResult =
        gitBridge.checkout(request, gitRevision.load()).withGitMutationRevision()

    suspend fun gitPush(request: GitPushRequest): GitOperationResult =
        gitBridge.push(request, gitRevision.load()).withGitMutationRevision()

    suspend fun gitCommit(request: GitCommitRequest): GitOperationResult =
        gitBridge.commit(request, gitRevision.load()).withGitMutationRevision()

    suspend fun gitRollback(request: GitRollbackRequest): GitOperationResult =
        gitBridge.rollback(request, gitRevision.load()).withGitMutationRevision()

    suspend fun gitFetch(repositoryId: String): GitOperationResult =
        gitBridge.fetch(repositoryId).withGitMutationRevision()

    suspend fun gitPull(repositoryId: String): GitOperationResult =
        gitBridge.pull(repositoryId).withGitMutationRevision()

    suspend fun gitMerge(repositoryId: String): GitOperationResult =
        gitBridge.merge(repositoryId).withGitMutationRevision()

    suspend fun gitCreateBranch(repositoryId: String, name: String): GitOperationResult =
        gitBridge.createBranch(repositoryId, name).withGitMutationRevision()

    suspend fun gitDeleteBranch(repositoryId: String, branchId: String, expectedRevision: Long): GitOperationResult =
        gitBridge.deleteBranch(repositoryId, branchId, expectedRevision, gitRevision.load()).withGitMutationRevision()

    suspend fun gitSaveRemote(repositoryId: String, previousName: String?, remote: GitRemote): GitOperationResult =
        gitBridge.saveRemote(repositoryId, previousName, remote).withGitMutationRevision()

    suspend fun gitRemoveRemote(repositoryId: String, name: String): GitOperationResult =
        gitBridge.removeRemote(repositoryId, name).withGitMutationRevision()

    suspend fun gitProjectAction(repositoryId: String, action: GitProjectAction): GitOperationResult =
        gitBridge.runProjectAction(repositoryId, action).withGitMutationRevision()

    /** A mutation that ran — even one that failed half-way or stopped on conflicts — may have changed the files. */
    private fun GitOperationResult.withGitMutationRevision(): GitOperationResult {
        if (outcome in UNCHANGED_OUTCOMES) return this
        emitGitInvalidated()
        return copy(revision = gitRevision.load())
    }

    fun emitGitInvalidated() {
        val revision = gitRevision.addAndFetch(1L)
        if (!session.shouldEmitPanelMetadata(ActivePanels.GIT)) return
        session.push(LinkPush.GitInvalidated(revision))
        session.emitStats()
    }

    private fun queueGitInvalidation() {
        if (!gitInvalidationPending.compareAndSet(expectedValue = false, newValue = true)) return
        session.sessionScope.launch {
            delay(250.milliseconds)
            gitInvalidationPending.store(false)
            emitGitInvalidated()
        }
    }

    fun close() {
        gitBridge.close("mobile session closed")
    }
}

/** Outcomes the port answers before running anything. */
private val UNCHANGED_OUTCOMES = setOf(
    GitOperationOutcome.BUSY,
    GitOperationOutcome.STALE_TARGET,
    GitOperationOutcome.UNSUPPORTED,
    GitOperationOutcome.MISSING_UPSTREAM,
)
