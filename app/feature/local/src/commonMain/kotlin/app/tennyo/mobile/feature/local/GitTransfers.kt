package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitCredentials
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitRepositorySource
import kotlinx.coroutines.flow.Flow

/** A Git operation that talks to a remote and may outlive the screen that started it. */
sealed interface GitTransfer {
    val workspaceSlug: String

    data class Clone(override val workspaceSlug: String, val url: String) : GitTransfer

    data class Fetch(override val workspaceSlug: String) : GitTransfer

    /** Pushes the current branch to its upstream. */
    data class Push(override val workspaceSlug: String) : GitTransfer
}

sealed interface GitTransferState {
    data object Queued : GitTransferState

    /** [progress] is the share of the current phase done, from 0 to 1, when the remote reports it. */
    data class Running(val progress: Float?) : GitTransferState

    data object Succeeded : GitTransferState

    data class Failed(val fault: GitFault) : GitTransferState

    data object Cancelled : GitTransferState

    val isFinished: Boolean get() = this is Succeeded || this is Failed || this is Cancelled
}

/** Runs transfers where they survive the app leaving the foreground, and reports each one's progress. */
interface GitTransferScheduler {
    /** Starts [transfer]; the flow reports its progress and completes after its final state. */
    fun start(transfer: GitTransfer): Flow<GitTransferState>

    fun cancel(transfer: GitTransfer)

    /** Uses [credentials] for the next transfer to [host]; they are kept only once that transfer succeeds. */
    fun stageCredentials(host: String, credentials: GitCredentials)
}

/** The Git side of on-device workspaces: a repository source per workspace, and `git init` for new ones. */
interface LocalGitRepositories {
    fun source(workspaceSlug: String): GitRepositorySource

    suspend fun init(workspaceSlug: String)
}
