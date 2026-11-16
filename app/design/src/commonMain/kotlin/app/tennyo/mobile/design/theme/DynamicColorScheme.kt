package app.tennyo.mobile.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** The system's wallpaper-derived colours, or `null` on a platform without them. */
@Composable
internal expect fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?
