package app.tennyo.mobile.design

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TennyoPromptTextFieldTest {
    @Test
    fun `external reset synchronizes while focused but ordinary updates do not replace typing`() {
        val state = TennyoPromptTextFieldState("draft")
        state.focused = true

        state.synchronizeExternalValue("server")
        assertEquals("draft", state.value.text)

        state.synchronizeExternalValue("")
        assertEquals(TextFieldValue("", TextRange.Zero), state.value)

        state.focused = false
        state.synchronizeExternalValue("server")
        assertEquals(TextFieldValue("server", TextRange(6)), state.value)
    }

    @Test
    fun `session identity creates independent cursor state and user callback fires only for text changes`() {
        val firstSession = TennyoPromptTextFieldState("first")
        assertEquals("first draft", firstSession.applyUserValue(TextFieldValue("first draft", TextRange(3))))
        assertNull(firstSession.applyUserValue(firstSession.value.copy(selection = TextRange(5))))

        val secondSession = TennyoPromptTextFieldState("second")
        assertEquals(TextFieldValue("second", TextRange(6)), secondSession.value)
        assertEquals(TextRange(5), firstSession.value.selection)
    }
}
