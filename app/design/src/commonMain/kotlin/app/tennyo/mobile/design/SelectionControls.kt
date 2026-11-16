package app.tennyo.mobile.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.util.lerp
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes

@Composable
fun TennyoCompactCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.size(ComponentSizes.selectionControl),
        enabled = enabled,
    )
}

private const val HALF_TURN_DEGREES = 180f
private const val ARC_SWEEP_DEGREES = 180f
private const val PRESSED_ARC_SWEEP_DEGREES = 240f

/**
 * A compact on/off switch sized for dense rows, with a full touch target around it. The thumb is an arc: off, a small
 * outlined-colour arc at the left that faces left on a quiet track; on, a larger arc at the right that faces right on
 * the accent-filled track, so the state never rests on colour alone. The arc turns over as it travels on a spring
 * and widens while pressed.
 */
@Composable
fun TennyoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme

    val direction = if (checked) -1 else 1
    val travel by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "TennyoSwitchTravel",
    )
    val stretch by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(AnimationDurations.quickMillis),
        label = "TennyoSwitchStretch",
    )
    val colorSpec = tween<Color>(AnimationDurations.quickMillis)
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.primary else colors.surfaceContainerHighest,
        animationSpec = colorSpec,
        label = "TennyoSwitchTrack",
    )
    val outlineColor by animateColorAsState(
        targetValue = if (checked) colors.primary else colors.outline,
        animationSpec = colorSpec,
        label = "TennyoSwitchOutline",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colors.onPrimary else colors.outline,
        animationSpec = colorSpec,
        label = "TennyoSwitchThumb",
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .wrapContentSize(Alignment.Center)
            .requiredSize(width = ComponentSizes.switchWidth, height = ComponentSizes.switchHeight)
            .then(if (enabled) Modifier else Modifier.alpha(ComponentAlphas.disabledContent))
            .drawBehind {
                val trackRadius = CornerRadius(size.height / 2)
                drawRoundRect(color = trackColor, cornerRadius = trackRadius)
                val outlineWidth = Borders.selectionControl.toPx()
                drawRoundRect(
                    color = outlineColor,
                    topLeft = Offset(outlineWidth / 2, outlineWidth / 2),
                    size = Size(size.width - outlineWidth, size.height - outlineWidth),
                    cornerRadius = CornerRadius(size.height / 2 - outlineWidth / 2),
                    style = Stroke(outlineWidth),
                )

                val diameter = lerp(
                    ComponentSizes.switchThumbUnchecked.toPx(),
                    ComponentSizes.switchThumbChecked.toPx(),
                    travel,
                )
                val stroke = ComponentSizes.switchThumbStroke.toPx()
                val arcSize = diameter - stroke
                val centerX = lerp(size.height / 2, size.width - size.height / 2, travel)
                // 0° faces right; off faces left (180°) and the arc turns over as it travels.
                val facing = direction * HALF_TURN_DEGREES * (1 - travel)
                val sweep = lerp(ARC_SWEEP_DEGREES, PRESSED_ARC_SWEEP_DEGREES, stretch)
                drawArc(
                    color = thumbColor,
                    startAngle = facing - sweep / 2,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(centerX - arcSize / 2, (size.height - arcSize) / 2),
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )

                if (focused) {
                    val ringWidth = Borders.focus.toPx()
                    val gap = (Borders.focus + Borders.regular).toPx()
                    drawRoundRect(
                        color = colors.primary,
                        topLeft = Offset(-gap, -gap),
                        size = Size(size.width + gap * 2, size.height + gap * 2),
                        cornerRadius = CornerRadius(size.height / 2 + gap),
                        style = Stroke(ringWidth),
                    )
                }
            },
    )
}

@Preview
@Composable
private fun ControlsPreview() {
    Column {
        var checkbox by remember { mutableStateOf(true) }
        var switch by remember { mutableStateOf(true) }
        TennyoCompactCheckbox(checkbox, { checkbox = it })
        TennyoSwitch(switch, { switch = it })
    }
}
