package app.tennyo.host.vscode.panes

import app.tennyo.host.engine.git.CliGitPort
import app.tennyo.host.engine.git.GitTask
import app.tennyo.host.engine.git.GitTaskReporter
import app.tennyo.host.engine.git.NodeGitProcesses
import app.tennyo.host.engine.panes.IdeGitPortFactory
import app.tennyo.host.vscode.api.ProgressLocation
import app.tennyo.host.vscode.api.VsCodeWindow
import app.tennyo.host.vscode.api.VsCodeWorkspace
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.js.Promise

/**
 * Git for sessions in this window: the engine's `git` command-line port over the open folders, the same one IntelliJ
 * uses. Each change the phone starts shows as a notification the user can cancel, and file changes in the folders ask
 * the phone to refresh.
 */
internal fun vsCodeGitFactory(): IdeGitPortFactory =
    CliGitPort.factory(
        processes = NodeGitProcesses,
        projectRoots = { VsCodeWorkspace.workspaceFolders.orEmpty().map { it.uri.linkPath() } },
        tasks = VsCodeGitTasks,
        watchChanges = ::watchWorkspaceFiles,
    )

private object VsCodeGitTasks : GitTaskReporter {
    override suspend fun <T> run(task: GitTask, repositoryName: String, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        val options = js("{}")
        options.location = ProgressLocation.Notification
        options.title = task.title(repositoryName)
        options.cancellable = true
        VsCodeWindow.withProgress(options) { _, token ->
            token.onCancellationRequested { work.cancel() }
            Promise { resolve, _ -> work.invokeOnCompletion { resolve(Unit) } }
        }
        work.await()
    }

    private fun GitTask.title(repositoryName: String): String = when (this) {
        GitTask.COMMIT -> "Committing from Tennyo in $repositoryName"
        GitTask.CHECKOUT -> "Checking out a branch from Tennyo in $repositoryName"
        GitTask.PUSH -> "Pushing from Tennyo in $repositoryName"
        GitTask.FETCH -> "Fetching from Tennyo in $repositoryName"
        GitTask.PULL -> "Pulling from Tennyo in $repositoryName"
        GitTask.MERGE -> "Merging the upstream branch from Tennyo in $repositoryName"
        GitTask.CREATE_BRANCH -> "Creating a branch from Tennyo in $repositoryName"
        GitTask.DELETE_BRANCH -> "Deleting a branch from Tennyo in $repositoryName"
        GitTask.ROLLBACK -> "Rolling back changes from Tennyo in $repositoryName"
        GitTask.SAVE_REMOTE -> "Saving a remote from Tennyo in $repositoryName"
        GitTask.REMOVE_REMOTE -> "Removing a remote from Tennyo in $repositoryName"
        GitTask.STASH -> "Stashing changes from Tennyo in $repositoryName"
        GitTask.APPLY_STASH -> "Applying the latest stash from Tennyo in $repositoryName"
        GitTask.CLEAN_UP -> "Cleaning up the repository from Tennyo in $repositoryName"
        GitTask.RESET_HEAD -> "Resetting to HEAD from Tennyo in $repositoryName"
    }
}

/** Calls [onChanged] for every file the editor sees change in the open folders, `.git` included. */
private fun watchWorkspaceFiles(onChanged: () -> Unit): AutoCloseable {
    val watcher = VsCodeWorkspace.createFileSystemWatcher("**/*")
    watcher.onDidChange { onChanged() }
    watcher.onDidCreate { onChanged() }
    watcher.onDidDelete { onChanged() }
    return AutoCloseable { watcher.dispose() }
}
