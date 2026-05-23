package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.mindlayer.service.engine.util.OcrGroundTruthFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class OcrNumericValidationInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun production_backend_meets_synthetic_corpus_floor() = runBlocking {
        val assetList = context.assets.list("")?.toList().orEmpty()
        assumeTrue(
            "PaddleOCR AI Pack manifest not bundled — corpus runs only on CI " +
                "emulator lanes with -PpaddleOcr*Sha256 provisioned.",
            assetList.contains("paddleocr_model_integrity.json"),
        )

        val bundles = PaddleOcrModelRegistry.discoverBundles(context)
        val bundle = PaddleOcrModelRegistry.getDefaultBundle(bundles)
        assumeTrue("AI Pack bundle resolution returned null", bundle != null)
        val resolvedBundle = checkNotNull(bundle)

        val fixtures = OcrGroundTruthFixtures.all()
        val backend = LiteRtPaddleOcrBackend(context, memoryHeadroomBytes = 0L)
        try {
            backend.initialize(resolvedBundle, "CPU")

            var truePositives = 0
            var falsePositives = 0
            var falseNegatives = 0
            val perFixtureMetrics = mutableListOf<String>()

            for (fixture in fixtures) {
                val result = backend.recognise(
                    yPlane = fixture.bitmap.toYPlane(),
                    width = fixture.bitmap.width,
                    height = fixture.bitmap.height,
                    config = OcrEngineConfig(
                        emitBoundingBoxes = true,
                        maxLines = MAX_LINES_PER_FIXTURE,
                    ),
                )
                val matches = matchBoxes(
                    detections = result.lines,
                    truth = fixture.truth,
                    bitmapWidth = fixture.bitmap.width,
                    bitmapHeight = fixture.bitmap.height,
                    iouThreshold = IOU_THRESHOLD,
                )
                truePositives += matches.tp
                falsePositives += matches.fp
                falseNegatives += matches.fn
                perFixtureMetrics += "${fixture.name}: TP=${matches.tp} FP=${matches.fp} FN=${matches.fn}"
            }

            val recall = truePositives.toDouble() / (truePositives + falseNegatives).coerceAtLeast(1)
            val precision = truePositives.toDouble() / (truePositives + falsePositives).coerceAtLeast(1)
            val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0.0
            val summary = buildString {
                appendLine("recall=$recall precision=$precision f1=$f1")
                appendLine("TP=$truePositives FP=$falsePositives FN=$falseNegatives")
                perFixtureMetrics.forEach { appendLine(it) }
            }

            assertTrue("Recall below floor: $summary", recall >= RECALL_FLOOR)
            assertTrue("Precision below floor: $summary", precision >= PRECISION_FLOOR)
            assertTrue("F1 below floor: $summary", f1 >= F1_FLOOR)
        } finally {
            backend.shutdown()
            fixtures.forEach { fixture ->
                if (!fixture.bitmap.isRecycled) fixture.bitmap.recycle()
            }
        }
    }

    private fun matchBoxes(
        detections: List<OcrTextLine>,
        truth: List<OcrGroundTruthFixtures.GroundTruthBox>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        iouThreshold: Float,
    ): MatchCounts {
        val detectionBoxes = detections.map { line ->
            DetectionBox(line.boundingBox?.toImagePolygon(bitmapWidth, bitmapHeight).orEmpty())
        }
        val candidates = detectionBoxes.flatMapIndexed { detectionIndex, detection ->
            truth.mapIndexedNotNull { truthIndex, expected ->
                val iou = axisAlignedIou(detection.polygon, expected.polygon)
                if (iou >= iouThreshold) Candidate(detectionIndex, truthIndex, iou) else null
            }
        }.sortedByDescending { it.iou }

        // Sparse synthetic text boxes do not overlap, so deterministic greedy
        // IoU-descending assignment is enough; Hungarian matching is overkill.
        val matchedDetections = mutableSetOf<Int>()
        val matchedTruth = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.detectionIndex !in matchedDetections && candidate.truthIndex !in matchedTruth) {
                matchedDetections += candidate.detectionIndex
                matchedTruth += candidate.truthIndex
            }
        }
        return MatchCounts(
            tp = matchedTruth.size,
            fp = detectionBoxes.size - matchedDetections.size,
            fn = truth.size - matchedTruth.size,
        )
    }

    private fun FloatArray.toImagePolygon(bitmapWidth: Int, bitmapHeight: Int): List<Pair<Float, Float>> =
        (0 until 4).map { index ->
            (this[index * 2] * bitmapWidth.toFloat()) to (this[index * 2 + 1] * bitmapHeight.toFloat())
        }

    private fun axisAlignedIou(a: List<Pair<Float, Float>>, b: List<Pair<Float, Float>>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val left = max(a.minOf { it.first }, b.minOf { it.first })
        val top = max(a.minOf { it.second }, b.minOf { it.second })
        val right = min(a.maxOf { it.first }, b.maxOf { it.first })
        val bottom = min(a.maxOf { it.second }, b.maxOf { it.second })
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = (a.maxOf { it.first } - a.minOf { it.first }) * (a.maxOf { it.second } - a.minOf { it.second })
        val areaB = (b.maxOf { it.first } - b.minOf { it.first }) * (b.maxOf { it.second } - b.minOf { it.second })
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else (intersection / union).coerceIn(0f, 1f)
    }

    private fun Bitmap.toYPlane(): ByteArray {
        val out = ByteArray(width * height)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = getPixel(x, y)
                val luma = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 + 500) / 1000
                out[offset++] = luma.coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    private data class MatchCounts(val tp: Int, val fp: Int, val fn: Int)
    private data class DetectionBox(val polygon: List<Pair<Float, Float>>)
    private data class Candidate(val detectionIndex: Int, val truthIndex: Int, val iou: Float)

    private companion object {
        private const val MAX_LINES_PER_FIXTURE = 32
        private const val IOU_THRESHOLD = 0.5f

        // Lenient floors for v1. TODO: measure on the first green CI run and tune upward.
        private const val RECALL_FLOOR = 0.70
        private const val PRECISION_FLOOR = 0.70
        private const val F1_FLOOR = 0.70
    }
}
