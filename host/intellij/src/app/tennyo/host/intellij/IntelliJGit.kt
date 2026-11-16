package app.tennyo.host.intellij

import app.tennyo.host.engine.git.CliGitPort
import app.tennyo.host.engine.git.GitTask
import app.tennyo.host.engine.git.GitTaskReporter
import app.tennyo.host.engine.git.JvmGitProcesses
import app.tennyo.host.engine.panes.IdeGitPortFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.ide.progress.withBackgroundProgress

/**
 * Git for sessions in [project]: the engine's `git` command-line port over the project's content roots. Each change
 * the phone starts shows as a background task the user can cancel in the IDE, and file changes the IDE sees ask the
 * phone to refresh.
 */
internal fun intelliJGitFactory(project: Project): IdeGitPortFactory =
    CliGitPort.factory(
        processes = JvmGitProcesses,
        projectRoots = { projectRoots(project) },
        tasks = IntelliJGitTasks(project),
        watchChanges = { onChanged -> watchProjectFiles(project, onChanged) },
    )

private fun projectRoots(project: Project): List<String> = ReadAction.compute<List<String>, Throwable> {
    if (project.isDisposed) return@compute emptyList()
    listOfNotNull(project.basePath) + ProjectRootManager.getInstance(project).contentRoots.map { it.path }
}

private class IntelliJGitTasks(private val project: Project) : GitTaskReporter {
    override suspend fun <T> run(task: GitTask, repositoryName: String, block: suspend () -> T): T =
        withBackgroundProgress(project, task.title(repositoryName), cancellable = true) { block() }

    private fun GitTask.title(repositoryName: String): String = when (this) {
        GitTask.COMMIT -> HostBundle.message("git.task.commit", repositoryName)
        GitTask.CHECKOUT -> HostBundle.message("git.task.checkout", repositoryName)
        GitTask.PUSH -> HostBundle.message("git.task.push", repositoryName)
        GitTask.FETCH -> HostBundle.message("git.task.fetch", repositoryName)
        GitTask.PULL -> HostBundle.message("git.task.pull", repositoryName)
        GitTask.MERGE -> HostBundle.message("git.task.merge", repositoryName)
        GitTask.CREATE_BRANCH -> HostBundle.message("git.task.create.branch", repositoryName)
        GitTask.DELETE_BRANCH -> HostBundle.message("git.task.delete.branch", repositoryName)
        GitTask.ROLLBACK -> HostBundle.message("git.task.rollback", repositoryName)
        GitTask.SAVE_REMOTE -> HostBundle.message("git.task.save.remote", repositoryName)
        GitTask.REMOVE_REMOTE -> HostBundle.message("git.task.remove.remote", repositoryName)
        GitTask.STASH -> HostBundle.message("git.task.stash", repositoryName)
        GitTask.APPLY_STASH -> HostBundle.message("git.task.apply.stash", repositoryName)
        GitTask.CLEAN_UP -> HostBundle.message("git.task.clean.up", repositoryName)
        GitTask.RESET_HEAD -> HostBundle.message("git.task.reset.head", repositoryName)
    }
}

/** Calls [onChanged] after the IDE sees files under [project] change; closing stops listening. */
private fun watchProjectFiles(project: Project, onChanged: () -> Unit): AutoCloseable {
    val disposable = Disposer.newDisposable("Tennyo Git file watch")
    ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(
        VirtualFileManager.VFS_CHANGES,
        object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val roots = projectRoots(project)
                if (events.any { event -> roots.any { root -> event.path.startsWith(root) } }) onChanged()
            }
        },
    )
    return AutoCloseable { Disposer.dispose(disposable) }
}
