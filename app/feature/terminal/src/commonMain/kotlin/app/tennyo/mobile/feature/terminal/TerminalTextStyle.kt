package app.tennyo.mobile.feature.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.tennyo.mobile.design.theme.monospaceFontFamily
import app.tennyo.mobile.design.theme.withFontSizeReducedBy

@Composable
internal fun terminalTextStyle(color: Color = TERMINAL_DEFAULT_FOREGROUND): TextStyle =
    terminalTextStyle(defaultTextStyle = MaterialTheme.typography.bodyLarge).copy(
        fontFamily = monospaceFontFamily(),
        color = color,
    )

/** Terminal output sits 4sp below the app's default body text so more columns fit on a phone. */
internal fun terminalTextStyle(defaultTextStyle: TextStyle): TextStyle =
    defaultTextStyle.withFontSizeReducedBy(TERMINAL_FONT_SIZE_REDUCTION_SP)

internal val TERMINAL_DEFAULT_FOREGROUND = Color(0xFFE5E5E5)
internal val TERMINAL_BACKGROUND = Color.Black
private const val TERMINAL_FONT_SIZE_REDUCTION_SP = 4f
