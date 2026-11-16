package app.tennyo.mobile.design.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/** Whether the software keyboard covers part of the window. */
val LocalKeyboardVisible = compositionLocalOf { false }

/** Provides [LocalKeyboardVisible] to [content]. */
@Composable
fun KeyboardVisibilityProvider(content: @Composable () -> Unit) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // The IME inset changes every frame while the keyboard animates; only its open/closed edge should recompose.
    val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    CompositionLocalProvider(LocalKeyboardVisible provides keyboardVisible, content = content)
}
