package com.adsamcik.mindlayer.service.engine

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class DbPostProcessorTest {
    @Test fun `synthetic OCR fixtures meet recall at half IoU`() {
        val fixtures = listOf(
            fixture("fixture-01", "AB12", listOf(IntPoint(8, 10), IntPoint(56, 10), IntPoint(56, 24), IntPoint(8, 24))),
            fixture("fixture-02", "CODE128", listOf(IntPoint(12, 36), IntPoint(74, 22), IntPoint(80, 38), IntPoint(18, 52))),
            fixture("fixture-03", "ML7", listOf(IntPoint(20, 70), IntPoint(92, 70), IntPoint(92, 88), IntPoint(20, 88))),
            fixture("fixture-04", "OCR4", listOf(IntPoint(90, 14), IntPoint(122, 18), IntPoint(120, 34), IntPoint(88, 30))),
        )
        val writer = MultiFormatWriter()
        var hits = 0
        for (fixture in fixtures) {
            // Generates the same style of Code-128/text fixture this resource represents;
            // the DB unit assertion below is on the known heatmap polygon.
            val matrix = writer.encode(fixture.text, BarcodeFormat.CODE_128, 96, 24)
            assertTrue(matrix.width > 0)

            val decoded = DbPostProcessor.decode(
                heatmap = heatmap(128, shrink(fixture.polygon, 0.65f)),
                side = 128,
                config = DetectionConfig(boxThreshold = 0.6f, nmsIoUThreshold = 0.3f),
            )
            if (decoded.any { axisAlignedIou(it.polygon, fixture.polygon) >= 0.5f }) hits++
        }
        val recall = hits.toFloat() / fixtures.size.toFloat()
        assertTrue("recall@0.5 IoU was $recall", recall >= 0.9f)
    }

    @Test fun `nms drops lower scoring overlapping quads`() {
        val quads = listOf(
            DetectedQuad(listOf(IntPoint(0, 0), IntPoint(10, 0), IntPoint(10, 10), IntPoint(0, 10)), 0.9f, 100),
            DetectedQuad(listOf(IntPoint(1, 1), IntPoint(11, 1), IntPoint(11, 11), IntPoint(1, 11)), 0.7f, 100),
        )
        assertEquals(1, DbPostProcessor.nonMaximumSuppression(quads, 0.3f).size)
    }

    private fun fixture(name: String, text: String, polygon: List<IntPoint>): Fixture {
        val stream = javaClass.classLoader!!.getResourceAsStream("ocr-fixtures/$name.json")
        assertTrue("missing sidecar for $name", stream != null)
        return Fixture(text, polygon)
    }

    private fun shrink(polygon: List<IntPoint>, ratio: Float): List<IntPoint> {
        val cx = polygon.map { it.x }.average().toFloat()
        val cy = polygon.map { it.y }.average().toFloat()
        return polygon.map { point ->
            IntPoint(
                x = (cx + (point.x - cx) * ratio).toInt(),
                y = (cy + (point.y - cy) * ratio).toInt(),
            )
        }
    }

    private fun heatmap(side: Int, polygon: List<IntPoint>): FloatArray {
        val out = FloatArray(side * side)
        val left = polygon.minOf { it.x }
        val right = polygon.maxOf { it.x }
        val top = polygon.minOf { it.y }
        val bottom = polygon.maxOf { it.y }
        for (y in top..bottom) {
            for (x in left..right) {
                if (pointInPolygon(x + 0.5f, y + 0.5f, polygon)) out[y * side + x] = 0.9f
            }
        }
        return out
    }

    private fun pointInPolygon(x: Float, y: Float, polygon: List<IntPoint>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > y) != (pj.y > y) &&
                x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun axisAlignedIou(a: List<IntPoint>, b: List<IntPoint>): Float {
        val left = max(a.minOf { it.x }, b.minOf { it.x })
        val top = max(a.minOf { it.y }, b.minOf { it.y })
        val right = min(a.maxOf { it.x }, b.maxOf { it.x })
        val bottom = min(a.maxOf { it.y }, b.maxOf { it.y })
        val intersection = max(0, right - left) * max(0, bottom - top)
        val areaA = (a.maxOf { it.x } - a.minOf { it.x }) * (a.maxOf { it.y } - a.minOf { it.y })
        val areaB = (b.maxOf { it.x } - b.minOf { it.x }) * (b.maxOf { it.y } - b.minOf { it.y })
        return intersection.toFloat() / (areaA + areaB - intersection).toFloat()
    }

    private data class Fixture(val text: String, val polygon: List<IntPoint>)
}
