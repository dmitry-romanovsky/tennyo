package app.tennyo.mobile.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable

private val TennyoShapes = Shapes(
    extraSmall = Corners.xs,
    small = Corners.sm,
    medium = Corners.md,
    large = Corners.lg,
    extraLarge = Corners.xl,
)

/**
 * The app theme. [colorScheme] defaults to the fixed Tennyo palette so previews render the same everywhere; the app
 * passes the user's choice, and [AppColorScheme.Dynamic] falls back to that palette where the platform has none.
 */
@Composable
fun TennyoTheme(
    themeMode: AppThemeMode = AppThemeMode.Auto,
    colorScheme: AppColorScheme = AppColorScheme.TennyoTheme,
    content: @Composable () -> Unit,
) {
    val theme = themeMode.resolveAppTheme(systemDarkTheme = isSystemInDarkTheme())
    val dynamicColors = if (colorScheme == AppColorScheme.Dynamic) dynamicColorScheme(theme.darkTheme) else null
    MaterialTheme(
        colorScheme = dynamicColors ?: tennyoColorScheme(theme),
        typography = Typography,
        shapes = TennyoShapes,
        content = content,
    )
}
