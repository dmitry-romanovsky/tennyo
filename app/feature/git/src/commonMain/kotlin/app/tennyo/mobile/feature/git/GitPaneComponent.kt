package app.tennyo.mobile.feature.git

import app.tennyo.mobile.design.PaneNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The Git pane: status and commit, history, branches and push, over any [GitRepositorySource]. */
interface GitPaneComponent {
    val state: StateFlow<GitState>

    /** Consumed by the host screen, which shows them in its message area. */
    val notices: Flow<PaneNotice>

    fun onIntent(intent: GitIntent)

    /** Closes the innermost Git destination (a sheet, a diff, a commit); `false` when there is none. */
    fun onBack(): Boolean
}

sealed interface GitIntent {
    data object Refresh : GitIntent
    data class SelectRepository(val id: String) : GitIntent
    data class SelectSection(val section: GitSection) : GitIntent
    /** Opens the branch list of [repositoryId] over the current section. */
    data class OpenBranches(val repositoryId: String) : GitIntent
    data object DismissBranches : GitIntent
    data class ChangeBranchSearch(val query: String) : GitIntent
    data class ToggleBranchFolder(val folderId: String) : GitIntent
    data class ToggleChange(val repositoryId: String, val changeId: String) : GitIntent
    data class SelectAllChanges(val repositoryId: String, val selected: Boolean) : GitIntent
    data class ChangeCommitMessage(val repositoryId: String, val message: String) : GitIntent
    data class Commit(val repositoryId: String) : GitIntent

    /** Commits like [Commit], then opens the push preview. */
    data class CommitAndPush(val repositoryId: String) : GitIntent
    data class OpenDiff(val repositoryId: String, val changeId: String) : GitIntent
    data class OpenCommit(val repositoryId: String, val commitId: String) : GitIntent
    data class OpenCommitFileDiff(val repositoryId: String, val commitId: String, val fileId: String) : GitIntent
    data object CloseDestination : GitIntent

    /** The destination sheet was swiped away; closes the diff and the commit together. */
    data object DismissDestination : GitIntent

    /** Closes the diff and opens [path], relative to the repository root, in the workspace's editor. */
    data class OpenInEditor(val path: String) : GitIntent
    data class LoadHistory(val repositoryId: String) : GitIntent
    data class LoadNextHistoryPage(val repositoryId: String) : GitIntent
    data class LoadBranches(val repositoryId: String) : GitIntent
    data class OpenPushPreview(val repositoryId: String) : GitIntent
    data object DismissPushPreview : GitIntent
    data class OpenBranchAction(
        val repositoryId: String,
        val branchId: String,
        val branchName: String,
        val expectedRevision: Long,
    ) : GitIntent

    data object DismissBranchAction : GitIntent
    data object ConfirmCheckout : GitIntent
    data class Push(val repositoryId: String, val pushTargetId: String, val previewRevision: Long) : GitIntent
    data class Fetch(val repositoryId: String) : GitIntent
    data class Pull(val repositoryId: String) : GitIntent
    data class OpenNewBranch(val repositoryId: String) : GitIntent

    /** Opens the remote editor: for [remote], or for a new one when it is `null`. */
    data class OpenRemote(val repositoryId: String, val remote: GitRemote?) : GitIntent
    data class SaveRemote(val name: String, val url: String) : GitIntent
    data object RemoveRemote : GitIntent
    /** Runs [action]; one that discards work asks first. */
    data class RunProjectAction(val repositoryId: String, val action: GitProjectAction) : GitIntent

    /** Asks to discard the working-tree change [changeId]. */
    data class RollbackChange(val repositoryId: String, val changeId: String) : GitIntent

    /** Asks to discard the selected changes of [repositoryId] that can be rolled back. */
    data class RollbackSelectedChanges(val repositoryId: String) : GitIntent

    /** Accepts the open [GitPrompt.ConfirmDiscard]. */
    data object ConfirmDiscard : GitIntent
    data class CreateBranch(val repositoryId: String, val name: String) : GitIntent

    /** Asks to delete the local branch [branchId], as the branch list at [expectedRevision] showed it. */
    data class DeleteBranch(
        val repositoryId: String,
        val branchId: String,
        val branchName: String,
        val expectedRevision: Long,
    ) : GitIntent

    /** Answers the open [GitPrompt.Credentials]. */
    data class SubmitCredentials(val username: String, val secret: String) : GitIntent

    /** Answers the open [GitPrompt.Identity]. */
    data class SubmitIdentity(val name: String, val email: String) : GitIntent

    /** Opens the commit author editor of [repositoryId], where the source can store one. */
    data class EditIdentity(val repositoryId: String) : GitIntent

    /** Hides the finished-mutation notice of [repositoryId]; one whose outcome is unknown stays. */
    data class DismissOutcome(val repositoryId: String) : GitIntent

    /** Accepts the open [GitPrompt.Diverged]. */
    data object ConfirmMerge : GitIntent
    data object DismissPrompt : GitIntent
}
