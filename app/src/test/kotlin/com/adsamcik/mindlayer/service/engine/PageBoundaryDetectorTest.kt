package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PageBoundaryDetector] — verifies the combined OR rule
 * (jaccard OR spatial OR gyro) gated by the N-frame stability window
 * and the reset-on-fire counter behaviour.
 */
class PageBoundaryDetectorTest {

    private fun line(text: String, bbox: FloatArray? = null) =
        OcrTextLine(text = text, confidence = OcrFieldFusion.Confidence.HIGH, boundingBox = bbox)

    private fun quad(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray =
        floatArrayOf(x1, y1, x2, y1, x2, y2, x1, y2)

    private fun configWith(
        stabilityFrames: Int = 3,
        jaccard: Double = 0.3,
        spatial: Double = 0.5,
        gyro: Double = 2.0,
    ) = PageBoundariesConfig(
        enabled = true,
        jaccardThreshold = jaccard,
        spatialThreshold = spatial,
        gyroThreshold = gyro,
        stabilityFrames = stabilityFrames,
    )

    /** Seeds [acc] with one frame so the detector has a baseline to compare against. */
    private fun seed(acc: PageAccumulator, lines: List<OcrTextLine>, frameId: Long = 0L) {
        acc.extend(lines, frameId)
    }

    // ── First-frame guard ──────────────────────────────────────────────

    @Test
    fun `disabled config never fires a boundary`() {
        val det = PageBoundaryDetector(PageBoundariesConfig.DISABLED)
        val acc = PageAccumulator(0)
        seed(acc, listOf(line("Alpha")))
        assertFalse(det.isBoundary(acc, listOf(line("Totally different")), ImuFrameMetadata.NONE))
    }

    @Test
    fun `empty accumulator never fires a boundary`() {
        val det = PageBoundaryDetector(configWith())
        val acc = PageAccumulator(0)
        // No seed; framesContributed == 0.
        assertFalse(det.isBoundary(acc, listOf(line("Totally different")), ImuFrameMetadata.NONE))
        assertEquals(0, det.consecutiveDifferentFrames)
    }

    // ── Single-signal fires ────────────────────────────────────────────

    @Test
    fun `same content frames never fire`() {
        val det = PageBoundaryDetector(configWith())
        val acc = PageAccumulator(0)
        val same = listOf(line("Welcome to the test"))
        seed(acc, same)
        repeat(10) {
            assertFalse(det.isBoundary(acc, same, ImuFrameMetadata.NONE))
            acc.extend(same, frameId = it.toLong())
        }
    }

    @Test
    fun `jaccard zero for N consecutive frames fires on the Nth`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3))
        val acc = PageAccumulator(0)
        seed(acc, listOf(line("Original chapter contents here")))

        val different = listOf(line("Pineapple stowaway concerto"))
        assertFalse("frame 1", det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        assertFalse("frame 2", det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        assertTrue("frame 3 fires", det.isBoundary(acc, different, ImuFrameMetadata.NONE))
    }

    @Test
    fun `gyro spike for N consecutive frames fires`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3, gyro = 2.0))
        val acc = PageAccumulator(0)
        // Same content → jaccard alone wouldn't fire, but gyro will.
        val sameContent = listOf(line("Receipt total"))
        seed(acc, sameContent)
        val spike = ImuFrameMetadata(gyroMaxRadPerS = 5f)
        assertFalse(det.isBoundary(acc, sameContent, spike))
        assertFalse(det.isBoundary(acc, sameContent, spike))
        assertTrue(det.isBoundary(acc, sameContent, spike))
    }

    @Test
    fun `gyro below threshold does not fire`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3, gyro = 2.0))
        val acc = PageAccumulator(0)
        val sameContent = listOf(line("Receipt total"))
        seed(acc, sameContent)
        val small = ImuFrameMetadata(gyroMaxRadPerS = 1.0f)
        repeat(5) { assertFalse(det.isBoundary(acc, sameContent, small)) }
    }

    @Test
    fun `spatial shift above threshold for N consecutive frames fires`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3, spatial = 0.5))
        val acc = PageAccumulator(0)
        // Seed at top-left.
        seed(acc, listOf(line("Header", bbox = quad(0f, 0f, 0.1f, 0.1f))))
        // New frame has same text but at bottom-right — centroid shift ~ 1.6 > 0.5.
        val shifted = listOf(line("Header", bbox = quad(0.9f, 0.9f, 1f, 1f)))
        assertFalse(det.isBoundary(acc, shifted, ImuFrameMetadata.NONE))
        assertFalse(det.isBoundary(acc, shifted, ImuFrameMetadata.NONE))
        assertTrue(det.isBoundary(acc, shifted, ImuFrameMetadata.NONE))
    }

    @Test
    fun `tiny spatial shift below threshold does not fire`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3, spatial = 0.5))
        val acc = PageAccumulator(0)
        seed(acc, listOf(line("Header", bbox = quad(0f, 0f, 0.1f, 0.1f))))
        val tinyShift = listOf(line("Header", bbox = quad(0.02f, 0.02f, 0.12f, 0.12f)))
        repeat(5) { assertFalse(det.isBoundary(acc, tinyShift, ImuFrameMetadata.NONE)) }
    }

    // ── Stability window ───────────────────────────────────────────────

    @Test
    fun `one-frame glitch surrounded by same content does not fire`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3))
        val acc = PageAccumulator(0)
        val same = listOf(line("Receipt totals"))
        val different = listOf(line("Map of the kingdom"))
        seed(acc, same)

        // Glitch: one "different" frame, then same content again.
        assertFalse(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        // Counter should be back to 0 after one same-content frame.
        assertFalse(det.isBoundary(acc, same, ImuFrameMetadata.NONE))
        assertEquals(0, det.consecutiveDifferentFrames)
        // Now two "different" frames again — still not at N=3.
        assertFalse(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        assertFalse(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
    }

    @Test
    fun `consecutive different streak then reset then different streak`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3))
        val acc = PageAccumulator(0)
        // Vocabularies must be fully disjoint after the MIN_TOKEN_LENGTH=3
        // filter; "Header A" vs "Header B" would both reduce to {header}.
        val same = listOf(line("foreword chapter prologue"))
        val different = listOf(line("entirely orthogonal vocabulary"))
        seed(acc, same)

        // Two different, then same → counter resets.
        assertFalse(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        assertFalse(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        assertFalse(det.isBoundary(acc, same, ImuFrameMetadata.NONE))
        assertEquals(0, det.consecutiveDifferentFrames)
        // Now need a fresh streak of 3.
        assertFalse(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        assertFalse(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
        assertTrue(det.isBoundary(acc, different, ImuFrameMetadata.NONE))
    }

    @Test
    fun `counter resets after firing so next boundary needs another N`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 3))
        val acc = PageAccumulator(0)
        val a = listOf(line("foreword chapter prologue"))
        val b = listOf(line("entirely orthogonal vocabulary"))
        seed(acc, a)

        // First boundary: 3 different frames.
        repeat(2) { assertFalse(det.isBoundary(acc, b, ImuFrameMetadata.NONE)) }
        assertTrue(det.isBoundary(acc, b, ImuFrameMetadata.NONE))
        assertEquals("counter resets after fire", 0, det.consecutiveDifferentFrames)

        // Immediate next call (still different) — must NOT fire yet.
        assertFalse(det.isBoundary(acc, b, ImuFrameMetadata.NONE))
        assertFalse(det.isBoundary(acc, b, ImuFrameMetadata.NONE))
        assertTrue("second boundary fires after fresh N", det.isBoundary(acc, b, ImuFrameMetadata.NONE))
    }

    // ── Combined OR rule ───────────────────────────────────────────────

    @Test
    fun `borderline jaccard plus gyro spike still fires on OR rule`() {
        val det = PageBoundaryDetector(
            configWith(stabilityFrames = 3, jaccard = 0.3, gyro = 2.0),
        )
        val acc = PageAccumulator(0)
        // Heavy overlap → jaccard above threshold; gyro spike pushes us over.
        val same = listOf(line("Alpha beta gamma delta epsilon"))
        seed(acc, same)
        val spike = ImuFrameMetadata(gyroMaxRadPerS = 4f)
        assertFalse(det.isBoundary(acc, same, spike))
        assertFalse(det.isBoundary(acc, same, spike))
        assertTrue(det.isBoundary(acc, same, spike))
    }

    @Test
    fun `stabilityFrames=1 fires on the first different frame`() {
        val det = PageBoundaryDetector(configWith(stabilityFrames = 1))
        val acc = PageAccumulator(0)
        seed(acc, listOf(line("Original content here")))
        assertTrue(det.isBoundary(acc, listOf(line("Totally new content")), ImuFrameMetadata.NONE))
    }

    // ── Jaccard helper edge cases ──────────────────────────────────────

    @Test
    fun `jaccard of empty empty is 1`() {
        assertEquals(1.0, PageBoundaryDetector.jaccard(emptySet(), emptySet()), 1e-9)
    }

    @Test
    fun `jaccard of empty and non-empty is 0`() {
        assertEquals(0.0, PageBoundaryDetector.jaccard(setOf("a"), emptySet()), 1e-9)
        assertEquals(0.0, PageBoundaryDetector.jaccard(emptySet(), setOf("a")), 1e-9)
    }

    @Test
    fun `jaccard of identical sets is 1`() {
        assertEquals(1.0, PageBoundaryDetector.jaccard(setOf("a", "b"), setOf("a", "b")), 1e-9)
    }

    @Test
    fun `jaccard of disjoint sets is 0`() {
        assertEquals(0.0, PageBoundaryDetector.jaccard(setOf("a"), setOf("b")), 1e-9)
    }

    @Test
    fun `jaccard of half overlap is one third`() {
        // {a,b} ∩ {b,c} = {b}; union = {a,b,c}; J = 1/3.
        assertEquals(1.0 / 3.0, PageBoundaryDetector.jaccard(setOf("a", "b"), setOf("b", "c")), 1e-9)
    }
}
