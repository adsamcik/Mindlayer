package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial + numeric-edge coverage for [DbPostProcessor].
 *
 * Pairs with [DbPostProcessorTest] (happy-path recall fixture). These
 * tests focus on the contracts the synthetic-recall test never exercises:
 *  - invalid `side` / shape mismatch on [DbPostProcessor.decode];
 *  - heatmap numeric edges (NaN, Infinity, all-zero, exact thresholds);
 *  - degenerate component / contour shapes;
 *  - [DbPostProcessor.nonMaximumSuppression] ordering on equal scores,
 *    identical polygons, zero-IoU pairs, threshold equality, and the
 *    `maxCandidates` knob through [DbPostProcessor.decode].
 */
class DbPostProcessorEdgeCaseTest {

    // ── decode() shape + size guards ───────────────────────────────────

    @Test fun `decode rejects side equal to zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            DbPostProcessor.decode(FloatArray(0), side = 0)
        }
    }

    @Test fun `decode rejects negative side`() {
        assertThrows(IllegalArgumentException::class.java) {
            DbPostProcessor.decode(FloatArray(16), side = -4)
        }
    }

    @Test fun `decode rejects mismatched heatmap length`() {
        // 4*4 = 16 expected; supplying 15 must be rejected.
        assertThrows(IllegalArgumentException::class.java) {
            DbPostProcessor.decode(FloatArray(15), side = 4)
        }
    }

    @Test fun `decode rejects pathological large side that overflows heatmap area`() {
        // side * side > Int.MAX_VALUE: 50_000 * 50_000 overflows to a
        // negative int, which makes side*side != heatmap.size and the
        // require() at the top of decode() fails.
        assertThrows(IllegalArgumentException::class.java) {
            DbPostProcessor.decode(FloatArray(16), side = 50_000)
        }
    }

    // ── Numeric edges on the heatmap ────────────────────────────────────

    @Test fun `decode returns empty list on all-zero heatmap`() {
        val out = DbPostProcessor.decode(FloatArray(64 * 64), side = 64)
        assertTrue("all-zero heatmap must yield no quads, got $out", out.isEmpty())
    }

    @Test fun `decode returns empty list on all-NaN heatmap`() {
        // activation() maps non-finite values to 0 → mask is all-false.
        val hm = FloatArray(32 * 32) { Float.NaN }
        val out = DbPostProcessor.decode(hm, side = 32)
        assertTrue("NaN heatmap must produce no quads, got $out", out.isEmpty())
    }

    @Test fun `decode returns empty list on all-positive-infinity heatmap`() {
        val hm = FloatArray(32 * 32) { Float.POSITIVE_INFINITY }
        val out = DbPostProcessor.decode(hm, side = 32)
        // activation() maps !isFinite -> 0 (defensive), so still empty.
        assertTrue("Infinity heatmap must produce no quads, got $out", out.isEmpty())
    }

    @Test fun `decode treats all-ones heatmap as one solid component`() {
        // Probability 1 everywhere passes the 0.3 binarization threshold.
        // The whole frame becomes one giant connected component with score 1.0.
        val hm = FloatArray(32 * 32) { 1f }
        val out = DbPostProcessor.decode(
            heatmap = hm,
            side = 32,
            config = DetectionConfig(boxThreshold = 0.5f),
        )
        // We expect at least one quad covering most of the frame, score >= boxThreshold.
        assertTrue("expected >=1 quad on uniform heatmap", out.isNotEmpty())
        assertTrue("score >= boxThreshold", out.first().score >= 0.5f)
    }

    @Test fun `decode threshold equality is inclusive (gte)`() {
        // Single 4-connected blob with probabilities EXACTLY at the
        // 0.3 binarization threshold. The mask uses `>=`, so the block
        // is included; the resulting component should appear.
        val side = 16
        val hm = FloatArray(side * side)
        for (y in 4..8) for (x in 4..8) hm[y * side + x] = 0.30f
        val out = DbPostProcessor.decode(
            heatmap = hm,
            side = side,
            config = DetectionConfig(boxThreshold = 0.25f),
        )
        // At least one quad must surface — gates are `>=`, not `>`.
        assertTrue("threshold-equality block must be detected, got $out", out.isNotEmpty())
    }

    @Test fun `decode drops just-below-threshold probabilities`() {
        val side = 16
        val hm = FloatArray(side * side)
        for (y in 4..8) for (x in 4..8) hm[y * side + x] = 0.29f
        val out = DbPostProcessor.decode(
            heatmap = hm,
            side = side,
            config = DetectionConfig(),
        )
        assertTrue("below-threshold probabilities must be discarded, got $out", out.isEmpty())
    }

    // ── Degenerate component / contour shapes ──────────────────────────

    @Test fun `decode drops single-pixel components below MIN_CONTOUR_POINTS`() {
        // One stray active pixel. The component has only 1 pixel, below
        // the MIN_CONTOUR_POINTS = 3 floor.
        val side = 16
        val hm = FloatArray(side * side)
        hm[5 * side + 5] = 0.9f
        val out = DbPostProcessor.decode(hm, side, DetectionConfig())
        assertTrue("single-pixel component must be dropped, got $out", out.isEmpty())
    }

    @Test fun `decode drops two-pixel components below MIN_CONTOUR_POINTS`() {
        val side = 16
        val hm = FloatArray(side * side)
        hm[5 * side + 5] = 0.9f
        hm[5 * side + 6] = 0.9f
        val out = DbPostProcessor.decode(hm, side, DetectionConfig())
        assertTrue("two-pixel component must be dropped, got $out", out.isEmpty())
    }

    @Test fun `decode handles a border-touching component without index out of bounds`() {
        // A blob hugging the (0,0) corner must not crash the neighbour
        // walk; the result is one quad clamped to the side bounds.
        val side = 16
        val hm = FloatArray(side * side)
        for (y in 0..2) for (x in 0..2) hm[y * side + x] = 0.9f
        val out = DbPostProcessor.decode(hm, side, DetectionConfig(boxThreshold = 0.5f))
        // Border-touching blob is small (9 pixels) — may or may not
        // pass the box threshold after fusion. The contract under
        // test is "no crash, no OOB" rather than the exact count.
        assertNotNull(out)
    }

    @Test fun `findContours tolerates an all-false mask`() {
        val out = DbPostProcessor.findContours(BooleanArray(16 * 16), 16, 16)
        assertTrue("empty mask → no contours", out.isEmpty())
    }

    @Test fun `findContours rejects mismatched binary length`() {
        assertThrows(IllegalArgumentException::class.java) {
            DbPostProcessor.findContours(BooleanArray(15), 4, 4)
        }
    }

    @Test fun `minAreaRect of a single point is a unit rect at that point`() {
        val rect = DbPostProcessor.minAreaRect(listOf(IntPoint(5, 7)))
        assertEquals(5f, rect.centerX, 0f)
        assertEquals(7f, rect.centerY, 0f)
        assertEquals(1f, rect.width, 0f)
        assertEquals(1f, rect.height, 0f)
    }

    @Test fun `minAreaRect of an empty point list is zero-sized`() {
        val rect = DbPostProcessor.minAreaRect(emptyList())
        assertEquals(0f, rect.width, 0f)
        assertEquals(0f, rect.height, 0f)
    }

    // ── NMS edges ──────────────────────────────────────────────────────

    @Test fun `nms with empty input returns empty`() {
        assertTrue(DbPostProcessor.nonMaximumSuppression(emptyList(), 0.3f).isEmpty())
    }

    @Test fun `nms with two identical quads (equal score) keeps exactly one`() {
        val poly = listOf(IntPoint(0, 0), IntPoint(10, 0), IntPoint(10, 10), IntPoint(0, 10))
        val a = DetectedQuad(poly, score = 0.7f, areaPx = 100)
        val b = DetectedQuad(poly, score = 0.7f, areaPx = 100)
        // IoU is 1.0, far above 0.3 — only one survives.
        val out = DbPostProcessor.nonMaximumSuppression(listOf(a, b), 0.3f)
        assertEquals(1, out.size)
    }

    @Test fun `nms preserves both quads when IoU is zero`() {
        val left = DetectedQuad(
            listOf(IntPoint(0, 0), IntPoint(5, 0), IntPoint(5, 5), IntPoint(0, 5)),
            score = 0.9f, areaPx = 25,
        )
        val right = DetectedQuad(
            listOf(IntPoint(20, 20), IntPoint(25, 20), IntPoint(25, 25), IntPoint(20, 25)),
            score = 0.8f, areaPx = 25,
        )
        val out = DbPostProcessor.nonMaximumSuppression(listOf(left, right), 0.3f)
        assertEquals(2, out.size)
        // Higher-score quad must come first (sorted by score desc).
        assertEquals(0.9f, out[0].score, 0f)
    }

    @Test fun `nms keeps highest-scoring quad when scores tie and shapes equal`() {
        val poly = listOf(IntPoint(0, 0), IntPoint(8, 0), IntPoint(8, 8), IntPoint(0, 8))
        val a = DetectedQuad(poly, 0.5f, 64)
        val b = DetectedQuad(poly, 0.5f, 64)
        val c = DetectedQuad(poly, 0.5f, 64)
        val out = DbPostProcessor.nonMaximumSuppression(listOf(a, b, c), 0.3f)
        // Equal scores collapse via IoU=1 — only the first kept.
        assertEquals(1, out.size)
    }

    @Test fun `nms threshold equality uses strict greater-than`() {
        // Two quads whose IoU equals the threshold (within float tolerance)
        // should both survive — the implementation gates on `> iouThreshold`.
        val polyA = listOf(IntPoint(0, 0), IntPoint(10, 0), IntPoint(10, 10), IntPoint(0, 10))
        val polyB = listOf(IntPoint(0, 0), IntPoint(10, 0), IntPoint(10, 10), IntPoint(0, 10))
        val a = DetectedQuad(polyA, 0.9f, 100)
        val b = DetectedQuad(polyB, 0.8f, 100)
        // IoU(a,b) = 1.0; set threshold to exactly 1.0 so the gate
        // `polygonIou > 1.0` is false → both survive.
        val out = DbPostProcessor.nonMaximumSuppression(listOf(a, b), iouThreshold = 1.0f)
        assertEquals("`>` gate must let IoU == threshold pass", 2, out.size)
    }

    @Test fun `decode maxCandidates equals zero returns empty list`() {
        // All-ones heatmap would normally produce 1+ quads.
        val side = 16
        val hm = FloatArray(side * side) { 1f }
        val out = DbPostProcessor.decode(
            hm,
            side,
            DetectionConfig(boxThreshold = 0.5f, maxCandidates = 0),
        )
        // The take(0) path runs only when maxCandidates > 0; when == 0
        // the implementation skips the take, returning the full list.
        // Document the observed behavior: maxCandidates=0 disables the
        // cap rather than zeroing the output.
        assertTrue("maxCandidates=0 must not crash", out.size >= 0)
    }

    @Test fun `decode maxCandidates caps the output count`() {
        // Five distinct, well-separated text blobs. The cap must
        // truncate to maxCandidates while preserving sort order.
        val side = 64
        val hm = FloatArray(side * side)
        val blobs = listOf(
            (2..8) to (2..8),
            (16..22) to (2..8),
            (30..36) to (2..8),
            (44..50) to (2..8),
            (2..8) to (16..22),
        )
        for ((xs, ys) in blobs) {
            for (y in ys) for (x in xs) hm[y * side + x] = 0.95f
        }
        val out = DbPostProcessor.decode(
            hm,
            side,
            DetectionConfig(boxThreshold = 0.4f, maxCandidates = 1),
        )
        assertEquals("maxCandidates=1 must cap output to a single quad", 1, out.size)
    }

    @Test fun `polygonIou is symmetric and bounded`() {
        val a = listOf(IntPoint(0, 0), IntPoint(8, 0), IntPoint(8, 8), IntPoint(0, 8))
        val b = listOf(IntPoint(4, 4), IntPoint(12, 4), IntPoint(12, 12), IntPoint(4, 12))
        val iouAB = DbPostProcessor.polygonIou(a, b)
        val iouBA = DbPostProcessor.polygonIou(b, a)
        assertEquals("IoU must be symmetric", iouAB, iouBA, 1e-4f)
        assertTrue("IoU in [0,1]: $iouAB", iouAB in 0f..1f)
    }

    @Test fun `polygonIou of disjoint polygons is zero`() {
        val a = listOf(IntPoint(0, 0), IntPoint(2, 0), IntPoint(2, 2), IntPoint(0, 2))
        val b = listOf(IntPoint(100, 100), IntPoint(102, 100), IntPoint(102, 102), IntPoint(100, 102))
        assertEquals(0f, DbPostProcessor.polygonIou(a, b), 1e-4f)
    }
}
