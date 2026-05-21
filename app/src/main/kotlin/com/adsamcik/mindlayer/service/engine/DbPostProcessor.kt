package com.adsamcik.mindlayer.service.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** PaddleOCR DB postprocessor ported to pure Kotlin (no OpenCV dependency). */
data class IntPoint(val x: Int, val y: Int)

data class RotatedRect(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val angleDeg: Float,
)

data class DetectedQuad(
    val polygon: List<IntPoint>,
    val score: Float,
    val areaPx: Int,
)

data class DetectionConfig(
    val binarizationThreshold: Float = DEFAULT_BINARIZATION_THRESHOLD,
    val boxThreshold: Float = DEFAULT_BOX_THRESHOLD,
    val unclipRatio: Float = DEFAULT_UNCLIP_RATIO,
    val nmsIoUThreshold: Float = DEFAULT_NMS_IOU_THRESHOLD,
    val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    companion object {
        const val DEFAULT_BINARIZATION_THRESHOLD = 0.3f
        const val DEFAULT_BOX_THRESHOLD = 0.6f
        const val DEFAULT_UNCLIP_RATIO = 1.6f
        const val DEFAULT_NMS_IOU_THRESHOLD = 0.3f
        const val DEFAULT_MAX_CANDIDATES = 1000
    }
}

object DbPostProcessor {
    private const val MIN_CONTOUR_POINTS = 3

    fun decode(heatmap: FloatArray, side: Int, config: DetectionConfig = DetectionConfig()): List<DetectedQuad> {
        require(side > 0 && heatmap.size == side * side) {
            "Unexpected DB heatmap size ${heatmap.size} for side $side"
        }
        val probabilities = FloatArray(heatmap.size) { activation(heatmap[it]) }
        val mask = BooleanArray(heatmap.size) { probabilities[it] >= config.binarizationThreshold }
        val components = findComponents(mask, probabilities, side, side)
        val decoded = components.asSequence()
            .filter { it.pixels.size >= MIN_CONTOUR_POINTS }
            .mapNotNull { component ->
                val contour = traceContour(component, side, side)
                if (contour.size < MIN_CONTOUR_POINTS || component.score < config.boxThreshold) {
                    null
                } else {
                    val rect = minAreaRect(contour)
                    val quad = unclip(rect, config.unclipRatio)
                    DetectedQuad(
                        polygon = orderClockwise(quad.map { it.clamp(side) }),
                        score = component.score,
                        areaPx = component.pixels.size,
                    )
                }
            }
            .sortedByDescending { it.score }
            .toList()
        return nonMaximumSuppression(decoded, config.nmsIoUThreshold)
            .let { if (config.maxCandidates > 0) it.take(config.maxCandidates) else it }
    }

    internal fun findContours(binary: BooleanArray, width: Int, height: Int): List<List<IntPoint>> {
        require(binary.size == width * height)
        val probabilities = FloatArray(binary.size) { if (binary[it]) 1f else 0f }
        return findComponents(binary, probabilities, width, height).map { traceContour(it, width, height) }
    }

    internal fun minAreaRect(points: List<IntPoint>): RotatedRect {
        val hull = convexHull(points.map { FloatPoint(it.x.toFloat(), it.y.toFloat()) })
        if (hull.isEmpty()) return RotatedRect(0f, 0f, 0f, 0f, 0f)
        if (hull.size == 1) return RotatedRect(hull[0].x, hull[0].y, 1f, 1f, 0f)

        var bestArea = Float.POSITIVE_INFINITY
        var best = RotatedRect(0f, 0f, 0f, 0f, 0f)
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val angle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (p in hull) {
                val rx = p.x * c + p.y * s
                val ry = -p.x * s + p.y * c
                minX = min(minX, rx)
                maxX = max(maxX, rx)
                minY = min(minY, ry)
                maxY = max(maxY, ry)
            }
            val rectWidth = maxX - minX
            val rectHeight = maxY - minY
            val area = rectWidth * rectHeight
            if (area < bestArea) {
                bestArea = area
                val cx = (minX + maxX) * 0.5f
                val cy = (minY + maxY) * 0.5f
                best = RotatedRect(
                    centerX = cx * c - cy * s,
                    centerY = cx * s + cy * c,
                    width = rectWidth,
                    height = rectHeight,
                    angleDeg = Math.toDegrees(angle).toFloat(),
                )
            }
        }
        return best
    }

    internal fun nonMaximumSuppression(quads: List<DetectedQuad>, iouThreshold: Float): List<DetectedQuad> {
        val kept = ArrayList<DetectedQuad>(quads.size)
        for (quad in quads.sortedByDescending { it.score }) {
            if (kept.none { polygonIou(it.polygon, quad.polygon) > iouThreshold }) {
                kept += quad
            }
        }
        return kept
    }

    internal fun polygonIou(a: List<IntPoint>, b: List<IntPoint>): Float {
        val polyA = a.map { FloatPoint(it.x.toFloat(), it.y.toFloat()) }
        val polyB = b.map { FloatPoint(it.x.toFloat(), it.y.toFloat()) }
        val areaA = polygonArea(polyA)
        val areaB = polygonArea(polyB)
        if (areaA <= 0f || areaB <= 0f) return 0f
        val intersection = clipPolygon(polyA, ensureClockwise(polyB))
        val intersectionArea = polygonArea(intersection)
        val union = areaA + areaB - intersectionArea
        return if (union <= 0f) 0f else (intersectionArea / union).coerceIn(0f, 1f)
    }

    private fun findComponents(mask: BooleanArray, probabilities: FloatArray, width: Int, height: Int): List<Component> {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val components = ArrayList<Component>()
        for (start in mask.indices) {
            if (visited[start] || !mask[start]) continue
            var head = 0
            var tail = 0
            var scoreSum = 0.0
            val pixels = ArrayList<Int>()
            visited[start] = true
            queue[tail++] = start
            while (head < tail) {
                val index = queue[head++]
                pixels += index
                scoreSum += probabilities[index].toDouble()
                val x = index % width
                val y = index / width
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (!visited[next] && mask[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }
            components += Component(pixels, (scoreSum / pixels.size).toFloat())
        }
        return components
    }

    /** Moore/Suzuki-Abe style outer-border trace for one connected DB component. */
    private fun traceContour(component: Component, width: Int, height: Int): List<IntPoint> {
        val set = component.pixels.toHashSet()
        val boundary = component.pixels.mapNotNull { index ->
            val x = index % width
            val y = index / width
            val isBoundary = NEIGHBORS_4.any { (dx, dy) ->
                val nx = x + dx
                val ny = y + dy
                nx !in 0 until width || ny !in 0 until height || (ny * width + nx) !in set
            }
            if (isBoundary) IntPoint(x, y) else null
        }
        if (boundary.size <= 2) return boundary
        val hull = convexHull(boundary.map { FloatPoint(it.x.toFloat(), it.y.toFloat()) })
        return hull.map { IntPoint(it.x.roundToInt(), it.y.roundToInt()) }
    }

    private fun unclip(rect: RotatedRect, ratio: Float): List<FloatPoint> {
        val perimeter = 2f * (rect.width + rect.height)
        val area = rect.width * rect.height
        val distance = if (perimeter <= 0f) 0f else area * ratio / perimeter
        return rectCorners(rect.copy(width = rect.width + 2f * distance, height = rect.height + 2f * distance))
    }

    private fun rectCorners(rect: RotatedRect): List<FloatPoint> {
        val angle = Math.toRadians(rect.angleDeg.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val halfW = rect.width * 0.5f
        val halfH = rect.height * 0.5f
        val local = listOf(
            FloatPoint(-halfW, -halfH),
            FloatPoint(halfW, -halfH),
            FloatPoint(halfW, halfH),
            FloatPoint(-halfW, halfH),
        )
        return local.map { p ->
            FloatPoint(
                rect.centerX + p.x * c - p.y * s,
                rect.centerY + p.x * s + p.y * c,
            )
        }
    }

    private fun orderClockwise(points: List<IntPoint>): List<IntPoint> {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        val sorted = points.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return sorted.drop(start) + sorted.take(start)
    }

    private fun convexHull(points: List<FloatPoint>): List<FloatPoint> {
        val sorted = points.distinct().sortedWith(compareBy<FloatPoint> { it.x }.thenBy { it.y })
        if (sorted.size <= 1) return sorted
        fun cross(o: FloatPoint, a: FloatPoint, b: FloatPoint): Float =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<FloatPoint>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0f) lower.removeAt(lower.lastIndex)
            lower += p
        }
        val upper = ArrayList<FloatPoint>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0f) upper.removeAt(upper.lastIndex)
            upper += p
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun clipPolygon(subject: List<FloatPoint>, clip: List<FloatPoint>): List<FloatPoint> {
        var output = subject
        for (i in clip.indices) {
            val a = clip[i]
            val b = clip[(i + 1) % clip.size]
            val input = output
            if (input.isEmpty()) break
            output = ArrayList()
            var previous = input.last()
            for (current in input) {
                val currentInside = inside(current, a, b)
                val previousInside = inside(previous, a, b)
                if (currentInside) {
                    if (!previousInside) output += intersection(previous, current, a, b)
                    output += current
                } else if (previousInside) {
                    output += intersection(previous, current, a, b)
                }
                previous = current
            }
        }
        return output
    }

    private fun inside(p: FloatPoint, a: FloatPoint, b: FloatPoint): Boolean =
        ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) <= 0f

    private fun intersection(p1: FloatPoint, p2: FloatPoint, a: FloatPoint, b: FloatPoint): FloatPoint {
        val x1 = p1.x
        val y1 = p1.y
        val x2 = p2.x
        val y2 = p2.y
        val x3 = a.x
        val y3 = a.y
        val x4 = b.x
        val y4 = b.y
        val denominator = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denominator) < 1e-6f) return p2
        val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / denominator
        val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / denominator
        return FloatPoint(px, py)
    }

    private fun polygonArea(points: List<FloatPoint>): Float {
        if (points.size < 3) return 0f
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += (a.x * b.y - b.x * a.y).toDouble()
        }
        return abs(sum * 0.5).toFloat()
    }

    private fun ensureClockwise(points: List<FloatPoint>): List<FloatPoint> {
        if (points.size < 3) return points
        var signed = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            signed += ((b.x - a.x) * (b.y + a.y)).toDouble()
        }
        return if (signed > 0.0) points else points.asReversed()
    }

    private fun FloatPoint.clamp(side: Int): IntPoint = IntPoint(
        x = x.roundToInt().coerceIn(0, side),
        y = y.roundToInt().coerceIn(0, side),
    )

    private fun activation(value: Float): Float = when {
        !value.isFinite() -> 0f
        value in 0f..1f -> value
        else -> (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
    }

    private data class FloatPoint(val x: Float, val y: Float)
    private data class Component(val pixels: List<Int>, val score: Float)

    private val NEIGHBORS_4 = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
