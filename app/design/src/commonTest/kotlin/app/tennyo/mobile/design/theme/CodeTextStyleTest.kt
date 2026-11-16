package app.tennyo.mobile.design.theme

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeTextStyleTest {
    @Test
    fun editorTextIsTwoSpBelowTheDefaultBodyText() {
        val style = editorTextStyle(defaultTextStyle = Typography.bodyLarge)

        assertEquals(Typography.bodyLarge.fontSize.value - 2f, style.fontSize.value)
    }

    @Test
    fun editorLineHeightShrinksInProportionToTheFont() {
        val style = editorTextStyle(defaultTextStyle = Typography.bodyLarge)

        assertEquals(21.sp, style.lineHeight)
    }
}
