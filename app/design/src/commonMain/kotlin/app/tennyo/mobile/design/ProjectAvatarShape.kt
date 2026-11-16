package app.tennyo.mobile.design

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val OUTLINE_SAMPLES = 360
private const val SMOOTHING_HALF_WIDTH = 6
private const val SQUIRCLE_EXPONENT = 4.0
private const val CLOVER_NOTCH = 0.6
private const val CLOVER_LEAF_CURVE = 0.7
private const val COOKIE_LOBES = 9.0
private const val COOKIE_CENTER = 0.93
private const val COOKIE_DEPTH = 0.07
private const val BURST_VALLEY = 0.8
private const val BURST_RIPPLES = 6.0
private const val PILL_CAP_RADIUS = 0.5
private const val PILL_CAP_CENTER = 0.5

private data class Point(val x: Double, val y: Double)

internal fun projectAvatarShape(style: ProjectAvatarStyle): Shape {
    val points = style.outlinePoints()
    return GenericShape { size, _ ->
        val radius = min(size.width, size.height) / 2f
        points.forEachIndexed { index, point ->
            val x = size.width / 2f + point.x * radius
            val y = size.height / 2f + point.y * radius
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** Points rotated, centred and scaled so the outline's larger extent spans the box. */
private fun ProjectAvatarStyle.outlinePoints(): List<Offset> {
    val angle = rotationDegrees * PI / 180
    val cosAngle = cos(angle)
    val sinAngle = sin(angle)
    val rotated = form.unitPoints().map { Point(it.x * cosAngle - it.y * sinAngle, it.x * sinAngle + it.y * cosAngle) }
    val minX = rotated.minOf { it.x }
    val maxX = rotated.maxOf { it.x }
    val minY = rotated.minOf { it.y }
    val maxY = rotated.maxOf { it.y }
    val centerX = (minX + maxX) / 2
    val centerY = (minY + maxY) / 2
    val half = max(maxX - minX, maxY - minY) / 2
    return rotated.map { Offset(((it.x - centerX) / half).toFloat(), ((it.y - centerY) / half).toFloat()) }
}

private fun ProjectAvatarForm.unitPoints(): List<Point> = when (this) {
    ProjectAvatarForm.Arch -> archPoints()
    ProjectAvatarForm.Pill -> pillPoints()
    else -> smoothedPolarPoints(this)
}

/**
 * Polar outlines are averaged around the circle so sharp polygon corners and cusps round off
 * into the soft, organic silhouettes Material's expressive shapes use.
 */
private fun smoothedPolarPoints(form: ProjectAvatarForm): List<Point> {
    val raw = List(OUTLINE_SAMPLES) { polarRadius(form, 2 * PI * it / OUTLINE_SAMPLES) }
    val smoothed = List(OUTLINE_SAMPLES) { index ->
        (-SMOOTHING_HALF_WIDTH..SMOOTHING_HALF_WIDTH).sumOf { raw[(index + it + OUTLINE_SAMPLES) % OUTLINE_SAMPLES] } /
            (2 * SMOOTHING_HALF_WIDTH + 1)
    }
    val peak = smoothed.max()
    return smoothed.mapIndexed { index, radius ->
        val theta = 2 * PI * index / OUTLINE_SAMPLES
        Point(radius / peak * cos(theta), radius / peak * sin(theta))
    }
}

private fun polarRadius(form: ProjectAvatarForm, theta: Double): Double = when (form) {
    ProjectAvatarForm.Squircle -> {
        val root = 1 / SQUIRCLE_EXPONENT
        1 / (cos(theta).pow(SQUIRCLE_EXPONENT) + sin(theta).pow(SQUIRCLE_EXPONENT)).pow(root)
    }
    ProjectAvatarForm.Clover -> CLOVER_NOTCH + (1 - CLOVER_NOTCH) * abs(cos(2 * theta)).pow(CLOVER_LEAF_CURVE)
    ProjectAvatarForm.Cookie -> COOKIE_CENTER + COOKIE_DEPTH * cos(COOKIE_LOBES * theta)
    ProjectAvatarForm.Burst -> BURST_VALLEY + (1 - BURST_VALLEY) * abs(cos(BURST_RIPPLES * theta))
    ProjectAvatarForm.Triangle -> regularPolygonRadius(sides = 3, theta = theta)
    ProjectAvatarForm.Pentagon -> regularPolygonRadius(sides = 5, theta = theta)
    ProjectAvatarForm.Hexagon -> regularPolygonRadius(sides = 6, theta = theta)
    ProjectAvatarForm.Circle, ProjectAvatarForm.Arch, ProjectAvatarForm.Pill -> 1.0
}

private fun regularPolygonRadius(sides: Int, theta: Double): Double {
    val sector = 2 * PI / sides
    val offsetFromEdgeMidpoint = ((theta % sector) + sector) % sector - sector / 2
    return cos(PI / sides) / cos(offsetFromEdgeMidpoint)
}

private fun archPoints(): List<Point> {
    val arc = List(OUTLINE_SAMPLES / 2 + 1) { index ->
        val theta = PI + PI * index / (OUTLINE_SAMPLES / 2)
        Point(cos(theta), sin(theta))
    }
    return arc + listOf(Point(1.0, 1.0), Point(-1.0, 1.0))
}

private fun pillPoints(): List<Point> {
    val half = OUTLINE_SAMPLES / 2
    val rightCap = List(half + 1) { index ->
        val theta = -PI / 2 + PI * index / half
        Point(PILL_CAP_CENTER + PILL_CAP_RADIUS * cos(theta), PILL_CAP_RADIUS * sin(theta))
    }
    val leftCap = List(half + 1) { index ->
        val theta = PI / 2 + PI * index / half
        Point(-PILL_CAP_CENTER + PILL_CAP_RADIUS * cos(theta), PILL_CAP_RADIUS * sin(theta))
    }
    return rightCap + leftCap
}
