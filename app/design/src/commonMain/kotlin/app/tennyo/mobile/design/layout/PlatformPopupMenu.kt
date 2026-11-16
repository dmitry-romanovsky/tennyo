package app.tennyo.mobile.design.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners

/** A menu anchored to the control that opens it — one implementation, on every platform. */
@Composable
fun PlatformPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        anchor()
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            shape = Corners.md,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = content,
        )
    }
}

/** One action in a [PlatformPopupMenu]; [destructive] actions take the error colour so they never read as routine. */
@Composable
fun TennyoMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(ComponentSizes.icon)) },
        colors = MenuDefaults.itemColors(
            textColor = color,
            leadingIconColor = color,
            disabledTextColor = color.copy(alpha = ComponentAlphas.disabledContent),
            disabledLeadingIconColor = color.copy(alpha = ComponentAlphas.disabledContent),
        ),
    )
}
