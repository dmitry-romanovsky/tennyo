package app.tennyo.mobile.design.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class TextStyleReductionTest {
    @Test
    fun unspecifiedLineHeightStaysUnspecified() {
        val reduced = TextStyle(fontSize = 16.sp).withFontSizeReducedBy(2f)

        assertEquals(14.sp, reduced.fontSize)
        assertEquals(TextUnit.Unspecified, reduced.lineHeight)
    }
}
