package app.tennyo.mobile.design.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing

/**
 * How every workspace pane frames its body: inset from the screen's edges rather than bleeding into them, so the
 * pane reads as a surface laid on the background (screens 03, 08 and 09).
 */
fun Modifier.paneInset(): Modifier = padding(horizontal = Spacing.medium)

/**
 * The tab strip as every pane frames it: inset with the pane body and sitting flush on it, so tabs and body read as one
 * card. Only its top corners are rounded; the body under it keeps a square top edge.
 */
@Composable
fun PaneTabStrip(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .paneInset()
            .clip(Corners.panelTop),
    ) {
        content()
    }
}

/** A destination the host does not serve: its icon, what is missing, and why — never an empty or greyed-out pane. */
@Composable
fun UnavailablePane(
    icon: ImageVector,
    title: String,
    reason: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.extraLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
