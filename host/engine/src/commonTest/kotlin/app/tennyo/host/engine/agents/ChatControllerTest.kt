package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFailureKind
import app.tennyo.contract.ChatFieldKind
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatPlanStatus
import app.tennyo.contract.ChatState
import app.tennyo.contract.ChatToolStatus
import app.tennyo.host.engine.panes.FakeProjectFiles
import app.tennyo.host.engine.rpc.CallFault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A scripted ACP agent driven through the controller, the way a phone drives it over the link. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private val server = AgentServer("test:scripted", "Scripted", "scripted-agent")
    private val files = FakeProjectFiles().directory("/work/project").file("/work/project/src/app.py", "print(1)")
    private val pushed = mutableListOf<ChatState>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cancelAgents() = scopes.forEach { it.cancel() }

    @Test
    fun `scripted agent streams a turn and a permission`() = runTest {
        val agent = ScriptedAgent()
        val chat = controller(agent)

        val started = chat.start(server.id, sessionId = null)
        assertEquals(server.id, started.agentId)
        assertEquals("s1", started.sessionId)
        assertEquals(null, started.failure)

        assertTrue(chat.prompt("Fix the typo", listOf(ChatAttachment.FileLink("src/app.py"))))
        assertFalse(chat.prompt("A second turn while one runs", emptyList()))
        testScheduler.advanceUntilIdle()

        val sent = agent.lastPrompt.getValue("prompt").jsonArray
        assertEquals("file:///work/project/src/app.py", sent[1].jsonObject.text("uri"))
        val asking = chat.snapshot()
        val permission = assertIs<ChatEntry.Permission>(asking.entries.last())
        assertEquals(listOf("allow", "reject"), permission.options.map { it.optionId })
        assertTrue(asking.busy)

        assertTrue(chat.answer(permission.requestId, "allow"))
        assertFalse(chat.answer(permission.requestId, "allow"), "a permission is answered once")
        testScheduler.advanceUntilIdle()

        val done = chat.snapshot()
        assertFalse(done.busy)
        assertEquals("allow", agent.chosenOption)
        assertEquals(ChatEntry.Prompt("Fix the typo", listOf("src/app.py")), done.entries.first())
        assertEquals(ChatEntry.Message("Hello"), done.entries[1])
        val tool = done.entries.filterIsInstance<ChatEntry.Tool>().single()
        assertEquals(ChatToolStatus.DONE, tool.status)
        assertEquals("a.txt", tool.diff?.path)
        assertEquals(done, pushed.last(), "the final state is pushed")
    }

    @Test
    fun `a file outside the project is never attached`() = runTest {
        val chat = controller(ScriptedAgent())
        chat.start(server.id, sessionId = null)

        listOf("../../etc/passwd", "/etc/passwd", "src").forEach { path ->
            assertFailsWith<CallFault>(path) { chat.prompt("read this", listOf(ChatAttachment.FileLink(path))) }
        }
    }

    @Test
    fun `earlier sessions are listed and resumed with their history`() = runTest {
        val chat = controller(ScriptedAgent())

        val listed = chat.listSessions(server.id)
        assertEquals(listOf("old"), listed.sessions.map { it.sessionId })
        assertTrue(listed.canLoadSessions)

        val resumed = chat.start(server.id, sessionId = "old")
        testScheduler.advanceUntilIdle()

        assertEquals("old", resumed.sessionId)
        assertEquals(listOf(ChatEntry.Prompt("hi"), ChatEntry.Message("hello")), chat.snapshot().entries)
    }

    @Test
    fun `sign-in is offered, then the waiting session opens`() = runTest {
        val chat = controller(ScriptedAgent(signInRequired = true))

        val waiting = chat.start(server.id, sessionId = null)
        assertEquals(ChatFailureKind.SIGN_IN_REQUIRED, waiting.failure?.kind)
        assertEquals(listOf(null, "scripted-agent login"), waiting.authMethods.map { it.terminalCommand })

        val signedIn = chat.authenticate("browser")

        assertEquals("s1", signedIn.sessionId)
        assertTrue(signedIn.authMethods.isEmpty())
        assertEquals(null, signedIn.failure)
    }

    @Test
    fun `modes and settings follow the agent`() = runTest {
        val chat = controller(ScriptedAgent())
        val started = chat.start(server.id, sessionId = null)
        assertEquals("ask", started.currentModeId)
        assertEquals(listOf("fast", "deep"), started.configOptions.single().choices.map { it.value })

        assertTrue(chat.setMode("code"))
        assertTrue(chat.setConfig("model", "deep"))

        val changed = chat.snapshot()
        assertEquals("code", changed.currentModeId)
        assertEquals("deep", changed.configOptions.single().value)
    }

    @Test
    fun `a form, a plan and commands reach the phone`() = runTest {
        val agent = ScriptedAgent()
        val chat = controller(agent)
        chat.start(server.id, sessionId = null)

        chat.prompt("form", emptyList())
        testScheduler.advanceUntilIdle()
        val asked = chat.snapshot()
        val form = assertIs<ChatEntry.Form>(asked.entries.last())
        assertEquals(listOf(ChatFieldKind.TEXT, ChatFieldKind.INTEGER), form.fields.map { it.kind })
        assertEquals(ChatPlanStatus.IN_PROGRESS, asked.entries.filterIsInstance<ChatEntry.Plan>().single().steps.single().status)
        assertEquals(listOf("review"), asked.commands.map { it.name })

        assertTrue(chat.answerForm(form.requestId, ChatFormAction.ACCEPT, mapOf("name" to "Ada", "count" to "3")))
        testScheduler.advanceUntilIdle()

        assertEquals("""{"action":"accept","content":{"name":"Ada","count":3}}""", agent.formAnswer.toString())
        assertEquals(ChatFormAction.ACCEPT, chat.snapshot().entries.filterIsInstance<ChatEntry.Form>().single().answer)
    }

    @Test
    fun `the host serves the agent no file reads`() = runTest {
        val agent = ScriptedAgent()
        val chat = controller(agent)
        chat.start(server.id, sessionId = null)

        agent.request(id = 7, method = "fs/read_text_file", params = """{"sessionId":"s1","path":"/etc/passwd"}""")
        testScheduler.advanceUntilIdle()

        assertTrue(agent.replies.getValue(7).contains("-32601"), agent.replies.getValue(7))
    }

    @Test
    fun `an agent the configuration does not name is refused`() = runTest {
        val chat = controller(ScriptedAgent())

        val state = chat.start("vscode:rm -rf", sessionId = null)

        assertEquals(ChatFailureKind.START_FAILED, state.failure?.kind)
    }

    @Test
    fun `the agent exiting ends the turn with a typed failure`() = runTest {
        val agent = ScriptedAgent()
        val chat = controller(agent)
        chat.start(server.id, sessionId = null)

        agent.exit()
        testScheduler.advanceUntilIdle()

        assertEquals(ChatFailureKind.AGENT_EXITED, chat.snapshot().failure?.kind)
        assertFalse(chat.prompt("anyone there?", emptyList()))
    }

    @Test
    fun `an agent that never answers fails the start instead of keeping the phone waiting`() = runTest {
        val chat = controller(ScriptedAgent(silent = true))

        val state = chat.start(server.id, sessionId = null)

        assertEquals(ChatFailureKind.START_FAILED, state.failure?.kind)
    }

    private fun TestScope.controller(agent: ScriptedAgent) = ChatController(
        agents = IdeAgents(launcher = agent, servers = { listOf(server) }),
        files = files,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also(scopes::add),
        publish = { pushed += it },
    )
}

/** Answers like an ACP agent: capabilities, sessions to list and load, sign-in, modes, a streamed turn and a form. */
private class ScriptedAgent(
    private val signInRequired: Boolean = false,
    /** Never writes a line, as an agent stuck before its first answer. */
    private val silent: Boolean = false,
) : AgentLauncher, AgentProcess {
    var chosenOption: String? = null
    var formAnswer: JsonObject? = null
    var lastPrompt: JsonObject = JsonObject(emptyMap())
    val replies = mutableMapOf<Int, String>()
    private var authenticated = false
    private lateinit var onLine: (String) -> Unit
    private lateinit var onExit: () -> Unit
    private var promptId: String? = null

    override fun launch(
        server: AgentServer,
        workingDirectory: String,
        onLine: (String) -> Unit,
        onExit: () -> Unit,
    ): AgentProcess {
        this.onLine = onLine
        this.onExit = onExit
        return this
    }

    fun exit() = onExit()

    fun request(id: Int, method: String, params: String) =
        onLine("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

    override fun send(line: String) {
        if (silent) return
        val message = AcpJson.parseToJsonElement(line).jsonObject
        val id = message["id"]?.toString()
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        when (message.text("method")) {
            // ACP's Implementation requires a version; agents that validate their input (Qwen Code) refuse without it.
            "initialize" -> if ((params["clientInfo"] as? JsonObject)?.get("version") == null) {
                onLine("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32603,"message":"Internal error"}}""")
            } else {
                reply(id, INITIALIZE)
            }
            "authenticate" -> { authenticated = true; reply(id, "{}") }
            "session/new" -> if (signInRequired && !authenticated) {
                onLine("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"Authentication required"}}""")
            } else {
                reply(id, SESSION)
            }
            "session/list" -> reply(id, """{"sessions":[{"sessionId":"old","cwd":"/work/project","title":"Earlier","updatedAt":"2026-01-01"}]}""")
            "session/load" -> {
                update("""{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"hi"}}""")
                update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hello"}}""")
                reply(id, "{}")
            }
            "session/set_mode" -> reply(id, "{}")
            "session/set_config_option" -> reply(id, """{"configOptions":[${model("deep")}]}""")
            "session/prompt" -> prompt(id, params)
            null -> answered(message, line)
        }
    }

    private fun prompt(id: String?, params: JsonObject) {
        promptId = id
        lastPrompt = params
        val text = params.getValue("prompt").jsonArray.first().jsonObject.text("text")
        if (text == "form") {
            update("""{"sessionUpdate":"plan","entries":[{"content":"Ask","priority":"high","status":"in_progress"}]}""")
            update("""{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"review","description":"Review"}]}""")
            onLine(
                """{"jsonrpc":"2.0","id":97,"method":"elicitation/create","params":{"sessionId":"s1","mode":"form",""" +
                    """"message":"Details?","requestedSchema":{"type":"object","properties":{""" +
                    """"name":{"type":"string","title":"Name"},"count":{"type":"integer"}},"required":["name"]}}}""",
            )
            return
        }
        update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}""")
        update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}""")
        update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Edit a.txt","kind":"edit","status":"pending"}""")
        onLine(
            """{"jsonrpc":"2.0","id":99,"method":"session/request_permission","params":{"sessionId":"s1",""" +
                """"toolCall":{"toolCallId":"t1","title":"Edit a.txt"},"options":[""" +
                """{"optionId":"allow","name":"Allow","kind":"allow_once"},""" +
                """{"optionId":"reject","name":"Reject","kind":"reject_once"}]}}""",
        )
    }

    /** A reply the client sent to one of the agent's own requests. */
    private fun answered(message: JsonObject, line: String) {
        when (message["id"]?.toString()) {
            "99" -> {
                chosenOption = (message["result"] as JsonObject)["outcome"]!!.jsonObject.text("optionId")
                update(
                    """{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed",""" +
                        """"content":[{"type":"diff","path":"a.txt","oldText":"teh","newText":"the"}]}""",
                )
                reply(promptId, """{"stopReason":"end_turn"}""")
            }

            "97" -> {
                formAnswer = message["result"] as JsonObject
                reply(promptId, """{"stopReason":"end_turn"}""")
            }

            else -> message["id"]?.toString()?.toIntOrNull()?.let { replies[it] = line }
        }
    }

    override fun stop() = Unit

    private fun reply(id: String?, result: String) = onLine("""{"jsonrpc":"2.0","id":$id,"result":$result}""")

    private fun update(change: String) =
        onLine("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$change}}""")

    private companion object {
        val INITIALIZE = """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,""" +
            """"promptCapabilities":{"image":true},"sessionCapabilities":{"list":{}}},""" +
            """"authMethods":[{"id":"browser","name":"Sign in"},""" +
            """{"type":"terminal","id":"cli","name":"CLI login","args":["login"]}]}"""

        val SESSION = """{"sessionId":"s1","modes":{"currentModeId":"ask","availableModes":""" +
            """[{"id":"ask","name":"Ask"},{"id":"code","name":"Code"}]},"configOptions":[${model("fast")}]}"""

        fun model(current: String) = """{"id":"model","name":"Model","type":"select","currentValue":"$current",""" +
            """"options":[{"value":"fast","name":"Fast"},{"value":"deep","name":"Deep"}]}"""
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
