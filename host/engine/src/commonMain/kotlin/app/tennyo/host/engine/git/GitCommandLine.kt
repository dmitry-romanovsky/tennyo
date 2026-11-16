package app.tennyo.host.engine.git

import app.tennyo.host.engine.panes.gitRepositoryId
import app.tennyo.host.engine.platform.HostMonitor
import app.tennyo.host.engine.platform.guarded
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A repository the port found under one of the project's roots. */
internal data class GitRepo(val id: String, val root: String, val projectPath: String? = "") {
    val name: String get() = root.trimEnd('/').substringAfterLast('/')
}

/**
 * Every `git` invocation the port makes, and the project's repositories. Each run gets an environment that can never
 * wait for a person — no terminal or credential-manager prompt, no editor, no optional index lock that would race the
 * editor's own Git — and a timeout after which the process is killed.
 */
internal class GitCommandLine(
    private val processes: GitProcesses,
    private val projectRoots: () -> List<String>,
) {
    private val monitor = HostMonitor()
    private var known: Map<String, GitRepo> = emptyMap()

    suspend fun run(
        repo: GitRepo,
        vararg arguments: String,
        input: List<String>? = null,
        outputLimit: Int = MAX_COMMAND_OUTPUT_BYTES,
        timeout: Duration = READ_TIMEOUT,
    ): GitProcessResult = run(repo.root, arguments.toList(), input, outputLimit, timeout)

    /** Runs a command whose arguments end with `--pathspec-from-file=-`: [paths] go to its stdin, never on the line. */
    suspend fun runWithPaths(repo: GitRepo, paths: List<String>, vararg arguments: String): GitProcessResult =
        run(repo, *arguments, "--pathspec-from-file=-", "--pathspec-file-nul", input = paths, timeout = WRITE_TIMEOUT)

    /** Every repository under the project's roots, refreshing the ids later calls resolve. */
    suspend fun repositories(): List<GitRepo> {
        val projectRoots = projectRoots()
        val found = projectRoots.distinct().mapNotNull { root ->
            val topLevel = listOf("rev-parse", "--show-toplevel")
            runCatching { run(root, topLevel, null, MAX_COMMAND_OUTPUT_BYTES, READ_TIMEOUT) }
                .getOrNull()
                ?.takeIf(GitProcessResult::succeeded)
                ?.text?.trim()?.takeIf(String::isNotEmpty)
        }.distinct().sorted().map { root ->
            GitRepo(gitRepositoryId(root), root, projectPathOf(projectRoots.firstOrNull(), root))
        }
        monitor.guarded { known = found.associateBy(GitRepo::id) }
        return found
    }

    suspend fun repository(id: String): GitRepo? =
        monitor.guarded { known[id] } ?: repositories().firstOrNull { it.id == id }

    suspend fun readWorkingFile(repo: GitRepo, path: String): ByteArray? =
        processes.readFile("${repo.root.trimEnd('/')}/$path", MAX_CONTENT_BYTES)

    private suspend fun run(
        directory: String,
        arguments: List<String>,
        input: List<String>?,
        outputLimit: Int,
        timeout: Duration,
    ): GitProcessResult = withTimeout(timeout) {
        processes.run(
            directory = directory,
            arguments = GLOBAL_OPTIONS + arguments,
            environment = NON_INTERACTIVE_ENVIRONMENT,
            input = input?.joinToString("") { "$it\u0000" }?.encodeToByteArray(),
            outputLimit = outputLimit,
        )
    }
}

/** Unquoted paths in every output, so `-z`-less commands print them as they are. */
private val GLOBAL_OPTIONS = listOf("-c", "core.quotepath=off")

private val NON_INTERACTIVE_ENVIRONMENT = mapOf(
    "GIT_TERMINAL_PROMPT" to "0",
    "GCM_INTERACTIVE" to "never",
    "GIT_EDITOR" to "true",
    "GIT_OPTIONAL_LOCKS" to "0",
    // Paths from the phone name files, never patterns.
    "GIT_LITERAL_PATHSPECS" to "1",
    // A remote URL from the phone must not reach a transport that runs commands (`ext::`) or reads local files.
    "GIT_ALLOW_PROTOCOL" to "git:http:https:ssh",
    // Failures are classified by Git's English messages.
    "LC_ALL" to "C",
    "LANGUAGE" to "C",
)

internal val READ_TIMEOUT: Duration = 60.seconds
internal val WRITE_TIMEOUT: Duration = 10.minutes
internal const val MAX_CONTENT_BYTES = 4_000_000
private const val MAX_COMMAND_OUTPUT_BYTES = 32_000_000

/** Where [repositoryRoot] lies below [projectRoot], `/`-separated: empty for the folder itself, `null` outside it. */
internal fun projectPathOf(projectRoot: String?, repositoryRoot: String): String? {
    val project = projectRoot?.trimEnd('/') ?: return null
    val repository = repositoryRoot.trimEnd('/')
    return when {
        repository == project -> ""
        repository.startsWith("$project/") -> repository.removePrefix("$project/")
        else -> null
    }
}
