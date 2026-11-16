package app.tennyo.mobile.feature.chat

import app.tennyo.contract.Capability
import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkPush
import app.tennyo.contract.LinkResult
import app.tennyo.mobile.link.LinkFault
import app.tennyo.mobile.link.LinkProject
import app.tennyo.mobile.link.LinkTicket
import app.tennyo.mobile.link.pane.PaneLink
import app.tennyo.mobile.link.pane.SyncPass
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.tennyo.contract.ChatState as HostChatState

/**
 * The agent list must not wait on a snapshot that never comes. A pane built while the session is already live, or
 * before the host reports Chat, loads the conversation itself instead of showing "Loading agents" for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealChatPaneComponentTest {
    private val link = FakeChatLink()

    @Test
    fun `chat reported after the pane was built loads its agents`() = runTest {
        val pane = pane()
        assertFalse(pane.state.value.loaded)

        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))

        assertTrue(pane.state.value.loaded)
        assertEquals(listOf("claude"), pane.state.value.host.agents.map { it.id })
    }

    @Test
    fun `a snapshot that failed is fetched again on the next sync`() = runTest {
        link.failing = true
        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))
        val pane = pane()
        assertFalse(pane.state.value.loaded)

        link.failing = false
        pane.sync(SyncPass(shown = false, metadata = false, explicitContent = false, nowEpochMillis = 0L))

        assertTrue(pane.state.value.loaded)
    }

    @Test
    fun `back leaves a conversation for the agent list and starting an agent returns to it`() = runTest {
        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))
        val pane = pane()
        pane.onIntent(ChatIntent.StartAgent("claude"))
        assertTrue(pane.state.value.inConversation)

        assertTrue(pane.onBack())
        assertFalse(pane.state.value.inConversation)
        assertFalse(pane.onBack(), "the agent list is the pane's root")

        pane.onIntent(ChatIntent.StartAgent("claude"))
        assertTrue(pane.state.value.inConversation)
    }

    @Test
    fun `the agent list shows earlier conversations across agents and are refreshed on coming back to it`() = runTest {
        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))
        val pane = pane()
        assertEquals(listOf("old"), pane.state.value.recent.map { it.sessionId })

        link.history = listOf(ChatHistoryEntry("claude", "newer"), ChatHistoryEntry("claude", "old"))
        pane.onIntent(ChatIntent.StartAgent("claude"))
        pane.onBack()

        assertEquals(listOf("newer", "old"), pane.state.value.recent.map { it.sessionId })
    }

    @Test
    fun `the conversation left for the agent list can be returned to without starting anew`() = runTest {
        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))
        val pane = pane()
        pane.onIntent(ChatIntent.StartAgent("claude"))
        pane.onBack()
        assertTrue(pane.state.value.hasOpenConversation)

        pane.onIntent(ChatIntent.ReturnToConversation)

        assertTrue(pane.state.value.inConversation)
        assertEquals(1, link.starts)
    }

    @Test
    fun `selecting a recent session starts that exact agent session`() = runTest {
        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))
        val pane = pane()

        pane.onIntent(ChatIntent.StartAgent("claude", "older-session"))

        assertEquals(listOf<Pair<String, String?>>("claude" to "older-session"), link.startedSessions)
        assertTrue(pane.state.value.inConversation)
    }

    @Test
    fun `a push that beats the snapshot still lists earlier conversations`() = runTest {
        val pane = pane()
        pane.apply(LinkPush.Chat(HostChatState(agents = listOf(ChatAgent("claude", "Claude Code")))))
        assertTrue(pane.state.value.loaded)

        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))

        assertEquals(listOf("old"), pane.state.value.recent.map { it.sessionId })
    }

    @Test
    fun `showing the agent list again refreshes earlier conversations`() = runTest {
        link.project.value = LinkProject(capabilities = setOf(Capability.CHAT))
        val pane = pane()
        link.history = listOf(ChatHistoryEntry("claude", "newer"))

        pane.onShown()

        assertEquals(listOf("newer"), pane.state.value.recent.map { it.sessionId })
    }

    private fun pane(): RealChatPaneComponent {
        val lifecycle = LifecycleRegistry().apply { create() }
        return RealChatPaneComponent(DefaultComponentContext(lifecycle), link, UnconfinedTestDispatcher())
    }

    private class FakeChatLink : PaneLink {
        override val ticket = MutableStateFlow<LinkTicket?>(null)
        override val project = MutableStateFlow(LinkProject())
        var failing = false
        var starts = 0
        var history = listOf(ChatHistoryEntry("claude", "old", "Fix the build"))
        val startedSessions = mutableListOf<Pair<String, String?>>()

        override suspend fun call(call: LinkCall): LinkResult {
            if (failing) throw LinkFault.StreamClosed(beforeLiveSync = true)
            val agents = listOf(ChatAgent("claude", "Claude Code"))
            return when (call) {
                LinkCall.ChatSnapshot -> LinkResult.Chat(HostChatState(agents = agents))
                is LinkCall.ChatStart -> {
                    starts++
                    startedSessions += call.agentId to call.sessionId
                    LinkResult.Chat(
                        HostChatState(agents = agents, agentId = call.agentId, sessionId = call.sessionId ?: "s1"),
                    )
                }
                LinkCall.ChatHistory -> LinkResult.ChatHistory(history)
                else -> error("unexpected $call")
            }
        }
    }
}
