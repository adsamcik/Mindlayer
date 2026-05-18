package com.adsamcik.mindlayer.sdk.camerax

import com.adsamcik.mindlayer.OcrFrameMeta
import kotlin.math.abs

/**
 * Client-side presort scoring for OCR frames.
 *
 * Runs a cheap blur + exposure + dHash duplicate check on a Y-plane
 * and returns the corresponding [com.adsamcik.mindlayer.OcrFrameMeta.QUALITY_*]
 * hint. Used by SDK consumers (typically inside an
 * [androidx.camera.core.ImageAnalysis.Analyzer]) to drop bad frames
 * BEFORE crossing the service binder boundary, saving wakeups +
 * SharedMemory allocation cost.
 *
 * # Cost
 *
 * Designed to run in <10 ms on a 540p Y plane on mid-tier ARM. The
 * Laplacian variance pass is the dominant cost (~6-8 ms); Sobel and
 * histogram are <1 ms each. The dHash is computed lazily on
 * downsampled rows (8x8 thumbnail) so it's effectively free.
 *
 * # Not a security gate
 *
 * The service runs its own presort on every accepted frame (see
 * [com.adsamcik.mindlayer.service.engine.OcrFrameQualityPresort]),
 * so a buggy or hostile client cannot bypass quality gating by
 * mis-labelling. This client-side path exists purely to save the
 * binder round-trip for already-bad frames.
 *
 * # Mirroring the service-side thresholds
 *
 * Default thresholds match the service-side defaults in PR C1. Any
 * future tuning of the service-side values should re-spike these as
 * well to keep client + service verdicts aligned.
 */
object OcrFramePresort {

    /** Default thresholds — mirror the service-side ones in PR C1. */
    object Thresholds {
        const val BLUR_VARIANCE_MIN: Double = 150.0
        const val LUMA_MEAN_MIN: Int = 25
        const val LUMA_MEAN_MAX: Int = 240
        const val DUPLICATE_HAMMING_MAX: Int = 5
        const val SOBEL_MEAN_MIN: Double = 8.0
    }

    /**
     * Score a single frame and return the resulting
     * [com.adsamcik.mindlayer.OcrFrameMeta.QUALITY_*] hint.
     *
     * @param yPlane row-major 8-bit greyscale Y data.
     * @param width pixel width.
     * @param height pixel height.
     * @param previousDHash dHash of the most-recently-accepted frame;
     *   null on the first frame.
     * @return the wire-stable quality hint constant + the dHash for
     *   the next call.
     */
    fun score(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        previousDHash: ULong? = null,
    ): Result {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(yPlane.size == width * height) { "yPlane length mismatch" }

        val lumaMean = lumaMean(yPlane)
        val sobel = sobelMean(yPlane, width, height)
        val blur = laplacianVariance(yPlane, width, height)
        val dHash = computeDHash(yPlane, width, height)
        val hamming = previousDHash?.let { hamming(dHash, it) }

        val hint = when {
            lumaMean < Thresholds.LUMA_MEAN_MIN -> OcrFrameMeta.QUALITY_TOO_DARK
            lumaMean > Thresholds.LUMA_MEAN_MAX -> OcrFrameMeta.QUALITY_TOO_DARK
            blur < Thresholds.BLUR_VARIANCE_MIN -> OcrFrameMeta.QUALITY_BLURRY
            sobel < Thresholds.SOBEL_MEAN_MIN -> OcrFrameMeta.QUALITY_BLURRY
            hamming != null && hamming <= Thresholds.DUPLICATE_HAMMING_MAX ->
                OcrFrameMeta.QUALITY_DUPLICATE
            else -> OcrFrameMeta.QUALITY_GOOD
        }
        return Result(hint, dHash, blur, lumaMean, sobel)
    }

    /** Carried result + dHash so callers can pipe into the next score. */
    data class Result(
        val hint: Int,
        val dHash: ULong,
        val blurVariance: Double,
        val lumaMean: Int,
        val sobelMean: Double,
    ) {
        val isGood: Boolean get() = hint == OcrFrameMeta.QUALITY_GOOD
    }

    // ── Building blocks (kept package-internal for unit tests) ───────────

    internal fun lumaMean(yPlane: ByteArray): Int {
        var sum = 0L
        for (i in yPlane.indices) sum += yPlane[i].toInt() and 0xFF
        return if (yPlane.isEmpty()) 0 else (sum / yPlane.size).toInt()
    }

    internal fun laplacianVariance(yPlane: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val i = rowOffset + x
                val c = yPlane[i].toInt() and 0xFF
                val u = yPlane[i - width].toInt() and 0xFF
                val d = yPlane[i + width].toInt() and 0xFF
                val l = yPlane[i - 1].toInt() and 0xFF
                val r = yPlane[i + 1].toInt() and 0xFF
                val v = (4 * c - u - d - l - r).toDouble()
                sum += v
                sumSq += v * v
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    internal fun sobelMean(yPlane: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0L
        var count = 0
        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val i = rowOffset + x
                val tl = (yPlane[i - width - 1].toInt() and 0xFF)
                val t = (yPlane[i - width].toInt() and 0xFF)
                val tr = (yPlane[i - width + 1].toInt() and 0xFF)
                val l = (yPlane[i - 1].toInt() and 0xFF)
                val r = (yPlane[i + 1].toInt() and 0xFF)
                val bl = (yPlane[i + width - 1].toInt() and 0xFF)
                val b = (yPlane[i + width].toInt() and 0xFF)
                val br = (yPlane[i + width + 1].toInt() and 0xFF)
                val gx = -tl - 2 * l - bl + tr + 2 * r + br
                val gy = -tl - 2 * t - tr + bl + 2 * b + br
                sum += abs(gx).toLong() + abs(gy).toLong()
                count++
            }
        }
        return if (count == 0) 0.0 else sum.toDouble() / count
    }

    internal fun computeDHash(yPlane: ByteArray, width: Int, height: Int): ULong {
        val cols = 9
        val rows = 8
        val thumb = IntArray(cols * rows)
        for (r in 0 until rows) {
            val y0 = r * height / rows
            val y1 = (r + 1) * height / rows
            for (c in 0 until cols) {
                val x0 = c * width / cols
                val x1 = (c + 1) * width / cols
                var sum = 0
                var n = 0
                for (yi in y0 until y1) {
                    val rowOffset = yi * width
                    for (xi in x0 until x1) {
                        sum += yPlane[rowOffset + xi].toInt() and 0xFF
                        n++
                    }
                }
                thumb[r * cols + c] = if (n == 0) 0 else sum / n
            }
        }
        var hash = 0uL
        var bit = 63
        for (r in 0 until rows) {
            for (c in 0 until cols - 1) {
                if (thumb[r * cols + c] > thumb[r * cols + c + 1]) {
                    hash = hash or (1uL shl bit)
                }
                bit--
            }
        }
        return hash
    }

    internal fun hamming(a: ULong, b: ULong): Int =
        java.lang.Long.bitCount((a xor b).toLong())
}
