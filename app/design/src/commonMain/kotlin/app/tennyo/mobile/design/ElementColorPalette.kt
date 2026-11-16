package app.tennyo.mobile.design

import androidx.compose.ui.graphics.Color

/** The three colors an element draws itself with, resolved from the theme for one style and state. */
internal data class ElementColorPalette(
    val container: Color,
    val content: Color,
    val border: Color = Color.Transparent,
)
