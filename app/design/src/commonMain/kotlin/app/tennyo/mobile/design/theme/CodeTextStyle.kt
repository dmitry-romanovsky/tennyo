package app.tennyo.mobile.design.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

@Composable
fun editorTextStyle(color: Color = MaterialTheme.colorScheme.onSurface): TextStyle =
    editorTextStyle(defaultTextStyle = MaterialTheme.typography.bodyLarge).copy(color = color)

/** Code in the editor, diffs, and consoles sits 2sp below the app's default body text. */
fun editorTextStyle(defaultTextStyle: TextStyle): TextStyle =
    defaultTextStyle.withFontSizeReducedBy(EDITOR_FONT_SIZE_REDUCTION_SP)

private const val EDITOR_FONT_SIZE_REDUCTION_SP = 2f
