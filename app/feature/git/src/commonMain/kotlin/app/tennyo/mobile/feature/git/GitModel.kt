package app.tennyo.mobile.feature.git

/*
 * The Git pane's own model of a repository, independent of where it comes from: the IDE over the link
 * (IdeGitSource) or an on-device checkout. Sources translate into these types at their boundary.
 */

/** What the source lets the user do with an item; the UI shows only these. */
enum class GitAction { REFRESH, VIEW_DIFF, VIEW_HISTORY, VIEW_BRANCHES, PUSH, CHECKOUT, ROLLBACK, DELETE }

enum class GitOutcome {
    SUCCESS,
    CANCELLED,
    REJECTED,
    BUSY,
    MISSING_UPSTREAM,
    STALE_TARGET,
    UNSUPPORTED,
    PLATFORM_FAILURE,

    /** The remote refused the credentials the source has, or it has none for it. */
    AUTH_FAILED,
}

enum class GitChangeKind { ADDED, MODIFIED, RENAMED, DELETED, MOVED, UNTRACKED, CONFLICTED }

enum class GitRefKind { LOCAL_BRANCH, REMOTE_BRANCH }

/** How large a commit the source accepts; a repository without limits cannot be committed to from the phone. */
data class GitCommitLimits(
    val maxSelectedChangeCount: Int,
    val maxMessageLength: Int,
)

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

data class GitChangeList(
    val id: String,
    val name: String,
    val changes: List<GitChange>,
)

data class GitRepository(
    val id: String,
    val rootName: String,
    /** Where the repository lies below the project folder (empty: the folder itself); `null` when outside it. */
    val projectPath: String? = "",
    val currentBranch: String? = null,
    val upstreamBranch: String? = null,
    val aheadCount: Int? = null,
    val behindCount: Int? = null,
    val isDetachedHead: Boolean = false,
    val availableActions: Set<GitAction> = setOf(GitAction.REFRESH, GitAction.VIEW_HISTORY, GitAction.VIEW_BRANCHES),
    val changeLists: List<GitChangeList> = emptyList(),
    val changesTruncated: Boolean = false,
    val commitCapability: GitCommitLimits? = null,
)

/** Every repository the source knows, at [revision]; newer revisions supersede older answers. */
data class GitStatus(
    val repositories: List<GitRepository>,
    val selectedRepositoryId: String? = null,
    val revision: Long = 0,
)

data class GitDiff(
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
    /** Either side is longer than the source is willing to send; the pane shows no content. */
    val tooLarge: Boolean = false,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
)

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

data class GitBranches(
    val repositoryId: String,
    val revision: Long,
    val isDetachedHead: Boolean = false,
    val localBranches: List<GitBranch>,
    val remoteBranches: List<GitBranch>,
    val localBranchesTruncated: Boolean = false,
    val remoteBranchesTruncated: Boolean = false,
)

data class GitCommitRef(
    val id: String,
    val fullName: String,
    val displayName: String,
    val kind: GitRefKind,
)

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

data class GitHistoryPage(
    val repositoryId: String,
    val revision: Long,
    val commits: List<GitCommitSummary>,
    val refsTruncated: Boolean = false,
    val nextCursor: String? = null,
)

data class GitCommitFile(
    val id: String,
    val kind: GitChangeKind,
    val beforePath: String? = null,
    val afterPath: String? = null,
    val availableActions: Set<GitAction> = setOf(GitAction.VIEW_DIFF),
)

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

data class GitCommitDetails(
    val repositoryId: String,
    val revision: Long,
    val commit: GitCommitDetail,
    val files: List<GitCommitFile>,
    val filesTruncated: Boolean = false,
)

/** The commits a push would send, and the target it would send them to, as of [revision]. */
data class GitPushPreview(
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

/** The author a commit is recorded under. */
data class GitIdentity(val name: String, val email: String)

/** What a remote's host accepts for HTTPS: a user name and a password or access token. */
class GitCredentials(val username: String, val secret: String) {
    override fun toString(): String = "GitCredentials(username=$username)"
}

/** What the Project tab shows about a repository. */
data class GitProjectInfo(
    /** The folder the repository lives in, as the user would name it. */
    val location: String,
    val branch: String?,
    val head: GitCommitSummary?,
    val identity: GitIdentity?,
    val remotes: List<GitRemote>,
    val stashCount: Int,
    val tagCount: Int,
)

data class GitRemote(val name: String, val url: String)

/** Maintenance the Project tab offers beside fetch, pull and push. */
enum class GitProjectAction {
    /** Puts the uncommitted changes aside. */
    STASH,

    /** Brings the latest stash back and drops it. */
    APPLY_STASH,

    /** Packs the repository and removes unreachable objects. */
    CLEAN_UP,

    /** Resets the index and every tracked file to `HEAD`; untracked files stay. Asks first: it discards work. */
    RESET_HEAD,
}

data class GitOperationResult(
    val repositoryId: String,
    val outcome: GitOutcome,
    val revision: Long? = null,
)
