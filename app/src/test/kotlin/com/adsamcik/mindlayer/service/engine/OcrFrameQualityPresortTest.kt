package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrFrameMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for [OcrFrameQualityPresort].
 *
 * Pure JVM — no Robolectric, no Android dependencies. Each test
 * fabricates a synthetic Y-plane and asserts the resulting quality
 * verdict.
 *
 * The thresholds in [OcrFrameQualityPresort.Thresholds] are pinned
 * here so a future tuning pass cannot silently change behaviour.
 */
class OcrFrameQualityPresortTest {

    // ── Thresholds are wire-stable ──────────────────────────────────────

    @Test fun `BLUR_VARIANCE_MIN threshold is pinned at 150`() {
        assertEquals(150.0, OcrFrameQualityPresort.Thresholds.BLUR_VARIANCE_MIN, 0.0)
    }

    @Test fun `LUMA_MEAN bounds are pinned`() {
        assertEquals(25, OcrFrameQualityPresort.Thresholds.LUMA_MEAN_MIN)
        assertEquals(240, OcrFrameQualityPresort.Thresholds.LUMA_MEAN_MAX)
    }

    @Test fun `DUPLICATE_HAMMING_MAX is pinned at 5`() {
        assertEquals(5, OcrFrameQualityPresort.Thresholds.DUPLICATE_HAMMING_MAX)
    }

    @Test fun `SOBEL_MEAN_MIN is pinned`() {
        assertEquals(8.0, OcrFrameQualityPresort.Thresholds.SOBEL_MEAN_MIN, 0.0)
    }

    // ── Input validation ────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `score rejects zero width`() {
        OcrFrameQualityPresort.score(ByteArray(0), width = 0, height = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `score rejects mismatched dimensions`() {
        OcrFrameQualityPresort.score(ByteArray(100), width = 9, height = 10)
    }

    // ── Quality verdicts ────────────────────────────────────────────────

    @Test fun `a uniformly black frame is TOO_DARK`() {
        val w = 32; val h = 32
        val score = OcrFrameQualityPresort.score(ByteArray(w * h) { 0 }, w, h)
        assertEquals(OcrFrameMeta.QUALITY_TOO_DARK, score.hint)
    }

    @Test fun `a uniformly grey low-luma frame is TOO_DARK`() {
        val w = 32; val h = 32
        val score = OcrFrameQualityPresort.score(ByteArray(w * h) { 20 }, w, h)
        assertEquals(OcrFrameMeta.QUALITY_TOO_DARK, score.hint)
    }

    @Test fun `a uniformly grey mid-luma frame with no edges is BLURRY`() {
        val w = 32; val h = 32
        val score = OcrFrameQualityPresort.score(ByteArray(w * h) { 128.toByte() }, w, h)
        // Sobel and Laplacian are both zero on a uniform frame.
        assertEquals(OcrFrameMeta.QUALITY_BLURRY, score.hint)
        assertEquals(0.0, score.sobelMean, 0.01)
    }

    @Test fun `an unblurred high-contrast text-like frame is GOOD`() {
        val frame = textLikeFrame(width = 64, height = 64)
        val score = OcrFrameQualityPresort.score(frame, 64, 64)
        assertEquals(
            "Synthetic text-like frame must clear the GOOD bar (blur=${score.blurVariance}, sobel=${score.sobelMean})",
            OcrFrameMeta.QUALITY_GOOD,
            score.hint,
        )
        assertTrue(score.isAccepted)
    }

    @Test fun `a slightly noisy uniform frame is still BLURRY`() {
        // Tiny ±2 noise on top of mid-grey: text-density (Sobel) stays
        // well below SOBEL_MEAN_MIN, so the frame must NOT be GOOD.
        val w = 32; val h = 32
        val rng = Random(0)
        val frame = ByteArray(w * h) { ((128 + rng.nextInt(5) - 2) and 0xFF).toByte() }
        val score = OcrFrameQualityPresort.score(frame, w, h)
        assertFalse(
            "Low-amplitude noise frame must not be GOOD (sobel=${score.sobelMean}, blur=${score.blurVariance})",
            score.isAccepted,
        )
    }

    // ── dHash + duplicate detection ─────────────────────────────────────

    @Test fun `identical frames have hamming distance 0`() {
        val a = textLikeFrame(64, 64, seed = 1)
        val scoreA = OcrFrameQualityPresort.score(a, 64, 64)
        val scoreB = OcrFrameQualityPresort.score(a, 64, 64, previousDHash = scoreA.dHash)
        assertEquals(0, scoreB.hammingToPrevious)
        assertEquals(OcrFrameMeta.QUALITY_DUPLICATE, scoreB.hint)
    }

    @Test fun `unrelated frames are not duplicates`() {
        // Two genuinely-different text-like frames generated from
        // different seeds.
        val a = textLikeFrame(64, 64, seed = 1)
        val b = textLikeFrame(64, 64, seed = 2)
        val scoreA = OcrFrameQualityPresort.score(a, 64, 64)
        val scoreB = OcrFrameQualityPresort.score(b, 64, 64, previousDHash = scoreA.dHash)
        // We assert hamming > threshold rather than exact value to
        // tolerate RNG drift; threshold is 5/64.
        assertTrue(
            "Unrelated frames must have Hamming > ${OcrFrameQualityPresort.Thresholds.DUPLICATE_HAMMING_MAX} " +
                "(got ${scoreB.hammingToPrevious})",
            (scoreB.hammingToPrevious ?: 0) >
                OcrFrameQualityPresort.Thresholds.DUPLICATE_HAMMING_MAX,
        )
    }

    @Test fun `hammingToPrevious is null when no previous frame`() {
        val a = textLikeFrame(32, 32)
        val score = OcrFrameQualityPresort.score(a, 32, 32, previousDHash = null)
        assertNull(score.hammingToPrevious)
    }

    // ── Per-building-block tests ────────────────────────────────────────

    @Test fun `lumaStats of uniform frame returns that value with zero variance`() {
        val frame = ByteArray(100) { 100 }
        val (mean, variance) = OcrFrameQualityPresort.lumaStats(frame)
        assertEquals(100, mean)
        assertEquals(0.0, variance, 0.01)
    }

    @Test fun `laplacianVariance of uniform frame is zero`() {
        val v = OcrFrameQualityPresort.laplacianVariance(ByteArray(100) { 128.toByte() }, 10, 10)
        assertEquals(0.0, v, 0.01)
    }

    @Test fun `sobelMeanGradient of uniform frame is zero`() {
        val v = OcrFrameQualityPresort.sobelMeanGradient(ByteArray(100) { 128.toByte() }, 10, 10)
        assertEquals(0.0, v, 0.01)
    }

    @Test fun `sobelMeanGradient of vertical stripe pattern is non-zero`() {
        val w = 16; val h = 16
        val frame = ByteArray(w * h) { idx ->
            // Vertical stripes every 4 columns.
            val x = idx % w
            if ((x / 4) % 2 == 0) 30.toByte() else 200.toByte()
        }
        val v = OcrFrameQualityPresort.sobelMeanGradient(frame, w, h)
        assertTrue("Sobel must detect vertical stripes (got $v)", v > 10.0)
    }

    @Test fun `dhashHamming computes XOR popcount correctly`() {
        // 0xAAAA... vs 0x5555... differ in every bit -> 64.
        val a = 0xAAAAAAAAAAAAAAAAuL
        val b = 0x5555555555555555uL
        assertEquals(64, OcrFrameQualityPresort.dhashHamming(a, b))
        assertEquals(0, OcrFrameQualityPresort.dhashHamming(a, a))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Synthesizes a frame with a horizontal-stripe pattern that mimics
     * the gradient signature of a row of text. Used for "this should
     * be GOOD" assertions.
     */
    private fun textLikeFrame(width: Int, height: Int, seed: Int = 0): ByteArray {
        val rng = Random(seed)
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Alternating thick light + dark rows with high-freq
                // ink-style speckle so Laplacian variance is high.
                val rowBand = (y / 4) % 2
                val base = if (rowBand == 0) 235 else 25
                val jitter = if (rowBand == 0) rng.nextInt(-15, 16) else rng.nextInt(-10, 11)
                val v = (base + jitter).coerceIn(0, 255)
                out[y * width + x] = v.toByte()
            }
        }
        return out
    }
}
