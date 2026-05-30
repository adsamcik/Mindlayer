package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PageBoundariesConfig.parse] — the optionsJson envelope
 * is user-supplied / SDK-supplied JSON, so its parser must be totally
 * non-throwing and degrade gracefully to the documented defaults.
 */
class PageBoundariesConfigTest {

    // ── Off-path / DISABLED guarantees ──────────────────────────────────

    @Test
    fun `null optionsJson returns DISABLED`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse(null))
    }

    @Test
    fun `blank optionsJson returns DISABLED`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("   "))
    }

    @Test
    fun `non-object optionsJson returns DISABLED`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("\"not-an-object\""))
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("42"))
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("[1,2,3]"))
    }

    @Test
    fun `malformed JSON returns DISABLED without throwing`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("{not json"))
    }

    @Test
    fun `missing pageBoundaries block returns DISABLED`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("""{"otherFeature":true}"""))
    }

    @Test
    fun `pageBoundaries block of wrong type returns DISABLED`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("""{"pageBoundaries":"yes please"}"""))
    }

    @Test
    fun `missing enabled defaults to DISABLED`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("""{"pageBoundaries":{"jaccardThreshold":0.5}}"""))
    }

    @Test
    fun `explicit enabled false returns DISABLED`() {
        assertSame(PageBoundariesConfig.DISABLED, PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":false}}"""))
    }

    // ── On-path / round-trip ────────────────────────────────────────────

    @Test
    fun `enabled true with no other fields uses documented defaults`() {
        val cfg = PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":true}}""")
        assertTrue("expected enabled", cfg.enabled)
        assertEquals(PageBoundariesConfig.DEFAULT_JACCARD_THRESHOLD, cfg.jaccardThreshold, 1e-9)
        assertEquals(PageBoundariesConfig.DEFAULT_SPATIAL_THRESHOLD, cfg.spatialThreshold, 1e-9)
        assertEquals(PageBoundariesConfig.DEFAULT_GYRO_THRESHOLD, cfg.gyroThreshold, 1e-9)
        assertEquals(PageBoundariesConfig.DEFAULT_STABILITY_FRAMES, cfg.stabilityFrames)
        assertFalse("default llmExtractPerPage", cfg.llmExtractPerPage)
        assertTrue("default llmExtractFinal", cfg.llmExtractFinal)
    }

    @Test
    fun `round-trip parses every field`() {
        val cfg = PageBoundariesConfig.parse(
            """
            {
              "pageBoundaries": {
                "enabled": true,
                "jaccardThreshold": 0.42,
                "spatialThreshold": 0.7,
                "gyroThreshold": 3.5,
                "stabilityFrames": 5,
                "llmExtractPerPage": true,
                "llmExtractFinal": false
              }
            }
            """.trimIndent()
        )
        assertTrue(cfg.enabled)
        assertEquals(0.42, cfg.jaccardThreshold, 1e-9)
        assertEquals(0.7, cfg.spatialThreshold, 1e-9)
        assertEquals(3.5, cfg.gyroThreshold, 1e-9)
        assertEquals(5, cfg.stabilityFrames)
        assertTrue(cfg.llmExtractPerPage)
        assertFalse(cfg.llmExtractFinal)
    }

    // ── Clamping ────────────────────────────────────────────────────────

    @Test
    fun `over-range jaccardThreshold is clamped`() {
        val high = PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":true,"jaccardThreshold":7.5}}""")
        assertEquals(PageBoundariesConfig.MAX_JACCARD_THRESHOLD, high.jaccardThreshold, 1e-9)
        val low = PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":true,"jaccardThreshold":-0.5}}""")
        assertEquals(PageBoundariesConfig.MIN_JACCARD_THRESHOLD, low.jaccardThreshold, 1e-9)
    }

    @Test
    fun `over-range spatialThreshold is clamped`() {
        val cfg = PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":true,"spatialThreshold":99.0}}""")
        assertEquals(PageBoundariesConfig.MAX_SPATIAL_THRESHOLD, cfg.spatialThreshold, 1e-9)
    }

    @Test
    fun `over-range gyroThreshold is clamped`() {
        val cfg = PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":true,"gyroThreshold":250.0}}""")
        assertEquals(PageBoundariesConfig.MAX_GYRO_THRESHOLD, cfg.gyroThreshold, 1e-9)
    }

    @Test
    fun `over-range stabilityFrames is clamped`() {
        val high = PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":true,"stabilityFrames":500}}""")
        assertEquals(PageBoundariesConfig.MAX_STABILITY_FRAMES, high.stabilityFrames)
        val low = PageBoundariesConfig.parse("""{"pageBoundaries":{"enabled":true,"stabilityFrames":0}}""")
        assertEquals(PageBoundariesConfig.MIN_STABILITY_FRAMES, low.stabilityFrames)
    }

    // ── Per-field type tolerance ────────────────────────────────────────

    @Test
    fun `string-typed numeric fields fall back to defaults`() {
        val cfg = PageBoundariesConfig.parse(
            """{"pageBoundaries":{"enabled":true,"jaccardThreshold":"oops","stabilityFrames":"five"}}"""
        )
        assertTrue(cfg.enabled)
        assertEquals(PageBoundariesConfig.DEFAULT_JACCARD_THRESHOLD, cfg.jaccardThreshold, 1e-9)
        assertEquals(PageBoundariesConfig.DEFAULT_STABILITY_FRAMES, cfg.stabilityFrames)
    }

    @Test
    fun `null-typed fields fall back to defaults`() {
        val cfg = PageBoundariesConfig.parse(
            """{"pageBoundaries":{"enabled":true,"jaccardThreshold":null,"gyroThreshold":null,"llmExtractPerPage":null}}"""
        )
        assertTrue(cfg.enabled)
        assertEquals(PageBoundariesConfig.DEFAULT_JACCARD_THRESHOLD, cfg.jaccardThreshold, 1e-9)
        assertEquals(PageBoundariesConfig.DEFAULT_GYRO_THRESHOLD, cfg.gyroThreshold, 1e-9)
        assertFalse(cfg.llmExtractPerPage)
    }

    // ── Forward-compat ──────────────────────────────────────────────────

    @Test
    fun `unknown keys at root are ignored`() {
        val cfg = PageBoundariesConfig.parse(
            """{"someV0_10Knob":42,"pageBoundaries":{"enabled":true}}"""
        )
        assertTrue(cfg.enabled)
    }

    @Test
    fun `unknown keys inside pageBoundaries are ignored`() {
        val cfg = PageBoundariesConfig.parse(
            """{"pageBoundaries":{"enabled":true,"jaccardThreshold":0.4,"futureKnob":true,"anotherFuture":"abc"}}"""
        )
        assertTrue(cfg.enabled)
        assertEquals(0.4, cfg.jaccardThreshold, 1e-9)
    }
}
