package app.tennyo.mobile.feature.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import app.tennyo.mobile.design.TennyoAccessoryKey
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Spacing
import app.tennyo.mobile.feature.terminal.generated.resources.Res
import app.tennyo.mobile.feature.terminal.generated.resources.terminal_hotkey_add
import org.jetbrains.compose.resources.stringResource

/**
 * The terminal key bar: fixed columns, a divider, then the toggleable columns, every column the same width — the
 * widest key's, or wider to fill the viewport. The whole bar scrolls horizontally when it does not fit.
 */
@Composable
internal fun TerminalKeyBar(
    layout: TerminalKeyBarLayout,
    latched: TerminalModifiers,
    onLatchedChange: (TerminalModifiers) -> Unit,
    onInput: (String) -> Unit,
    onAddHotkey: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val layoutDirection = LocalLayoutDirection.current
        val columnWidth = terminalKeyBarColumnWidth(
            viewportWidth = maxWidth.takeIf { constraints.hasBoundedWidth },
            horizontalPadding = contentPadding.calculateStartPadding(layoutDirection) +
                contentPadding.calculateEndPadding(layoutDirection),
            columnCount = layout.fixedColumns.size + layout.toggleableColumns.size,
        )
        val onItemClick: (TerminalKeyBarItem) -> Unit = { item ->
            when (item) {
                is TerminalKeyBarItem.Key -> onInput(item.chord.encode(latched))
                is TerminalKeyBarItem.Latch -> onLatchedChange(latched.toggled(item.modifier))
                TerminalKeyBarItem.AddHotkey -> onAddHotkey()
            }
        }
        // Padding sits inside the scroll so keys scroll all the way to the screen edge.
        EqualWidthColumns(
            minColumnWidth = columnWidth,
            spacing = Spacing.extraSmall,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            layout.fixedColumns.forEach { TerminalKeyBarColumn(it, latched, onItemClick) }
            VerticalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = Borders.regular,
                modifier = Modifier.layoutId(KEY_BAR_DIVIDER),
            )
            layout.toggleableColumns.forEach { TerminalKeyBarColumn(it, latched, onItemClick) }
        }
    }
}

@Composable
private fun TerminalKeyBarColumn(
    items: List<TerminalKeyBarItem>,
    latched: TerminalModifiers,
    onItemClick: (TerminalKeyBarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        items.forEach { item ->
            key(item.stableKey) {
                TerminalKeyBarButton(
                    item = item,
                    latched = latched,
                    onClick = { onItemClick(item) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ComponentSizes.accessoryKeyHeight),
                )
            }
        }
    }
}

@Composable
private fun TerminalKeyBarButton(
    item: TerminalKeyBarItem,
    latched: TerminalModifiers,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is TerminalKeyBarItem.Key -> TennyoAccessoryKey(
            label = item.label,
            contentDescription = item.customName ?: item.chord.accessibilityLabel(),
            emphasized = item.emphasized,
            onClick = onClick,
            modifier = modifier,
        )

        is TerminalKeyBarItem.Latch -> TennyoAccessoryKey(
            label = item.modifier.label,
            contentDescription = stringResource(item.modifier.spokenName),
            selected = item.modifier in latched,
            toggle = true,
            onClick = onClick,
            modifier = modifier,
        )

        TerminalKeyBarItem.AddHotkey -> TennyoAccessoryKey(
            label = "+",
            contentDescription = stringResource(Res.string.terminal_hotkey_add),
            onClick = onClick,
            modifier = modifier,
        )
    }
}

/**
 * Lays its children out in a row, each column as wide as the widest one's natural width and never narrower than
 * [minColumnWidth]; the child tagged [KEY_BAR_DIVIDER] keeps its own width and takes the row's height.
 */
@Composable
private fun EqualWidthColumns(
    minColumnWidth: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, _ ->
        val columns = measurables.filter { it.layoutId != KEY_BAR_DIVIDER }
        val widest = columns.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val width = maxOf(minColumnWidth.roundToPx(), widest)
        val placedColumns = columns.associateWith { it.measure(Constraints.fixedWidth(width)) }
        val height = placedColumns.values.maxOfOrNull { it.height } ?: 0
        val placeables = measurables.map { measurable ->
            placedColumns[measurable] ?: measurable.measure(Constraints(maxHeight = height, minHeight = height))
        }
        val gap = spacing.roundToPx()
        val rowWidth = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(rowWidth, height) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width + gap
            }
        }
    }
}

private const val KEY_BAR_DIVIDER = "divider"

/** Stretches columns to fill the viewport, but never below the minimum key width. */
private fun terminalKeyBarColumnWidth(viewportWidth: Dp?, horizontalPadding: Dp, columnCount: Int): Dp {
    if (columnCount == 0 || viewportWidth == null) return ComponentSizes.accessoryKeyMinWidth
    // The divider is one more child in the row, so there is one gap per column.
    val available = viewportWidth - horizontalPadding - Spacing.extraSmall * columnCount - Borders.regular
    return max(ComponentSizes.accessoryKeyMinWidth, available / columnCount)
}
