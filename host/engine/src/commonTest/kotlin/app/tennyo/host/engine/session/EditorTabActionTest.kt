package app.tennyo.host.engine.session

import app.tennyo.contract.EditorTabAction
import app.tennyo.contract.EditorTabActionRequest
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFaultCode
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkResult
import app.tennyo.host.engine.panes.EditorTabInfo
import app.tennyo.host.engine.panes.FakeProjectFiles
import app.tennyo.host.engine.panes.IdeEditorTabsPort
import app.tennyo.host.engine.platform.hostWallClockMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A host that advertises fewer editor-tab actions than it has calls for: the phone is told which actions exist, and a
 * call for one it was not offered is faulted rather than half-performed.
 */
class EditorTabActionTest {
    private val clock = AtomicLong(hostWallClockMillis())
    private val scope = CoroutineScope(SupervisorJob())
    private val files = FakeProjectFiles().file("/work/project/src/Main.kt", "fun main() {}")
    private val readOnlyTabs = object : IdeEditorTabsPort by NoEditorTabs {
        override val allowedActions = setOf(EditorTabAction.OPEN, EditorTabAction.SELECT)

        override fun tabs(): List<EditorTabInfo> =
            listOf(EditorTabInfo("/work/project/src/Main.kt", "Main.kt", writable = true))

        override fun open(path: String): Boolean = true
    }
    private val host = SessionHost(testPanes(files, editorTabs = readOnlyTabs), FakeHostPlatform(), scope, clock::load)
    private val device = TestDevice()

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `the tab state carries the host's actions and a call outside them faults`() = runTest {
        val channel = connect()

        val state = channel.awaitAnswer(channel.call(LinkCall.EditorTabs))
        val closed = channel.awaitAnswer(
            channel.call(LinkCall.EditorTabActionCall(EditorTabActionRequest("src/Main.kt", EditorTabAction.CLOSE))),
        )
        val opened = channel.awaitAnswer(
            channel.call(LinkCall.EditorTabActionCall(EditorTabActionRequest("src/Main.kt", EditorTabAction.OPEN))),
        )

        val tabs = ((state as LinkFrame.Reply).result as LinkResult.EditorTabs).state
        assertEquals(setOf(EditorTabAction.OPEN, EditorTabAction.SELECT), tabs.allowedActions)
        assertEquals(LinkFaultCode.UNSUPPORTED, (closed as LinkFrame.Fault).fault)
        assertTrue((opened as LinkFrame.Reply).result is LinkResult.Done)
    }

    private fun connect(): TestChannel {
        val ticket = host.startSession().also {
            assertNotNull(host.session(it.sessionId)).auth.approveDevice(device.publicKey, "Pixel", "request-1")
        }
        return TestChannel(host, ticket, device, clock::load).open(null)
    }

    private suspend fun TestChannel.awaitAnswer(call: LinkFrame.Call): LinkFrame =
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                var answer: LinkFrame? = null
                while (answer == null) {
                    answer = socket.frames.firstOrNull {
                        (it as? LinkFrame.Reply)?.id == call.id || (it as? LinkFrame.Fault)?.id == call.id
                    }
                }
                answer
            }
        }
}
