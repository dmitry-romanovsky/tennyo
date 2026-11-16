package app.tennyo.host.intellij.popup

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.ui.dsl.builder.IntelliJSpacingConfiguration

internal val spacing = IntelliJSpacingConfiguration()
internal val popupShape = RoundedCornerShape(8.dp)
internal val popupCompactIconButtonSize = 26.dp
internal val popupCompactIconSize = 14.dp

internal fun popupDeviceAvatarLabel(deviceName: String): String {
    val tokens = deviceName
        .trim()
        .split(Regex("[\\s_-]+"))
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return "^‿^"
    val mainToken = if (tokens.size > 2 && !tokens[1].all(Char::isDigit)) tokens[1] else tokens.first()
    if (mainToken.length == 3) {
        return mainToken.take(3)
    }
    val lastToken = tokens.last()
    val suffix = when {
        isShortDeviceToken(lastToken) -> lastToken.take(3)
        else -> lastToken.first().toString()
    }
    return "${mainToken.first()}$suffix".take(3)
}

private fun isShortDeviceToken(token: String): Boolean =
    token.length <= 4 || token.any(Char::isDigit)

@Composable
internal fun popupWaitingAccent(): Color = popupPalette().secondary

@Composable
internal fun popupWarningAccent(): Color = popupPalette().onWarningContainer

@Composable
internal fun popupWarningBackground(): Color = popupPalette().warningContainer

@Composable
internal fun popupWarningBorder(): Color = popupPalette().onWarningContainer

@Composable
internal fun popupDangerAccent(): Color = popupPalette().error

/** The design's "connected" and "words" accent: the IDE palette's primary, not a success green. */
@Composable
internal fun popupReadyAccent(): Color = popupPalette().primary

@Composable
internal fun popupTextColor(): Color = popupPalette().onSurface

@Composable
internal fun popupMutedTextColor(): Color = popupPalette().onSurfaceVariant

@Composable
internal fun popupButtonColors(style: PopupButtonStyle, enabled: Boolean): PopupButtonColors {
    val palette = popupPalette()
    val colors = when (style) {
        PopupButtonStyle.Primary, PopupButtonStyle.Success -> PopupButtonColors(
            background = palette.primary,
            border = palette.primary,
            content = palette.onPrimary,
        )

        PopupButtonStyle.Secondary -> PopupButtonColors(
            background = Color.Transparent,
            border = palette.outline,
            content = palette.onSurface,
        )

        PopupButtonStyle.Quiet -> PopupButtonColors(
            background = Color.Transparent,
            border = Color.Transparent,
            content = palette.onSurfaceVariant,
        )

        PopupButtonStyle.Danger -> PopupButtonColors(
            background = Color.Transparent,
            border = palette.error.copy(alpha = 0.5f),
            content = palette.error,
        )
    }
    return if (enabled) colors else colors.disabled()
}

private fun PopupButtonColors.disabled(): PopupButtonColors =
    PopupButtonColors(
        background = background.copy(alpha = 0.45f),
        border = border.copy(alpha = 0.45f),
        content = content.copy(alpha = 0.45f),
    )

@Composable
internal fun popupBackground(): Color = popupPalette().surface

@Composable
internal fun popupBorderColor(): Color = popupPalette().outlineVariant

internal enum class PopupButtonStyle {
    Primary,
    Secondary,

    /** The quiet way out of a state: text only, no frame, so it never competes with the primary action. */
    Quiet,
    Success,
    Danger,
}

internal enum class PopupButtonSize(
    val height: Dp,
    val horizontalPadding: Dp,
) {
    Small(height = 30.dp, horizontalPadding = spacing.horizontalSmallGap.dp),
    Medium(height = 32.dp, horizontalPadding = spacing.horizontalDefaultGap.dp),
    Large(height = 34.dp, horizontalPadding = spacing.horizontalDefaultGap.dp),
}

internal data class PopupButtonColors(
    val background: Color,
    val border: Color,
    val content: Color,
)

