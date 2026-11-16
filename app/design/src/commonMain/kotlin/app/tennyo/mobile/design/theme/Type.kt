package app.tennyo.mobile.design.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import app.tennyo.mobile.design.generated.resources.Res
import app.tennyo.mobile.design.generated.resources.dejavu_sans_mono_bold
import app.tennyo.mobile.design.generated.resources.dejavu_sans_mono_regular
import org.jetbrains.compose.resources.Font

val Typography = Typography()

/** Shrinks the font by [reductionSp] and scales the line height with it, so line spacing keeps its proportion. */
fun TextStyle.withFontSizeReducedBy(reductionSp: Float): TextStyle {
    val reducedFontSizeSp = fontSize.value - reductionSp
    return copy(
        fontSize = reducedFontSizeSp.sp,
        lineHeight = if (lineHeight.isSpecified) lineHeight * (reducedFontSizeSp / fontSize.value) else lineHeight,
    )
}

/** The bundled DejaVu Sans Mono, used for code, terminal output and key caps. Italic is synthesized. */
@Composable
fun monospaceFontFamily(): FontFamily =
    FontFamily(
        Font(Res.font.dejavu_sans_mono_regular, weight = FontWeight.Normal),
        Font(Res.font.dejavu_sans_mono_bold, weight = FontWeight.Bold),
    )
