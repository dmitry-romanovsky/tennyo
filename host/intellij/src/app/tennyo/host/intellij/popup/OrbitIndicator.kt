package app.tennyo.host.intellij.popup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun OrbitIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 22.dp,
    trackColor: Color = popupBorderColor(),
) {
    val angle by rememberInfiniteTransition(label = "orbit").animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(ORBIT_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "moon",
    )
    Canvas(modifier.size(indicatorSize)) {
        scale(size.minDimension / MARK_EXTENT, pivot = center) {
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
            // An oval arc runs on its parametric angle, so the moon is placed the same way, at the trail's end.
            val radians = angle * PI.toFloat() / HALF_TURN
            val tilt = ORBIT_TILT * PI.toFloat() / HALF_TURN
            val x = ORBIT_RX * cos(radians)
            val y = ORBIT_RY * sin(radians)
            val nearness = (sin(radians) + 1f) / 2f
            drawCircle(
                color = color.copy(alpha = color.alpha * (FAR_ALPHA + (1f - FAR_ALPHA) * nearness)),
                radius = MOON_RADIUS * (FAR_SCALE + (1f - FAR_SCALE) * nearness),
                center = center + Offset(x * cos(tilt) - y * sin(tilt), x * sin(tilt) + y * cos(tilt)),
            )
        }
    }
}

// The mark's geometry, in the 192-unit grid of its icon.
private const val ORBIT_RX = 66f
private const val ORBIT_RY = 30f
private const val ORBIT_TILT = -28f
private const val PLANET_RADIUS = 15f
private const val MOON_RADIUS = 10f
private const val TRACK_WIDTH = 5f
private const val TRAIL_WIDTH = 12f
private const val MARK_EXTENT = 144f
private const val TRAIL_SWEEP = 140f
private const val FAR_SCALE = 0.6f
private const val FAR_ALPHA = 0.45f
private const val ORBIT_MILLIS = 1_600
private const val FULL_TURN = 360f
private const val HALF_TURN = 180f
