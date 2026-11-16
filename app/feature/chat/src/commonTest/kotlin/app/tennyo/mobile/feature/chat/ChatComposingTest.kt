package app.tennyo.mobile.feature.chat

import app.tennyo.contract.ChatAttachment
import app.tennyo.contract.ChatCommand
import app.tennyo.contract.ChatEntry
import app.tennyo.contract.ChatField
import app.tennyo.contract.ChatFieldKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.tennyo.contract.ChatState as HostChatState

class ChatComposingTest {
    @Test
    fun `mentions name project files and trailing punctuation aside`() {
        assertEquals(
            listOf("src/app.py", "README.md"),
            mentionedFiles("Look at @src/app.py, and @README.md. Mail me@example.com @src/app.py"),
        )
    }

    @Test
    fun `slash suggestions follow what is typed until the command is complete`() {
        val commands = listOf(ChatCommand("review", "Review"), ChatCommand("plan", "Plan"))

        assertEquals(listOf("review"), commandSuggestions("/re", commands).map { it.name })
        assertEquals(2, commandSuggestions("/", commands).size)
        assertTrue(commandSuggestions("/review now", commands).isEmpty())
        assertTrue(commandSuggestions("hello", commands).isEmpty())
    }

    @Test
    fun `a sent draft clears its text and pictures`() {
        val picked = reduceChat(ChatState(composerText = "hi"), ChatMsg.ImageAdded(ChatAttachment.Image("image/jpeg", "AA")))

        val sent = reduceChat(picked, ChatMsg.DraftSent)

        assertEquals("", sent.composerText)
        assertTrue(sent.pendingImages.isEmpty())
    }

    @Test
    fun `a prompt that failed to send returns to an empty composer only`() {
        val image = ChatAttachment.Image("image/jpeg", "AA")

        val returned = reduceChat(ChatState(), ChatMsg.DraftReturned("hi", listOf(image)))
        val retyped = reduceChat(ChatState(composerText = "next"), ChatMsg.DraftReturned("hi", listOf(image)))

        assertEquals("hi", returned.composerText)
        assertEquals(listOf(image), returned.pendingImages)
        assertEquals(1, returned.composerRevision)
        assertEquals("next", retyped.composerText)
        assertTrue(retyped.pendingImages.isEmpty())
    }

    @Test
    fun `form drafts live until the host shows the form answered`() {
        val form = ChatEntry.Form("f1", "Details?", listOf(ChatField("name", "Name", ChatFieldKind.TEXT)))
        val open = reduceChat(ChatState(), ChatMsg.HostChanged(HostChatState(entries = listOf(form))))

        val typed = reduceChat(open, ChatMsg.FormValueChanged("f1", "name", "Ada"))
        assertEquals(mapOf("name" to "Ada"), typed.formDrafts["f1"])

        val answered = reduceChat(typed, ChatMsg.HostChanged(HostChatState(entries = listOf(form.copy(answer = app.tennyo.contract.ChatFormAction.ACCEPT)))))
        assertTrue(answered.formDrafts.isEmpty())
    }
}
