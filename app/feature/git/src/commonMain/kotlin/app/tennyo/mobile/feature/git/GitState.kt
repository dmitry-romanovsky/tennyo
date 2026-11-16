package app.tennyo.mobile.feature.git

enum class GitSection {
    Changes,
    History,

    /** Repository details, remotes and maintenance; only where the source offers them. */
    Project,
}

/** The sections [capabilities] allow, in tab order. */
internal fun GitSourceCapabilities.sections(): List<GitSection> =
    GitSection.entries.filter { it != GitSection.Project || projectSettings }

internal val GitRepository.hasPushableCommits: Boolean
    get() = GitAction.PUSH in availableActions && aheadCount?.let { it > 0 } == true

data class GitDiffKey(
    val repositoryId: String,
    val changeId: String,
    val commitId: String? = null,
)

data class GitCommitKey(
    val repositoryId: String,
    val commitId: String,
)

data class GitCheckoutSelection(
    val repositoryId: String,
    val branchId: String,
    val branchName: String,
    val expectedRevision: Long,
)

/** A change the pane asks the source to make, kept as data so a prompt can run it again. */
sealed interface GitMutation {
    data class Commit(
        val selection: GitCommitSelection,
        val message: String,
        /** Opens the push preview once the commit succeeds. */
        val pushAfter: Boolean = false,
    ) : GitMutation
    data class Rollback(val selection: GitCommitSelection) : GitMutation
    data class Checkout(val target: GitCheckoutTarget) : GitMutation
    data class Push(val pushTargetId: String, val previewRevision: Long) : GitMutation
    data object Fetch : GitMutation
    data object Pull : GitMutation
    data object Merge : GitMutation
    data class CreateBranch(val name: String) : GitMutation
    data class DeleteBranch(val branchId: String, val expectedRevision: Long) : GitMutation
    data class SaveRemote(val previousName: String?, val remote: GitRemote) : GitMutation
    data class RemoveRemote(val name: String) : GitMutation
    data class ProjectAction(val action: GitProjectAction) : GitMutation
}

/** A question the pane needs answered before it can go on. */
sealed interface GitPrompt {
    val repositoryId: String

    /** [host] refused the transfer; the answer is used once to run [retry] again. */
    data class Credentials(override val repositoryId: String, val host: String, val retry: GitMutation) : GitPrompt

    /** Sets the commit author; when [retry] is set, saving runs it again. */
    data class Identity(
        override val repositoryId: String,
        val retry: GitMutation?,
        val current: GitIdentity? = null,
    ) : GitPrompt

    /** A pull could not fast-forward; the user may merge instead. */
    data class Diverged(override val repositoryId: String) : GitPrompt

    data class NewBranch(override val repositoryId: String) : GitPrompt

    /** Adds a remote when [remote] is `null`, otherwise edits or removes it. */
    data class EditRemote(override val repositoryId: String, val remote: GitRemote?) : GitPrompt

    /** [discard] throws work away for good, so it runs only once the user confirms. */
    data class ConfirmDiscard(override val repositoryId: String, val discard: GitDiscard) : GitPrompt
}

/** Work the user is about to throw away. */
sealed interface GitDiscard {
    /** Changed files, as the status at [expectedRevision] showed them. */
    data class Changes(val changes: List<GitChange>, val expectedRevision: Long) : GitDiscard

    /** Every change to tracked files: a reset to `HEAD`. */
    data object AllTracked : GitDiscard

    /** A local branch, as the branch list at [expectedRevision] showed it; its remote branch stays. */
    data class Branch(val branchId: String, val name: String, val expectedRevision: Long) : GitDiscard
}

sealed interface GitMutationOutcome {
    data class Received(val outcome: GitOutcome) : GitMutationOutcome
    data object Unknown : GitMutationOutcome
}

sealed interface GitRequestKey {
    data object Overview : GitRequestKey
    data class History(val repositoryId: String) : GitRequestKey
    data class HistoryNext(val repositoryId: String, val cursor: String) : GitRequestKey
    data class Branches(val repositoryId: String) : GitRequestKey
    data class Diff(val target: GitDiffKey) : GitRequestKey
    data class CommitDetail(val target: GitCommitKey) : GitRequestKey
    data class PushPreview(val repositoryId: String) : GitRequestKey
    data class ProjectInfo(val repositoryId: String) : GitRequestKey
}

data class GitCommitDraft(
    val selectedChangeIds: Set<String>,
    val message: String = "",
    val revision: Long,
    val stale: Boolean = false,
)

data class GitHistoryState(
    val response: GitHistoryPage,
    val loadingNextPage: Boolean = false,
) {
    val nextCursor: String? get() = response.nextCursor
}

data class GitState(
    val capabilities: GitSourceCapabilities = GitSourceCapabilities(),
    /** The workspace can open a changed file in its editor. */
    val opensFilesInEditor: Boolean = false,
    val overview: GitStatus? = null,
    val selectedRepositoryId: String? = null,
    val selectedSection: GitSection = GitSection.Changes,
    /** The branch list, opened from the branch bar over whichever section is shown. */
    val branchesOpen: Boolean = false,
    val branchSearchQuery: String = "",
    val expandedBranchFolders: Set<String> = emptySet(),
    val diffs: Map<GitDiffKey, GitDiff> = emptyMap(),
    val diffPresentations: Map<GitDiffKey, GitDiffPresentation> = emptyMap(),
    val selectedDiff: GitDiffKey? = null,
    val histories: Map<String, GitHistoryState> = emptyMap(),
    val commitDetails: Map<GitCommitKey, GitCommitDetails> = emptyMap(),
    val selectedCommit: GitCommitKey? = null,
    val branches: Map<String, GitBranches> = emptyMap(),
    val projectInfos: Map<String, GitProjectInfo> = emptyMap(),
    val pushPreviews: Map<String, GitPushPreview> = emptyMap(),
    val checkoutSelection: GitCheckoutSelection? = null,
    val loadingKeys: Set<GitRequestKey> = emptySet(),
    val requestFailures: Map<GitRequestKey, GitFault> = emptyMap(),
    val commitDrafts: Map<String, GitCommitDraft> = emptyMap(),
    val mutatingRepositories: Set<String> = emptySet(),
    val mutationOutcome: Map<String, GitMutationOutcome> = emptyMap(),
    val latestInvalidationRevision: Long = 0,
    val appliedOverviewRevision: Long = 0,
    val overviewStale: Boolean = false,
    /** When the phone last heard a fresh overview from the host — what the branch bar's sync age counts from. */
    val overviewReceivedAtEpochMillis: Long = 0,
    val prompt: GitPrompt? = null,
) {
    val hasNestedDestination: Boolean
        get() = selectedDiff != null || selectedCommit != null || checkoutSelection != null ||
                pushPreviews.isNotEmpty() || prompt != null || branchesOpen
    fun isMutating(repositoryId: String): Boolean = repositoryId in mutatingRepositories
    fun isMutationBlocked(repositoryId: String): Boolean =
        isMutating(repositoryId) || mutationOutcome[repositoryId] == GitMutationOutcome.Unknown

    fun selectRepository(repositoryId: String): GitState = copy(
        selectedRepositoryId = repositoryId,
        selectedSection = GitSection.Changes,
        branchesOpen = false,
        branchSearchQuery = "",
        expandedBranchFolders = emptySet(),
        selectedDiff = null,
        selectedCommit = null,
        pushPreviews = emptyMap(),
        checkoutSelection = null,
        requestFailures = emptyMap(),
    )

    fun invalidate(revision: Long): GitState {
        if (revision <= latestInvalidationRevision) return this
        return copy(
            latestInvalidationRevision = revision,
            overviewStale = revision > appliedOverviewRevision,
            commitDrafts = commitDrafts.mapValues { (_, draft) -> draft.copy(stale = true) },
            pushPreviews = emptyMap(),
            checkoutSelection = checkoutSelection?.takeIf { it.repositoryId in mutatingRepositories },
        )
    }

    /**
     * An answer older than the latest invalidation is still shown, marked stale, so a host that invalidates faster
     * than it answers never leaves the pane loading; only one older than what is already shown is dropped.
     */
    fun canAcceptOverview(requestedRepositoryId: String?, response: GitStatus): Boolean =
        response.revision >= appliedOverviewRevision &&
                (requestedRepositoryId == null || requestedRepositoryId == selectedRepositoryId) &&
                (requestedRepositoryId == null || response.selectedRepositoryId == requestedRepositoryId)

    fun acceptOverview(response: GitStatus): GitState {
        val reconciledDrafts = response.repositories.mapNotNull { repository ->
            if (repository.commitCapability == null) return@mapNotNull null
            val currentIds = repository.changeLists.flatMap { it.changes }.mapTo(linkedSetOf()) { it.id }
            val previous = commitDrafts[repository.id]
            repository.id to if (previous == null) {
                GitCommitDraft(currentIds, revision = response.revision)
            } else {
                previous.copy(
                    selectedChangeIds = previous.selectedChangeIds intersect currentIds,
                    revision = response.revision,
                    stale = false,
                )
            }
        }.toMap()
        val reconciledSelection = response.repositories
            .firstOrNull { it.id == response.selectedRepositoryId }
            ?.id
            ?: response.repositories.firstOrNull { it.id == selectedRepositoryId }?.id
            ?: response.repositories.firstOrNull()?.id
        return copy(
            overview = response,
            selectedRepositoryId = reconciledSelection,
            appliedOverviewRevision = maxOf(appliedOverviewRevision, response.revision),
            overviewStale = response.revision < latestInvalidationRevision,
            commitDrafts = reconciledDrafts,
        )
    }

    fun canCommit(repositoryId: String): Boolean {
        val repository = overview?.repositories?.firstOrNull { it.id == repositoryId } ?: return false
        val capability = repository.commitCapability ?: return false
        val draft = commitDrafts[repositoryId] ?: return false
        return !draft.stale && draft.revision == overview.revision && draft.selectedChangeIds.isNotEmpty() &&
                draft.selectedChangeIds.size <= capability.maxSelectedChangeCount && draft.message.trim()
            .isNotEmpty() &&
                draft.message.length <= capability.maxMessageLength && !isMutationBlocked(repositoryId)
    }

    fun acceptHistoryPage(
        repositoryId: String,
        response: GitHistoryPage,
        append: Boolean,
    ): GitState {
        val previous = histories[repositoryId]?.response
        val merged = if (append && previous?.revision == response.revision) {
            response.copy(commits = (previous.commits + response.commits).distinctBy { it.id })
        } else {
            response
        }
        return copy(histories = histories + (repositoryId to GitHistoryState(merged)))
    }

    fun acceptDiff(
        key: GitDiffKey,
        response: GitDiff,
        presentation: GitDiffPresentation,
    ): GitState = copy(
        diffs = diffs + (key to response),
        diffPresentations = diffPresentations + (key to presentation),
    )

    fun acceptCommitDetail(
        key: GitCommitKey,
        response: GitCommitDetails,
    ): GitState = copy(commitDetails = commitDetails + (key to response))
}

/** The selected changes of [repositoryId] that can be rolled back; empty while the selection is stale or blocked. */
internal fun GitState.rollbackableSelection(repositoryId: String): List<GitChange> {
    val draft = commitDrafts[repositoryId]?.takeUnless { it.stale } ?: return emptyList()
    if (isMutationBlocked(repositoryId)) return emptyList()
    return overview?.repositories?.firstOrNull { it.id == repositoryId }
        ?.changeLists?.flatMap(GitChangeList::changes)
        ?.filter { it.id in draft.selectedChangeIds && GitAction.ROLLBACK in it.availableActions }
        .orEmpty()
}

/** The confirmation before the working change [changeId] is rolled back; `null` when it cannot be. */
internal fun GitState.rollbackPrompt(repositoryId: String, changeId: String): GitPrompt? {
    val status = overview ?: return null
    val change = status.repositories.firstOrNull { it.id == repositoryId }
        ?.changeLists?.flatMap(GitChangeList::changes)
        ?.firstOrNull { it.id == changeId && GitAction.ROLLBACK in it.availableActions }
        ?: return null
    return GitPrompt.ConfirmDiscard(repositoryId, GitDiscard.Changes(listOf(change), status.revision))
}

internal val GitState.isOverviewPending: Boolean
    get() = overview == null && GitRequestKey.Overview !in requestFailures

internal fun GitState.failRequest(key: GitRequestKey, failure: GitFault): GitState {
    val failed = copy(loadingKeys = loadingKeys - key, requestFailures = requestFailures + (key to failure))
    val page = key as? GitRequestKey.HistoryNext ?: return failed
    val history = failed.histories[page.repositoryId] ?: return failed
    return failed.copy(
        histories = failed.histories + (page.repositoryId to history.copy(loadingNextPage = false)),
    )
}

internal fun shouldStartGitOverviewRequest(manual: Boolean, backoffAllows: Boolean, active: Boolean): Boolean =
    !active && (manual || backoffAllows)
