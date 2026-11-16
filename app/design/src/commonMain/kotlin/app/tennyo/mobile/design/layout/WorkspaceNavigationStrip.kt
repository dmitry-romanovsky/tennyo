package app.tennyo.mobile.design.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.TennyoIcons
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing

private val StripItemHeight = 44.dp
private val StripIconSize = 20.dp
private val BadgeSize = 20.dp
private val OverflowDividerHeight = 20.dp

/** The divider inside the overflow pill sits on the pill's own content colour, dimmed so it reads as a rule. */
private const val OVERFLOW_DIVIDER_ALPHA = 0.35f

/** The open panel's icon grows into the overflow button from slightly smaller, and shrinks back out of it. */
private const val PANEL_ICON_ENTER_SCALE = 0.6f

/**
 * The destination strip and the panels overflow beside it (screens N1, N2 and N5): a row of pills directly under
 * the toolbar, not a bottom bar — the phone's own gesture area is right below the composer.
 *
 * With one destination there is nothing to navigate between, so the caller does not render this at all. With a
 * panel open the overflow button carries that panel's own icon before the divider, so what is over the user's work
 * is visible without opening the sheet.
 */
@Composable
fun WorkspaceNavigationStrip(
    destinations: List<WorkspaceDestinationItem>,
    selectedKey: String?,
    onDestinationSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    openPanelIcon: ImageVector? = null,
    singlePanelIcon: ImageVector? = null,
    singlePanelLabel: String? = null,
    onOverflowClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            key(destination.key) {
                DestinationPill(
                    destination = destination,
                    selected = destination.key == selectedKey,
                    onClick = { onDestinationSelected(destination.key) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // A host with one destination draws no pills, and the overflow keeps the trailing edge it always sits on
        // rather than sliding over to the start of an otherwise empty row.
        if (destinations.isEmpty()) Spacer(modifier = Modifier.weight(1f))
        onOverflowClick?.let { overflow ->
            OverflowPill(
                open = openPanelIcon != null,
                // A lone panel's button is already its icon, so it never doubles it before a divider.
                openPanelIcon = openPanelIcon.takeIf { singlePanelIcon == null },
                glyph = singlePanelIcon ?: TennyoIcons.MoreHoriz,
                glyphLabel = singlePanelLabel,
                onClick = overflow,
            )
        }
    }
}

@Composable
private fun DestinationPill(
    destination: WorkspaceDestinationItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(StripItemHeight)
            .clip(Corners.full)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .border(
                width = Borders.regular,
                color = if (selected) Color.Transparent else colors.outlineVariant,
                shape = Corners.full,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(StripIconSize),
        )
        Text(
            text = destination.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        destination.badge?.let { DestinationBadge(count = it) }
    }
}

/** The count rides beside the label rather than over the icon, so it never covers the glyph it belongs to. */
@Composable
private fun DestinationBadge(count: Int) {
    Box(
        modifier = Modifier
            .height(BadgeSize)
            .defaultMinSize(minWidth = BadgeSize)
            .clip(Corners.full)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = Spacing.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OverflowPill(
    open: Boolean,
    openPanelIcon: ImageVector?,
    glyph: ImageVector,
    glyphLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val colorSpec = tween<Color>(AnimationDurations.quickMillis)
    val contentColor by animateColorAsState(
        if (open) colors.onPrimaryContainer else colors.onSurfaceVariant,
        colorSpec,
        label = "OverflowContent",
    )
    val containerColor by animateColorAsState(
        if (open) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f),
        colorSpec,
        label = "OverflowContainer",
    )
    val borderColor by animateColorAsState(
        if (open) colors.outlineVariant.copy(alpha = 0f) else colors.outlineVariant,
        colorSpec,
        label = "OverflowBorder",
    )
    // Remember the last panel icon so it can still be drawn while it animates away.
    var shownPanelIcon by remember { mutableStateOf(openPanelIcon) }
    if (openPanelIcon != null) shownPanelIcon = openPanelIcon
    Row(
        modifier = modifier
            .height(StripItemHeight)
            .defaultMinSize(minWidth = StripItemHeight)
            .clip(Corners.full)
            .background(containerColor)
            .border(width = Borders.regular, color = borderColor, shape = Corners.full)
            .clickable(role = Role.Button, onClick = onClick)
            .animateContentSize(tween(AnimationDurations.quickMillis))
            .padding(horizontal = Spacing.medium),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // With a panel open the button wears that panel's icon, so the strip says what is over the work.
        AnimatedVisibility(
            visible = openPanelIcon != null,
            enter = fadeIn(tween(AnimationDurations.quickMillis)) +
                scaleIn(tween(AnimationDurations.quickMillis), initialScale = PANEL_ICON_ENTER_SCALE),
            exit = fadeOut(tween(AnimationDurations.quickMillis)) +
                scaleOut(tween(AnimationDurations.quickMillis), targetScale = PANEL_ICON_ENTER_SCALE),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = Spacing.small),
            ) {
                shownPanelIcon?.let { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(StripIconSize),
                    )
                }
                Box(
                    modifier = Modifier
                        .width(Borders.regular)
                        .height(OverflowDividerHeight)
                        .background(contentColor.copy(alpha = OVERFLOW_DIVIDER_ALPHA)),
                )
            }
        }
        Crossfade(targetState = glyph, animationSpec = tween(AnimationDurations.quickMillis), label = "OverflowGlyph") {
            Icon(
                imageVector = it,
                contentDescription = glyphLabel,
                tint = contentColor,
                modifier = Modifier.size(StripIconSize),
            )
        }
    }
}
