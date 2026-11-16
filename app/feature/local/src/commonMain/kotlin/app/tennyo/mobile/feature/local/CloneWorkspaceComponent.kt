package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitCredentials
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.GitRejection
import app.tennyo.mobile.link.store.FeatureStore
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Clones a remote repository into a new workspace. */
interface CloneWorkspaceComponent {
    val state: StateFlow<CloneWorkspaceState>

    fun onIntent(intent: CloneWorkspaceIntent)

    fun onBack()
}

sealed interface ClonePhase {
    data object Editing : ClonePhase

    data class Cloning(val progress: Float?) : ClonePhase

    /** [host] refused the clone; the user may sign in once and retry. */
    data class CredentialsNeeded(val host: String) : ClonePhase

    data class Failed(val fault: GitFault) : ClonePhase
}

data class CloneWorkspaceState(
    val url: String = "",
    val phase: ClonePhase = ClonePhase.Editing,
    /** The user pressed back while a clone runs and is asked whether to cancel it. */
    val confirmingLeave: Boolean = false,
) {
    val canStart: Boolean get() = url.isNotBlank() && phase !is ClonePhase.Cloning
}

sealed interface CloneWorkspaceIntent {
    data class ChangeUrl(val url: String) : CloneWorkspaceIntent
    data object Start : CloneWorkspaceIntent
    data class SubmitCredentials(val username: String, val secret: String) : CloneWorkspaceIntent
    data object Cancel : CloneWorkspaceIntent
    data object ConfirmLeave : CloneWorkspaceIntent
    data object DismissLeave : CloneWorkspaceIntent
}

internal sealed interface CloneWorkspaceMsg {
    data class UrlChanged(val url: String) : CloneWorkspaceMsg
    data class PhaseChanged(val phase: ClonePhase) : CloneWorkspaceMsg
    data class LeaveConfirmation(val shown: Boolean) : CloneWorkspaceMsg
}

internal fun reduce(state: CloneWorkspaceState, msg: CloneWorkspaceMsg): CloneWorkspaceState = when (msg) {
    is CloneWorkspaceMsg.UrlChanged -> if (state.phase is ClonePhase.Cloning) state else state.copy(url = msg.url)
    is CloneWorkspaceMsg.PhaseChanged -> state.copy(
        phase = msg.phase,
        confirmingLeave = state.confirmingLeave && msg.phase is ClonePhase.Cloning,
    )

    is CloneWorkspaceMsg.LeaveConfirmation -> state.copy(confirmingLeave = msg.shown)
}

/** The workspace is named after the repository in [url]: its last path segment without `.git`. */
internal fun cloneWorkspaceName(url: String): String =
    url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git").ifBlank { url }

/**
 * Clones through [transfers], so the clone keeps running with the app in the background. A failed or cancelled clone
 * removes its half-filled workspace.
 */
class RealCloneWorkspaceComponent(
    componentContext: ComponentContext,
    private val store: LocalWorkspaceStore,
    private val transfers: GitTransferScheduler,
    private val onBackNav: () -> Unit,
    private val onCloned: (slug: String) -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : CloneWorkspaceComponent, ComponentContext by componentContext {
    private val feature = FeatureStore<CloneWorkspaceState, CloneWorkspaceMsg, Nothing>(
        CloneWorkspaceState(),
        lifecycle,
        ::reduce,
        mainContext,
    )
    private var running: GitTransfer.Clone? = null

    override val state: StateFlow<CloneWorkspaceState> = feature.state

    override fun onIntent(intent: CloneWorkspaceIntent) {
        when (intent) {
            is CloneWorkspaceIntent.ChangeUrl -> feature.dispatch(CloneWorkspaceMsg.UrlChanged(intent.url))
            CloneWorkspaceIntent.Start -> start()
            is CloneWorkspaceIntent.SubmitCredentials -> {
                val phase = state.value.phase as? ClonePhase.CredentialsNeeded ?: return
                transfers.stageCredentials(phase.host, GitCredentials(intent.username, intent.secret))
                start(credentialsSupplied = true)
            }

            CloneWorkspaceIntent.Cancel -> cancel()
            CloneWorkspaceIntent.ConfirmLeave -> {
                cancel()
                onBackNav()
            }

            CloneWorkspaceIntent.DismissLeave -> feature.dispatch(CloneWorkspaceMsg.LeaveConfirmation(false))
        }
    }

    override fun onBack() {
        if (state.value.phase is ClonePhase.Cloning) {
            feature.dispatch(CloneWorkspaceMsg.LeaveConfirmation(true))
        } else {
            onBackNav()
        }
    }

    private fun start(credentialsSupplied: Boolean = false) {
        val url = state.value.url.trim()
        if (url.isEmpty() || running != null) return
        feature.dispatch(CloneWorkspaceMsg.PhaseChanged(ClonePhase.Cloning(progress = null)))
        feature.scope.launch {
            val workspace = try {
                store.reserve(cloneWorkspaceName(url))
            } catch (_: WorkspaceFault) {
                val fault = GitFault.Rejected(GitRejection.PLATFORM_FAILURE)
                feature.dispatch(CloneWorkspaceMsg.PhaseChanged(ClonePhase.Failed(fault)))
                return@launch
            }
            val transfer = GitTransfer.Clone(workspace.slug, url)
            running = transfer
            // Leaving the screen stops only the observation; the clone itself runs on until it ends or is cancelled.
            try {
                transfers.start(transfer).collect { transferState ->
                    onTransferState(workspace, transferState, credentialsSupplied)
                }
            } finally {
                running = null
            }
        }
    }

    private suspend fun onTransferState(
        workspace: LocalWorkspace,
        transferState: GitTransferState,
        credentialsSupplied: Boolean,
    ) {
        when (transferState) {
            GitTransferState.Queued -> Unit
            is GitTransferState.Running ->
                feature.dispatch(CloneWorkspaceMsg.PhaseChanged(ClonePhase.Cloning(transferState.progress)))

            GitTransferState.Succeeded -> {
                feature.dispatch(CloneWorkspaceMsg.PhaseChanged(ClonePhase.Editing))
                onCloned(workspace.slug)
            }

            is GitTransferState.Failed -> {
                store.remove(workspace.slug)
                val fault = transferState.fault
                val phase = if (fault is GitFault.AuthRequired && !credentialsSupplied) {
                    ClonePhase.CredentialsNeeded(fault.host)
                } else {
                    ClonePhase.Failed(fault)
                }
                feature.dispatch(CloneWorkspaceMsg.PhaseChanged(phase))
            }

            GitTransferState.Cancelled -> {
                store.remove(workspace.slug)
                feature.dispatch(CloneWorkspaceMsg.PhaseChanged(ClonePhase.Editing))
            }
        }
    }

    private fun cancel() {
        running?.let(transfers::cancel)
    }
}
