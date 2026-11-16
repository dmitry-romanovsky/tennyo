package app.tennyo.mobile.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.design.theme.monospaceFontFamily

/**
 * Shared "pill" primitive backing [TennyoTag], [TennyoBadge], [TennyoStatusChip], and [TennyoKeyCap]: a
 * clipped, colored container with an optional leading dot and a label. The named wrappers
 * exist only for call-site clarity (each pins its own colors/shape/typography default) —
 * reach for this directly only when none of them fit.
 */
@Composable
internal fun TennyoPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = Corners.xs,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.medium, vertical = Spacing.extraSmall),
    dotColor: Color? = null,
    fontWeight: FontWeight? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Row(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(ComponentSizes.statusDot)
                    .clip(Corners.full)
                    .background(dotColor),
            )
        }
        Text(
            text = label,
            style = textStyle,
            color = contentColor,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TennyoTag(
    label: String,
    modifier: Modifier = Modifier,
    leadingDotColor: Color? = null,
) {
    TennyoPill(
        label = label,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        dotColor = leadingDotColor,
    )
}

@Composable
fun TennyoStatusChip(
    label: String,
    modifier: Modifier = Modifier,
    tone: TennyoStatusTone = TennyoStatusTone.Neutral,
    showDot: Boolean = true,
) {
    val palette = tennyoStatusPalette(tone)
    TennyoPill(
        label = label,
        containerColor = palette.container,
        contentColor = palette.content,
        modifier = modifier,
        shape = Corners.full,
        dotColor = if (showDot) palette.content else null,
    )
}

/** A keyboard key or chord label, such as `PgUp` or `Ctrl+D`, set in the terminal's monospace face. */
@Composable
fun TennyoKeyCap(
    label: String,
    modifier: Modifier = Modifier,
) {
    TennyoPill(
        label = label,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
        shape = Corners.sm,
        contentPadding = PaddingValues(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        fontWeight = FontWeight.SemiBold,
        textStyle = MaterialTheme.typography.labelLarge.copy(fontFamily = monospaceFontFamily()),
    )
}
