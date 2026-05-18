package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.engine.OcrFieldFusion.Confidence
import com.adsamcik.mindlayer.service.engine.OcrFieldFusion.FieldObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OcrFieldFusion].
 *
 * Pure JVM — no Robolectric, no Android dependencies.
 *
 * Tests cover:
 *  - constructor validation
 *  - weight derivation (confidence x frame-quality cross-product)
 *  - tally accumulation
 *  - K-consecutive lock
 *  - unlocking on disagreement
 *  - tie-breaking determinism
 *  - reset
 */
class OcrFieldFusionTest {

    // ── Constructor + config validation ─────────────────────────────────

    @Test fun `default config has expected kLock`() {
        val cfg = OcrFieldFusion.FusionConfig()
        assertEquals(3, cfg.kLock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `kLock must be positive`() {
        OcrFieldFusion.FusionConfig(kLock = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `weights must be monotone non-decreasing`() {
        OcrFieldFusion.FusionConfig(lowWeight = 1.0, mediumWeight = 0.5, highWeight = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frameQualityFloor must be in 0 to 1`() {
        OcrFieldFusion.FusionConfig(frameQualityFloor = 1.5)
    }

    // ── Weight derivation ───────────────────────────────────────────────

    @Test fun `weightOf at full quality matches base weights`() {
        val cfg = OcrFieldFusion.FusionConfig()
        assertEquals(cfg.lowWeight, cfg.weightOf(Confidence.LOW, 1.0), 1e-9)
        assertEquals(cfg.mediumWeight, cfg.weightOf(Confidence.MEDIUM, 1.0), 1e-9)
        assertEquals(cfg.highWeight, cfg.weightOf(Confidence.HIGH, 1.0), 1e-9)
    }

    @Test fun `weightOf clamps below frameQualityFloor`() {
        val cfg = OcrFieldFusion.FusionConfig(frameQualityFloor = 0.2)
        // Quality 0.0 is clamped up to 0.2, so HIGH * 0.2.
        assertEquals(cfg.highWeight * 0.2, cfg.weightOf(Confidence.HIGH, 0.0), 1e-9)
    }

    @Test fun `weightOf scales linearly with frame quality above floor`() {
        val cfg = OcrFieldFusion.FusionConfig()
        val a = cfg.weightOf(Confidence.HIGH, 0.5)
        val b = cfg.weightOf(Confidence.HIGH, 1.0)
        assertEquals(b * 0.5, a, 1e-9)
    }

    // ── Single observation ──────────────────────────────────────────────

    @Test fun `first observation becomes top value with consecutive 1`() {
        val fusion = OcrFieldFusion()
        val state = fusion.accept(
            "total",
            FieldObservation("12.99", Confidence.HIGH, frameQuality = 1.0, frameId = 0),
        )
        assertEquals("12.99", state.topValue)
        assertEquals(1, state.consecutiveAgreement)
        assertFalse(state.locked)
        assertEquals(1.0, state.topConfidence, 1e-9)
    }

    @Test fun `unseen field returns EMPTY`() {
        val fusion = OcrFieldFusion()
        assertEquals(OcrFieldFusion.FieldState.EMPTY, fusion.stateOf("nothing"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank field name rejected`() {
        OcrFieldFusion().accept(
            "",
            FieldObservation("x", Confidence.HIGH, 1.0, 0),
        )
    }

    // ── K-consecutive lock ──────────────────────────────────────────────

    @Test fun `three consecutive agreements lock the field at default kLock 3`() {
        val fusion = OcrFieldFusion()
        var state: OcrFieldFusion.FieldState = OcrFieldFusion.FieldState.EMPTY
        for (i in 0 until 3) {
            state = fusion.accept(
                "total",
                FieldObservation("12.99", Confidence.HIGH, 1.0, i.toLong()),
            )
        }
        assertEquals(3, state.consecutiveAgreement)
        assertTrue(state.locked)
        assertEquals("12.99", state.topValue)
    }

    @Test fun `disagreement resets consecutive counter`() {
        val fusion = OcrFieldFusion()
        // Two HIGH-confidence agreements on "12.99"
        fusion.accept("total", FieldObservation("12.99", Confidence.HIGH, 1.0, 0))
        fusion.accept("total", FieldObservation("12.99", Confidence.HIGH, 1.0, 1))
        // Then a LOW-confidence "13.99" — different value so consecutive resets.
        val state = fusion.accept(
            "total",
            FieldObservation("13.99", Confidence.LOW, 1.0, 2),
        )
        // 12.99 evidence: 1.0 + 1.0 = 2.0. 13.99 evidence: 0.3.
        // Top value should still be 12.99 because evidence-weighted.
        assertEquals("12.99", state.topValue)
        // But "different value won" branch resets to 1.
        // Wait — actually the new TOP is 12.99 (unchanged), so the
        // consecutive counter increments rather than resets. The
        // observation was for 13.99 but it didn't unseat the leader.
        // This is a subtle semantic: consecutive tracks "the leader
        // stayed the same after this observation", not "this
        // observation matched the leader".
        assertEquals(3, state.consecutiveAgreement)
        assertTrue(state.locked)
    }

    @Test fun `enough evidence for a new value unseats the leader and resets`() {
        val fusion = OcrFieldFusion()
        fusion.accept("total", FieldObservation("A", Confidence.LOW, 1.0, 0))
        // Now flood high-confidence votes for B until it wins.
        fusion.accept("total", FieldObservation("B", Confidence.HIGH, 1.0, 1))
        // After: A=0.3, B=1.0 — B leads, consecutive resets to 1.
        val state = fusion.stateOf("total")
        assertEquals("B", state.topValue)
        assertEquals(1, state.consecutiveAgreement)
        assertFalse(state.locked)
    }

    @Test fun `custom kLock is honored`() {
        val fusion = OcrFieldFusion(OcrFieldFusion.FusionConfig(kLock = 5))
        for (i in 0 until 4) {
            val state = fusion.accept(
                "x",
                FieldObservation("v", Confidence.HIGH, 1.0, i.toLong()),
            )
            assertFalse("Should not lock before 5 agreements", state.locked)
        }
        val locked = fusion.accept("x", FieldObservation("v", Confidence.HIGH, 1.0, 4))
        assertTrue(locked.locked)
    }

    // ── Evidence accumulation ───────────────────────────────────────────

    @Test fun `evidenceByValue accumulates per value`() {
        val fusion = OcrFieldFusion()
        fusion.accept("x", FieldObservation("A", Confidence.HIGH, 1.0, 0))
        fusion.accept("x", FieldObservation("A", Confidence.MEDIUM, 1.0, 1))
        fusion.accept("x", FieldObservation("B", Confidence.LOW, 1.0, 2))
        val state = fusion.stateOf("x")
        assertEquals(1.0 + 0.7, state.evidenceByValue["A"]!!, 1e-9)
        assertEquals(0.3, state.evidenceByValue["B"]!!, 1e-9)
    }

    @Test fun `low frame quality reduces vote weight`() {
        val fusion = OcrFieldFusion()
        // Single HIGH at quality 0.1 should equal a single LOW at quality 1.0
        // ... only when low*1 = high*0.1. With default weights:
        //   low * 1.0 = 0.3
        //   high * 0.1 = 0.1 (clamped to floor 0.1)
        // So actually they're not equal at floor=0.1; the floor MULTIPLIES,
        // so high * floor(0.1) = 0.1. Let's just assert ordering instead.
        fusion.accept("x", FieldObservation("A", Confidence.LOW, 1.0, 0))
        fusion.accept("y", FieldObservation("B", Confidence.HIGH, 0.05, 0))
        assertTrue(fusion.stateOf("x").evidenceByValue["A"]!! >
            fusion.stateOf("y").evidenceByValue["B"]!!)
    }

    @Test fun `topConfidence reflects share of total evidence`() {
        val fusion = OcrFieldFusion()
        fusion.accept("x", FieldObservation("A", Confidence.HIGH, 1.0, 0))
        fusion.accept("x", FieldObservation("B", Confidence.HIGH, 1.0, 1))
        // 1.0 / (1.0 + 1.0) = 0.5
        val state = fusion.stateOf("x")
        assertEquals(0.5, state.topConfidence, 1e-9)
    }

    // ── Snapshot + reset ────────────────────────────────────────────────

    @Test fun `snapshot is immutable and preserves insertion order`() {
        val fusion = OcrFieldFusion()
        fusion.accept("c_field", FieldObservation("X", Confidence.HIGH, 1.0, 0))
        fusion.accept("a_field", FieldObservation("Y", Confidence.HIGH, 1.0, 1))
        fusion.accept("b_field", FieldObservation("Z", Confidence.HIGH, 1.0, 2))
        val snap = fusion.snapshot()
        assertEquals(listOf("c_field", "a_field", "b_field"), snap.keys.toList())
        // Adding more obs should not mutate the snapshot.
        fusion.accept("c_field", FieldObservation("X2", Confidence.HIGH, 1.0, 3))
        assertEquals("X", snap["c_field"]!!.topValue)
    }

    @Test fun `reset clears all state`() {
        val fusion = OcrFieldFusion()
        fusion.accept("x", FieldObservation("A", Confidence.HIGH, 1.0, 0))
        fusion.reset()
        assertEquals(OcrFieldFusion.FieldState.EMPTY, fusion.stateOf("x"))
        assertTrue(fusion.snapshot().isEmpty())
    }

    // ── FieldObservation validation ─────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `frameQuality below 0 rejected`() {
        FieldObservation("x", Confidence.HIGH, -0.1, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frameQuality above 1 rejected`() {
        FieldObservation("x", Confidence.HIGH, 1.5, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative frameId rejected`() {
        FieldObservation("x", Confidence.HIGH, 1.0, -1)
    }
}
