package app.tennyo.host.intellij.popup

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.jewel.foundation.theme.JewelTheme

internal data class PopupPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val error: Color,
    val onError: Color,
    val outline: Color,
    val outlineVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerHigh: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

/**
 * The panel wears Tennyo's own scheme rather than the IDE's: it is the same product surface as the phone, and a
 * user moving between the two should not see the accent change underneath them. Only light/dark follows the IDE.
 */
@Composable
internal fun popupPalette(): PopupPalette = if (JewelTheme.isDark) DarkPopupPalette else LightPopupPalette

private val LightPopupPalette = PopupPalette(
    primary = Color(0xFF1B7A55),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCF3E7),
    onPrimaryContainer = Color(0xFF0D4A32),
    secondary = Color(0xFF6D4DC4),
    secondaryContainer = Color(0xFFEAE3FB),
    onSecondaryContainer = Color(0xFF34215F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF262331),
    onSurfaceVariant = Color(0xFF6A6579),
    error = Color(0xFFCC3645),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFFA9A4BA),
    outlineVariant = Color(0xFFE1DEEA),
    surfaceContainer = Color(0xFFF2F0F7),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEBF3),
    // The design states the two warning tones in oklch; these are their sRGB equivalents.
    warningContainer = Color(0xFFFBEECF),
    onWarningContainer = Color(0xFF6A4304),
)

private val DarkPopupPalette = PopupPalette(
    primary = Color(0xFF3FBF87),
    onPrimary = Color(0xFF06301F),
    primaryContainer = Color(0xFF1E4C3A),
    onPrimaryContainer = Color(0xFFC9F0DC),
    secondary = Color(0xFFB79CFF),
    secondaryContainer = Color(0xFF3E2E63),
    onSecondaryContainer = Color(0xFFE6DBFF),
    surface = Color(0xFF232130),
    onSurface = Color(0xFFE2DFEA),
    onSurfaceVariant = Color(0xFFA19CB0),
    error = Color(0xFFE55765),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF726D82),
    outlineVariant = Color(0xFF332F40),
    surfaceContainer = Color(0xFF2B2839),
    surfaceContainerLow = Color(0xFF232130),
    surfaceContainerHigh = Color(0xFF332F42),
    warningContainer = Color(0xFF3B2B0D),
    onWarningContainer = Color(0xFFE6CE9D),
)
