package app.tennyo.mobile.feature.files

import app.tennyo.contract.Capability
import app.tennyo.contract.FileContent
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pane.PaneLink
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RealFilesPaneComponentTest {
    private val path = "src/Main.kt"
    private val link = FakeFilesLink()

    @Test
    fun `a save the IDE refuses leaves the file modified and not saving`() = runTest {
        val pane = pane()
        pane.onIntent(FilesIntent.OpenFile(path))
        pane.onIntent(FilesIntent.EditText(path, "edited"))

        pane.onIntent(FilesIntent.SaveSelectedFile)

        assertEquals(FileSyncState.MODIFIED, pane.state.value.file(path)?.syncState)
    }

    private fun pane(): RealFilesPaneComponent {
        val lifecycle = LifecycleRegistry().apply { create() }
        return RealFilesPaneComponent(
            componentContext = DefaultComponentContext(lifecycle),
            link = link,
            isShown = { true },
            onSurfaceChanged = {},
            mainContext = UnconfinedTestDispatcher(),
        )
    }

    /** Serves one file and refuses every write, the way a host that rejects the save would. */
    private class FakeFilesLink : PaneLink {
        override val ticket = MutableStateFlow<LinkTicket?>(
            LinkTicket(
                endpoint = "https://ide.test",
                sessionId = "session",
                token = "token",
                fingerprint = "fingerprint",
                projectName = "Tennyo",
                projectPath = "/work/Tennyo",
            ),
        )
        override val project = MutableStateFlow(LinkProject(capabilities = setOf(Capability.FILES)))

        override suspend fun call(call: LinkCall): LinkResult = when (call) {
            is LinkCall.ReadFile ->
                LinkResult.File(FileContent(path = call.path, name = "Main.kt", text = "original", revision = "1"))

            is LinkCall.WriteFile -> throw LinkFault.StreamClosed(beforeLiveSync = false)
            else -> LinkResult.Done()
        }
    }
}
