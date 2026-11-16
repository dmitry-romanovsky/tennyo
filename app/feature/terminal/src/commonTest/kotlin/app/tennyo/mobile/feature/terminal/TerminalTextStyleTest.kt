package app.tennyo.mobile.feature.terminal

import androidx.compose.ui.unit.sp
import app.tennyo.mobile.design.theme.Typography
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalTextStyleTest {
    @Test
    fun terminalTextIsFourSpBelowTheDefaultBodyText() {
        val style = terminalTextStyle(defaultTextStyle = Typography.bodyLarge)

        assertEquals(Typography.bodyLarge.fontSize.value - 4f, style.fontSize.value)
    }

    @Test
    fun terminalLineHeightShrinksInProportionToTheFont() {
        val style = terminalTextStyle(defaultTextStyle = Typography.bodyLarge)

        assertEquals(18.sp, style.lineHeight)
    }
}
