package app.tennyo.mobile.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

private data class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val inversePrimary: Color,
)

private data class NeutralColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerLowest: Color,
)

internal fun tennyoColorScheme(theme: AppNeutralPalette): ColorScheme {
    val accent = if (theme.darkTheme) DarkAccentColors else LightAccentColors
    val fixed = LightAccentColors
    val neutral = theme.colors()

    return ColorScheme(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        inversePrimary = accent.inversePrimary,
        secondary = accent.secondary,
        onSecondary = accent.onSecondary,
        secondaryContainer = accent.secondaryContainer,
        onSecondaryContainer = accent.onSecondaryContainer,
        tertiary = accent.tertiary,
        onTertiary = accent.onTertiary,
        tertiaryContainer = accent.tertiaryContainer,
        onTertiaryContainer = accent.onTertiaryContainer,
        background = neutral.background,
        onBackground = neutral.onBackground,
        surface = neutral.surface,
        onSurface = neutral.onSurface,
        surfaceVariant = neutral.surfaceVariant,
        onSurfaceVariant = neutral.onSurfaceVariant,
        surfaceTint = accent.primary,
        inverseSurface = neutral.inverseSurface,
        inverseOnSurface = neutral.inverseOnSurface,
        error = neutral.error,
        onError = neutral.onError,
        errorContainer = neutral.errorContainer,
        onErrorContainer = neutral.onErrorContainer,
        outline = neutral.outline,
        outlineVariant = neutral.outlineVariant,
        scrim = neutral.scrim,
        surfaceBright = neutral.surfaceBright,
        surfaceDim = neutral.surfaceDim,
        surfaceContainer = neutral.surfaceContainer,
        surfaceContainerHigh = neutral.surfaceContainerHigh,
        surfaceContainerHighest = neutral.surfaceContainerHighest,
        surfaceContainerLow = neutral.surfaceContainerLow,
        surfaceContainerLowest = neutral.surfaceContainerLowest,
        primaryFixed = fixed.primaryContainer,
        primaryFixedDim = fixed.primary,
        onPrimaryFixed = fixed.onPrimaryContainer,
        onPrimaryFixedVariant = fixed.primary,
        secondaryFixed = fixed.secondaryContainer,
        secondaryFixedDim = fixed.secondary,
        onSecondaryFixed = fixed.onSecondaryContainer,
        onSecondaryFixedVariant = fixed.secondary,
        tertiaryFixed = fixed.tertiaryContainer,
        tertiaryFixedDim = fixed.tertiary,
        onTertiaryFixed = fixed.onTertiaryContainer,
        onTertiaryFixedVariant = fixed.tertiary,
    )
}

private fun AppNeutralPalette.colors(): NeutralColors =
    when (this) {
        AppNeutralPalette.IslandLight -> LightNeutralColors
        AppNeutralPalette.IslandDark -> DarkNeutralColors
    }

private val LightNeutralColors = NeutralColors(
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF262331),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF262331),
    surfaceVariant = Color(0xFFEDEBF3),
    onSurfaceVariant = Color(0xFF6A6579),
    inverseSurface = Color(0xFF262331),
    inverseOnSurface = Color(0xFFFFFFFF),
    error = Color(0xFFCC3645),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFF2F3),
    onErrorContainer = Color(0xFFA5202D),
    outline = Color(0xFFA9A4BA),
    outlineVariant = Color(0xFFE1DEEA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE9E7F0),
    surfaceContainer = Color(0xFFF2F0F7),
    surfaceContainerHigh = Color(0xFFEDEBF3),
    surfaceContainerHighest = Color(0xFFE1DEEA),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

private val DarkNeutralColors = NeutralColors(
    background = Color(0xFF141220),
    onBackground = Color(0xFFE2DFEA),
    surface = Color(0xFF232130),
    onSurface = Color(0xFFE2DFEA),
    surfaceVariant = Color(0xFF3D3A4C),
    onSurfaceVariant = Color(0xFFA19CB0),
    inverseSurface = Color(0xFFE2DFEA),
    inverseOnSurface = Color(0xFF232130),
    error = Color(0xFFE55765),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A2326),
    onErrorContainer = Color(0xFFF2A3AB),
    outline = Color(0xFF726D82),
    outlineVariant = Color(0xFF332F40),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF45415A),
    surfaceDim = Color(0xFF0F0E19),
    surfaceContainer = Color(0xFF2B2839),
    surfaceContainerHigh = Color(0xFF332F42),
    surfaceContainerHighest = Color(0xFF3D3A4C),
    surfaceContainerLow = Color(0xFF232130),
    surfaceContainerLowest = Color(0xFF1A1823),
)

/**
 * Tennyo's accent. Green leads on `primary`, violet answers on `secondary`, and green stays on `tertiary` as
 * well — diff-added rows and connected chips read as green whatever the accent source, so their meaning does not
 * move when the device's dynamic colors take over.
 */
private val LightAccentColors = AccentColors(
    primary = Color(0xFF1B7A55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCF3E7),
    onPrimaryContainer = Color(0xFF0D4A32),
    secondary = Color(0xFF6D4DC4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAE3FB),
    onSecondaryContainer = Color(0xFF34215F),
    tertiary = Color(0xFF1F8039),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8F8EC),
    onTertiaryContainer = Color(0xFF146B2C),
    inversePrimary = Color(0xFF7C5CE0),
)

private val DarkAccentColors = AccentColors(
    primary = Color(0xFF3FBF87),
    onPrimary = Color(0xFF06301F),
    primaryContainer = Color(0xFF1E4C3A),
    onPrimaryContainer = Color(0xFFC9F0DC),
    secondary = Color(0xFFB79CFF),
    onSecondary = Color(0xFF2A1560),
    secondaryContainer = Color(0xFF3E2E63),
    onSecondaryContainer = Color(0xFFE6DBFF),
    tertiary = Color(0xFF5FAD65),
    onTertiary = Color(0xFF0B2410),
    tertiaryContainer = Color(0xFF1F3324),
    onTertiaryContainer = Color(0xFFA7DBAB),
    inversePrimary = Color(0xFF1B7A55),
)

/** The amber pair behind [app.tennyo.mobile.design.TennyoStatusTone.Warning]; Material has no warning role. */
internal val LightWarningColors = ElementWarningColors(
    container = Color(0xFFFBEECF),
    content = Color(0xFF6A4304),
)

internal val DarkWarningColors = ElementWarningColors(
    container = Color(0xFF3B2B0D),
    content = Color(0xFFE6CE9D),
)

internal data class ElementWarningColors(
    val container: Color,
    val content: Color,
)
