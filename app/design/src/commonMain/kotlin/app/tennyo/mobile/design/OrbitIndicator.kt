package app.tennyo.mobile.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.tennyo.mobile.design.theme.ComponentSizes
import app.tennyo.mobile.design.theme.TennyoTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Tennyo mark in motion, for work that cannot say how far along it is: a moon travels the mark's tilted orbit
 * around its planet, a short trail behind it, and grows as it swings to the near side. It replaces the plain spinner
 * so waiting reads as Tennyo's own, and keeps the mark's geometry (a 66 × 30 orbit tilted 28° around the planet).
 */
@Composable
fun TennyoOrbitIndicator(
    modifier: Modifier = Modifier,
    indicatorSize: Dp = ComponentSizes.spinner,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    TennyoOrbitMark(
        modifier = modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        markSize = indicatorSize,
        color = color,
        trackColor = trackColor,
    )
}

/**
 * The same orbiting mark as a brand moment rather than a wait: no progress semantics, and [orbitMillis] lets a large
 * mark turn slowly where a spinner's pace would feel restless.
 */
@Composable
fun TennyoOrbitMark(
    markSize: Dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
    orbitMillis: Int = ORBIT_MILLIS,
) {
    val orbit by rememberInfiniteTransition(label = "orbit").animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(orbitMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "moon",
    )
    Canvas(modifier.size(markSize)) {
        scale(size.minDimension / MARK_EXTENT, pivot = center) {
            drawOrbit(orbit, color, trackColor)
        }
    }
}

/** Draws in the mark's own units, centred on the planet; the caller scales [MARK_EXTENT] to the indicator. */
private fun DrawScope.drawOrbit(angle: Float, color: Color, trackColor: Color) {
    val orbitSize = Size(ORBIT_RX * 2, ORBIT_RY * 2)
    val orbitTopLeft = Offset(center.x - ORBIT_RX, center.y - ORBIT_RY)
    rotate(ORBIT_TILT, pivot = center) {
        drawOval(trackColor, orbitTopLeft, orbitSize, style = Stroke(TRACK_WIDTH))
        drawArc(
            color = color,
            startAngle = angle - TRAIL_SWEEP,
            sweepAngle = TRAIL_SWEEP,
            useCenter = false,
            topLeft = orbitTopLeft,
            size = orbitSize,
            style = Stroke(TRAIL_WIDTH, cap = StrokeCap.Round),
        )
    }
    drawCircle(color, PLANET_RADIUS, center)
    // Below the planet (the lower half, before the tilt) the moon is on the near side: larger and fully opaque.
    val radians = angle * PI.toFloat() / HALF_TURN
    val nearness = (sin(radians) + 1f) / 2f
    drawCircle(
        color = color.copy(alpha = color.alpha * (FAR_ALPHA + (1f - FAR_ALPHA) * nearness)),
        radius = MOON_RADIUS * (FAR_SCALE + (1f - FAR_SCALE) * nearness),
        center = center + orbitPoint(radians),
    )
}

/**
 * The point of the tilted orbit at [radians]. An oval arc is drawn by its parametric angle (the unit circle stretched
 * to the oval), so the moon is placed the same way to sit exactly where the trail ends.
 */
private fun orbitPoint(radians: Float): Offset {
    val x = ORBIT_RX * cos(radians)
    val y = ORBIT_RY * sin(radians)
    val tilt = ORBIT_TILT * PI.toFloat() / HALF_TURN
    return Offset(x * cos(tilt) - y * sin(tilt), x * sin(tilt) + y * cos(tilt))
}

// The mark's geometry, in the 192-unit grid of its vector drawable.
private const val ORBIT_RX = 66f
private const val ORBIT_RY = 30f
private const val ORBIT_TILT = -28f
private const val PLANET_RADIUS = 15f
private const val MOON_RADIUS = 10f
private const val TRACK_WIDTH = 5f
private const val TRAIL_WIDTH = 12f

/** The tilted orbit plus the moon spans about 140 units; that is what fills the indicator. */
private const val MARK_EXTENT = 144f
private const val TRAIL_SWEEP = 140f
private const val FAR_SCALE = 0.6f
private const val FAR_ALPHA = 0.45f
private const val ORBIT_MILLIS = 1_600
private const val FULL_TURN = 360f
private const val HALF_TURN = 180f

@Preview
@Composable
private fun TennyoOrbitIndicatorPreview() {
    TennyoTheme {
        Row {
            TennyoOrbitIndicator(indicatorSize = ComponentSizes.icon)
            TennyoOrbitIndicator(indicatorSize = ComponentSizes.spinner * 2)
        }
    }
}
