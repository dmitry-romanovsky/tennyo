package app.tennyo.contract

import kotlinx.serialization.Serializable

@Serializable
enum class GitAction { REFRESH, VIEW_DIFF, VIEW_HISTORY, VIEW_BRANCHES, PUSH, CHECKOUT, ROLLBACK, DELETE }

@Serializable
enum class GitOperationOutcome {
    SUCCESS,
    CANCELLED,
    REJECTED,
    BUSY,
    MISSING_UPSTREAM,
    STALE_TARGET,
    UNSUPPORTED,
    PLATFORM_FAILURE,

    /** A pull could not fast-forward; the phone may ask for a merge instead. */
    NON_FAST_FORWARD,

    /** The operation stopped with conflicts left in the working tree. */
    CONFLICTS,

    /** The remote refused the computer's credentials, or the computer has none for it. */
    AUTH_FAILED,

    /** Committing needs `user.name` and `user.email`, which the computer's Git does not have. */
    IDENTITY_MISSING,
}

@Serializable
enum class GitChangeKind { ADDED, MODIFIED, RENAMED, DELETED, MOVED, UNTRACKED, CONFLICTED }

@Serializable
enum class GitRefKind { LOCAL_BRANCH, REMOTE_BRANCH }

const val GIT_MAX_SELECTED_CHANGES = 500
const val GIT_MAX_COMMIT_MESSAGE_LENGTH = 16_000

@Serializable
data class GitCommitCapability(
    val maxSelectedChangeCount: Int,
    val maxMessageLength: Int,
)

@Serializable
data class GitChange(
    val id: String,
    val kind: GitChangeKind,
    val beforePath: String? = null,
    val afterPath: String? = null,
    val fileName: String,
    val parentPath: String,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
    val availableActions: Set<GitAction> = emptySet(),
)

@Serializable
data class GitChangeList(
    val id: String,
    val name: String,
    val changes: List<GitChange>,
)

@Serializable
data class GitRepositorySummary(
    val id: String,
    val rootName: String,
    /**
     * Where the repository root lies below the project folder, `/`-separated; empty when it is the folder itself and
     * `null` when it lies outside it, so its files cannot be opened as project files.
     */
    val projectPath: String? = "",
    val currentBranch: String? = null,
    val upstreamBranch: String? = null,
    val aheadCount: Int? = null,
    val behindCount: Int? = null,
    val isDetachedHead: Boolean = false,
    val availableActions: Set<GitAction> = setOf(
        GitAction.REFRESH,
        GitAction.VIEW_HISTORY,
        GitAction.VIEW_BRANCHES,
    ),
    val changeLists: List<GitChangeList> = emptyList(),
    val changesTruncated: Boolean = false,
    val commitCapability: GitCommitCapability? = null,
)

@Serializable
data class GitStatusResponse(
    val repositories: List<GitRepositorySummary>,
    val selectedRepositoryId: String? = null,
    val revision: Long = 0,
)

@Serializable
data class GitDiffResponse(
    val repositoryId: String,
    val changeId: String,
    val beforePath: String? = null,
    val afterPath: String? = null,
    val beforeRevision: String? = null,
    val afterRevision: String? = null,
    val beforeContent: String? = null,
    val afterContent: String? = null,
    val isBinary: Boolean = false,
    val unavailableReason: String? = null,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
)

@Serializable
data class GitBranch(
    val id: String,
    val fullName: String,
    val displayName: String,
    val isCurrent: Boolean = false,
    val trackedUpstream: String? = null,
    val remoteName: String? = null,
    val upstreamId: String? = null,
    val aheadCount: Int? = null,
    val behindCount: Int? = null,
    val availableActions: Set<GitAction> = emptySet(),
)

@Serializable
data class GitBranchesResponse(
    val repositoryId: String,
    val revision: Long,
    val isDetachedHead: Boolean = false,
    val localBranches: List<GitBranch>,
    val remoteBranches: List<GitBranch>,
    val localBranchesTruncated: Boolean = false,
    val remoteBranchesTruncated: Boolean = false,
)

@Serializable
data class GitCommitRef(
    val id: String,
    val fullName: String,
    val displayName: String,
    val kind: GitRefKind,
)

@Serializable
data class GitCommitSummary(
    val id: String,
    val shortId: String,
    val subject: String,
    val authorName: String,
    val authoredAtEpochMillis: Long,
    val committerName: String,
    val committedAtEpochMillis: Long,
    val parentIds: List<String>,
    val refs: List<GitCommitRef> = emptyList(),
)

@Serializable
data class GitHistoryResponse(
    val repositoryId: String,
    val revision: Long,
    val commits: List<GitCommitSummary>,
    val refsTruncated: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
data class GitCommitFile(
    val id: String,
    val kind: GitChangeKind,
    val beforePath: String? = null,
    val afterPath: String? = null,
    val availableActions: Set<GitAction> = setOf(GitAction.VIEW_DIFF),
)

@Serializable
data class GitCommitDetail(
    val id: String,
    val shortId: String,
    val subject: String,
    val fullMessage: String,
    val authorName: String,
    val authorEmail: String? = null,
    val authoredAtEpochMillis: Long,
    val committerName: String,
    val committerEmail: String? = null,
    val committedAtEpochMillis: Long,
    val parentIds: List<String>,
    val refs: List<GitCommitRef> = emptyList(),
)

@Serializable
data class GitCommitDetailResponse(
    val repositoryId: String,
    val revision: Long,
    val commit: GitCommitDetail,
    val files: List<GitCommitFile>,
    val filesTruncated: Boolean = false,
)

@Serializable
data class GitPushPreviewResponse(
    val repositoryId: String,
    val revision: Long,
    val pushTargetId: String,
    val localBranch: String,
    val upstreamBranch: String,
    val remoteName: String,
    val remoteDisplayUrl: String,
    val commits: List<GitCommitSummary>,
    val commitsTruncated: Boolean,
)

@Serializable
data class GitCheckoutRequest(
    val repositoryId: String,
    val branchId: String,
    val expectedRevision: Long,
)

@Serializable
data class GitPushRequest(
    val repositoryId: String,
    val pushTargetId: String,
    val previewRevision: Long,
)

@Serializable
data class GitCommitRequest(
    val repositoryId: String,
    val changeIds: List<String>,
    val message: String,
    val expectedRevision: Long,
)

/** Discards the working-tree changes [changeIds] name, as of the status [expectedRevision] they were chosen from. */
@Serializable
data class GitRollbackRequest(
    val repositoryId: String,
    val changeIds: List<String>,
    val expectedRevision: Long,
)

@Serializable
data class GitRemote(val name: String, val url: String)

/** What the Project tab shows about a repository. Remote URLs never carry credentials. */
@Serializable
data class GitProjectInfoResponse(
    val repositoryId: String,
    val location: String,
    val branch: String? = null,
    val head: GitCommitSummary? = null,
    val identityName: String? = null,
    val identityEmail: String? = null,
    val remotes: List<GitRemote>,
    val stashCount: Int,
    val tagCount: Int,
)

/** Maintenance the Project tab offers beside fetch, pull and push. */
@Serializable
enum class GitProjectAction {
    /** Puts the uncommitted changes to tracked files aside. */
    STASH,

    /** Brings the latest stash back and drops it. */
    APPLY_STASH,

    /** Packs the repository and removes unreachable objects. */
    CLEAN_UP,

    /** Resets the index and every tracked file to `HEAD`; untracked files stay. */
    RESET_HEAD,
}

@Serializable
data class GitOperationResult(
    val repositoryId: String,
    val outcome: GitOperationOutcome,
    val revision: Long? = null,
)
