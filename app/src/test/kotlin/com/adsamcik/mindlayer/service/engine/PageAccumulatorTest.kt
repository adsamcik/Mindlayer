package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PageAccumulator] — verifies line clustering by
 * normalised text + bounding-box IoU, best-reading selection, and the
 * derived helpers used by [PageBoundaryDetector] (token set, centroid).
 */
class PageAccumulatorTest {

    private fun line(
        text: String,
        confidence: OcrFieldFusion.Confidence = OcrFieldFusion.Confidence.HIGH,
        bbox: FloatArray? = null,
    ): OcrTextLine = OcrTextLine(text = text, confidence = confidence, boundingBox = bbox)

    /** Clockwise quad covering an axis-aligned rectangle. */
    private fun quad(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray =
        floatArrayOf(x1, y1, x2, y1, x2, y2, x1, y2)

    @Test
    fun `single frame produces one cluster per distinct line`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(
            listOf(
                line("Alpha", bbox = quad(0f, 0f, 0.5f, 0.1f)),
                line("Beta",  bbox = quad(0f, 0.2f, 0.5f, 0.3f)),
            ),
            frameId = 7L,
        )
        assertEquals(1, acc.framesContributed)
        assertEquals(2, acc.lineCount())
        assertEquals(7L, acc.triggerFrameId)
        val best = acc.bestLines()
        assertEquals(setOf("Alpha", "Beta"), best.map { it.text }.toSet())
    }

    @Test
    fun `same line seen across three frames picks highest confidence`() {
        val acc = PageAccumulator(pageIndex = 0)
        val box = quad(0f, 0f, 0.5f, 0.1f)
        acc.extend(listOf(line("Total 100", OcrFieldFusion.Confidence.LOW, box)), frameId = 1L)
        acc.extend(listOf(line("Total 100", OcrFieldFusion.Confidence.MEDIUM, box)), frameId = 2L)
        acc.extend(listOf(line("Total 100", OcrFieldFusion.Confidence.HIGH, box)), frameId = 3L)

        assertEquals(3, acc.framesContributed)
        assertEquals(1, acc.lineCount())
        val best = acc.bestLines().single()
        assertEquals("Total 100", best.text)
        assertEquals(OcrFieldFusion.Confidence.HIGH, best.confidence)
    }

    @Test
    fun `same-text different-bbox creates separate clusters`() {
        val acc = PageAccumulator(pageIndex = 0)
        // Two "0.00" lines on different rows — IoU = 0 → separate clusters.
        acc.extend(
            listOf(
                line("0.00", bbox = quad(0.0f, 0.0f, 0.2f, 0.05f)),
                line("0.00", bbox = quad(0.0f, 0.5f, 0.2f, 0.55f)),
            ),
            frameId = 1L,
        )
        assertEquals(2, acc.lineCount())
    }

    @Test
    fun `bbox jitter under threshold merges into same cluster`() {
        val acc = PageAccumulator(pageIndex = 0)
        // First frame: line at (0,0)-(0.5,0.1).
        acc.extend(listOf(line("Header", bbox = quad(0f, 0f, 0.5f, 0.1f))), frameId = 1L)
        // Second frame: same text, tiny jitter — IoU is comfortably > 0.5.
        acc.extend(listOf(line("Header", bbox = quad(0.01f, 0.005f, 0.51f, 0.105f))), frameId = 2L)
        assertEquals(1, acc.lineCount())
    }

    @Test
    fun `lines without bounding boxes cluster by text only`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(listOf(line("Receipt", OcrFieldFusion.Confidence.LOW)), frameId = 1L)
        acc.extend(listOf(line("Receipt", OcrFieldFusion.Confidence.HIGH)), frameId = 2L)
        assertEquals(1, acc.lineCount())
        assertEquals(OcrFieldFusion.Confidence.HIGH, acc.bestLines().single().confidence)
    }

    @Test
    fun `aggregate text is deterministic newline-joined`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(listOf(line("Alpha"), line("Beta"), line("Gamma")), frameId = 1L)
        assertEquals("Alpha\nBeta\nGamma", acc.aggregateText())
    }

    @Test
    fun `token set normalises and drops short tokens`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(listOf(line("Total Amount  Due 42.00")), frameId = 1L)
        val tokens = acc.tokenSet()
        assertTrue("expected total", "total" in tokens)
        assertTrue("expected amount", "amount" in tokens)
        assertTrue("expected due", "due" in tokens)
        assertTrue("expected 42.00", "42.00" in tokens)
        // length < 3 dropped
        assertTrue("no short tokens", tokens.none { it.length < 3 })
    }

    @Test
    fun `token set is cached and invalidated on extend`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(listOf(line("Hello world")), frameId = 1L)
        val firstCall = acc.tokenSet()
        val secondCall = acc.tokenSet()
        // Same instance → caching works.
        assertTrue("cache should return identical Set instance", firstCall === secondCall)
        acc.extend(listOf(line("Goodbye world")), frameId = 2L)
        val afterExtend = acc.tokenSet()
        assertTrue("cache should be invalidated", afterExtend !== firstCall)
        assertTrue("hello" in afterExtend)
        assertTrue("goodbye" in afterExtend)
        assertTrue("world" in afterExtend)
    }

    @Test
    fun `last frame centroid tracks most recent frame`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(listOf(line("A", bbox = quad(0f, 0f, 0.2f, 0.1f))), frameId = 1L)
        val first = acc.lastFrameBboxCentroid
        assertNotNull(first)

        acc.extend(listOf(line("B", bbox = quad(0.8f, 0.8f, 1f, 0.9f))), frameId = 2L)
        val second = acc.lastFrameBboxCentroid!!
        // Centroid clearly moved to the bottom-right.
        assertTrue(second.x > 0.5)
        assertTrue(second.y > 0.5)
    }

    @Test
    fun `last frame centroid persists when latest frame has no bboxes`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(listOf(line("A", bbox = quad(0f, 0f, 0.2f, 0.1f))), frameId = 1L)
        val first = acc.lastFrameBboxCentroid!!
        acc.extend(listOf(line("A")), frameId = 2L) // no bbox
        assertEquals(first, acc.lastFrameBboxCentroid)
    }

    @Test
    fun `empty accumulator returns null centroid and empty token set`() {
        val acc = PageAccumulator(pageIndex = 0)
        assertNull(acc.lastFrameBboxCentroid)
        assertEquals(emptySet<String>(), acc.tokenSet())
        assertEquals(0, acc.framesContributed)
        assertEquals(0, acc.lineCount())
        assertEquals("", acc.aggregateText())
    }

    @Test
    fun `pageIndex round-trips through accessor`() {
        val acc = PageAccumulator(pageIndex = 7)
        assertEquals(7, acc.pageIndex)
    }

    @Test
    fun `triggerFrameId is set from first extend only`() {
        val acc = PageAccumulator(pageIndex = 0)
        acc.extend(listOf(line("X")), frameId = 100L)
        acc.extend(listOf(line("Y")), frameId = 200L)
        assertEquals("trigger should remain first frame id", 100L, acc.triggerFrameId)
    }
}
