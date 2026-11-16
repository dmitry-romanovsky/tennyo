package app.tennyo.mobile.feature.git

import app.tennyo.mobile.design.PaneNotice
import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The Git pane over [source]. Each kind of read runs once at a time: a newer read of the same kind supersedes the
 * older one, whose answer is then ignored. A mutation blocks further mutations of its repository until the source
 * answers; if the answer is lost, the outcome stays unknown until the next status says what happened.
 */
@Suppress("TooManyFunctions")
class RealGitPaneComponent(
    componentContext: ComponentContext,
    private val source: GitRepositorySource,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
    private val diffContext: CoroutineContext = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Opens a repository-relative path in the workspace's editor; `null` where there is none. */
    private val openInEditor: ((path: String) -> Unit)? = null,
) : GitPaneComponent, ComponentContext by componentContext {
    private val store = FeatureStore<GitState, GitMsg, PaneNotice>(
        GitState(capabilities = source.capabilities, opensFilesInEditor = openInEditor != null),
        lifecycle,
        ::reduce,
        mainContext,
    )
    private val readJobs = mutableMapOf<GitRequestKey, Job>()
    private val readGenerations = mutableMapOf<GitRequestKey, Long>()
    private val scope get() = store.scope

    override val state: StateFlow<GitState> = store.state
    override val notices: Flow<PaneNotice> = store.effects

    private val current: GitState get() = store.state.value

    @Suppress("CyclomaticComplexMethod")
    override fun onIntent(intent: GitIntent) {
        when (intent) {
            GitIntent.DismissBranches, is GitIntent.ChangeBranchSearch, is GitIntent.ToggleBranchFolder,
            is GitIntent.ToggleChange, is GitIntent.SelectAllChanges, is GitIntent.ChangeCommitMessage,
            GitIntent.CloseDestination, GitIntent.DismissDestination, is GitIntent.OpenBranchAction,
            is GitIntent.DismissOutcome, GitIntent.DismissPrompt,
            -> intent.stateChange()?.let(store::dispatch)

            GitIntent.Refresh -> refresh(manual = true)
            is GitIntent.SelectRepository -> selectRepository(intent.id)
            is GitIntent.SelectSection -> selectSection(intent.section)
            is GitIntent.OpenBranches -> {
                store.dispatch(GitMsg.BranchesOpened)
                loadBranches(intent.repositoryId)
            }

            is GitIntent.Commit -> commit(intent.repositoryId, pushAfter = false)
            is GitIntent.CommitAndPush -> commit(intent.repositoryId, pushAfter = true)
            is GitIntent.OpenDiff -> openDiff(GitDiffKey(intent.repositoryId, intent.changeId)) {
                source.diff(intent.repositoryId, intent.changeId)
            }

            is GitIntent.OpenCommitFileDiff -> with(intent) {
                openDiff(GitDiffKey(repositoryId, fileId, commitId)) {
                    source.commitFileDiff(repositoryId, commitId, fileId)
                }
            }

            is GitIntent.OpenCommit -> openCommit(GitCommitKey(intent.repositoryId, intent.commitId))
            is GitIntent.OpenInEditor -> openInEditor?.let { open ->
                store.dispatch(GitMsg.DestinationDismissed)
                open(intent.path)
            }

            is GitIntent.LoadHistory -> loadHistoryPage(intent.repositoryId, cursor = null)
            is GitIntent.LoadNextHistoryPage -> current.histories[intent.repositoryId]?.nextCursor?.let { cursor ->
                loadHistoryPage(intent.repositoryId, cursor)
            }

            is GitIntent.LoadBranches -> loadBranches(intent.repositoryId)
            is GitIntent.OpenPushPreview -> openPushPreview(intent.repositoryId)
            GitIntent.DismissPushPreview -> current.selectedRepositoryId
                ?.takeUnless(current::isMutating)
                ?.let { store.dispatch(GitMsg.PushPreviewDismissed(it)) }

            GitIntent.DismissBranchAction -> dismissBranchAction()
            GitIntent.ConfirmCheckout -> current.checkoutSelection?.let { selection ->
                val target = GitCheckoutTarget(selection.branchId, selection.expectedRevision)
                mutate(selection.repositoryId, GitMutation.Checkout(target))
            }

            is GitIntent.Push ->
                mutate(intent.repositoryId, GitMutation.Push(intent.pushTargetId, intent.previewRevision))

            is GitIntent.Fetch -> if (current.capabilities.fetch) mutate(intent.repositoryId, GitMutation.Fetch)
            is GitIntent.Pull -> if (current.capabilities.pull) mutate(intent.repositoryId, GitMutation.Pull)
            is GitIntent.OpenNewBranch -> if (current.capabilities.createBranch) {
                store.dispatch(GitMsg.PromptOpened(GitPrompt.NewBranch(intent.repositoryId)))
            }

            is GitIntent.CreateBranch -> createBranch(intent.repositoryId, intent.name)
            is GitIntent.OpenRemote -> if (current.capabilities.projectSettings) {
                store.dispatch(GitMsg.PromptOpened(GitPrompt.EditRemote(intent.repositoryId, intent.remote)))
            }

            is GitIntent.SaveRemote -> saveRemote(intent.name.trim(), intent.url.trim())
            GitIntent.RemoveRemote -> removeRemote()
            is GitIntent.RunProjectAction -> runProjectAction(intent.repositoryId, intent.action)
            is GitIntent.RollbackChange -> openRollback(intent.repositoryId, intent.changeId)
            is GitIntent.RollbackSelectedChanges -> openSelectedRollback(intent.repositoryId)
            GitIntent.ConfirmDiscard -> confirmDiscard()
            is GitIntent.EditIdentity -> if (current.capabilities.identity) {
                val identity = current.projectInfos[intent.repositoryId]?.identity
                store.dispatch(GitMsg.PromptOpened(GitPrompt.Identity(intent.repositoryId, retry = null, identity)))
            }

            is GitIntent.SubmitCredentials -> submitCredentials(GitCredentials(intent.username, intent.secret))
            is GitIntent.SubmitIdentity -> submitIdentity(GitIdentity(intent.name.trim(), intent.email.trim()))
            GitIntent.ConfirmMerge -> (current.prompt as? GitPrompt.Diverged)?.let { prompt ->
                store.dispatch(GitMsg.PromptDismissed)
                mutate(prompt.repositoryId, GitMutation.Merge)
            }

            is GitIntent.DeleteBranch -> if (!current.isMutationBlocked(intent.repositoryId)) {
                val discard = with(intent) { GitDiscard.Branch(branchId, branchName, expectedRevision) }
                store.dispatch(GitMsg.PromptOpened(GitPrompt.ConfirmDiscard(intent.repositoryId, discard)))
            }
        }
    }

    /** Innermost first: a prompt, the checkout sheet, a push preview, the branch list, then an open diff or commit. */
    override fun onBack(): Boolean {
        val git = current
        val previewRepositoryId = git.selectedRepositoryId?.takeIf(git.pushPreviews::containsKey)
            ?: git.pushPreviews.keys.firstOrNull()
        val closing = when {
            git.prompt != null -> GitMsg.PromptDismissed
            git.checkoutSelection != null ->
                GitMsg.CheckoutSelectionCleared.takeUnless { git.isMutating(git.checkoutSelection.repositoryId) }

            previewRepositoryId != null ->
                GitMsg.PushPreviewDismissed(previewRepositoryId).takeUnless { git.isMutating(previewRepositoryId) }

            git.branchesOpen -> GitMsg.BranchesClosed
            git.selectedDiff != null || git.selectedCommit != null -> GitMsg.DestinationClosed
            else -> return false
        }
        closing?.let(store::dispatch)
        return true
    }

    /**
     * Refreshes the status of [repositoryId]. A refresh the user did not ask for yields to one already running and to
     * a source that is backing off.
     */
    fun refresh(repositoryId: String? = current.selectedRepositoryId, manual: Boolean = false) {
        val active = readJobs[GitRequestKey.Overview]?.isActive == true
        if (!shouldStartGitOverviewRequest(manual, manual || source.automaticRefreshAllowed(), active)) return
        read(GitRequestKey.Overview, { source.status(repositoryId) }) { GitMsg.OverviewLoaded(repositoryId, it, now()) }
    }

    /** The repository changed at [revision]; when the pane is on screen what it shows is refetched. */
    fun invalidate(revision: Long, shown: Boolean) {
        store.dispatch(GitMsg.Invalidated(revision))
        if (!shown) return
        refresh()
        val repositoryId = current.selectedRepositoryId ?: return
        if (current.branchesOpen) loadBranches(repositoryId)
        when (current.selectedSection) {
            GitSection.Changes -> Unit
            GitSection.History -> loadHistoryPage(repositoryId, cursor = null)
            GitSection.Project -> loadProjectInfo(repositoryId)
        }
    }

    /** The source can no longer be reached; reads stop and everything shown is dropped. */
    fun reset() {
        cancelReads()
        store.dispatch(GitMsg.Reset)
    }

    private fun selectRepository(repositoryId: String) {
        if (repositoryId == current.selectedRepositoryId || current.mutatingRepositories.isNotEmpty()) return
        cancelReads()
        store.dispatch(GitMsg.RepositorySelected(repositoryId))
        refresh(repositoryId)
    }

    private fun selectSection(section: GitSection) {
        store.dispatch(GitMsg.SectionSelected(section))
        val repositoryId = current.selectedRepositoryId ?: return
        when (section) {
            GitSection.Changes -> Unit
            GitSection.History -> loadHistoryPage(repositoryId, cursor = null)
            GitSection.Project -> loadProjectInfo(repositoryId)
        }
    }

    private fun openDiff(key: GitDiffKey, load: suspend () -> GitDiff) {
        store.dispatch(GitMsg.DiffOpened(key))
        read(GitRequestKey.Diff(key), {
            val diff = load()
            diff to withContext(diffContext) {
                val calculation = currentCoroutineContext()
                diff.toPresentation(cancellationCheck = calculation::ensureActive)
            }
        }) { (diff, presentation) -> GitMsg.DiffLoaded(key, diff, presentation) }
    }

    private fun openCommit(key: GitCommitKey) {
        store.dispatch(GitMsg.CommitOpened(key))
        read(GitRequestKey.CommitDetail(key), { source.commitDetail(key.repositoryId, key.commitId) }) {
            GitMsg.CommitLoaded(key, it)
        }
    }

    private fun loadHistoryPage(repositoryId: String, cursor: String?) {
        val append = cursor != null
        if (append && current.histories[repositoryId]?.loadingNextPage == true) return
        val key = cursor?.let { GitRequestKey.HistoryNext(repositoryId, it) } ?: GitRequestKey.History(repositoryId)
        if (append) store.dispatch(GitMsg.HistoryPageRequested(repositoryId))
        read(key, { source.history(repositoryId, cursor) }) { GitMsg.HistoryLoaded(key, repositoryId, it, append) }
    }

    private fun loadBranches(repositoryId: String) {
        read(GitRequestKey.Branches(repositoryId), { source.branches(repositoryId) }) {
            GitMsg.BranchesLoaded(repositoryId, it)
        }
    }

    private fun loadProjectInfo(repositoryId: String) {
        read(GitRequestKey.ProjectInfo(repositoryId), { source.projectInfo(repositoryId) }) {
            GitMsg.ProjectInfoLoaded(repositoryId, it)
        }
    }

    /** Saves the remote the open editor names; a name or URL left blank, or with spaces in the name, is not sent. */
    private fun saveRemote(name: String, url: String) {
        val prompt = current.prompt as? GitPrompt.EditRemote ?: return
        if (name.isEmpty() || name.any(Char::isWhitespace) || url.isEmpty()) return
        store.dispatch(GitMsg.PromptDismissed)
        mutate(prompt.repositoryId, GitMutation.SaveRemote(prompt.remote?.name, GitRemote(name, url)))
    }

    private fun removeRemote() {
        val prompt = current.prompt as? GitPrompt.EditRemote ?: return
        val remote = prompt.remote ?: return
        store.dispatch(GitMsg.PromptDismissed)
        mutate(prompt.repositoryId, GitMutation.RemoveRemote(remote.name))
    }

    private fun runProjectAction(repositoryId: String, action: GitProjectAction) {
        if (!current.capabilities.projectSettings) return
        if (action == GitProjectAction.RESET_HEAD) {
            store.dispatch(GitMsg.PromptOpened(GitPrompt.ConfirmDiscard(repositoryId, GitDiscard.AllTracked)))
        } else {
            mutate(repositoryId, GitMutation.ProjectAction(action))
        }
    }

    private fun openRollback(repositoryId: String, changeId: String) {
        current.rollbackPrompt(repositoryId, changeId)?.let { store.dispatch(GitMsg.PromptOpened(it)) }
    }

    private fun openSelectedRollback(repositoryId: String) {
        val changes = current.rollbackableSelection(repositoryId).takeIf { it.isNotEmpty() } ?: return
        val revision = current.overview?.revision ?: return
        val discard = GitDiscard.Changes(changes, revision)
        store.dispatch(GitMsg.PromptOpened(GitPrompt.ConfirmDiscard(repositoryId, discard)))
    }

    private fun confirmDiscard() {
        val prompt = current.prompt as? GitPrompt.ConfirmDiscard ?: return
        store.dispatch(GitMsg.PromptDismissed)
        val mutation = when (val discard = prompt.discard) {
            is GitDiscard.Changes ->
                GitMutation.Rollback(GitCommitSelection(discard.changes.map(GitChange::id), discard.expectedRevision))
            GitDiscard.AllTracked -> GitMutation.ProjectAction(GitProjectAction.RESET_HEAD)
            is GitDiscard.Branch -> GitMutation.DeleteBranch(discard.branchId, discard.expectedRevision)
        }
        mutate(prompt.repositoryId, mutation)
    }

    /** A rolled-back file has nothing left to show, so its open diff closes. */
    private fun closeRolledBackDiff(changeIds: List<String>) {
        val diff = current.selectedDiff ?: return
        if (diff.commitId == null && diff.changeId in changeIds) store.dispatch(GitMsg.DestinationClosed)
    }

    private fun openPushPreview(repositoryId: String) {
        store.dispatch(GitMsg.CheckoutSelectionCleared)
        read(GitRequestKey.PushPreview(repositoryId), { source.pushPreview(repositoryId) }) {
            GitMsg.PushPreviewLoaded(repositoryId, it)
        }
    }

    private fun dismissBranchAction() {
        val selection = current.checkoutSelection
        if (selection == null || !current.isMutating(selection.repositoryId)) {
            store.dispatch(GitMsg.CheckoutSelectionCleared)
        }
    }

    private fun <T> read(key: GitRequestKey, request: suspend () -> T, loaded: (T) -> GitMsg) {
        if (key == GitRequestKey.Overview && readJobs[key]?.isActive == true) return
        readJobs.remove(key)?.cancel()
        val generation = (readGenerations[key] ?: 0L) + 1L
        readGenerations[key] = generation
        store.dispatch(GitMsg.ReadStarted(key))
        readJobs[key] = scope.launch {
            try {
                val response = withTimeout(GIT_READ_TIMEOUT) { request() }
                if (readGenerations[key] == generation) store.dispatch(loaded(response))
            } catch (timedOut: TimeoutCancellationException) {
                Logger.w("Git read $key got no answer in $GIT_READ_TIMEOUT", timedOut)
                if (readGenerations[key] == generation) {
                    store.dispatch(GitMsg.ReadFailed(key, GitFault.Rejected(GitRejection.TIMED_OUT)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                if (readGenerations[key] == generation) store.dispatch(GitMsg.ReadFailed(key, failure.toGitFault()))
            } finally {
                if (readGenerations[key] == generation) readJobs.remove(key)
            }
        }
    }

    private fun cancelReads() {
        readJobs.values.forEach(Job::cancel)
        readJobs.clear()
        readGenerations.keys.toList().forEach { key -> readGenerations[key] = (readGenerations[key] ?: 0L) + 1L }
    }

    private fun commit(repositoryId: String, pushAfter: Boolean) {
        if (!current.canCommit(repositoryId)) return
        val repository = current.overview?.repositories?.firstOrNull { it.id == repositoryId } ?: return
        if (pushAfter && GitAction.PUSH !in repository.availableActions) return
        val draft = current.commitDrafts.getValue(repositoryId)
        val selection = GitCommitSelection(draft.selectedChangeIds.toList(), draft.revision)
        mutate(repositoryId, GitMutation.Commit(selection, draft.message, pushAfter))
    }

    private fun createBranch(repositoryId: String, name: String) {
        val branchName = name.trim()
        if (!current.capabilities.createBranch || branchName.isEmpty()) return
        store.dispatch(GitMsg.PromptDismissed)
        mutate(repositoryId, GitMutation.CreateBranch(branchName))
    }

    private fun submitCredentials(credentials: GitCredentials) {
        val prompt = current.prompt as? GitPrompt.Credentials ?: return
        store.dispatch(GitMsg.PromptDismissed)
        scope.launch {
            answerPrompt({ source.useCredentials(prompt.host, credentials) }) {
                mutate(prompt.repositoryId, prompt.retry, credentialsSupplied = true)
            }
        }
    }

    private fun submitIdentity(identity: GitIdentity) {
        val prompt = current.prompt as? GitPrompt.Identity ?: return
        if (identity.name.isEmpty() || identity.email.isEmpty()) return
        store.dispatch(GitMsg.PromptDismissed)
        scope.launch {
            answerPrompt({ source.saveIdentity(identity) }) {
                if (prompt.retry != null) {
                    mutate(prompt.repositoryId, prompt.retry)
                } else {
                    loadProjectInfo(prompt.repositoryId)
                }
            }
        }
    }

    private suspend fun answerPrompt(answer: suspend () -> Unit, then: () -> Unit) {
        try {
            answer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            store.emit(PaneNotice.Failure(failure.toGitFault().text))
            return
        }
        then()
    }

    /**
     * Runs [mutation] unless the repository is busy. A refusal the user can answer opens a prompt that runs it again;
     * freshly entered credentials ([credentialsSupplied]) are tried only once.
     */
    private fun mutate(repositoryId: String, mutation: GitMutation, credentialsSupplied: Boolean = false) {
        if (current.isMutationBlocked(repositoryId)) return
        store.dispatch(GitMsg.MutationStarted(repositoryId))
        scope.launch {
            try {
                val result = source.run(repositoryId, mutation)
                store.dispatch(GitMsg.MutationFinished(result))
                if (result.outcome == GitOutcome.SUCCESS) onMutationSucceeded(result.repositoryId, mutation)
                // Success shows in the refreshed repository; anything else is a failure the banner keeps.
                if (result.outcome != GitOutcome.SUCCESS) store.emit(PaneNotice.Failure(result.outcome.message))
                refresh(result.repositoryId)
                if (current.branchesOpen) loadBranches(result.repositoryId)
                if (current.selectedSection == GitSection.Project) loadProjectInfo(result.repositoryId)
            } catch (cancelled: CancellationException) {
                store.dispatch(GitMsg.MutationOutcomeUnknown(repositoryId))
                throw cancelled
            } catch (fault: GitFault) {
                onMutationFault(repositoryId, mutation, fault, credentialsSupplied)
            } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                store.dispatch(GitMsg.MutationOutcomeUnknown(repositoryId))
                refresh(repositoryId)
            } finally {
                store.dispatch(GitMsg.MutationEnded(repositoryId))
            }
        }
    }

    private fun onMutationSucceeded(repositoryId: String, mutation: GitMutation) {
        dismissSuccessLater(repositoryId)
        when (mutation) {
            is GitMutation.Commit -> {
                store.dispatch(GitMsg.DraftCommitted(repositoryId))
                if (mutation.pushAfter) openPushPreview(repositoryId)
            }

            is GitMutation.Rollback -> closeRolledBackDiff(mutation.selection.changeIds)
            // Checked out: the list has done its job, and the branch bar now names the new branch.
            is GitMutation.Checkout -> store.dispatch(GitMsg.BranchesClosed)
            else -> Unit
        }
    }

    /** Success needs no acknowledgement: its notice goes away by itself, unless a newer outcome has replaced it. */
    private fun dismissSuccessLater(repositoryId: String) {
        val success = current.mutationOutcome[repositoryId]
        scope.launch {
            delay(GIT_SUCCESS_NOTICE_DURATION)
            if (current.mutationOutcome[repositoryId] === success) store.dispatch(GitMsg.OutcomeDismissed(repositoryId))
        }
    }

    private fun onMutationFault(
        repositoryId: String,
        mutation: GitMutation,
        fault: GitFault,
        credentialsSupplied: Boolean,
    ) {
        if (!fault.leavesRepositoryUnchanged) {
            store.dispatch(GitMsg.MutationOutcomeUnknown(repositoryId))
        } else {
            store.dispatch(GitMsg.MutationFailed(repositoryId))
            val prompt = fault.prompt(repositoryId, mutation, current.capabilities, credentialsSupplied)
            if (prompt != null) {
                store.dispatch(GitMsg.PromptOpened(prompt))
            } else {
                store.emit(PaneNotice.Failure(fault.text))
            }
        }
        refresh(repositoryId)
    }
}

/** The question that lets the user get past this fault, when the source can act on the answer. */
internal fun GitFault.prompt(
    repositoryId: String,
    mutation: GitMutation,
    capabilities: GitSourceCapabilities,
    credentialsSupplied: Boolean,
): GitPrompt? = when {
    this is GitFault.AuthRequired && capabilities.credentials && !credentialsSupplied ->
        GitPrompt.Credentials(repositoryId, host, mutation)

    this == GitFault.IdentityMissing && capabilities.identity -> GitPrompt.Identity(repositoryId, retry = mutation)
    this == GitFault.NonFastForward && mutation == GitMutation.Pull && capabilities.pull ->
        GitPrompt.Diverged(repositoryId)

    else -> null
}

private suspend fun GitRepositorySource.run(repositoryId: String, mutation: GitMutation): GitOperationResult =
    when (mutation) {
        is GitMutation.Commit -> commit(repositoryId, mutation.selection, mutation.message)
        is GitMutation.Rollback -> rollback(repositoryId, mutation.selection)
        is GitMutation.Checkout -> checkout(repositoryId, mutation.target)
        is GitMutation.Push -> push(repositoryId, mutation.pushTargetId, mutation.previewRevision)
        GitMutation.Fetch -> fetch(repositoryId)
        GitMutation.Pull -> pull(repositoryId)
        GitMutation.Merge -> merge(repositoryId)
        is GitMutation.CreateBranch -> createBranch(repositoryId, mutation.name)
        is GitMutation.DeleteBranch -> deleteBranch(repositoryId, mutation.branchId, mutation.expectedRevision)
        is GitMutation.SaveRemote -> saveRemote(repositoryId, mutation.previousName, mutation.remote)
        is GitMutation.RemoveRemote -> removeRemote(repositoryId, mutation.name)
        is GitMutation.ProjectAction -> runProjectAction(repositoryId, mutation.action)
    }

/**
 * How long a read waits for the source; past it the pane says so and offers a retry instead of loading forever. It
 * outlasts the host's own 60-second limit on a Git read, so a slow read fails with the host's answer, not this one.
 */
internal val GIT_READ_TIMEOUT = 90.seconds

/** How long the notice of a successful mutation stays before it dismisses itself; a failure stays until dismissed. */
internal val GIT_SUCCESS_NOTICE_DURATION = 4.seconds
