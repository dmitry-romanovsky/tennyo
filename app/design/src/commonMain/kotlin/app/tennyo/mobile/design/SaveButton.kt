package app.tennyo.mobile.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tennyo.mobile.design.theme.AnimationDurations
import app.tennyo.mobile.design.theme.ComponentAlphas
import app.tennyo.mobile.design.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * The countdown to an autosave: the button's border fills over [durationMillis] from [startedAtEpochMillis], and a
 * new [generation] — a new edit — restarts it.
 */
data class AutosaveCountdown(
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val generation: Long,
)

enum class SavePhase { SAVED, UNSAVED, SAVING }

/**
 * The editor's save button. Unsaved, its border fills as the autosave [countdown] runs out, so the user sees when the
 * file will save by itself; tapping saves now. Saving shows for at least [MIN_SAVING_MILLIS], so a fast save still
 * reads as one instead of a flicker.
 */
@Composable
fun TennyoSaveButton(
    phase: SavePhase,
    countdown: AutosaveCountdown?,
    onClick: () -> Unit,
    saveLabel: String,
    savingLabel: String,
    savedLabel: String,
    modifier: Modifier = Modifier,
) {
    val shown = rememberHeldSavingPhase(phase)
    val progress = rememberCountdownProgress(countdown.takeIf { shown == SavePhase.UNSAVED })
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (shown == SavePhase.UNSAVED) colors.primaryContainer else colors.surfaceVariant,
        tween(AnimationDurations.regularMillis),
        label = "SaveButtonContainer",
    )
    val content by animateColorAsState(
        if (shown == SavePhase.UNSAVED) colors.onPrimaryContainer else colors.onSurfaceVariant,
        tween(AnimationDurations.regularMillis),
        label = "SaveButtonContent",
    )
    val shape = RoundedCornerShape(SaveButtonRadius)
    val ring = colors.primary
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, colors.outlineVariant, shape)
            .drawWithContent {
                drawContent()
                if (progress <= 0f) return@drawWithContent
                val stroke = 2.dp.toPx()
                val inset = stroke / 2
                val radius = SaveButtonRadius.toPx()
                val outline = Path().apply {
                    addRoundRect(
                        RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(radius)),
                    )
                }
                val measure = PathMeasure().apply { setPath(outline, forceClosed = true) }
                val filled = Path()
                measure.getSegment(0f, measure.length * progress, filled, startWithMoveTo = true)
                drawPath(filled, ring, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            .clickable(enabled = shown == SavePhase.UNSAVED, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                fadeIn(tween(AnimationDurations.regularMillis)) togetherWith
                    fadeOut(tween(AnimationDurations.regularMillis))
            },
            label = "SaveButtonPhase",
        ) { current ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (current) {
                    SavePhase.SAVING -> TennyoOrbitIndicator(
                        indicatorSize = Spacing.large,
                        color = content,
                        trackColor = content.copy(alpha = ComponentAlphas.faintLine),
                    )

                    SavePhase.SAVED, SavePhase.UNSAVED -> Icon(
                        imageVector = if (current == SavePhase.SAVED) TennyoIcons.Check else TennyoIcons.Save,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(Spacing.large),
                    )
                }
                Text(
                    text = when (current) {
                        SavePhase.SAVED -> savedLabel
                        SavePhase.UNSAVED -> saveLabel
                        SavePhase.SAVING -> savingLabel
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                )
            }
        }
    }
}

/** [phase], except that once saving is shown it stays for [MIN_SAVING_MILLIS]. */
@Composable
private fun rememberHeldSavingPhase(phase: SavePhase): SavePhase {
    var shown by remember { mutableStateOf(phase) }
    var savingSince by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) {
        if (phase == SavePhase.SAVING) {
            savingSince = nowMillis()
        } else if (shown == SavePhase.SAVING) {
            delay((MIN_SAVING_MILLIS - (nowMillis() - savingSince)).coerceAtLeast(0L))
        }
        shown = phase
    }
    return shown
}

/** How much of [countdown] has run, `0` with none; it picks up mid-way when the button appears late. */
@Composable
private fun rememberCountdownProgress(countdown: AutosaveCountdown?): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(countdown?.generation, countdown?.startedAtEpochMillis) {
        if (countdown == null) {
            progress.animateTo(0f, tween(AnimationDurations.regularMillis))
            return@LaunchedEffect
        }
        val duration = countdown.durationMillis.coerceAtLeast(1L)
        val elapsed = (nowMillis() - countdown.startedAtEpochMillis).coerceIn(0L, duration)
        progress.snapTo(elapsed.toFloat() / duration)
        progress.animateTo(1f, tween(durationMillis = (duration - elapsed).toInt()))
    }
    return progress.value
}

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

const val MIN_SAVING_MILLIS = 500L

private val SaveButtonRadius = 8.dp
