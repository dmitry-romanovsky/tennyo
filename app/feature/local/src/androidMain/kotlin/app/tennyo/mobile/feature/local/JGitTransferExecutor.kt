package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitCredentials
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitRejection
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs [GitTransfer]s with JGit, in whatever process context calls it (a worker, or a test). Credentials come from
 * [credentials], or from a pair staged for a host after the user was asked; a staged pair is saved once a transfer
 * with it succeeds and dropped when the host refuses it.
 */
class JGitTransferExecutor(
    private val files: WorkspaceFiles,
    private val credentials: GitCredentialStore,
) {
    private val staged = ConcurrentHashMap<String, GitCredentials>()

    fun stage(host: String, credentials: GitCredentials) {
        staged[host.lowercase()] = credentials
    }

    /** Runs [transfer] to completion; throws [GitFault] when it fails. */
    suspend fun execute(transfer: GitTransfer, monitor: ProgressMonitor) {
        val directory = files.root(transfer.workspaceSlug).toFile()
        val host = remoteHost(transferUrl(transfer, directory))
        val stagedCredentials = host?.let { staged[it] }
        val provider = (stagedCredentials ?: host?.let { credentials.get(it) })
            ?.let { UsernamePasswordCredentialsProvider(it.username, it.secret) }
        try {
            run(transfer, directory, provider, monitor)
        } catch (failure: GitAPIException) {
            if (transfer is GitTransfer.Clone) directory.deleteRecursively()
            val fault = failure.toTransferFault(host, cancelled = monitor.isCancelled)
            if (fault is GitFault.AuthRequired) host?.let(staged::remove)
            throw fault.apply { initCause(failure) }
        }
        if (host != null && stagedCredentials != null) {
            credentials.save(host, stagedCredentials)
            staged.remove(host)
        }
    }

    private fun transferUrl(transfer: GitTransfer, directory: File): String = when (transfer) {
        is GitTransfer.Clone -> transfer.url
        is GitTransfer.Fetch, is GitTransfer.Push -> openRepository(directory).use { it.remoteUrl() }
    }

    private fun run(transfer: GitTransfer, directory: File, provider: CredentialsProvider?, monitor: ProgressMonitor) {
        when (transfer) {
            is GitTransfer.Clone -> clone(transfer.url, directory, provider, monitor)
            is GitTransfer.Fetch -> openRepository(directory).use { fetch(it, provider, monitor) }
            is GitTransfer.Push -> openRepository(directory).use { push(it, provider, monitor) }
        }
    }

    private fun clone(url: String, directory: File, provider: CredentialsProvider?, monitor: ProgressMonitor) {
        Git.cloneRepository()
            .setURI(url)
            .setDirectory(directory)
            .setCredentialsProvider(provider)
            .setProgressMonitor(monitor)
            .call()
            .close()
    }

    private fun fetch(repository: Repository, provider: CredentialsProvider?, monitor: ProgressMonitor) {
        Git(repository).fetch()
            .setRemote(repository.remoteName())
            .setCredentialsProvider(provider)
            .setProgressMonitor(monitor)
            .call()
    }

    private fun push(repository: Repository, provider: CredentialsProvider?, monitor: ProgressMonitor) {
        val branch = repository.branch
        val merge = branch?.let { BranchConfig(repository.config, it).merge }
            ?: throw GitFault.Rejected(GitRejection.MISSING_UPSTREAM)
        val results = Git(repository).push()
            .setRemote(repository.remoteName())
            .setRefSpecs(RefSpec("${Constants.R_HEADS}$branch:$merge"))
            .setCredentialsProvider(provider)
            .setProgressMonitor(monitor)
            .call()
        results.flatMap { it.remoteUpdates }.firstNotNullOfOrNull { it.status.toPushFault() }?.let { throw it }
    }
}

internal fun openRepository(directory: File): Repository {
    if (!File(directory, ".git").isDirectory) throw GitFault.NotARepository
    return Git.open(directory).repository
}

/** The remote the current branch tracks, else `origin`. */
internal fun Repository.remoteName(): String {
    val tracked = branch?.let { BranchConfig(config, it).remote }
    val remotes = config.getSubsections("remote")
    return tracked?.takeIf { it in remotes } ?: DEFAULT_REMOTE.takeIf { it in remotes }
        ?: remotes.firstOrNull()
        ?: throw GitFault.Rejected(GitRejection.MISSING_UPSTREAM)
}

internal fun Repository.remoteUrl(): String =
    config.getString("remote", remoteName(), "url") ?: throw GitFault.Rejected(GitRejection.MISSING_UPSTREAM)

/** The host credentials are kept under, lowercase; `null` for a remote without one (a local path). */
internal fun remoteHost(url: String): String? = runCatching { URIish(url).host?.lowercase() }.getOrNull()

private fun RemoteRefUpdate.Status.toPushFault(): GitFault? = when (this) {
    RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE, RemoteRefUpdate.Status.NOT_ATTEMPTED -> null
    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> GitFault.NonFastForward
    RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> GitFault.Rejected(GitRejection.STALE_TARGET)
    RemoteRefUpdate.Status.REJECTED_NODELETE,
    RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
    RemoteRefUpdate.Status.NON_EXISTING,
    RemoteRefUpdate.Status.AWAITING_REPORT,
    -> GitFault.Rejected(GitRejection.REJECTED)
}

/**
 * The fault for a failed transfer. JGit reports a refused login only in its messages (`not authorized`, or the HTTP
 * status), so those are matched anywhere in the cause chain.
 */
internal fun GitAPIException.toTransferFault(host: String?, cancelled: Boolean): GitFault {
    if (cancelled) return GitFault.Rejected(GitRejection.CANCELLED)
    val messages = generateSequence<Throwable>(this) { it.cause }.mapNotNull { it.message?.lowercase() }.toList()
    val authRefused = messages.any { message -> AUTH_MARKERS.any { it in message } }
    return when {
        this is TransportException && authRefused -> GitFault.AuthRequired(host.orEmpty())
        this is InvalidRemoteException || cause is NoRemoteRepositoryException ->
            GitFault.Rejected(GitRejection.REJECTED)
        this is TransportException -> GitFault.Network
        else -> GitFault.Rejected(GitRejection.PLATFORM_FAILURE)
    }
}

private const val DEFAULT_REMOTE = "origin"
private val AUTH_MARKERS = listOf("not authorized", "authentication is required", "401", "403")
