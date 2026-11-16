package app.tennyo.mobile.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing

@Composable
fun TennyoProjectRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    leadingText: String = title.take(1).uppercase(),
) {
    TennyoListRow(
        title = title,
        subtitle = subtitle,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leading = {
            ProjectInitial(
                text = leadingText,
                identity = title,
                selected = selected,
            )
        },
        trailing = {
            Icon(
                imageVector = TennyoIcons.KeyboardArrowRight,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
                },
                modifier = Modifier.size(ComponentSizes.compactIcon),
            )
        },
    )
}

@Composable
fun TennyoGitFileRow(
    status: String,
    fileName: String,
    changedLines: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    addedLines: Int? = null,
    deletedLines: Int? = null,
    selection: (@Composable () -> Unit)? = null,
) {
    val statusColor = gitStatusColor(status)
    TennyoListRow(
        title = fileName,
        subtitle = changedLines,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leading = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                selection?.invoke()
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(ComponentSizes.compactIcon),
                )
            }
        },
        trailing = if (addedLines != null || deletedLines != null) {
            {
                addedLines?.let {
                    Text(
                        text = "+$it",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (addedLines != null && deletedLines != null) Box(Modifier.width(Spacing.extraSmall))
                deletedLines?.let {
                    Text(
                        text = "-$it",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        } else {
            null
        },
    )
}


@Composable
fun TennyoProjectAvatar(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    ProjectInitial(
        text = text,
        identity = text,
        selected = selected,
        modifier = modifier,
    )
}



@Composable
fun TennyoListRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    leading: @Composable RowScope.() -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = ComponentAlphas.selectedContainer)
    } else {
        Color.Transparent
    }
    // The border is always drawn, transparent when unselected, so selecting a row never shifts its text.
    Row(
        modifier = modifier
            .clip(Corners.sm)
            .background(containerColor)
            .border(
                width = Borders.regular,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = Corners.sm,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
                },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun gitStatusColor(status: String): Color =
    when (status.uppercase()) {
        "A", "+", "?" -> MaterialTheme.colorScheme.tertiary
        "D", "-", "U" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

@Composable
private fun ProjectInitial(
    text: String,
    identity: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = remember(identity) { projectAvatarShape(projectAvatarStyle(identity)) }
    Box(
        modifier = modifier
            .size(ComponentSizes.projectAvatar)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}


