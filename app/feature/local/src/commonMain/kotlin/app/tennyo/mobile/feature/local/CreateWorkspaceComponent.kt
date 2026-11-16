package app.tennyo.mobile.feature.local

import app.tennyo.mobile.design.UiText
import app.tennyo.mobile.feature.git.GitFault
import app.tennyo.mobile.feature.git.text
import app.tennyo.mobile.feature.local.generated.resources.Res
import app.tennyo.mobile.feature.local.generated.resources.local_create_failed
import app.tennyo.mobile.link.store.FeatureStore
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The form that creates a new workspace from a [WorkspaceTemplate]. */
interface CreateWorkspaceComponent {
    val state: StateFlow<CreateWorkspaceState>

    fun onIntent(intent: CreateWorkspaceIntent)

    fun onBack()
}

data class CreateWorkspaceState(
    val name: String = DEFAULT_WORKSPACE_NAME,
    val template: WorkspaceTemplate = WorkspaceTemplate(),
    val creating: Boolean = false,
    /** Why the last attempt failed, shown above the form until the next one. */
    val failure: UiText? = null,
) {
    val canCreate: Boolean get() = name.isNotBlank() && !creating
}

sealed interface CreateWorkspaceIntent {
    data class ChangeName(val name: String) : CreateWorkspaceIntent
    data class ChangeTemplate(val template: WorkspaceTemplate) : CreateWorkspaceIntent
    data object Create : CreateWorkspaceIntent
}

internal sealed interface CreateWorkspaceMsg {
    data class NameChanged(val name: String) : CreateWorkspaceMsg
    data class TemplateChanged(val template: WorkspaceTemplate) : CreateWorkspaceMsg
    data class CreatingChanged(val creating: Boolean) : CreateWorkspaceMsg
    data class Failed(val text: UiText) : CreateWorkspaceMsg
}

internal fun reduce(state: CreateWorkspaceState, msg: CreateWorkspaceMsg): CreateWorkspaceState = when (msg) {
    is CreateWorkspaceMsg.NameChanged -> state.copy(name = msg.name)
    is CreateWorkspaceMsg.TemplateChanged -> state.copy(template = msg.template)

    is CreateWorkspaceMsg.CreatingChanged ->
        state.copy(creating = msg.creating, failure = state.failure.takeUnless { msg.creating })

    is CreateWorkspaceMsg.Failed -> state.copy(failure = msg.text)
}

class RealCreateWorkspaceComponent(
    componentContext: ComponentContext,
    private val store: LocalWorkspaceStore,
    private val files: WorkspaceFiles,
    private val repositories: LocalGitRepositories,
    private val onBackNav: () -> Unit,
    private val onCreated: (slug: String) -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : CreateWorkspaceComponent, ComponentContext by componentContext {
    private val feature = FeatureStore<CreateWorkspaceState, CreateWorkspaceMsg, Nothing>(
        CreateWorkspaceState(),
        lifecycle,
        ::reduce,
        mainContext,
    )

    override val state: StateFlow<CreateWorkspaceState> = feature.state

    override fun onIntent(intent: CreateWorkspaceIntent) {
        when (intent) {
            is CreateWorkspaceIntent.ChangeName -> feature.dispatch(CreateWorkspaceMsg.NameChanged(intent.name))
            is CreateWorkspaceIntent.ChangeTemplate ->
                feature.dispatch(CreateWorkspaceMsg.TemplateChanged(intent.template))

            CreateWorkspaceIntent.Create -> create()
        }
    }

    override fun onBack() {
        if (!state.value.creating) onBackNav()
    }

    private fun create() {
        val request = state.value
        if (!request.canCreate) return
        feature.dispatch(CreateWorkspaceMsg.CreatingChanged(true))
        feature.scope.launch {
            var reserved: LocalWorkspace? = null
            try {
                val workspace = store.reserve(request.name).also { reserved = it }
                request.template.files(request.name.trim()).forEach { (path, text) ->
                    files.writeText(workspace.slug, path, text)
                }
                if (request.template.createGitRepository) repositories.init(workspace.slug)
                onCreated(workspace.slug)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                reserved?.let { runCatching { store.remove(it.slug) } }
                val text = when (failure) {
                    is WorkspaceFault -> failure.text
                    is GitFault -> failure.text
                    else -> UiText.Resource(Res.string.local_create_failed)
                }
                feature.dispatch(CreateWorkspaceMsg.Failed(text))
            } finally {
                feature.dispatch(CreateWorkspaceMsg.CreatingChanged(false))
            }
        }
    }
}

private const val DEFAULT_WORKSPACE_NAME = "untitled"
