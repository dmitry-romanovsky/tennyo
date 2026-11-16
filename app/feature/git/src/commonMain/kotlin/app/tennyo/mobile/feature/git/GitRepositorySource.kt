package app.tennyo.mobile.feature.git

/**
 * Where the Git pane reads and changes repositories. Every call throws [GitFault] on failure; operations that reach a
 * verdict (even a refusal) return it as a [GitOperationResult] instead.
 */
interface GitRepositorySource {
    val capabilities: GitSourceCapabilities

    /** Status of every repository, detailing [repositoryId] (or the source's own choice when `null`). */
    suspend fun status(repositoryId: String?): GitStatus

    suspend fun diff(repositoryId: String, changeId: String): GitDiff

    suspend fun commit(repositoryId: String, selection: GitCommitSelection, message: String): GitOperationResult

    /** Discards the changes [selection] names: tracked files go back to `HEAD`, untracked ones are deleted. */
    suspend fun rollback(repositoryId: String, selection: GitCommitSelection): GitOperationResult

    suspend fun history(repositoryId: String, cursor: String?, limit: Int = GIT_HISTORY_PAGE_SIZE): GitHistoryPage

    suspend fun commitDetail(repositoryId: String, commitId: String): GitCommitDetails

    suspend fun commitFileDiff(repositoryId: String, commitId: String, fileId: String): GitDiff

    suspend fun branches(repositoryId: String): GitBranches

    suspend fun checkout(repositoryId: String, target: GitCheckoutTarget): GitOperationResult

    /**
     * Deletes the local branch [branchId] as of the branch list [expectedRevision]; its remote branch stays. Only for
     * a branch offering [GitAction.DELETE].
     */
    suspend fun deleteBranch(repositoryId: String, branchId: String, expectedRevision: Long): GitOperationResult =
        unsupported()

    suspend fun pushPreview(repositoryId: String): GitPushPreview

    suspend fun push(repositoryId: String, pushTargetId: String, previewRevision: Long): GitOperationResult

    /** Only when [GitSourceCapabilities.fetch]. */
    suspend fun fetch(repositoryId: String): GitOperationResult = unsupported()

    /** Only when [GitSourceCapabilities.pull]. */
    suspend fun pull(repositoryId: String): GitOperationResult = unsupported()

    /** Merges the upstream branch after a pull could not fast-forward. Only when [GitSourceCapabilities.pull]. */
    suspend fun merge(repositoryId: String): GitOperationResult = unsupported()

    /**
     * Uses [credentials] for [host] on the next transfer. The source keeps them only once a transfer with them
     * succeeds. Only when [GitSourceCapabilities.credentials].
     */
    suspend fun useCredentials(host: String, credentials: GitCredentials): Unit = unsupported()

    /** Records who commits from now on. Only when [GitSourceCapabilities.identity]. */
    suspend fun saveIdentity(identity: GitIdentity): Unit = unsupported()

    /** Only when [GitSourceCapabilities.createBranch]. */
    suspend fun createBranch(repositoryId: String, name: String): GitOperationResult = unsupported()

    /** Where the repository is, what it is on, and its remotes. Only when [GitSourceCapabilities.projectSettings]. */
    suspend fun projectInfo(repositoryId: String): GitProjectInfo = unsupported()

    /**
     * Adds a remote, or changes [previousName]'s name and URL. Only when [GitSourceCapabilities.projectSettings].
     */
    suspend fun saveRemote(repositoryId: String, previousName: String?, remote: GitRemote): GitOperationResult =
        unsupported()

    /** Only when [GitSourceCapabilities.projectSettings]. */
    suspend fun removeRemote(repositoryId: String, name: String): GitOperationResult = unsupported()

    /** Only when [GitSourceCapabilities.projectSettings]. */
    suspend fun runProjectAction(repositoryId: String, action: GitProjectAction): GitOperationResult = unsupported()

    /**
     * Whether a status refresh nobody asked for (a sync pass, an invalidation) may run now. A source whose host
     * rate-limits it says no while backing off; a refresh the user asks for always runs.
     */
    fun automaticRefreshAllowed(): Boolean = true
}

/** The optional operations a source supports; the UI hides the rest. */
data class GitSourceCapabilities(
    val fetch: Boolean = false,
    val pull: Boolean = false,
    val createBranch: Boolean = false,
    /** The user can supply HTTPS credentials when a remote asks for them. */
    val credentials: Boolean = false,
    /** The user can set the commit author when none is configured. */
    val identity: Boolean = false,
    /** The Project tab: repository details, editable remotes and maintenance actions. */
    val projectSettings: Boolean = false,
)

/** Changes to commit or roll back, as of the status [expectedRevision] they were chosen from. */
data class GitCommitSelection(
    val changeIds: List<String>,
    val expectedRevision: Long,
)

/** The branch to check out, as of the branch list [expectedRevision] it was chosen from. */
data class GitCheckoutTarget(
    val branchId: String,
    val expectedRevision: Long,
)

/** Commits per history page. */
const val GIT_HISTORY_PAGE_SIZE = 100

private fun unsupported(): Nothing = throw GitFault.Rejected(GitRejection.UNSUPPORTED_TARGET)
