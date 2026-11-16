package app.tennyo.host.engine.session

import app.tennyo.contract.ChatAgent
import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatHistoryEntry
import app.tennyo.contract.ChatState
import app.tennyo.contract.GitStatusResponse
import app.tennyo.contract.LinkCall
import app.tennyo.contract.LinkFrame
import app.tennyo.contract.LinkResult
import app.tennyo.host.engine.agents.AgentLauncher
import app.tennyo.host.engine.agents.ChatBackend
import app.tennyo.host.engine.agents.ChatSource
import app.tennyo.host.engine.agents.IdeAgents
import app.tennyo.host.engine.panes.IdeGitPort
import app.tennyo.host.engine.panes.UnavailableGitPort
import app.tennyo.host.engine.platform.hostWallClockMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An agent that never answers holds its own calls, never the panes': Git still answers while chat calls wait. A
 * call that gives up on its own still owes the device an answer.
 */
class AgentCallIsolationTest {
    private val clock = AtomicLong(hostWallClockMillis())
    private val scope = CoroutineScope(SupervisorJob())
    private val git = object : IdeGitPort by UnavailableGitPort {
        override suspend fun status(selectedRepositoryId: String?, revision: Long) =
            GitStatusResponse(emptyList(), null, revision)
    }
    private val agents = IdeAgents(
        launcher = AgentLauncher { _, _, _, _ -> error("No ACP agent runs here.") },
        servers = { emptyList() },
        sources = listOf(StuckAgentSource),
    )
    private val host = SessionHost(testPanes(git = git, agents = agents), FakeHostPlatform(), scope, clock::load)
    private val device = TestDevice()

    @AfterTest
    fun close() {
        host.close()
        scope.cancel()
    }

    @Test
    fun `chat calls stuck on an agent leave Git answering`() = runTest {
        val ticket = host.startSession().also {
            assertNotNull(host.session(it.sessionId)).auth.approveDevice(device.publicKey, "Pixel", "request-1")
        }
        val channel = TestChannel(host, ticket, device, clock::load).open(null)

        repeat(STUCK_CALLS) { channel.call(LinkCall.ChatListSessions(StuckAgentSource.AGENT.id)) }
        val status = channel.call(LinkCall.GitStatus(null))

        assertIs<LinkResult.GitStatus>(assertIs<LinkFrame.Reply>(awaitReply(channel, status)).result)
    }

    @Test
    fun `a call whose own timeout fires is answered with a fault`() = runTest {
        val ticket = host.startSession().also {
            assertNotNull(host.session(it.sessionId)).auth.approveDevice(device.publicKey, "Pixel", "request-1")
        }
        val channel = TestChannel(host, ticket, device, clock::load).open(null)

        val call = channel.call(LinkCall.ChatStart(StuckAgentSource.AGENT.id, sessionId = null))

        assertIs<LinkFrame.Fault>(awaitReply(channel, call))
    }

    private suspend fun awaitReply(channel: TestChannel, call: LinkFrame.Call): LinkFrame = withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            var reply: LinkFrame? = null
            while (reply == null) {
                delay(10.milliseconds)
                reply = channel.replyTo(call)
            }
            reply
        }
    }

    private object StuckAgentSource : ChatSource {
        val AGENT = ChatAgent("stuck", "Stuck")

        override fun agents() = listOf(AGENT)

        override fun backend(scope: CoroutineScope, publish: (ChatState) -> Unit): ChatBackend = object : ChatBackend {
            override fun agents() = listOf(AGENT)

            override fun snapshot() = ChatState(agents = listOf(AGENT))

            override suspend fun start(agentId: String, sessionId: String?): ChatState =
                withTimeout(1.milliseconds) { awaitCancellation() }

            override suspend fun listSessions(agentId: String): ChatState = awaitCancellation()

            override suspend fun history(): List<ChatHistoryEntry> = awaitCancellation()

            override suspend fun authenticate(methodId: String): ChatState = awaitCancellation()

            override suspend fun setMode(modeId: String): Boolean = awaitCancellation()

            override suspend fun setConfig(configId: String, value: String): Boolean = awaitCancellation()

            override fun prompt(text: String, attachments: List<ChatAttachment>) = false

            override fun cancel() = false

            override fun answer(requestId: String, optionId: String?) = false

            override fun answerForm(requestId: String, action: ChatFormAction, values: Map<String, String>) = false

            override fun close() = Unit
        }
    }

    private companion object {
        const val STUCK_CALLS = 8
    }
}
