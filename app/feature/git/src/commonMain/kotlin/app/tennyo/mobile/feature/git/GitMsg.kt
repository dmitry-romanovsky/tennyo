package app.tennyo.mobile.feature.git

/** Every change to [GitState]; applied only through [reduce]. */
internal sealed interface GitMsg {
    data class RepositorySelected(val repositoryId: String) : GitMsg
    data class SectionSelected(val section: GitSection) : GitMsg
    data object BranchesOpened : GitMsg
    data object BranchesClosed : GitMsg
    data class BranchSearchChanged(val query: String) : GitMsg
    data class BranchFolderToggled(val folderId: String) : GitMsg
    data class ChangeToggled(val repositoryId: String, val changeId: String) : GitMsg
    data class AllChangesSelected(val repositoryId: String, val selected: Boolean) : GitMsg
    data class CommitMessageChanged(val repositoryId: String, val message: String) : GitMsg
    data class DiffOpened(val key: GitDiffKey) : GitMsg
    data class CommitOpened(val key: GitCommitKey) : GitMsg
    data object DestinationClosed : GitMsg

    /** The destination sheet was swiped away: the diff and the commit both close. */
    data object DestinationDismissed : GitMsg
    data class PushPreviewDismissed(val repositoryId: String) : GitMsg
    data class BranchActionOpened(val selection: GitCheckoutSelection) : GitMsg
    data object CheckoutSelectionCleared : GitMsg
    data class HistoryPageRequested(val repositoryId: String) : GitMsg
    data class Invalidated(val revision: Long) : GitMsg

    /** The source went away (the host no longer serves Git): everything shown is dropped. */
    data object Reset : GitMsg

    data class ReadStarted(val key: GitRequestKey) : GitMsg
    data class ReadFailed(val key: GitRequestKey, val fault: GitFault) : GitMsg
    data class OverviewLoaded(
        val requestedRepositoryId: String?,
        val status: GitStatus,
        /** When this answer arrived, so the branch bar can say how long ago the phone last heard from the host. */
        val receivedAtEpochMillis: Long,
    ) : GitMsg
    data class DiffLoaded(val key: GitDiffKey, val diff: GitDiff, val presentation: GitDiffPresentation) : GitMsg
    data class CommitLoaded(val key: GitCommitKey, val details: GitCommitDetails) : GitMsg
    data class HistoryLoaded(
        val key: GitRequestKey,
        val repositoryId: String,
        val page: GitHistoryPage,
        val append: Boolean,
    ) : GitMsg
    data class BranchesLoaded(val repositoryId: String, val branches: GitBranches) : GitMsg
    data class ProjectInfoLoaded(val repositoryId: String, val info: GitProjectInfo) : GitMsg
    data class PushPreviewLoaded(val repositoryId: String, val preview: GitPushPreview) : GitMsg

    data class MutationStarted(val repositoryId: String) : GitMsg
    data class MutationFinished(val result: GitOperationResult) : GitMsg
    data class MutationOutcomeUnknown(val repositoryId: String) : GitMsg
    data class MutationEnded(val repositoryId: String) : GitMsg
    data class DraftCommitted(val repositoryId: String) : GitMsg
    data class OutcomeDismissed(val repositoryId: String) : GitMsg

    /** The source refused the mutation for a known reason; nothing changed, so the repository is free again. */
    data class MutationFailed(val repositoryId: String) : GitMsg
    data class PromptOpened(val prompt: GitPrompt) : GitMsg
    data object PromptDismissed : GitMsg
}

/** The intents that only change what the pane shows, as the message that does it; `null` for the rest. */
internal fun GitIntent.stateChange(): GitMsg? = when (this) {
    GitIntent.DismissBranches -> GitMsg.BranchesClosed
    is GitIntent.ChangeBranchSearch -> GitMsg.BranchSearchChanged(query)
    is GitIntent.ToggleBranchFolder -> GitMsg.BranchFolderToggled(folderId)
    is GitIntent.ToggleChange -> GitMsg.ChangeToggled(repositoryId, changeId)
    is GitIntent.SelectAllChanges -> GitMsg.AllChangesSelected(repositoryId, selected)
    is GitIntent.ChangeCommitMessage -> GitMsg.CommitMessageChanged(repositoryId, message)
    GitIntent.CloseDestination -> GitMsg.DestinationClosed
    GitIntent.DismissDestination -> GitMsg.DestinationDismissed
    is GitIntent.OpenBranchAction ->
        GitMsg.BranchActionOpened(GitCheckoutSelection(repositoryId, branchId, branchName, expectedRevision))
    is GitIntent.DismissOutcome -> GitMsg.OutcomeDismissed(repositoryId)
    GitIntent.DismissPrompt -> GitMsg.PromptDismissed
    else -> null
}

internal fun reduce(state: GitState, msg: GitMsg): GitState = when (msg) {
    is GitMsg.RepositorySelected -> state.selectRepository(msg.repositoryId)
    is GitMsg.SectionSelected -> state.copy(selectedSection = msg.section)
    GitMsg.BranchesOpened, GitMsg.BranchesClosed, is GitMsg.BranchSearchChanged, is GitMsg.BranchFolderToggled,
    -> state.reduceBranchList(msg)

    is GitMsg.ChangeToggled -> state.withDraft(msg.repositoryId) { draft ->
        val ids = draft.selectedChangeIds
        draft.copy(selectedChangeIds = if (msg.changeId in ids) ids - msg.changeId else ids + msg.changeId)
    }

    is GitMsg.AllChangesSelected -> state.withDraft(msg.repositoryId) { draft ->
        val ids = state.overview?.repositories?.firstOrNull { it.id == msg.repositoryId }
            ?.changeLists?.flatMap { it.changes }?.mapTo(linkedSetOf()) { it.id }.orEmpty()
        draft.copy(selectedChangeIds = if (msg.selected) ids else emptySet())
    }

    is GitMsg.CommitMessageChanged -> state.withDraft(msg.repositoryId) { it.copy(message = msg.message) }
    // A change's diff replaces any open commit; a commit file's diff opens on top of its commit.
    is GitMsg.DiffOpened -> if (msg.key.commitId == null) {
        state.copy(selectedDiff = msg.key, selectedCommit = null)
    } else {
        state.copy(selectedDiff = msg.key)
    }

    is GitMsg.CommitOpened -> state.copy(selectedCommit = msg.key, selectedDiff = null)
    GitMsg.DestinationClosed -> state.closeInnermostDestination()

    GitMsg.DestinationDismissed -> state.copy(selectedDiff = null, selectedCommit = null)
    is GitMsg.PushPreviewDismissed -> state.copy(
        pushPreviews = state.pushPreviews - msg.repositoryId,
        requestFailures = state.requestFailures - GitRequestKey.PushPreview(msg.repositoryId),
    )

    is GitMsg.BranchActionOpened -> state.copy(
        pushPreviews = state.pushPreviews - msg.selection.repositoryId,
        checkoutSelection = msg.selection,
    )

    GitMsg.CheckoutSelectionCleared -> state.copy(checkoutSelection = null)
    is GitMsg.HistoryPageRequested -> state.histories[msg.repositoryId]?.let { history ->
        state.copy(histories = state.histories + (msg.repositoryId to history.copy(loadingNextPage = true)))
    } ?: state

    is GitMsg.Invalidated -> state.invalidate(msg.revision)
    GitMsg.Reset -> GitState(capabilities = state.capabilities, opensFilesInEditor = state.opensFilesInEditor)
    is GitMsg.ReadStarted -> state.copy(
        loadingKeys = state.loadingKeys + msg.key,
        requestFailures = state.requestFailures - msg.key,
    )

    is GitMsg.ReadFailed -> state.failRequest(msg.key, msg.fault)
    is GitMsg.OverviewLoaded -> state.loaded(GitRequestKey.Overview) {
        if (canAcceptOverview(msg.requestedRepositoryId, msg.status)) {
            acceptOverview(msg.status).copy(overviewReceivedAtEpochMillis = msg.receivedAtEpochMillis)
        } else {
            this
        }
    }

    is GitMsg.DiffLoaded ->
        state.loaded(GitRequestKey.Diff(msg.key)) { acceptDiff(msg.key, msg.diff, msg.presentation) }
    is GitMsg.CommitLoaded ->
        state.loaded(GitRequestKey.CommitDetail(msg.key)) { acceptCommitDetail(msg.key, msg.details) }

    is GitMsg.HistoryLoaded -> state.loaded(msg.key) { acceptHistoryPage(msg.repositoryId, msg.page, msg.append) }

    is GitMsg.ProjectInfoLoaded -> state.loaded(GitRequestKey.ProjectInfo(msg.repositoryId)) {
        copy(projectInfos = projectInfos + (msg.repositoryId to msg.info))
    }

    is GitMsg.BranchesLoaded -> state.loaded(GitRequestKey.Branches(msg.repositoryId)) {
        copy(
            branches = branches + (msg.repositoryId to msg.branches),
            mutationOutcome = mutationOutcome - msg.repositoryId,
        )
    }

    is GitMsg.PushPreviewLoaded -> state.loaded(GitRequestKey.PushPreview(msg.repositoryId)) {
        copy(
            pushPreviews = pushPreviews + (msg.repositoryId to msg.preview),
            mutationOutcome = mutationOutcome - msg.repositoryId,
        )
    }

    is GitMsg.MutationStarted, is GitMsg.MutationFinished, is GitMsg.MutationOutcomeUnknown,
    is GitMsg.MutationEnded, is GitMsg.DraftCommitted, is GitMsg.MutationFailed, is GitMsg.OutcomeDismissed,
    -> state.reduceMutation(msg)

    is GitMsg.PromptOpened -> state.copy(prompt = msg.prompt, checkoutSelection = null)
    GitMsg.PromptDismissed -> state.copy(prompt = null)
}

private fun GitState.reduceMutation(msg: GitMsg): GitState = when (msg) {
    is GitMsg.MutationStarted -> copy(
        mutatingRepositories = mutatingRepositories + msg.repositoryId,
        mutationOutcome = mutationOutcome - msg.repositoryId,
    )

    is GitMsg.MutationFinished -> copy(
        mutationOutcome = mutationOutcome +
            (msg.result.repositoryId to GitMutationOutcome.Received(msg.result.outcome)),
        pushPreviews = pushPreviews - msg.result.repositoryId,
        checkoutSelection = checkoutSelection.takeUnless { msg.result.outcome == GitOutcome.SUCCESS },
    )

    is GitMsg.MutationOutcomeUnknown ->
        copy(mutationOutcome = mutationOutcome + (msg.repositoryId to GitMutationOutcome.Unknown))

    is GitMsg.MutationEnded -> copy(mutatingRepositories = mutatingRepositories - msg.repositoryId)
    is GitMsg.DraftCommitted -> copy(commitDrafts = commitDrafts - msg.repositoryId)
    is GitMsg.MutationFailed -> copy(mutationOutcome = mutationOutcome - msg.repositoryId)
    // Unknown stays: it is the guard that blocks further mutations until a status says what happened.
    is GitMsg.OutcomeDismissed ->
        if (mutationOutcome[msg.repositoryId] is GitMutationOutcome.Received) {
            copy(mutationOutcome = mutationOutcome - msg.repositoryId)
        } else {
            this
        }

    else -> this
}

private fun GitState.reduceBranchList(msg: GitMsg): GitState = when (msg) {
    GitMsg.BranchesOpened -> copy(branchesOpen = true)
    // The search and the opened folders belong to one visit to the list.
    GitMsg.BranchesClosed -> copy(branchesOpen = false, branchSearchQuery = "", expandedBranchFolders = emptySet())
    is GitMsg.BranchSearchChanged -> copy(branchSearchQuery = msg.query)
    is GitMsg.BranchFolderToggled -> copy(
        expandedBranchFolders = expandedBranchFolders.let {
            if (msg.folderId in it) it - msg.folderId else it + msg.folderId
        },
    )

    else -> this
}

/** Pops the diff first, then the commit it was opened from. */
private fun GitState.closeInnermostDestination(): GitState =
    if (selectedDiff != null) copy(selectedDiff = null) else copy(selectedCommit = null)

private fun GitState.withDraft(repositoryId: String, transform: (GitCommitDraft) -> GitCommitDraft): GitState {
    val draft = commitDrafts[repositoryId] ?: return this
    return copy(commitDrafts = commitDrafts + (repositoryId to transform(draft)))
}

private fun GitState.loaded(key: GitRequestKey, accept: GitState.() -> GitState): GitState =
    accept().copy(loadingKeys = loadingKeys - key)

