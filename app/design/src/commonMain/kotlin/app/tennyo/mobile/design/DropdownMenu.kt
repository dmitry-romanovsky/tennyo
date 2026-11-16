package app.tennyo.mobile.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Elevations
import app.tennyo.mobile.design.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private const val DROPDOWN_MENU_COLLAPSED_SCALE = 0.9f

@Composable
fun TennyoDropdownMenu(
    selectedLabel: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    enabled: Boolean = true,
    fillAnchor: Boolean = false,
    maxMenuHeight: Dp? = null,
) {
    val supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (enabled) ComponentAlphas.visible else ComponentAlphas.disabledContent,
    )
    val iconRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AnimationDurations.quickMillis),
        label = "DropdownIconRotation",
    )
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val verticalMarginPx = with(density) { Spacing.small.roundToPx() }
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    // The closed menu wears the open menu's shape and outline, so the two read as one control.
    val outlineColor by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(AnimationDurations.quickMillis),
        label = "DropdownOutline",
    )
    Box(
        modifier = modifier
            .clip(Corners.sm)
            .background(containerColor)
            .border(Borders.regular, outlineColor, Corners.sm)
            .then(if (fillAnchor) Modifier.fillMaxWidth() else Modifier)
            .clickable(enabled = enabled, role = Role.Button) { onExpandedChange(!expanded) }
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        val geometry = dropdownMenuGeometry(
            anchorBounds = anchorBounds,
            maxMenuHeight = maxMenuHeight,
            fillAnchor = fillAnchor,
            windowHeightPx = windowHeightPx,
            verticalMarginPx = verticalMarginPx,
        )
        val animation = dropdownMenuAnimation(expanded = expanded, canShowPopup = geometry.canShowPopup)

        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                anchorBounds = IntRect(
                    left = bounds.left.roundToInt(),
                    top = bounds.top.roundToInt(),
                    right = bounds.right.roundToInt(),
                    bottom = bounds.bottom.roundToInt(),
                )
            },
        ) {
            DropdownAnchorRow(
                selectedLabel = selectedLabel,
                enabled = enabled,
                fillAnchor = fillAnchor,
                iconRotation = iconRotation,
                iconTint = supportingColor,
            )
            if (animation.renderPopup) {
                DropdownMenuPopup(
                    options = options,
                    selectedIndex = selectedIndex,
                    onSelected = onSelected,
                    onExpandedChange = onExpandedChange,
                    expanded = expanded,
                    positionProvider = geometry.positionProvider,
                    menuWidth = geometry.menuWidth,
                    maxMenuHeight = geometry.maxHeight,
                    menuVisibility = animation.visibility,
                    menuTransformOrigin = geometry.transformOrigin,
                )
            }
        }
    }
}

/** Where the open menu fits next to its anchor, and how wide and tall it may be there. */
private data class DropdownMenuGeometry(
    val canShowPopup: Boolean,
    val maxHeight: Dp,
    val menuWidth: Dp,
    val transformOrigin: TransformOrigin,
    val positionProvider: PopupPositionProvider,
)

@Composable
private fun dropdownMenuGeometry(
    anchorBounds: IntRect,
    maxMenuHeight: Dp?,
    fillAnchor: Boolean,
    windowHeightPx: Int,
    verticalMarginPx: Int,
): DropdownMenuGeometry {
    val density = LocalDensity.current
    val explicitMaxHeightPx = maxMenuHeight?.let { with(density) { it.roundToPx() } }
    val placement = remember(anchorBounds, explicitMaxHeightPx, windowHeightPx, verticalMarginPx) {
        resolveDropdownMenuPlacement(
            anchorBounds = anchorBounds,
            explicitMaxHeightPx = explicitMaxHeightPx,
            windowHeightPx = windowHeightPx,
            verticalMarginPx = verticalMarginPx,
        )
    }
    val positionProvider = remember(anchorBounds, placement.showAbove) {
        TennyoDropdownPopupPositionProvider(
            measuredAnchorBounds = anchorBounds,
            showAbove = placement.showAbove,
        )
    }
    val pivotY = if (placement.showAbove) 1f else 0f
    return DropdownMenuGeometry(
        canShowPopup = anchorBounds != IntRect.Zero && placement.maxHeightPx > 0,
        maxHeight = with(density) { placement.maxHeightPx.toDp() },
        menuWidth = if (fillAnchor) {
            with(density) { anchorBounds.width.toDp() }
        } else {
            ComponentSizes.dropdownMenuWidth
        },
        transformOrigin = TransformOrigin(pivotFractionX = 0f, pivotFractionY = pivotY),
        positionProvider = positionProvider,
    )
}

/** Whether the popup is on screen at all, and how far its open animation has come. */
private data class DropdownMenuAnimation(
    val renderPopup: Boolean,
    val visibility: Float,
)

/**
 * Keeps the popup mounted until its closing animation has played, and starts the opening animation one frame
 * after it is mounted, so the menu always grows out of the anchor instead of appearing at full size.
 */
@Composable
private fun dropdownMenuAnimation(expanded: Boolean, canShowPopup: Boolean): DropdownMenuAnimation {
    var renderPopup by remember { mutableStateOf(expanded) }
    var animationVisible by remember { mutableStateOf(false) }
    val visibility by animateFloatAsState(
        targetValue = if (animationVisible) 1f else 0f,
        animationSpec = tween(AnimationDurations.quickMillis),
        label = "DropdownMenuVisibility",
    )
    LaunchedEffect(expanded) {
        if (expanded) {
            renderPopup = true
        } else if (renderPopup) {
            animationVisible = false
            delay(AnimationDurations.quickMillis.milliseconds)
            renderPopup = false
        }
    }
    LaunchedEffect(expanded, canShowPopup) {
        if (expanded && canShowPopup && !animationVisible) {
            animationVisible = false
            withFrameNanos { }
            animationVisible = true
        }
    }
    return DropdownMenuAnimation(renderPopup = renderPopup && canShowPopup, visibility = visibility)
}

/** The closed menu: the selected option and the chevron that turns as the menu opens. */
@Composable
private fun DropdownAnchorRow(
    selectedLabel: String,
    enabled: Boolean,
    fillAnchor: Boolean,
    iconRotation: Float,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = selectedLabel,
            modifier = if (fillAnchor) Modifier.weight(1f) else Modifier,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = TennyoIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .size(ComponentSizes.appMarkGlyph)
                .rotate(iconRotation),
        )
    }
}

/** The open menu, scaled and faded from the anchor edge it grows out of. */
@Composable
private fun DropdownMenuPopup(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    expanded: Boolean,
    positionProvider: PopupPositionProvider,
    menuWidth: Dp,
    maxMenuHeight: Dp,
    menuVisibility: Float,
    menuTransformOrigin: TransformOrigin,
) {
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { onExpandedChange(false) },
        properties = PopupProperties(focusable = expanded),
    ) {
        TennyoDropdownMenuSurface(
            options = options,
            selectedIndex = selectedIndex,
            onSelected = { index ->
                onSelected(index)
                onExpandedChange(false)
            },
            modifier = Modifier
                .width(menuWidth)
                .heightIn(max = maxMenuHeight)
                .graphicsLayer {
                    alpha = menuVisibility
                    scaleY = DROPDOWN_MENU_COLLAPSED_SCALE +
                            ((1f - DROPDOWN_MENU_COLLAPSED_SCALE) * menuVisibility)
                    transformOrigin = menuTransformOrigin
                },
            interactive = expanded,
        )
    }
}

internal data class TennyoDropdownPlacement(
    val maxHeightPx: Int,
    val showAbove: Boolean,
)

private class TennyoDropdownPopupPositionProvider(
    private val measuredAnchorBounds: IntRect,
    private val showAbove: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = measuredAnchorBounds
        val alignedX = if (layoutDirection == LayoutDirection.Ltr) {
            anchor.left
        } else {
            anchor.right - popupContentSize.width
        }
        val alignedY = if (showAbove) {
            anchor.top - popupContentSize.height
        } else {
            anchor.bottom
        }
        return IntOffset(
            x = alignedX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = alignedY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

internal fun resolveDropdownMenuPlacement(
    anchorBounds: IntRect,
    explicitMaxHeightPx: Int?,
    windowHeightPx: Int,
    verticalMarginPx: Int,
): TennyoDropdownPlacement {
    val screenBottomLimit = (windowHeightPx - verticalMarginPx).coerceAtLeast(verticalMarginPx)
    val availableAbovePx = (anchorBounds.top - verticalMarginPx).coerceAtLeast(0)
    val availableBelowPx = (screenBottomLimit - anchorBounds.bottom).coerceAtLeast(0)
    val availableHeightPx = max(availableAbovePx, availableBelowPx)
    val fallbackHeightPx = (screenBottomLimit - verticalMarginPx).coerceAtLeast(0)
    val resolvedMaxHeightPx = (explicitMaxHeightPx ?: availableHeightPx.takeIf { it > 0 } ?: fallbackHeightPx)
        .coerceAtMost(if (availableHeightPx > 0) availableHeightPx else fallbackHeightPx)
        .coerceAtLeast(0)
    return TennyoDropdownPlacement(
        maxHeightPx = resolvedMaxHeightPx,
        showAbove = availableAbovePx > availableBelowPx,
    )
}

@Composable
private fun TennyoDropdownMenuSurface(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = modifier,
        shape = Corners.sm,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Borders.regular, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = Elevations.subtle,
    ) {
        Column(modifier = Modifier.verticalScroll(scrollState)) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Text(
                    text = option,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable(enabled = interactive, role = Role.Button) { onSelected(index) }
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                )
            }
        }
    }
}


