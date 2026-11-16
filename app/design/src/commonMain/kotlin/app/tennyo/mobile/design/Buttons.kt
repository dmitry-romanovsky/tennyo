package app.tennyo.mobile.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.tennyo.mobile.design.generated.resources.Res
import app.tennyo.mobile.design.generated.resources.tabs_more_actions
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing
import org.jetbrains.compose.resources.stringResource

enum class TennyoButtonStyle {
    Primary,
    Secondary,
    Tonal,
    Text,
    Success,
    Danger,
}

enum class TennyoButtonSize(
    val height: Dp,
    val horizontalPadding: Dp,
) {
    Small(ComponentSizes.buttonSmallHeight, ComponentSizes.buttonSmallHorizontalPadding),
    Medium(ComponentSizes.buttonMediumHeight, ComponentSizes.buttonMediumHorizontalPadding),
    PrimaryAction(ComponentSizes.primaryActionHeight, ComponentSizes.primaryActionHorizontalPadding),
}

@Composable
fun TennyoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TennyoButtonStyle = TennyoButtonStyle.Primary,
    size: TennyoButtonSize = TennyoButtonSize.Medium,
    loading: Boolean = false,
    shape: Shape = Corners.full,
    content: @Composable RowScope.() -> Unit,
) {
    TennyoButtonLayout(
        onClick = onClick,
        modifier = modifier,
        style = style,
        size = size,
        enabled = enabled,
        loading = loading,
        shape = shape,
        content = content,
    )
}

@Composable
fun TennyoButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TennyoButtonStyle = TennyoButtonStyle.Primary,
    size: TennyoButtonSize = TennyoButtonSize.Medium,
    icon: ImageVector? = null,
    loading: Boolean = false,
    shape: Shape = Corners.full,
) {
    TennyoButtonLayout(
        onClick = onClick,
        modifier = modifier,
        label = label,
        style = style,
        size = size,
        icon = icon,
        enabled = enabled,
        loading = loading,
        shape = shape,
    )
}

@Composable
fun TennyoProgressButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TennyoButtonStyle = TennyoButtonStyle.Text,
    size: TennyoButtonSize = TennyoButtonSize.Medium,
    loading: Boolean = false,
    progressFraction: Float = 0f,
    progressAlpha: Float = 0f,
    progressColor: Color = MaterialTheme.colorScheme.primaryContainer,
    shape: Shape = Corners.full,
    content: @Composable RowScope.() -> Unit,
) {
    TennyoButtonLayout(
        onClick = onClick,
        modifier = modifier,
        style = style,
        size = size,
        enabled = enabled,
        loading = loading,
        progressFraction = progressFraction,
        progressAlpha = progressAlpha,
        progressColor = progressColor,
        shape = shape,
        content = content,
    )
}

/** Shared layout + defaults for every [TennyoButton]/[TennyoProgressButton] overload. */
@Composable
private fun TennyoButtonLayout(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    style: TennyoButtonStyle = TennyoButtonStyle.Primary,
    size: TennyoButtonSize = TennyoButtonSize.Medium,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    progressFraction: Float = 0f,
    progressAlpha: Float = 0f,
    progressColor: Color = Color.Transparent,
    shape: Shape = Corners.full,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = tennyoButtonPalette(style = style, enabled = enabled)
    val containerColor by animateColorAsState(
        targetValue = palette.container,
        animationSpec = tween(AnimationDurations.regularMillis),
        label = "TennyoButtonContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = palette.content,
        animationSpec = tween(AnimationDurations.regularMillis),
        label = "TennyoButtonContent",
    )
    val boundedProgressFraction = progressFraction.coerceIn(0f, 1f)
    val boundedProgressAlpha = if (enabled && !loading) {
        progressAlpha.coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        modifier = modifier
            .height(size.height)
            .clip(shape)
            .background(containerColor)
            .drawWithContent {
                if (boundedProgressFraction > 0f && boundedProgressAlpha > 0f) {
                    val drawSize = this.size
                    drawRect(
                        color = progressColor.copy(alpha = boundedProgressAlpha),
                        size = Size(
                            width = drawSize.width * boundedProgressFraction,
                            height = drawSize.height,
                        ),
                    )
                }
                drawContent()
            }
            .then(
                if (palette.border != Color.Transparent) {
                    Modifier.border(Borders.regular, palette.border, shape)
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = size.horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                when {
                    loading -> {
                        TennyoOrbitIndicator(
                            indicatorSize = ComponentSizes.buttonLoadingIndicator,
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = ComponentAlphas.faintLine),
                        )
                        Spacer(modifier = Modifier.width(Spacing.extraSmall))
                    }

                    content != null -> content()

                    icon != null -> {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(ComponentSizes.compactIcon),
                            tint = contentColor,
                        )
                        Spacer(modifier = Modifier.width(Spacing.extraSmall))
                    }
                }
                if (content == null && label != null) {
                    Text(
                        text = label,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun TennyoIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = ComponentSizes.iconButton,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val resolvedContentColor by animateColorAsState(
        if (enabled) contentColor else contentColor.copy(alpha = ComponentAlphas.disabledContent),
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(Corners.full)
            .background(containerColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides resolvedContentColor) {
            content()
        }
    }
}

@Composable
fun TennyoIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = ComponentSizes.iconButton,
    iconSize: Dp = ComponentSizes.compactIcon,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = Color.Transparent,
) {
    TennyoIconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        size = size,
        contentColor = contentColor,
        containerColor = containerColor,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun TennyoTabStripButton(
    modifier: Modifier = Modifier,
    imageVector: ImageVector,
    contentDescription: String = stringResource(Res.string.tabs_more_actions),
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(AnimationDurations.quickMillis),
        label = "Tab strip action press",
    )
    Box(
        modifier = modifier
            .width(ComponentSizes.editorTabAddWidth)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = Spacing.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ComponentSizes.compactIcon),
        )
    }
}
