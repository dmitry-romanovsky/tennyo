package app.tennyo.mobile.link.store

import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureStoreTest {
    private sealed interface CounterMsg {
        data class Added(val amount: Int) : CounterMsg
        data object Reset : CounterMsg
    }

    private fun reduce(state: Int, msg: CounterMsg): Int = when (msg) {
        is CounterMsg.Added -> state + msg.amount
        CounterMsg.Reset -> 0
    }

    @Test
    fun messagesChangeStateThroughTheReducer() = runTest {
        val store = FeatureStore<Int, CounterMsg, Nothing>(1, LifecycleRegistry(), ::reduce, UnconfinedTestDispatcher())

        store.dispatch(CounterMsg.Added(2))
        assertEquals(3, store.state.value)

        store.dispatch(CounterMsg.Reset)
        assertEquals(0, store.state.value)
    }

    @Test
    fun effectsSentBeforeCollectionAreDelivered() = runTest {
        val store = FeatureStore<Int, CounterMsg, String>(0, LifecycleRegistry(), ::reduce, UnconfinedTestDispatcher())

        store.emit("opened")

        assertEquals("opened", store.effects.first())
    }

    @Test
    fun destroyingTheLifecycleCancelsTheScope() {
        val lifecycle = LifecycleRegistry().apply { resume() }
        val store = FeatureStore<Int, CounterMsg, Nothing>(0, lifecycle, ::reduce, UnconfinedTestDispatcher())

        lifecycle.destroy()

        assertFalse(store.scope.isActive)
    }
}
