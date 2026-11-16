package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitCredentials
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.NullProgressMonitor
import java.io.File
import java.nio.file.Files

/** Temporary workspaces with real JGit repositories, and a scheduler that runs transfers in the calling coroutine. */
internal class GitTestRepositories : AutoCloseable {
    val root: File = Files.createTempDirectory("tennyo-local-git").toFile()
    val files = WorkspaceFiles(
        FileSystem.SYSTEM,
        File(root, "workspaces").toOkioPath(),
        File(root, "python-packages").toOkioPath(),
        Dispatchers.Unconfined,
    )
    val credentials = MemoryCredentialStore()
    val executor = JGitTransferExecutor(files, credentials)
    val transfers = DirectGitTransferScheduler(executor)
    var identity: GitIdentity? = GitIdentity("Ada", "ada@example.com")

    fun workspace(slug: String): File = files.root(slug).toFile().apply { mkdirs() }

    fun source(slug: String) = JGitRepositorySource(
        workspaceSlug = slug,
        directory = workspace(slug),
        identity = { identity },
        saveIdentity = { identity = it },
        transfers = transfers,
        ioContext = Dispatchers.Unconfined,
    )

    /** A repository in workspace [slug] with [files] committed. */
    fun initWithCommit(slug: String, files: Map<String, String>, message: String = "Initial"): Git {
        val git = Git.init().setDirectory(workspace(slug)).setInitialBranch(MAIN).call()
        files.forEach { (path, text) -> write(slug, path, text) }
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).setAuthor("Seed", "seed@example.com").setCommitter("Seed", "seed@example.com")
            .call()
        return git
    }

    /** A bare repository seeded with [files], to push to and clone from over `file://`. */
    fun bareRemote(files: Map<String, String>): String {
        val seed = File(root, "seed")
        Git.init().setDirectory(seed).setInitialBranch(MAIN).call().use { git ->
            files.forEach { (path, text) -> File(seed, path).apply { parentFile.mkdirs() }.writeText(text) }
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Seed").setAuthor("Seed", "seed@example.com")
                .setCommitter("Seed", "seed@example.com").call()
        }
        val bare = File(root, "remote.git")
        Git.cloneRepository().setBare(true).setURI(seed.toURI().toString()).setDirectory(bare).call().close()
        return bare.toURI().toString()
    }

    /** Clones [url] outside any workspace and commits [path] = [text] there, then pushes it. */
    fun commitOnRemote(url: String, path: String, text: String, clone: String = "other") {
        val directory = File(root, clone)
        val git = if (directory.exists()) {
            Git.open(directory).also { it.pull().call() }
        } else {
            Git.cloneRepository().setURI(url).setDirectory(directory).call()
        }
        git.use {
            File(directory, path).writeText(text)
            it.add().addFilepattern(path).call()
            it.commit().setMessage("Remote change").setAuthor("Other", "other@example.com")
                .setCommitter("Other", "other@example.com").call()
            it.push().call()
        }
    }

    fun write(slug: String, path: String, text: String) {
        File(workspace(slug), path).apply { parentFile.mkdirs() }.writeText(text)
    }

    override fun close() {
        root.deleteRecursively()
    }

    companion object {
        const val MAIN = "main"
    }
}

internal class DirectGitTransferScheduler(private val executor: JGitTransferExecutor) : GitTransferScheduler {
    override fun start(transfer: GitTransfer): Flow<GitTransferState> = flow {
        emit(GitTransferState.Running(progress = null))
        val finished = try {
            executor.execute(transfer, NullProgressMonitor.INSTANCE)
            GitTransferState.Succeeded
        } catch (fault: GitFault) {
            GitTransferState.Failed(fault)
        }
        emit(finished)
    }

    override fun cancel(transfer: GitTransfer) = Unit

    override fun stageCredentials(host: String, credentials: GitCredentials) = executor.stage(host, credentials)
}

internal class MemoryCredentialStore : GitCredentialStore {
    private val entries = MutableStateFlow(emptyMap<String, GitCredentials>())
    override val hosts: Flow<List<String>> = entries.map { it.keys.sorted() }
    override suspend fun get(host: String): GitCredentials? = entries.value[host]
    override suspend fun save(host: String, credentials: GitCredentials) {
        entries.value += host to credentials
    }

    override suspend fun delete(host: String) {
        entries.value -= host
    }
}
