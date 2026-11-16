package app.tennyo.host.engine.git

/** A Git operation the phone started, as the editor names it while it runs. */
enum class GitTask {
    COMMIT,
    CHECKOUT,
    PUSH,
    FETCH,
    PULL,
    MERGE,
    CREATE_BRANCH,
    DELETE_BRANCH,
    ROLLBACK,
    SAVE_REMOTE,
    REMOVE_REMOTE,
    STASH,
    APPLY_STASH,
    CLEAN_UP,
    RESET_HEAD,
}

/** Shows a Git operation in the editor while it runs, where the user can cancel it. */
interface GitTaskReporter {
    /** Runs [block] as a cancellable editor task for [task] in [repositoryName]; cancelling there cancels [block]. */
    suspend fun <T> run(task: GitTask, repositoryName: String, block: suspend () -> T): T
}

/** Runs operations with nothing shown, for hosts and tests without an editor. */
object SilentGitTasks : GitTaskReporter {
    override suspend fun <T> run(task: GitTask, repositoryName: String, block: suspend () -> T): T = block()
}
