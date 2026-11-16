package app.tennyo.mobile.link.store

import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext

/**
 * The state holder a component composes: one [state] flow changed only by [dispatch]ing messages through a pure
 * [reduce], one-shot [effects] for navigation-like events the UI consumes once, and a [scope] for the component's
 * work that ends with the owning lifecycle.
 */
class FeatureStore<State : Any, Msg : Any, Effect : Any>(
    initialState: State,
    lifecycle: Lifecycle,
    private val reduce: (State, Msg) -> State,
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + mainContext)

    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val effectChannel = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = effectChannel.receiveAsFlow()

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    fun dispatch(msg: Msg) {
        mutableState.update { reduce(it, msg) }
    }

    /** Buffered, so an effect sent before the UI starts collecting is still delivered once it does. */
    fun emit(effect: Effect) {
        effectChannel.trySend(effect)
    }
}
