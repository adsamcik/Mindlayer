package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrFrameMeta
import kotlin.math.abs

/**
 * Service-side frame quality presort.
 *
 * The SDK is expected to do most of this work on the client (downscale
 * to 540p, Laplacian blur, gyro motion) and submit a
 * [OcrFrameMeta.qualityHint] so the service can skip redundant analysis
 * on already-good frames. This service-side implementation exists to:
 *
 *  1. Re-score frames whose client hint says ``QUALITY_UNKNOWN`` or
 *     ``QUALITY_GOOD`` so a buggy or hostile client can't bypass quality
 *     gating by mis-labelling a blurry frame as ``QUALITY_GOOD``.
 *  2. Detect duplicates across consecutive frames via dHash — the client
 *     may not have seen the previous successful frame's content.
 *  3. Provide deterministic, JVM-testable scoring that
 *     [OcrSessionManager] (PR C3) can route on.
 *
 * # Inputs
 *
 * All scoring functions operate on **8-bit grayscale Y-plane data**
 * (height x width bytes, row-major). Callers are expected to extract
 * the Y plane from YUV420 before calling — that's the contract with
 * CameraX ImageAnalysis on the SDK side (PR E).
 *
 * # No Android dependencies
 *
 * Pure JVM: callable from unit tests with synthetic [ByteArray] inputs.
 * No Bitmap / Image / Canvas / Sobel-OpenCV-via-JNI.
 *
 * # Cost
 *
 * Designed to run in 5-10 ms total per 540p frame on mid-tier ARM
 * cores (measured against PaddleOCR PP-OCRv5 mobile det inference at
 * ~150-300 ms, the presort is <5% of pipeline cost). All loops are
 * single-pass over the Y plane to keep cache locality.
 */
object OcrFrameQualityPresort {

    /** Default thresholds — pinned by tests, tuned during Phase 1 spike. */
    object Thresholds {
        /**
         * Minimum Laplacian variance for a frame to NOT be classified as
         * blurry. Lower = more permissive. The PaddleOCR PP-OCRv5 mobile
         * detection head needs at least this much edge contrast to find
         * text reliably.
         */
        const val BLUR_VARIANCE_MIN: Double = 150.0

        /**
         * Minimum mean luminance (0-255) for a frame to NOT be classified
         * as too dark. Receipts on poor light go below this fast.
         */
        const val LUMA_MEAN_MIN: Int = 25

        /**
         * Maximum mean luminance (0-255) — flagged as "blown out" if
         * exceeded. Screen captures with mostly white pixels routinely
         * cross this; we still accept those (mode ``SCREEN_CAPTURE``
         * is handled by [OcrSessionManager] bypass).
         */
        const val LUMA_MEAN_MAX: Int = 240

        /**
         * Maximum Hamming distance between two 8x8 dHashes below which
         * the two frames are considered near-duplicates and the new
         * frame is rejected. dHash is 64 bits so the range is 0..64.
         * Empirically 5 catches camera-jitter dupes but lets through
         * genuine reframes.
         */
        const val DUPLICATE_HAMMING_MAX: Int = 5

        /**
         * Minimum text-density (Sobel mean gradient magnitude, 0-255)
         * for a frame to be considered text-bearing. Frames below this
         * are rejected as "blank" or "non-text" so we don't burn the
         * LLM on captures of the floor.
         */
        const val SOBEL_MEAN_MIN: Double = 8.0
    }

    /**
     * The verdict for a single frame.
     *
     * The integer [hint] values match the wire-stable [OcrFrameMeta]
     * ``QUALITY_*`` constants so [OcrSessionManager] can echo them
     * straight into [com.adsamcik.mindlayer.OcrFrameAck].
     */
    data class FrameQualityScore(
        val hint: Int,
        val blurVariance: Double,
        val lumaMean: Int,
        val sobelMean: Double,
        val dHash: ULong,
        val hammingToPrevious: Int?,
    ) {
        val isAccepted: Boolean get() = hint == OcrFrameMeta.QUALITY_GOOD
    }

    /**
     * Score a single frame.
     *
     * @param yPlane 8-bit greyscale row-major Y data. Length must be
     *   exactly ``width * height``; the function throws
     *   [IllegalArgumentException] otherwise.
     * @param width column count in pixels (must be > 0).
     * @param height row count in pixels (must be > 0).
     * @param previousDHash dHash of the most-recently-accepted frame
     *   from the same OCR session, or null for the first frame.
     */
    fun score(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        previousDHash: ULong? = null,
    ): FrameQualityScore {
        require(width > 0 && height > 0) {
            "width and height must be positive (got $width x $height)"
        }
        require(yPlane.size == width * height) {
            "yPlane length ${yPlane.size} != width * height ${width * height}"
        }

        val (lumaMean, lumaVariance) = lumaStats(yPlane)
        val sobelMean = sobelMeanGradient(yPlane, width, height)
        val blurVariance = laplacianVariance(yPlane, width, height)
        val dHash = computeDHash(yPlane, width, height)
        val hamming = previousDHash?.let { dhashHamming(dHash, it) }

        val hint = when {
            // Order matters: dark / too-bright frames have artificially
            // low Laplacian variance, so check luminance first.
            lumaMean < Thresholds.LUMA_MEAN_MIN -> OcrFrameMeta.QUALITY_TOO_DARK
            lumaMean > Thresholds.LUMA_MEAN_MAX && lumaVariance < 50.0 ->
                OcrFrameMeta.QUALITY_TOO_DARK // "too bright with no detail" reuses TOO_DARK
            blurVariance < Thresholds.BLUR_VARIANCE_MIN -> OcrFrameMeta.QUALITY_BLURRY
            sobelMean < Thresholds.SOBEL_MEAN_MIN -> OcrFrameMeta.QUALITY_BLURRY
            hamming != null && hamming <= Thresholds.DUPLICATE_HAMMING_MAX ->
                OcrFrameMeta.QUALITY_DUPLICATE
            else -> OcrFrameMeta.QUALITY_GOOD
        }

        return FrameQualityScore(
            hint = hint,
            blurVariance = blurVariance,
            lumaMean = lumaMean,
            sobelMean = sobelMean,
            dHash = dHash,
            hammingToPrevious = hamming,
        )
    }

    // ── Building blocks ──────────────────────────────────────────────────

    /**
     * Single-pass mean + variance over a Y plane.
     *
     * Returns (mean rounded to int, variance as double). The variance
     * is only used downstream to distinguish "flat overexposed" from
     * "high-contrast detail" cases.
     */
    internal fun lumaStats(yPlane: ByteArray): Pair<Int, Double> {
        var sum = 0L
        var sumSq = 0L
        val n = yPlane.size
        for (i in 0 until n) {
            val v = yPlane[i].toInt() and 0xFF
            sum += v
            sumSq += (v * v).toLong()
        }
        val mean = sum.toDouble() / n
        val variance = sumSq.toDouble() / n - mean * mean
        return mean.toInt() to variance
    }

    /**
     * Approximate Laplacian-variance as a blur metric.
     *
     * Standard 3x3 discrete Laplacian: ``4 * center - up - down - left
     * - right``. Variance of this convolution is OpenCV's go-to
     * "is the image sharp?" metric (papers as old as 2000). Higher =
     * sharper. We do not weight by neighbourhood size — the absolute
     * value is meaningful only relative to [Thresholds.BLUR_VARIANCE_MIN]
     * which is calibrated empirically.
     *
     * The 1-pixel border is ignored; that's <1% of a 540p frame and not
     * worth the branch.
     */
    internal fun laplacianVariance(yPlane: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val i = rowOffset + x
                val center = (yPlane[i].toInt() and 0xFF)
                val up = (yPlane[i - width].toInt() and 0xFF)
                val down = (yPlane[i + width].toInt() and 0xFF)
                val left = (yPlane[i - 1].toInt() and 0xFF)
                val right = (yPlane[i + 1].toInt() and 0xFF)
                val l = (4 * center - up - down - left - right).toDouble()
                sum += l
                sumSq += l * l
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    /**
     * Mean Sobel gradient magnitude — a cheap "is there text here?"
     * estimate.
     *
     * Computes ``|Gx| + |Gy|`` per pixel using the standard 3x3 Sobel
     * masks and averages over the interior. Text-bearing frames score
     * 20-60; blank surfaces score below 5; gradients-but-no-text
     * (sky, soft fabric) sit at 5-10 — hence
     * [Thresholds.SOBEL_MEAN_MIN].
     *
     * Returns 0.0 for frames smaller than 3x3.
     */
    internal fun sobelMeanGradient(yPlane: ByteArray, width: Int, height: Int): Double {
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

    /**
     * Compute an 8x8 dHash for near-duplicate detection.
     *
     * The image is bucketed into an 8x9 thumbnail (8 rows, 9 columns)
     * via averaging, then each row produces 8 bits: bit_i = 1 if
     * thumbnail[r, i] > thumbnail[r, i+1] else 0. 8 rows x 8 bits = 64
     * bit hash returned as ULong.
     *
     * Compared via Hamming distance (XOR + popcount). Hamming <= 5 of
     * 64 means "near-duplicate". This matches the canonical perceptual-
     * hash pipeline ([Krawetz 2011]).
     */
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
                thumb[r * cols + c] = boxAverage(yPlane, width, x0, y0, x1, y1)
            }
        }
        var hash = 0uL
        var bit = 63
        for (r in 0 until rows) {
            for (c in 0 until cols - 1) {
                val left = thumb[r * cols + c]
                val right = thumb[r * cols + c + 1]
                if (left > right) {
                    hash = hash or (1uL shl bit)
                }
                bit--
            }
        }
        return hash
    }

    private fun boxAverage(yPlane: ByteArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        if (x1 <= x0 || y1 <= y0) return 0
        var sum = 0
        var n = 0
        for (y in y0 until y1) {
            val rowOffset = y * width
            for (x in x0 until x1) {
                sum += yPlane[rowOffset + x].toInt() and 0xFF
                n++
            }
        }
        return if (n == 0) 0 else sum / n
    }

    /** Hamming distance between two 64-bit dHashes. */
    internal fun dhashHamming(a: ULong, b: ULong): Int =
        java.lang.Long.bitCount((a xor b).toLong())
}
