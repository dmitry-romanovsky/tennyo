package app.tennyo.mobile.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppColorSchemeTest {
    @Test
    fun themeModesResolveToNeutralPalettes() {
        assertEquals(
            AppNeutralPalette.IslandLight,
            AppThemeMode.Auto.resolveAppTheme(false),
        )
        assertEquals(
            AppNeutralPalette.IslandDark,
            AppThemeMode.Auto.resolveAppTheme(true),
        )
        assertEquals(
            AppNeutralPalette.IslandLight,
            AppThemeMode.Light.resolveAppTheme(true),
        )
        assertEquals(
            AppNeutralPalette.IslandDark,
            AppThemeMode.Dark.resolveAppTheme(false),
        )
    }

    @Test
    fun theTennyoSchemeFillsEveryMaterialColorRole() {
        AppNeutralPalette.entries.forEach { palette ->
            val colors = tennyoColorScheme(palette).allRoles()

            assertTrue(
                colors.all { it != Color.Unspecified },
                "Expected $palette to fill every Material color role",
            )
        }
    }

    @Test
    fun lightAndDarkThemesUseTennyoNeutralPalettes() {
        val lightColors = tennyoColorScheme(AppNeutralPalette.IslandLight)
        val darkColors = tennyoColorScheme(AppNeutralPalette.IslandDark)

        assertEquals(Color(0xFFF8F7FC), lightColors.background)
        assertEquals(Color(0xFFFFFFFF), lightColors.surface)
        assertEquals(Color(0xFF262331), lightColors.onSurface)
        assertEquals(Color(0xFFF2F0F7), lightColors.surfaceContainer)
        assertEquals(Color(0xFFA9A4BA), lightColors.outline)
        assertEquals(Color(0xFFCC3645), lightColors.error)

        assertEquals(Color(0xFF141220), darkColors.background)
        assertEquals(Color(0xFF232130), darkColors.surface)
        assertEquals(Color(0xFFE2DFEA), darkColors.onSurface)
        assertEquals(Color(0xFF2B2839), darkColors.surfaceContainer)
        assertEquals(Color(0xFF726D82), darkColors.outline)
        assertEquals(Color(0xFFE55765), darkColors.error)
    }

    /** Tennyo's own accent roles: green leads, violet answers, and green stays on tertiary. */
    @Test
    fun tennyoSchemeUsesItsOwnAccentRoles() {
        val lightColors = tennyoColorScheme(AppNeutralPalette.IslandLight)
        val darkColors = tennyoColorScheme(AppNeutralPalette.IslandDark)

        assertEquals(Color(0xFF1B7A55), lightColors.primary)
        assertEquals(Color(0xFF6D4DC4), lightColors.secondary)
        assertEquals(Color(0xFF1F8039), lightColors.tertiary)

        assertEquals(Color(0xFF3FBF87), darkColors.primary)
        assertEquals(Color(0xFFB79CFF), darkColors.secondary)
        assertEquals(Color(0xFF5FAD65), darkColors.tertiary)
    }

    /** Enum names are the stored form of a scheme and a mode; renaming a constant changes what is persisted. */
    @Test
    fun storedFormIsTheEnumName() {
        assertEquals(
            listOf("Dynamic", "TennyoTheme"),
            AppColorScheme.entries.map { it.name },
        )
        assertEquals(listOf("Auto", "Light", "Dark"), AppThemeMode.entries.map { it.name })
    }

    /** The green that diff-added rows and connected chips read by stays green in both palettes. */
    @Test
    fun tertiaryStaysGreenInBothPalettes() {
        AppNeutralPalette.entries.forEach { palette ->
            val tertiary = tennyoColorScheme(palette).tertiary

            assertTrue(
                tertiary.green > tertiary.red && tertiary.green > tertiary.blue,
                "Expected tertiary to read as green in $palette, was $tertiary",
            )
        }
    }
}

private fun ColorScheme.allRoles(): List<Color> =
    listOf(
        primary,
        onPrimary,
        primaryContainer,
        onPrimaryContainer,
        inversePrimary,
        secondary,
        onSecondary,
        secondaryContainer,
        onSecondaryContainer,
        tertiary,
        onTertiary,
        tertiaryContainer,
        onTertiaryContainer,
        background,
        onBackground,
        surface,
        onSurface,
        surfaceVariant,
        onSurfaceVariant,
        surfaceTint,
        inverseSurface,
        inverseOnSurface,
        error,
        onError,
        errorContainer,
        onErrorContainer,
        outline,
        outlineVariant,
        scrim,
        surfaceBright,
        surfaceDim,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
        surfaceContainerLow,
        surfaceContainerLowest,
        primaryFixed,
        primaryFixedDim,
        onPrimaryFixed,
        onPrimaryFixedVariant,
        secondaryFixed,
        secondaryFixedDim,
        onSecondaryFixed,
        onSecondaryFixedVariant,
        tertiaryFixed,
        tertiaryFixedDim,
        onTertiaryFixed,
        onTertiaryFixedVariant,
    )
