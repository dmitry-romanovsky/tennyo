package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFailureKind
import app.tennyo.contract.ChatHistoryEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A phone's conversation with a VS Code agent-host session, over a host that plays the adapter's part. */
class AhpChatBackendTest {
    private val host = FakeAhpHost()
    private val source = AhpChatSource("/work/project") { host }

    @Test
    fun `a new session opens its default chat, prompts it and answers its confirmation`() = runTest {
        val chat = source.backend(backgroundScope) {}
        assertEquals(listOf("ahp:copilot"), chat.agents().map { it.id })

        val started = chat.start("ahp:copilot", sessionId = null)

        val created = host.requests.single { it.first == "createSession" }.second
        assertEquals("copilot", created.string("provider"))
        assertEquals("[\"file:///work/project\"]", created["workingDirectories"].toString())
        assertEquals(created.string("channel"), started.sessionId)
        assertEquals(null, started.failure)

        assertTrue(chat.prompt("Edit it", emptyList()))
        val turn = host.dispatched.single()
        assertEquals("ahp-chat:/c1" to "chat/turnStarted", turn.first to turn.second.string("type"))

        host.states["ahp-chat:/c1"] = json(
            """{"turns":[],"activeTurn":{"id":"t1","message":{"text":"Edit it"},"responseParts":[
              {"kind":"toolCall","toolCall":{"toolCallId":"tc1","toolName":"edit","displayName":"Edit","status":"pending-confirmation"}}]}}""",
        )
        val asking = chat.snapshot()
        assertTrue(asking.busy)
        val permission = assertIs<ChatEntry.Permission>(asking.entries.last())

        assertTrue(chat.answer(permission.requestId, DENY_OPTION))
        val confirmed = host.dispatched.last().second
        assertEquals("t1", confirmed.string("turnId"))
        assertEquals("false", confirmed.string("approved"))
        assertEquals("denied", confirmed.string("reason"))
        assertFalse(chat.answer("unknown", ALLOW_OPTION))

        chat.close()
        assertTrue(host.following.isEmpty(), "closing unfollows the session and the chat")
    }

    @Test
    fun `the session config becomes the mode and settings, and changing one dispatches session configChanged`() = runTest {
        host.newSession = """{"lifecycle":"creating","defaultChat":"ahp-chat:/c1","config":{"schema":{"type":"object","properties":{
          "mode":{"type":"string","title":"Mode","enum":["interactive","plan"],"enumLabels":["Interactive","Plan"],"default":"interactive","sessionMutable":true},
          "autoApprove":{"type":"string","title":"Approvals","enum":["default","autoApprove"],"sessionMutable":true},
          "sandbox":{"type":"boolean","title":"Sandbox","sessionMutable":true},
          "isolation":{"type":"string","title":"Isolation","enum":["folder"],"sessionMutable":false}}},
          "values":{"mode":"plan","autoApprove":"default","sandbox":false}}}"""
        val chat = source.backend(backgroundScope) {}

        val started = chat.start("ahp:copilot", sessionId = null)
        val session = started.sessionId!!

        assertEquals(listOf("interactive" to "Interactive", "plan" to "Plan"), started.modes.map { it.id to it.name })
        assertEquals("plan", chat.snapshot().currentModeId)
        assertEquals(listOf("autoApprove", "sandbox"), chat.snapshot().configOptions.map { it.id })
        assertTrue(chat.snapshot().configOptions.last().isSwitch)

        assertTrue(chat.setMode("interactive"))
        assertTrue(chat.setConfig("sandbox", "true"))
        val changes = host.dispatched.map { it.second }
        assertEquals(listOf("session/configChanged"), changes.map { it.string("type") }.distinct())
        assertEquals("""{"mode":"interactive"}""", changes[0]["config"].toString())
        assertEquals("""{"sandbox":true}""", changes[1]["config"].toString())

        assertFalse(chat.setMode("bogus"))
        assertFalse(chat.setConfig("isolation", "folder"), "a setting the host fixed at creation stays")
        assertFalse(chat.setConfig("unknown", "x"))
        assertEquals(2, host.dispatched.size)

        host.states[session] = json(host.states.getValue(session).toString().replace("\"mode\":\"plan\"", "\"mode\":\"interactive\""))
        assertEquals("interactive", chat.snapshot().currentModeId)
    }

    @Test
    fun `a provider that needs its account asks to sign in from VS Code, then retries`() = runTest {
        val chat = source.backend(backgroundScope) {}
        host.signedIn = false

        val refused = chat.start("ahp:copilot", sessionId = null)
        assertEquals(ChatFailureKind.SIGN_IN_REQUIRED, refused.failure?.kind)
        assertEquals(listOf("vscode"), refused.authMethods.map { it.id })

        host.signedIn = true
        val retried = chat.authenticate("vscode")
        assertEquals(null, retried.failure)
        assertTrue(retried.sessionId.orEmpty().startsWith("ahp-session:/"))
    }

    @Test
    fun `a session that never opens a chat fails the start instead of leaving it unanswered`() = runTest {
        val chat = source.backend(backgroundScope) {}
        host.newSession = """{"lifecycle":"creating"}"""

        val started = chat.start("ahp:copilot", sessionId = null)

        assertEquals(ChatFailureKind.START_FAILED, started.failure?.kind)
        assertTrue(host.following.isEmpty())
    }

    @Test
    fun `history lists the host's sessions of every provider it offers, newest first`() = runTest {
        val chat = source.backend(backgroundScope) {}
        host.listed = json(
            """{"items":[
              {"resource":"ahp-session:/a","provider":"copilot","title":"Fix tests","modifiedAt":"2026-10-01T10:00:00Z"},
              {"resource":"ahp-session:/b","provider":"copilot","modifiedAt":"2026-10-03T10:00:00Z"},
              {"resource":"ahp-session:/c","provider":"gone","title":"Old","modifiedAt":"2026-10-04T10:00:00Z"}]}""",
        )

        val history = chat.history()

        assertEquals(
            listOf(
                ChatHistoryEntry("ahp:copilot", "ahp-session:/b", null, "2026-10-03T10:00:00Z"),
                ChatHistoryEntry("ahp:copilot", "ahp-session:/a", "Fix tests", "2026-10-01T10:00:00Z"),
            ),
            history,
        )
    }

    @Test
    fun `the router merges every backend's history and leaves out one that fails`() = runTest {
        val signedOut = FakeAhpHost().apply { signedIn = false }
        val router = ChatRouter(
            publish = {},
            backends = listOf(
                { publish -> source.backend(backgroundScope, publish) },
                { publish -> AhpChatSource("/work/project") { signedOut }.backend(backgroundScope, publish) },
            ),
        )
        host.listed = json("""{"items":[{"resource":"ahp-session:/a","provider":"copilot"}]}""")

        assertEquals(listOf("ahp-session:/a"), router.history().map { it.sessionId })
    }

    private class FakeAhpHost : AhpHost {
        val states = mutableMapOf(AHP_ROOT to json("""{"agents":[{"provider":"copilot","displayName":"Copilot"}]}"""))
        val following = mutableMapOf<String, () -> Unit>()
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val dispatched = mutableListOf<Pair<String, JsonObject>>()
        var signedIn = true
        var listed = json("""{"items":[]}""")

        /** VS Code creates a session provisionally: it turns ready only once its first turn starts. */
        var newSession = """{"lifecycle":"creating","defaultChat":"ahp-chat:/c1"}"""

        override suspend fun follow(channel: String, onChange: () -> Unit) {
            following[channel] = onChange
            if (channel.startsWith("ahp-session:")) {
                states[channel] = json(newSession)
            } else {
                states[channel] = json("""{"turns":[]}""")
            }
            onChange()
        }

        override fun unfollow(channel: String) {
            following.remove(channel)
        }

        override fun state(channel: String): JsonObject? = states[channel]

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            if (!signedIn) throw AcpException(-32_007, "Sign in to GitHub.")
            requests += method to params
            return if (method == "listSessions") listed else JsonNull
        }

        override fun dispatch(channel: String, action: JsonObject) {
            dispatched += channel to action
        }
    }

    private companion object {
        fun json(text: String): JsonObject = AcpJson.parseToJsonElement(text).jsonObject
    }
}
