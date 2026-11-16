package app.tennyo.mobile.feature.local

import app.tennyo.mobile.link.store.FeatureStore
import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnCreate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The on-device workspaces section of Home. */
interface LocalHomeComponent {
    val state: StateFlow<LocalHomeState>

    fun onIntent(intent: LocalHomeIntent)
}

data class LocalHomeState(val workspaces: List<LocalWorkspace> = emptyList())

sealed interface LocalHomeIntent {
    data class OpenWorkspace(val slug: String) : LocalHomeIntent
    data object OpenCreate : LocalHomeIntent
    data object OpenClone : LocalHomeIntent
    data class DeleteWorkspace(val slug: String) : LocalHomeIntent
}

class RealLocalHomeComponent(
    componentContext: ComponentContext,
    private val store: LocalWorkspaceStore,
    private val onOpenWorkspace: (slug: String) -> Unit,
    private val onOpenCreate: () -> Unit,
    private val onOpenClone: () -> Unit,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : LocalHomeComponent, ComponentContext by componentContext {
    private val feature = FeatureStore<LocalHomeState, List<LocalWorkspace>, Nothing>(
        initialState = LocalHomeState(),
        lifecycle = lifecycle,
        reduce = { state, workspaces -> state.copy(workspaces = workspaces) },
        mainContext = mainContext,
    )

    override val state: StateFlow<LocalHomeState> = feature.state

    init {
        lifecycle.doOnCreate {
            store.workspaces.onEach(feature::dispatch).launchIn(feature.scope)
        }
    }

    override fun onIntent(intent: LocalHomeIntent) {
        when (intent) {
            is LocalHomeIntent.OpenWorkspace -> onOpenWorkspace(intent.slug)
            LocalHomeIntent.OpenCreate -> onOpenCreate()
            LocalHomeIntent.OpenClone -> onOpenClone()
            is LocalHomeIntent.DeleteWorkspace -> feature.scope.launch {
                try {
                    store.remove(intent.slug)
                } catch (fault: WorkspaceFault) {
                    Logger.w(fault) { "Workspace ${intent.slug} could not be deleted" }
                }
            }
        }
    }
}
