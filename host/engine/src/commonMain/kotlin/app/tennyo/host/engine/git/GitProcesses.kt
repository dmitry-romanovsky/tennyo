package app.tennyo.host.engine.git

/** Runs the `git` executable; each host platform supplies its own (a JVM process, a Node child process). */
interface GitProcesses {
    /**
     * Runs `git [arguments]` in [directory] with [environment] added to the host's own, writes [input] to its stdin and
     * closes it, and returns once the process exits. Cancelling the caller kills the process; stdout beyond
     * [outputLimit] bytes kills it too and marks the result truncated. Throws [GitLaunchException] when `git` cannot
     * be started at all.
     */
    suspend fun run(
        directory: String,
        arguments: List<String>,
        environment: Map<String, String>,
        input: ByteArray?,
        outputLimit: Int,
    ): GitProcessResult

    /**
     * Up to `limit + 1` bytes of the file at [path], so a caller can tell a file over [limit]; `null` when absent. A
     * symlink reads as its target path, as Git stores it: following it would hand the phone a file outside the project.
     */
    suspend fun readFile(path: String, limit: Int): ByteArray?
}

class GitProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    val truncated: Boolean = false,
) {
    val succeeded: Boolean get() = exitCode == 0 && !truncated
    val text: String get() = stdout.decodeToString()
}

class GitLaunchException(message: String, cause: Throwable? = null) : Exception(message, cause)
