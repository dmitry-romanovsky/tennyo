package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatFieldKind
import app.tennyo.contract.ChatFormAction
import app.tennyo.contract.ChatToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** VS Code agent host (AHP) state, read into the phone's transcript. */
class AhpMappingTest {
    private val chat = json(
        """{"resource":"ahp-chat:/c1","title":"Fix","status":8,"modifiedAt":"2026-09-25","turns":[
          {"id":"t1","message":{"text":"Hi","origin":{"kind":"user"}},"state":"complete","usage":null,"responseParts":[
            {"kind":"reasoning","id":"r1","content":"thinking"},
            {"kind":"markdown","id":"m1","content":"Hello"}]}],
          "activeTurn":{"id":"t2","startedAt":"x","usage":null,"message":{"text":"Edit it","origin":{"kind":"user"}},"responseParts":[
            {"kind":"toolCall","toolCall":{"toolCallId":"tc1","toolName":"editFile","displayName":"Edit a.txt","status":"pending-confirmation",
              "confirmationTitle":{"markdown":"Allow the edit?"}}},
            {"kind":"toolCall","toolCall":{"toolCallId":"tc0","toolName":"readFile","displayName":"Read b.txt","status":"completed",
              "success":true,"content":[{"type":"text","text":"contents"}]}},
            {"kind":"inputRequest","request":{"id":"in1","message":"Which?","questions":[
              {"id":"name","kind":"text","message":"Name","required":true},
              {"id":"pick","kind":"single-select","message":"Pick","options":[{"id":"a","label":"A"}]}]}}]}}""",
    )

    @Test
    fun `turns become prompts, thoughts, messages, tools, permissions and forms`() {
        val entries = ahpEntries(chat)

        assertEquals(ChatEntry.Prompt("Hi"), entries[0])
        assertEquals(ChatEntry.Thought("thinking"), entries[1])
        assertEquals(ChatEntry.Message("Hello"), entries[2])
        assertEquals(ChatEntry.Prompt("Edit it"), entries[3])
        val permission = assertIs<ChatEntry.Permission>(entries[5])
        assertEquals("tc1", permission.requestId)
        assertEquals("Allow the edit?", permission.title)
        val read = entries.filterIsInstance<ChatEntry.Tool>().single { it.toolCallId == "tc0" }
        assertEquals(ChatToolStatus.DONE, read.status)
        assertEquals("contents", read.output)
        val form = assertIs<ChatEntry.Form>(entries.last())
        assertEquals(listOf(ChatFieldKind.TEXT, ChatFieldKind.CHOICE), form.fields.map { it.kind })
        assertTrue(ahpBusy(chat))
        assertEquals("t2", ahpTurnOf(chat, "tc1"))
    }

    @Test
    fun `an answered request and a settled tool are no longer asking`() {
        val settled = json(
            """{"turns":[{"id":"t1","message":{"text":"x","origin":{"kind":"user"}},"state":"complete","responseParts":[
              {"kind":"toolCall","toolCall":{"toolCallId":"tc1","toolName":"t","displayName":"T","status":"cancelled"}},
              {"kind":"inputRequest","request":{"id":"in1","questions":[]},"response":"decline"}]}]}""",
        )

        val entries = ahpEntries(settled)

        assertTrue(entries.none { it is ChatEntry.Permission })
        assertEquals(ChatToolStatus.FAILED, entries.filterIsInstance<ChatEntry.Tool>().single().status)
        assertEquals(ChatFormAction.DECLINE, entries.filterIsInstance<ChatEntry.Form>().single().answer)
        assertFalse(ahpBusy(settled))
    }

    @Test
    fun `agents, sessions and answers keep to the host's shapes`() {
        val root = json("""{"agents":[{"provider":"copilot","displayName":"Copilot","description":"","models":[]}]}""")
        assertEquals(listOf("ahp:copilot" to "Copilot"), ahpAgents(root).map { it.id to it.name })

        val listed = json(
            """{"items":[{"resource":"ahp-session:/s1","provider":"copilot","title":"Old","status":1,"createdAt":"a","modifiedAt":"b"},
              {"resource":"ahp-session:/s2","provider":"claude","title":"Other","status":1,"createdAt":"a","modifiedAt":"c"}]}""",
        )
        assertEquals(listOf("ahp-session:/s1"), ahpSessions(listed, "copilot").map { it.sessionId })

        val form = assertIs<ChatEntry.Form>(ahpEntries(chat).last())
        assertEquals(
            """{"name":{"state":"submitted","value":{"kind":"text","value":"Ada"}},""" +
                """"pick":{"state":"submitted","value":{"kind":"selected","value":"a"}}}""",
            ahpAnswers(form.fields, mapOf("name" to "Ada", "pick" to "a")).toString(),
        )
    }

    private fun json(text: String): JsonObject = AcpJson.parseToJsonElement(text).jsonObject
}
