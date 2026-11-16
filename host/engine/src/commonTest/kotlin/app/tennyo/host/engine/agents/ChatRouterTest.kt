package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.ChatState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRouterTest {
    @Test
    fun `switching backend preserves its conversation and closing router closes every backend`() = runTest {
        val first = RecordingBackend(ChatAgent("first", "First"))
        val second = RecordingBackend(ChatAgent("second", "Second"))
        val router = ChatRouter(
            publish = {},
            backends = listOf({ first }, { second }),
        )

        router.start("first", sessionId = null)
        router.start("second", sessionId = null)
        router.start("first", sessionId = null)

        assertEquals(0, first.closeCount)
        assertEquals(0, second.closeCount)
        assertEquals(2, first.startCount)
        assertEquals(1, second.startCount)
        assertTrue(router.prompt("continue", emptyList()))
        assertEquals(1, first.promptCount)

        router.close()

        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun `a background backend waiting for a permission is cancelled and the selected one is not`() = runTest {
        val first = RecordingBackend(ChatAgent("first", "First"))
        val second = RecordingBackend(ChatAgent("second", "Second"))
        val published = mutableListOf<ChatState>()
        lateinit var publishFirst: (ChatState) -> Unit
        lateinit var publishSecond: (ChatState) -> Unit
        val router = ChatRouter(
            publish = { published += it },
            backends = listOf(
                { publish -> publishFirst = publish; first },
                { publish -> publishSecond = publish; second },
            ),
        )
        router.start("second", sessionId = null)
        val blocked = ChatState(
            entries = listOf(ChatEntry.Permission("req", "Run", emptyList())),
        )

        publishFirst(blocked)
        publishSecond(blocked)

        assertEquals(1, first.cancelCount)
        assertEquals(0, second.cancelCount)
        assertEquals(1, published.size)
    }

    @Test
    fun `switching away from a backend with a pending permission cancels it`() = runTest {
        val first = RecordingBackend(ChatAgent("first", "First"))
        val second = RecordingBackend(ChatAgent("second", "Second"))
        val router = ChatRouter(publish = {}, backends = listOf({ first }, { second }))
        router.start("first", sessionId = null)
        first.entries = listOf(ChatEntry.Permission("req", "Run", emptyList()))

        router.start("second", sessionId = null)

        assertEquals(1, first.cancelCount)
        assertEquals(0, second.cancelCount)
    }

    private class RecordingBackend(private val agent: ChatAgent) : ChatBackend {
        var closeCount = 0
        var startCount = 0
        var promptCount = 0
        var entries: List<ChatEntry> = emptyList()

        override fun agents() = listOf(agent)
        override fun snapshot() = ChatState(agents = agents(), agentId = agent.id, entries = entries)
        override suspend fun start(agentId: String, sessionId: String?): ChatState {
            startCount++
            return snapshot()
        }

        override suspend fun listSessions(agentId: String) = snapshot()
        override suspend fun history(): List<ChatHistoryEntry> = emptyList()
        override suspend fun authenticate(methodId: String) = snapshot()
        override suspend fun setMode(modeId: String) = true
        override suspend fun setConfig(configId: String, value: String) = true
        override fun prompt(text: String, attachments: List<ChatAttachment>): Boolean {
            promptCount++
            return true
        }

        var cancelCount = 0

        override fun cancel(): Boolean {
            cancelCount++
            return true
        }

        override fun answer(requestId: String, optionId: String?) = true
        override fun answerForm(
            requestId: String,
            action: ChatFormAction,
            values: Map<String, String>
        ) = true

        override fun close() {
            closeCount++
        }
    }
}
