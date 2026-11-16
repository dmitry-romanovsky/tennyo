package app.tennyo.host.intellij.popup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tennyo.host.intellij.HostBundle
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.minus
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun PopupErrorMessage(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PopupPanel(
        modifier = modifier,
        backgroundColor = popupDangerAccent().copy(alpha = 0.08f),
        borderColor = popupDangerAccent().copy(alpha = 0.36f),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = HostBundle.message("popup.error.title"),
                    color = popupDangerAccent(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(popupCompactIconButtonSize),
                ) {
                    Icon(
                        key = AllIconsKeys.Actions.Cancel,
                        contentDescription = HostBundle.message("popup.error.dismiss"),
                        modifier = Modifier.size(popupCompactIconSize),
                        tint = popupDangerAccent(),
                    )
                }
            }
            Text(
                text = message,
                color = popupDangerAccent(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PopupPanel(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedBackgroundColor = if (backgroundColor == Color.Unspecified) {
        popupBackground()
    } else {
        backgroundColor
    }
    val resolvedBorderColor = if (borderColor == Color.Unspecified) {
        popupBorderColor()
    } else {
        borderColor
    }
    Column(
        modifier = modifier
            .border(1.dp, resolvedBorderColor, popupShape)
            .background(resolvedBackgroundColor, popupShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.verticalSmallGap.dp),
        content = content,
    )
}

@Composable
internal fun PopupCallout(
    body: String,
    modifier: Modifier = Modifier,
) {
    PopupPanel(
        modifier = modifier,
        backgroundColor = popupWarningBackground(),
        borderColor = popupWarningBackground(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.horizontalSmallGap.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                key = AllIconsKeys.General.BalloonWarning,
                contentDescription = null,
                modifier = Modifier.size(popupCompactIconSize),
                tint = popupWarningAccent(),
            )
            Text(
                text = body,
                color = popupWarningAccent(),
                fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
            )
        }
    }
}

/** Copies, then names the outcome on the button itself for a moment instead of opening a tooltip. */
@Composable
internal fun PopupCopyButton(
    text: String,
    onClick: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    var copied by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(2.seconds)
            copied = null
        }
    }
    PopupButton(
        text = when (copied) {
            true -> HostBundle.message("popup.copy.done")
            false -> HostBundle.message("popup.copy.failed")
            null -> text
        },
        icon = if (copied == true) AllIconsKeys.Actions.Checked else AllIconsKeys.General.Copy,
        modifier = modifier,
        style = PopupButtonStyle.Secondary,
        size = PopupButtonSize.Small,
        onClick = { if (copied == null) copied = onClick() },
    )
}

@Composable
internal fun PopupSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, fontWeight = FontWeight.Medium)
}

@Composable
internal fun PopupNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = popupMutedTextColor(),
        fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
    )
}

@Composable
internal fun DeviceAvatar(
    deviceName: String,
    modifier: Modifier = Modifier,
    container: Color = Color.Unspecified,
    onContainer: Color = Color.Unspecified,
) {
    val palette = popupPalette()
    val label = remember(deviceName) { popupDeviceAvatarLabel(deviceName) }
    Box(
        modifier = modifier
            .size(38.dp)
            .background(
                if (container == Color.Unspecified) palette.secondaryContainer else container,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (onContainer == Color.Unspecified) palette.onSecondaryContainer else onContainer,
            fontWeight = FontWeight.Bold,
            fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
            overflow = TextOverflow.Clip,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PopupButton(
    text: String,
    icon: IconKey?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: PopupButtonStyle = PopupButtonStyle.Secondary,
    size: PopupButtonSize = PopupButtonSize.Medium,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = popupButtonColors(style, enabled)
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(size.height)
            .border(1.dp, colors.border, popupShape)
            .background(colors.background, popupShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = size.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = if (trailingContent != null) Modifier.fillMaxWidth() else Modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = if (trailingContent != null) Modifier.weight(1f, fill = false) else Modifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        key = icon,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = colors.content,
                    )
                    Spacer(Modifier.width(spacing.horizontalSmallGap.dp))
                }
                Text(
                    text,
                    color = colors.content,
                    fontSize = JewelTheme.defaultTextStyle.fontSize - 1.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailingContent != null) {
                Spacer(Modifier.weight(1f))
                trailingContent()
            }
        }
    }
}

@Composable
internal fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(popupBorderColor()),
    )
}
