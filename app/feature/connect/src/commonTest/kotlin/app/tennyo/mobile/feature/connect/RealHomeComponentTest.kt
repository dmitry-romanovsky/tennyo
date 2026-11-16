package app.tennyo.mobile.feature.connect

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RealHomeComponentTest {
    private val navigation = mutableListOf<String>()
    private val activeSession = MutableStateFlow<ActiveLinkSummary?>(null)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun intentsNavigate() {
        val home = home()

        home.onIntent(HomeIntent.OpenConnect)
        home.onIntent(HomeIntent.OpenSettings)
        home.onIntent(HomeIntent.EndSession)

        assertEquals(listOf("connect", "settings", "end"), navigation)
    }

    @Test
    fun linkSessionChangesReachTheState() {
        val home = home()

        val session = ActiveLinkSummary(hostLabel = "Cursor 1.6", startedAtEpochMillis = 1_000L)
        activeSession.value = session
        assertEquals(session, home.state.value.activeSession)

        activeSession.value = null
        assertNull(home.state.value.activeSession)
    }

    private fun home(): HomeComponent {
        val lifecycle = LifecycleRegistry()
        return RealHomeComponent(
            componentContext = DefaultComponentContext(lifecycle),
            activeSession = activeSession,
            onOpenConnect = { navigation += "connect" },
            onOpenSettings = { navigation += "settings" },
            onEndSession = { navigation += "end" },
        ).also { lifecycle.create() }
    }
}
