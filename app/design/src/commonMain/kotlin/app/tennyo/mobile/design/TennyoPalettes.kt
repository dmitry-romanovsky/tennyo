package app.tennyo.mobile.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.DarkWarningColors
import app.tennyo.mobile.design.theme.ElementWarningColors
import app.tennyo.mobile.design.theme.LightWarningColors

/** Above this, a surface is a light one; the neutral ramps sit far enough apart for one threshold to split them. */
private const val LIGHT_SURFACE_LUMINANCE = 0.5f

@Composable
internal fun tennyoButtonPalette(
    style: TennyoButtonStyle,
    enabled: Boolean,
): ElementColorPalette {
    val colors = MaterialTheme.colorScheme
    if (!enabled) {
        return ElementColorPalette(
            container = colors.surfaceVariant,
            content = colors.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent),
        )
    }
    return when (style) {
        TennyoButtonStyle.Primary -> ElementColorPalette(colors.primary, colors.onPrimary)
        TennyoButtonStyle.Secondary -> ElementColorPalette(
            container = colors.surface,
            content = colors.onSurface,
            border = colors.outline,
        )

        TennyoButtonStyle.Tonal -> ElementColorPalette(colors.primaryContainer, colors.onPrimaryContainer)
        TennyoButtonStyle.Text -> ElementColorPalette(Color.Transparent, colors.onPrimaryContainer)
        TennyoButtonStyle.Success -> ElementColorPalette(colors.tertiary, colors.onTertiary)
        // Filled, not outlined: an outlined button reads as the lesser control in a row, which is
        // backwards for the only destructive action on screen.
        TennyoButtonStyle.Danger -> ElementColorPalette(
            container = colors.errorContainer,
            content = colors.error,
            border = colors.error.copy(alpha = ComponentAlphas.faintLine),
        )
    }
}

/**
 * Warning, reconnecting and failed each own a tone. One shared red made them indistinguishable in a chip row and
 * read as an alarm for what is only a retry.
 */
@Composable
internal fun tennyoStatusPalette(tone: TennyoStatusTone): ElementColorPalette {
    val colors = MaterialTheme.colorScheme
    return when (tone) {
        TennyoStatusTone.Connected -> ElementColorPalette(
            container = colors.tertiaryContainer,
            content = colors.onTertiaryContainer,
        )

        TennyoStatusTone.Warning -> warningColors().let { ElementColorPalette(it.container, it.content) }

        TennyoStatusTone.Failed -> ElementColorPalette(
            container = colors.errorContainer,
            content = colors.onErrorContainer,
        )

        TennyoStatusTone.Reconnecting,
        TennyoStatusTone.Neutral -> ElementColorPalette(
            container = colors.surfaceVariant,
            content = colors.onSurfaceVariant,
        )
    }
}

/** Material carries no warning role, so the amber pair is read off whichever neutral ramp is in play. */
@Composable
private fun warningColors(): ElementWarningColors =
    if (MaterialTheme.colorScheme.surface.luminance() > LIGHT_SURFACE_LUMINANCE) {
        LightWarningColors
    } else {
        DarkWarningColors
    }
