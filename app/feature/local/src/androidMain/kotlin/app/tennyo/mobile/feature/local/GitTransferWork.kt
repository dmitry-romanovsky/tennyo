package app.tennyo.mobile.feature.local

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitRejection
import org.eclipse.jgit.lib.ProgressMonitor

/*
 * How a GitTransfer, its progress and its fault travel through WorkManager's Data. Credentials never do: WorkManager
 * persists Data in its database, so the worker reads them from the credential store instead.
 */

internal fun uniqueTransferName(workspaceSlug: String): String = "git-transfer-$workspaceSlug"

internal fun GitTransfer.toData(): Data = workDataOf(
    KEY_KIND to when (this) {
        is GitTransfer.Clone -> KIND_CLONE
        is GitTransfer.Fetch -> KIND_FETCH
        is GitTransfer.Push -> KIND_PUSH
    },
    KEY_SLUG to workspaceSlug,
    KEY_URL to (this as? GitTransfer.Clone)?.url,
)

internal fun Data.toTransfer(): GitTransfer? {
    val slug = getString(KEY_SLUG) ?: return null
    return when (getString(KEY_KIND)) {
        KIND_CLONE -> getString(KEY_URL)?.let { GitTransfer.Clone(slug, it) }
        KIND_FETCH -> GitTransfer.Fetch(slug)
        KIND_PUSH -> GitTransfer.Push(slug)
        else -> null
    }
}

internal fun progressData(progress: Float?): Data = workDataOf(KEY_PROGRESS to (progress ?: UNKNOWN_PROGRESS))

internal fun GitFault.toData(): Data = workDataOf(
    KEY_FAULT to when (this) {
        GitFault.NotARepository -> FAULT_NOT_A_REPOSITORY
        is GitFault.AuthRequired -> FAULT_AUTH_REQUIRED
        GitFault.IdentityMissing -> FAULT_IDENTITY_MISSING
        GitFault.NonFastForward -> FAULT_NON_FAST_FORWARD
        is GitFault.Conflicts -> FAULT_CONFLICTS
        GitFault.Network -> FAULT_NETWORK
        is GitFault.Rejected -> FAULT_REJECTED
    },
    KEY_HOST to (this as? GitFault.AuthRequired)?.host,
    KEY_PATHS to (this as? GitFault.Conflicts)?.paths?.toTypedArray(),
    KEY_REJECTION to (this as? GitFault.Rejected)?.reason?.name,
)

internal fun Data.toFault(): GitFault = when (getString(KEY_FAULT)) {
    FAULT_NOT_A_REPOSITORY -> GitFault.NotARepository
    FAULT_AUTH_REQUIRED -> GitFault.AuthRequired(getString(KEY_HOST).orEmpty())
    FAULT_IDENTITY_MISSING -> GitFault.IdentityMissing
    FAULT_NON_FAST_FORWARD -> GitFault.NonFastForward
    FAULT_CONFLICTS -> GitFault.Conflicts(getStringArray(KEY_PATHS)?.toList().orEmpty())
    FAULT_NETWORK -> GitFault.Network
    else -> GitFault.Rejected(
        getString(KEY_REJECTION)?.let { name -> GitRejection.entries.firstOrNull { it.name == name } }
            ?: GitRejection.UNEXPECTED,
    )
}

internal fun WorkInfo?.toTransferState(): GitTransferState = when (this?.state) {
    null -> GitTransferState.Failed(GitFault.Rejected(GitRejection.BUSY))
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GitTransferState.Queued
    WorkInfo.State.RUNNING ->
        GitTransferState.Running(progress.getFloat(KEY_PROGRESS, UNKNOWN_PROGRESS).takeIf { it >= 0f })

    WorkInfo.State.SUCCEEDED -> GitTransferState.Succeeded
    WorkInfo.State.FAILED -> GitTransferState.Failed(outputData.toFault())
    WorkInfo.State.CANCELLED -> GitTransferState.Cancelled
}

/** Reports JGit's task progress as a share of the current task, and stops JGit once [isCancelled] says so. */
internal class TransferProgressMonitor(
    private val isCancelled: () -> Boolean,
    private val onProgress: (Float?) -> Unit,
) : ProgressMonitor {
    private var totalWork = 0
    private var completedWork = 0

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(title: String?, totalWork: Int) {
        this.totalWork = totalWork
        completedWork = 0
        report()
    }

    override fun update(completed: Int) {
        completedWork += completed
        report()
    }

    override fun endTask() {
        completedWork = totalWork
        report()
    }

    override fun isCancelled(): Boolean = isCancelled.invoke()

    override fun showDuration(enabled: Boolean) = Unit

    private fun report() {
        onProgress(if (totalWork > 0) completedWork.toFloat() / totalWork else null)
    }
}

internal const val KEY_PROGRESS = "progress"
private const val KEY_KIND = "kind"
private const val KEY_SLUG = "slug"
private const val KEY_URL = "url"
private const val KEY_FAULT = "fault"
private const val KEY_HOST = "host"
private const val KEY_PATHS = "paths"
private const val KEY_REJECTION = "rejection"
private const val KIND_CLONE = "clone"
private const val KIND_FETCH = "fetch"
private const val KIND_PUSH = "push"
private const val FAULT_NOT_A_REPOSITORY = "not_a_repository"
private const val FAULT_AUTH_REQUIRED = "auth_required"
private const val FAULT_IDENTITY_MISSING = "identity_missing"
private const val FAULT_NON_FAST_FORWARD = "non_fast_forward"
private const val FAULT_CONFLICTS = "conflicts"
private const val FAULT_NETWORK = "network"
private const val FAULT_REJECTED = "rejected"
private const val UNKNOWN_PROGRESS = -1f
