package app.tennyo.mobile.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.tennyo.mobile.design.generated.resources.Res
import app.tennyo.mobile.design.generated.resources.dismiss
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun TennyoAppBrand(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TennyoAppMark(modifier = Modifier.size(ComponentSizes.iconButton))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@Composable
fun TennyoStatusBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: TennyoStatusTone = TennyoStatusTone.Neutral,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    /** Overrides the tone's own glyph, which speaks of the link; a banner about something else names its subject. */
    icon: ImageVector? = null,
) {
    val palette = tennyoStatusPalette(tone)
    // Fill only, no border: a tone-coloured outline over a tonal fill states the tone twice and makes even a
    // passive message read as an alert.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Corners.md,
        color = palette.container,
        contentColor = palette.content,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon ?: when (tone) {
                    TennyoStatusTone.Connected -> TennyoIcons.Wifi
                    TennyoStatusTone.Reconnecting -> TennyoIcons.Sync
                    TennyoStatusTone.Failed,
                    TennyoStatusTone.Warning -> TennyoIcons.ErrorOutline

                    TennyoStatusTone.Neutral -> TennyoIcons.Wifi
                },
                contentDescription = null,
                modifier = Modifier.size(ComponentSizes.compactIcon),
                tint = palette.content,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.content,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.content,
                    )
                }
                if (actionLabel != null && onAction != null) {
                    // A real pill on the surface token, not underlined text in the flow: it reads as tonal
                    // against every tone's fill without needing a variant per tone.
                    Text(
                        text = actionLabel,
                        modifier = Modifier
                            .padding(top = Spacing.extraSmall)
                            .clip(Corners.full)
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(role = Role.Button, onClick = onAction)
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.content,
                    )
                }
            }
            if (onDismiss != null) {
                TennyoIconButton(
                    icon = TennyoIcons.Close,
                    contentDescription = stringResource(Res.string.dismiss),
                    onClick = onDismiss,
                    contentColor = palette.content,
                )
            }
        }
    }
}


/**
 * A terminal key bar key. [toggle] keys latch a modifier and expose [selected] as a switch state;
 * [emphasized] highlights the bar's primary key (Enter) in the tertiary accent.
 */
@Composable
fun TennyoAccessoryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    toggle: Boolean = false,
    emphasized: Boolean = false,
    contentDescription: String? = null,
) {
    val shape = if (toggle) Corners.sm else Corners.xs
    val colors = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when {
        selected -> colors.primary to colors.onPrimary
        emphasized -> colors.tertiary to colors.onTertiary
        else -> colors.surface to colors.onSurface
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(Borders.regular, if (selected || emphasized) containerColor else colors.outlineVariant, shape)
            .then(
                if (toggle) {
                    Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = Spacing.small, horizontal = Spacing.small),
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = if (selected || emphasized) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        if (toggle) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = -Spacing.extraSmall, y = Spacing.extraSmall)
                    .size(ComponentSizes.accessoryKeyIndicator)
                    .clip(Corners.full)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
            )
        }
    }
}

/** The one indeterminate progress glyph: waits with no known duration, such as connecting or loading. */
@Composable
fun TennyoProgressIndicator(
    modifier: Modifier = Modifier,
    indicatorSize: Dp = ComponentSizes.spinner,
    /** How much is done, 0 to 1; `null` spins, for work that cannot say. */
    progress: Float? = null,
) {
    if (progress == null) {
        TennyoOrbitIndicator(modifier = modifier, indicatorSize = indicatorSize)
    } else {
        CircularProgressIndicator(
            progress = { progress },
            modifier = modifier.size(indicatorSize),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeWidth = Borders.focus,
        )
    }
}

@Composable
fun TennyoWorkingIndicator(
    modifier: Modifier = Modifier,
    label: String? = null,
    indicatorSize: Dp = ComponentSizes.spinner,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TennyoProgressIndicator(indicatorSize = indicatorSize)
        if (label != null) Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A sheet's title row: the title set large on the sheet itself, and a round close button that dismisses it.
 * [actionLabel] is the close button's spoken label, since every sheet header's action backs out of the sheet.
 */
@Composable
fun TennyoBottomSheetHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(
            start = Spacing.large,
            end = Spacing.medium,
            top = Spacing.medium,
            bottom = Spacing.small,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(ComponentSizes.buttonMediumHeight)
                .clip(Corners.full)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(role = Role.Button, onClick = onAction)
                .semantics { contentDescription = actionLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TennyoIcons.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ComponentSizes.appMarkGlyph),
            )
        }
    }
}

@Composable
fun TennyoConnectionIcon(
    tone: TennyoStatusTone,
    modifier: Modifier = Modifier,
) {
    val palette = tennyoStatusPalette(tone)
    Icon(
        imageVector = when (tone) {
            TennyoStatusTone.Connected -> TennyoIcons.Wifi
            TennyoStatusTone.Neutral -> TennyoIcons.Wifi
            TennyoStatusTone.Reconnecting -> TennyoIcons.Sync
            TennyoStatusTone.Failed,
            TennyoStatusTone.Warning -> TennyoIcons.ErrorOutline
        },
        contentDescription = null,
        tint = palette.content,
        modifier = modifier.size(ComponentSizes.icon),
    )
}

@Composable
private fun TennyoAppMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(Corners.xs)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = TennyoIcons.Brand,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(ComponentSizes.compactIcon),
        )
    }
}


