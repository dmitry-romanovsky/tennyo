package app.tennyo.mobile.feature.connect

import app.tennyo.mobile.link.store.FeatureStore
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnCreate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Home: the welcome, the way to an IDE (or back to the running session), and Settings. */
interface HomeComponent {
    val state: StateFlow<HomeState>

    fun onIntent(intent: HomeIntent)
}

/** The session running in the background: which computer, and since when. */
data class ActiveLinkSummary(
    val hostLabel: String,
    val startedAtEpochMillis: Long,
)

data class HomeState(
    val activeSession: ActiveLinkSummary? = null,
)

sealed interface HomeIntent {
    data object OpenConnect : HomeIntent
    data object OpenSettings : HomeIntent
    data object EndSession : HomeIntent
}

class RealHomeComponent(
    componentContext: ComponentContext,
    activeSession: StateFlow<ActiveLinkSummary?>,
    private val onOpenConnect: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onEndSession: () -> Unit,
) : HomeComponent, ComponentContext by componentContext {
    private val store = FeatureStore<HomeState, HomeMsg, Nothing>(
        initialState = HomeState(activeSession = activeSession.value),
        lifecycle = lifecycle,
        reduce = { state, msg ->
            when (msg) {
                is HomeMsg.LinkSessionChanged -> state.copy(activeSession = msg.session)
            }
        },
    )

    override val state: StateFlow<HomeState> = store.state

    init {
        lifecycle.doOnCreate {
            activeSession.onEach { store.dispatch(HomeMsg.LinkSessionChanged(it)) }.launchIn(store.scope)
        }
    }

    override fun onIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.OpenConnect -> onOpenConnect()
            HomeIntent.OpenSettings -> onOpenSettings()
            HomeIntent.EndSession -> onEndSession()
        }
    }
}

internal sealed interface HomeMsg {
    data class LinkSessionChanged(val session: ActiveLinkSummary?) : HomeMsg
}
