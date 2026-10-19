package com.humpbacklab.letsfly

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CustomPoint(val x: Float, val y: Float)
internal data class CustomBounds(val left: Float, val top: Float,
    val right: Float, val bottom: Float) {
    fun outline() = listOf(CustomPoint(left, top), CustomPoint(right, top),
        CustomPoint(right, bottom), CustomPoint(left, bottom))
}
internal data class CustomControlArea(val id: Int, val bounds: CustomBounds,
    val outline: List<CustomPoint>)
internal enum class CustomLayoutIssueKind { OUTSIDE_SCREEN, CONTROL_OVERLAP, FIXED_OVERLAP }
internal data class CustomLayoutIssue(val kind: CustomLayoutIssueKind, val ids: Set<Int>)

/** Match the painted control shapes, leaving transparent joystick corners available. */
internal object CustomControlGeometry {
    fun area(id: Int, type: CustomControlType, x: Float, y: Float,
        width: Float, height: Float, density: Float): CustomControlArea {
        val outline = when (type) {
            CustomControlType.JOYSTICK -> ellipse(x, y, width * .375f, height * .375f)
            CustomControlType.HORIZONTAL -> {
                val rail = minOf(width, height) * .30f
                val travel = (width / 2 - rail - 4 * density).coerceAtLeast(1f)
                roundedRectangle(x, y, travel + rail, rail, rail)
            }
            CustomControlType.VERTICAL -> {
                val rail = minOf(width, height) * .30f
                val travel = (height / 2 - rail - 4 * density).coerceAtLeast(1f)
                roundedRectangle(x, y, rail, travel + rail, rail)
            }
            CustomControlType.BUTTON -> roundedRectangle(x, y, width / 2, height / 2,
                minOf(8 * density, width / 2, height / 2))
        }
        return CustomControlArea(id, CustomBounds(x - width / 2, y - height / 2,
            x + width / 2, y + height / 2), outline)
    }

    private fun ellipse(x: Float, y: Float, radiusX: Float, radiusY: Float) =
        (0 until 48).map { index ->
            val angle = index * 2 * PI / 48
            CustomPoint(x + cos(angle).toFloat() * radiusX,
                y + sin(angle).toFloat() * radiusY)
        }

    private fun roundedRectangle(x: Float, y: Float, halfWidth: Float,
        halfHeight: Float, radius: Float): List<CustomPoint> = buildList {
        val corners = listOf(
            CustomPoint(x + halfWidth - radius, y + halfHeight - radius),
            CustomPoint(x - halfWidth + radius, y + halfHeight - radius),
            CustomPoint(x - halfWidth + radius, y - halfHeight + radius),
            CustomPoint(x + halfWidth - radius, y - halfHeight + radius))
        for (corner in corners.indices) {
            for (step in 0..12) {
                val angle = (corner * 90 + step * 90.0 / 12) * PI / 180
                add(CustomPoint(corners[corner].x + cos(angle).toFloat() * radius,
                    corners[corner].y + sin(angle).toFloat() * radius))
            }
        }
    }

    /** Separating axes for convex outlines; touching edges are allowed. */
    fun intersects(first: List<CustomPoint>, second: List<CustomPoint>,
        tolerance: Float = .5f): Boolean {
        for (outline in listOf(first, second)) {
            for (index in outline.indices) {
                val a = outline[index]
                val b = outline[(index + 1) % outline.size]
                val dx = b.y - a.y
                val dy = a.x - b.x
                val length = sqrt(dx * dx + dy * dy)
                if (length < .001f) continue
                val nx = dx / length
                val ny = dy / length
                fun project(points: List<CustomPoint>): Pair<Float, Float> {
                    var low = Float.POSITIVE_INFINITY
                    var high = Float.NEGATIVE_INFINITY
                    for (point in points) {
                        val value = point.x * nx + point.y * ny
                        low = minOf(low, value)
                        high = maxOf(high, value)
                    }
                    return low to high
                }
                val (lowA, highA) = project(first)
                val (lowB, highB) = project(second)
                if (minOf(highA, highB) - maxOf(lowA, lowB) <= tolerance) return false
            }
        }
        return true
    }

    fun contains(outline: List<CustomPoint>, x: Float, y: Float): Boolean {
        var sign = 0
        for (index in outline.indices) {
            val a = outline[index]
            val b = outline[(index + 1) % outline.size]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (kotlin.math.abs(cross) < .01f) continue
            val next = if (cross > 0) 1 else -1
            if (sign != 0 && sign != next) return false
            sign = next
        }
        return true
    }

    fun issues(areas: List<CustomControlArea>, width: Int, height: Int,
        fixedButtons: List<CustomBounds>, tolerance: Float): List<CustomLayoutIssue> = buildList {
        for ((index, area) in areas.withIndex()) {
            val box = area.bounds
            if (box.left < -tolerance || box.top < -tolerance ||
                box.right > width + tolerance || box.bottom > height + tolerance)
                add(CustomLayoutIssue(CustomLayoutIssueKind.OUTSIDE_SCREEN, setOf(area.id)))
            if (fixedButtons.any { intersects(area.outline, it.outline(), tolerance) })
                add(CustomLayoutIssue(CustomLayoutIssueKind.FIXED_OVERLAP, setOf(area.id)))
            for (other in index + 1 until areas.size) {
                if (intersects(area.outline, areas[other].outline, tolerance))
                    add(CustomLayoutIssue(CustomLayoutIssueKind.CONTROL_OVERLAP,
                        setOf(area.id, areas[other].id)))
            }
        }
    }
}
