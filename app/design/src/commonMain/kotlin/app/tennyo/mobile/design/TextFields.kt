package app.tennyo.mobile.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.Borders
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.Corners
import app.tennyo.mobile.design.theme.Spacing

@Composable
fun TennyoFocusedTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val labelColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = contentColor),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Column(
                modifier = Modifier
                    .clip(Corners.sm)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        Borders.regular,
                        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        Corners.sm,
                    )
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                AnimatedVisibility(value.isNotEmpty()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = value.isEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
fun TennyoPromptTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String,
    maxLines: Int = 6,
    identityKey: Any = Unit,
) {
    // Focus belongs to the field, not to one identity's draft: a new identity starts focused if the field is.
    var focused by remember { mutableStateOf(false) }
    val fieldState = remember(identityKey) { TennyoPromptTextFieldState(value).also { it.focused = focused } }
    val fieldAlpha by animateFloatAsState(
        if (fieldState.value.text.isNotEmpty()) ComponentAlphas.visible else ComponentAlphas.slightlyTransparent,
        label = "PromptFieldAlpha",
    )
    LaunchedEffect(value, fieldState.focused, identityKey) {
        fieldState.synchronizeExternalValue(value)
    }
    BasicTextField(
        value = fieldState.value,
        onValueChange = { updated ->
            fieldState.applyUserValue(updated)?.let(onValueChange)
        },
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                fieldState.focused = it.isFocused
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = fieldAlpha)),
        enabled = enabled,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent),
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth().animateContentSize().padding(Spacing.medium),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (fieldState.value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = ComponentAlphas.disabledContent),
                    )
                }
                innerTextField()
            }
        },
    )
}

internal class TennyoPromptTextFieldState(initialValue: String) {
    var value by mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length)))
        private set
    var focused by mutableStateOf(false)

    fun synchronizeExternalValue(externalValue: String) {
        if (externalValue != value.text && (!focused || externalValue.isEmpty())) {
            value = TextFieldValue(externalValue, TextRange(externalValue.length))
        }
    }

    fun applyUserValue(updated: TextFieldValue): String? {
        val changedText = updated.text.takeIf { it != value.text }
        value = updated
        return changedText
    }
}


@Composable
fun TennyoSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (enabled) ComponentAlphas.visible else ComponentAlphas.disabledContent,
    )
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = ComponentAlphas.disabledContent)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(Corners.sm),
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = contentColor),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(Borders.regular, MaterialTheme.colorScheme.outlineVariant, Corners.sm)
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = TennyoIcons.Search,
                    contentDescription = null,
                    tint = supportingColor,
                    modifier = Modifier.size(ComponentSizes.compactIcon),
                )
                Box(modifier = Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = value.isEmpty() && placeholder.isNotEmpty(),
                        enter = fadeIn(tween(AnimationDurations.regularMillis)),
                        exit = fadeOut(tween(AnimationDurations.quickMillis)),
                    ) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = supportingColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}


