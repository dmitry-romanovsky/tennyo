package app.tennyo.mobile.feature.local

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RealLocalHomeComponentTest {
    private val store = RemovingStore(
        LocalWorkspace("Notes", "notes", createdAtEpochMillis = 2),
        LocalWorkspace("Demo", "demo", createdAtEpochMillis = 1),
    )

    @Test
    fun `deleting a workspace removes it from the store and the list`() {
        val home = home()

        home.onIntent(LocalHomeIntent.DeleteWorkspace("notes"))

        assertEquals(listOf("demo"), home.state.value.workspaces.map(LocalWorkspace::slug))
    }

    @Test
    fun `a workspace that cannot be deleted stays listed`() {
        store.failing = true
        val home = home()

        home.onIntent(LocalHomeIntent.DeleteWorkspace("notes"))

        assertEquals(listOf("notes", "demo"), home.state.value.workspaces.map(LocalWorkspace::slug))
    }

    private fun home(): LocalHomeComponent {
        val lifecycle = LifecycleRegistry()
        return RealLocalHomeComponent(
            componentContext = DefaultComponentContext(lifecycle),
            store = store,
            onOpenWorkspace = {},
            onOpenCreate = {},
            onOpenClone = {},
            mainContext = UnconfinedTestDispatcher(),
        ).also { lifecycle.create() }
    }

    private class RemovingStore(vararg initial: LocalWorkspace) : LocalWorkspaceStore {
        var failing = false
        override val workspaces = MutableStateFlow(initial.toList())

        override suspend fun get(slug: String) = workspaces.value.firstOrNull { it.slug == slug }

        override suspend fun reserve(name: String) = error("not used")

        override suspend fun updateTabs(slug: String, openedTabs: List<String>, selectedTab: Int) = Unit

        override suspend fun remove(slug: String) {
            if (failing) throw WorkspaceFault.Unwritable(slug)
            workspaces.value = workspaces.value.filterNot { it.slug == slug }
        }
    }
}
