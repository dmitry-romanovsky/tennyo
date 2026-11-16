package app.tennyo.host.engine.git

import app.tennyo.contract.GitChangeKind
import app.tennyo.contract.GitCheckoutRequest
import app.tennyo.contract.GitCommitRequest
import app.tennyo.contract.GitOperationOutcome
import app.tennyo.contract.GitOperationResult
import app.tennyo.contract.GitProjectAction
import app.tennyo.contract.GitPushRequest
import app.tennyo.contract.GitRemote
import app.tennyo.contract.GitRollbackRequest
import app.tennyo.host.engine.panes.GitCommitDetailResult
import app.tennyo.host.engine.panes.GitCommitValidation
import app.tennyo.host.engine.panes.GitHistoryResult
import app.tennyo.host.engine.panes.GitMutationGuard
import app.tennyo.host.engine.panes.IdeGitPort
import app.tennyo.host.engine.panes.IdeGitPortFactory
import app.tennyo.host.engine.panes.validateCommitRequest
import app.tennyo.host.engine.platform.HostLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * The project's Git through the `git` executable alone, the same on every host: reads parse Git's porcelain output,
 * and every change runs as an editor task the user can cancel there. A cancelled, timed-out or closed session's call
 * kills its process, so no Git process outlives the call that started it.
 */
@Suppress("TooManyFunctions")
class CliGitPort(
    processes: GitProcesses,
    projectRoots: () -> List<String>,
    private val tasks: GitTaskReporter = SilentGitTasks,
) : IdeGitPort {
    private val git = GitCommandLine(processes, projectRoots)
    private val reads = GitReads(git)
    private val guard = GitMutationGuard()
    private val closed = AtomicBoolean(false)

    override suspend fun status(selectedRepositoryId: String?, revision: Long) =
        read(null) { reads.status(selectedRepositoryId, revision) }

    override suspend fun pushPreview(repositoryId: String, revision: Long) =
        read(null) { reads.pushPreview(repositoryId, revision) }

    override suspend fun diff(repositoryId: String, changeId: String) =
        read(null) { reads.diff(repositoryId, changeId) }

    override suspend fun branches(repositoryId: String, revision: Long) =
        read(null) { reads.branches(repositoryId, revision) }

    override suspend fun history(repositoryId: String, revision: Long, cursor: Int, limit: Int) =
        read(GitHistoryResult.PlatformFailure) { reads.history(repositoryId, revision, cursor, limit) }

    override suspend fun commitDetail(repositoryId: String, revision: Long, commitId: String) =
        read(GitCommitDetailResult.PlatformFailure) { reads.commitDetail(repositoryId, revision, commitId) }

    override suspend fun commitDiff(repositoryId: String, commitId: String, fileId: String) =
        read(null) { reads.commitDiff(repositoryId, commitId, fileId) }

    override suspend fun projectInfo(repositoryId: String) = read(null) { reads.projectInfo(repositoryId) }

    override suspend fun checkout(request: GitCheckoutRequest, revision: Long): GitOperationResult {
        if (request.expectedRevision != revision) return result(request.repositoryId, GitOperationOutcome.STALE_TARGET)
        val branch = reads.branches(request.repositoryId, revision)?.localBranches
            ?.firstOrNull { it.id == request.branchId }
            ?: return result(request.repositoryId, GitOperationOutcome.STALE_TARGET)
        if (branch.isCurrent) return result(request.repositoryId, GitOperationOutcome.UNSUPPORTED)
        return mutate(request.repositoryId, GitTask.CHECKOUT) { repo -> run(repo, "checkout", branch.fullName, "--") }
    }

    override suspend fun push(request: GitPushRequest, revision: Long): GitOperationResult {
        if (request.previewRevision != revision) return result(request.repositoryId, GitOperationOutcome.STALE_TARGET)
        val repo = git.repository(request.repositoryId)
            ?: return result(request.repositoryId, GitOperationOutcome.STALE_TARGET)
        val target = reads.pushTarget(repo, revision)
            ?: return result(request.repositoryId, GitOperationOutcome.MISSING_UPSTREAM)
        if (target.id != request.pushTargetId) return result(request.repositoryId, GitOperationOutcome.STALE_TARGET)
        return mutate(request.repositoryId, GitTask.PUSH) {
            run(it, "push", "--", target.remote, "refs/heads/${target.localBranch}:${target.remoteRef}")
        }
    }

    override suspend fun commit(request: GitCommitRequest, revision: Long): GitOperationResult {
        val refusal = when (validateCommitRequest(request, revision)) {
            GitCommitValidation.Valid -> null
            GitCommitValidation.Stale -> GitOperationOutcome.STALE_TARGET
            GitCommitValidation.Unsupported -> GitOperationOutcome.UNSUPPORTED
        }
        val entries = (if (refusal == null) resolveChanges(request.repositoryId, request.changeIds) else null)
            ?: return result(request.repositoryId, refusal ?: GitOperationOutcome.STALE_TARGET)
        return mutate(request.repositoryId, GitTask.COMMIT) { repo -> commit(repo, entries, request.message) }
    }

    override suspend fun rollback(request: GitRollbackRequest, revision: Long): GitOperationResult {
        val ids = request.changeIds
        val current = request.expectedRevision == revision && ids.isNotEmpty() && ids.distinct().size == ids.size
        val entries = resolveChanges(request.repositoryId, ids).takeIf { current }
            ?: return result(request.repositoryId, GitOperationOutcome.STALE_TARGET)
        return mutate(request.repositoryId, GitTask.ROLLBACK) { repo -> rollback(repo, entries) }
    }

    override suspend fun fetch(repositoryId: String) =
        mutate(repositoryId, GitTask.FETCH) { run(it, "fetch", "--all", "--prune") }

    override suspend fun pull(repositoryId: String) =
        mutate(repositoryId, GitTask.PULL) { run(it, "pull", "--ff-only", "--no-rebase") }

    override suspend fun merge(repositoryId: String) =
        mutate(repositoryId, GitTask.MERGE) { run(it, "merge", "--no-edit", "@{upstream}") }

    override suspend fun createBranch(repositoryId: String, name: String): GitOperationResult {
        if (!isOperand(name)) return result(repositoryId, GitOperationOutcome.REJECTED)
        return mutate(repositoryId, GitTask.CREATE_BRANCH) { repo ->
            val valid = git.run(repo, "check-ref-format", "--branch", name)
            if (valid.succeeded) run(repo, "branch", name) else GitOperationOutcome.REJECTED
        }
    }

    override suspend fun deleteBranch(
        repositoryId: String,
        branchId: String,
        expectedRevision: Long,
        revision: Long,
    ): GitOperationResult {
        if (expectedRevision != revision) return result(repositoryId, GitOperationOutcome.STALE_TARGET)
        val branch = reads.branches(repositoryId, revision)?.localBranches?.firstOrNull { it.id == branchId }
            ?: return result(repositoryId, GitOperationOutcome.STALE_TARGET)
        if (branch.isCurrent) return result(repositoryId, GitOperationOutcome.REJECTED)
        // Forced: the phone asked the user to confirm, and unmerged commits stay reachable through the reflog.
        return mutate(repositoryId, GitTask.DELETE_BRANCH) { repo -> run(repo, "branch", "-D", "--", branch.fullName) }
    }

    override suspend fun saveRemote(
        repositoryId: String,
        previousName: String?,
        remote: GitRemote,
    ): GitOperationResult {
        if (!listOfNotNull(previousName, remote.name).all(::isRemoteName) || !isOperand(remote.url)) {
            return result(repositoryId, GitOperationOutcome.REJECTED)
        }
        return mutate(repositoryId, GitTask.SAVE_REMOTE) { repo -> saveRemote(repo, previousName, remote) }
    }

    override suspend fun removeRemote(repositoryId: String, name: String): GitOperationResult {
        if (!isRemoteName(name)) return result(repositoryId, GitOperationOutcome.REJECTED)
        return mutate(repositoryId, GitTask.REMOVE_REMOTE) { run(it, "remote", "remove", name) }
    }

    override suspend fun runProjectAction(repositoryId: String, action: GitProjectAction): GitOperationResult =
        when (action) {
            GitProjectAction.STASH -> mutate(repositoryId, GitTask.STASH) { repo ->
                val stashed = git.run(repo, "stash", "push", timeout = WRITE_TIMEOUT)
                // Nothing to put aside is a refusal, not a silent success.
                if (stashed.succeeded && NO_LOCAL_CHANGES in stashed.text) {
                    GitOperationOutcome.REJECTED
                } else {
                    stashed.outcome()
                }
            }

            GitProjectAction.APPLY_STASH -> mutate(repositoryId, GitTask.APPLY_STASH) { run(it, "stash", "pop") }
            GitProjectAction.CLEAN_UP -> mutate(repositoryId, GitTask.CLEAN_UP) { run(it, "gc", "--quiet") }
            GitProjectAction.RESET_HEAD -> mutate(repositoryId, GitTask.RESET_HEAD) { repo ->
                if (reads.hasCommits(repo)) {
                    run(repo, "reset", "--hard", "--quiet", "HEAD")
                } else {
                    GitOperationOutcome.UNSUPPORTED
                }
            }
        }

    override fun close(reason: String) {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        LOG.debug { "Git port closed: $reason" }
    }

    /** Untracked files are added first; with paths, `commit` takes exactly those files, whatever else is staged. */
    private suspend fun commit(repo: GitRepo, entries: List<StatusEntry>, message: String): GitOperationOutcome {
        val untracked = entries.filter { it.kind == GitChangeKind.UNTRACKED }.map(StatusEntry::path)
        val added = untracked.takeIf(List<String>::isNotEmpty)?.let { git.runWithPaths(repo, it, "add") }
        if (added?.succeeded == false) return added.outcome()
        val paths = entries.flatMap { listOfNotNull(it.beforePath, it.afterPath) }.distinct()
        val committed = git.runWithPaths(repo, paths, "commit", "--quiet", "--message=$message")
        // A refused commit leaves untracked files untracked, as they were.
        if (!committed.succeeded && added != null) git.runWithPaths(repo, untracked, "reset", "--quiet")
        return committed.outcome()
    }

    /** Untracked files are deleted; every other path goes back to `HEAD` in both the index and the working tree. */
    private suspend fun rollback(repo: GitRepo, entries: List<StatusEntry>): GitOperationOutcome {
        val (untracked, tracked) = entries.partition { it.kind == GitChangeKind.UNTRACKED }
        val cleanFailure = untracked.map(StatusEntry::path).chunked(PATHS_PER_COMMAND).firstNotNullOfOrNull { paths ->
            git.run(repo, "clean", "--force", "--quiet", "--", *paths.toTypedArray(), timeout = WRITE_TIMEOUT)
                .takeUnless(GitProcessResult::succeeded)
        }
        val paths = tracked.flatMap { listOfNotNull(it.beforePath, it.afterPath) }.distinct()
        return when {
            cleanFailure != null -> cleanFailure.outcome()
            paths.isEmpty() -> GitOperationOutcome.SUCCESS
            reads.hasCommits(repo) ->
                git.runWithPaths(repo, paths, "restore", "--source=HEAD", "--staged", "--worktree").outcome()
            else -> git.runWithPaths(repo, paths, "rm", "-r", "--force", "--quiet").outcome()
        }
    }

    private suspend fun saveRemote(repo: GitRepo, previousName: String?, remote: GitRemote): GitOperationOutcome {
        if (previousName == null) return run(repo, "remote", "add", "--", remote.name, remote.url)
        if (previousName != remote.name) {
            val renamed = git.run(repo, "remote", "rename", previousName, remote.name, timeout = WRITE_TIMEOUT)
            if (!renamed.succeeded) return renamed.outcome()
        }
        // The phone saw the URL without credentials; sending that back unchanged must not strip them.
        val current = reads.remotes(repo).firstOrNull { it.name == remote.name }?.url
        return if (current == remote.url) {
            GitOperationOutcome.SUCCESS
        } else {
            run(repo, "remote", "set-url", "--", remote.name, remote.url)
        }
    }

    private suspend fun resolveChanges(repositoryId: String, changeIds: List<String>): List<StatusEntry>? =
        git.repository(repositoryId)?.let { reads.resolveChanges(it, changeIds) }

    private suspend fun run(repo: GitRepo, vararg arguments: String): GitOperationOutcome =
        git.run(repo, *arguments, timeout = WRITE_TIMEOUT).outcome()

    /**
     * Runs [change] on [repositoryId] as an editor task, one change per repository at a time. A task the user
     * cancels in the editor, or one that runs past its timeout, ends with its process killed.
     */
    private suspend fun mutate(
        repositoryId: String,
        task: GitTask,
        change: suspend (GitRepo) -> GitOperationOutcome,
    ): GitOperationResult {
        if (closed.load()) return result(repositoryId, GitOperationOutcome.UNSUPPORTED)
        val repo = git.repository(repositoryId) ?: return result(repositoryId, GitOperationOutcome.STALE_TARGET)
        return guard.run(repositoryId, onBusy = { result(repositoryId, GitOperationOutcome.BUSY) }) {
            val outcome = try {
                tasks.run(task, repo.name) { withTimeout(WRITE_TIMEOUT) { change(repo) } }
            } catch (timedOut: TimeoutCancellationException) {
                LOG.warn("Git ${task.name.lowercase()} timed out", timedOut)
                GitOperationOutcome.PLATFORM_FAILURE
            } catch (cancelled: CancellationException) {
                // The session going away rethrows; only the editor's own cancel button answers CANCELLED.
                currentCoroutineContext().ensureActive()
                LOG.debug { "Git ${task.name.lowercase()} cancelled in the editor: ${cancelled.message}" }
                GitOperationOutcome.CANCELLED
            } catch (launch: GitLaunchException) {
                LOG.warn("Git could not be started for ${task.name.lowercase()}", launch)
                GitOperationOutcome.PLATFORM_FAILURE
            }
            result(repositoryId, outcome)
        }
    }

    /** Runs a read; Git missing or a read past its timeout answers [failure] instead. */
    private suspend fun <T> read(failure: T, block: suspend () -> T): T =
        try {
            block()
        } catch (timedOut: TimeoutCancellationException) {
            LOG.warn("A Git read timed out", timedOut)
            failure
        } catch (launch: GitLaunchException) {
            LOG.warn("Git could not be started", launch)
            failure
        }

    private fun result(repositoryId: String, outcome: GitOperationOutcome) = GitOperationResult(repositoryId, outcome)

    companion object {
        private val LOG = HostLogger("CliGitPort")

        /** The factory a host binds: [projectRoots] are where repositories are looked for, [tasks] where they show. */
        fun factory(
            processes: GitProcesses,
            projectRoots: () -> List<String>,
            tasks: GitTaskReporter,
            watchChanges: (onChanged: () -> Unit) -> AutoCloseable,
        ): IdeGitPortFactory = object : IdeGitPortFactory {
            override fun create(onChangesChanged: () -> Unit): IdeGitPort {
                val port = CliGitPort(processes, projectRoots, tasks)
                val watch = watchChanges(onChangesChanged)
                return object : IdeGitPort by port {
                    override fun close(reason: String) {
                        watch.close()
                        port.close(reason)
                    }
                }
            }
        }
    }
}

/** A value that may follow a Git option: never empty, never read as an option itself. */
private fun isOperand(value: String): Boolean = value.isNotBlank() && !value.startsWith('-')

private fun isRemoteName(name: String): Boolean = isOperand(name) && name.none(Char::isWhitespace)

/** What a finished Git command means for the phone, read from its exit code and Git's English messages. */
internal fun GitProcessResult.outcome(): GitOperationOutcome {
    if (succeeded) return GitOperationOutcome.SUCCESS
    val message = stderr + text
    return when {
        CONFLICT_MARKERS.any { it in message } -> GitOperationOutcome.CONFLICTS
        NON_FAST_FORWARD_MARKERS.any { it in message } -> GitOperationOutcome.NON_FAST_FORWARD
        AUTH_MARKERS.any { it in message } -> GitOperationOutcome.AUTH_FAILED
        IDENTITY_MARKERS.any { it in message } -> GitOperationOutcome.IDENTITY_MISSING
        UPSTREAM_MARKERS.any { it in message } -> GitOperationOutcome.MISSING_UPSTREAM
        else -> GitOperationOutcome.REJECTED
    }
}

private val CONFLICT_MARKERS = listOf("CONFLICT (", "Automatic merge failed", "needs merge")
private val NON_FAST_FORWARD_MARKERS = listOf("Not possible to fast-forward", "not possible to fast-forward")
private val AUTH_MARKERS = listOf(
    "Authentication failed",
    "could not read Username",
    "could not read Password",
    "terminal prompts disabled",
    "Permission denied (publickey",
    "Host key verification failed",
)
private val IDENTITY_MARKERS = listOf("Please tell me who you are", "unable to auto-detect email address")
private val UPSTREAM_MARKERS = listOf("no tracking information", "No upstream configured", "no upstream configured")
private const val NO_LOCAL_CHANGES = "No local changes to save"
private const val PATHS_PER_COMMAND = 100
