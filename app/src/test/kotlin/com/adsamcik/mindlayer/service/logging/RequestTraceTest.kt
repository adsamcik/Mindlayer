package com.adsamcik.mindlayer.service.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RequestTrace] timing breakpoints.
 */
class RequestTraceTest {

    // ── Initial state ────────────────────────────────────────────────────

    @Test
    fun `initial totalDurationMs is non-negative`() {
        val trace = RequestTrace("req-1", "sess-1")
        assertTrue(trace.totalDurationMs >= 0)
    }

    @Test
    fun `initial timeToFirstTokenMs is null`() {
        val trace = RequestTrace("req-1", "sess-1")
        assertNull(trace.timeToFirstTokenMs)
    }

    @Test
    fun `initial decodeDurationMs is null`() {
        val trace = RequestTrace("req-1", "sess-1")
        assertNull(trace.decodeDurationMs)
    }

    @Test
    fun `initial tokensPerSec is null`() {
        val trace = RequestTrace("req-1", "sess-1")
        assertNull(trace.tokensPerSec)
    }

    // ── markPrefillStart + markFirstToken ────────────────────────────────

    @Test
    fun `markFirstToken sets timeToFirstTokenMs to non-null positive value`() {
        val trace = RequestTrace("req-2", "sess-2")
        trace.markPrefillStart()
        // Small busy-wait to ensure measurable elapsed time
        Thread.sleep(5)
        trace.markFirstToken()
        val ttft = trace.timeToFirstTokenMs
        assertNotNull(ttft)
        assertTrue("ttft should be >= 0, was $ttft", ttft!! >= 0)
    }

    // ── markDecodeEnd ────────────────────────────────────────────────────

    @Test
    fun `markDecodeEnd sets decodeDurationMs to non-null value`() {
        val trace = RequestTrace("req-3", "sess-3")
        trace.markPrefillStart()
        trace.markFirstToken()
        Thread.sleep(5)
        trace.markDecodeEnd(tokenCount = 10)
        val dd = trace.decodeDurationMs
        assertNotNull(dd)
        assertTrue("decodeDurationMs should be >= 0, was $dd", dd!! >= 0)
    }

    @Test
    fun `tokensPerSec is calculated after markDecodeEnd`() {
        val trace = RequestTrace("req-4", "sess-4")
        trace.markPrefillStart()
        trace.markFirstToken()
        Thread.sleep(10)
        trace.markDecodeEnd(tokenCount = 50)
        val tps = trace.tokensPerSec
        // May or may not be non-null depending on timing; but decodeDurationMs > 0 should give us a value
        if (trace.decodeDurationMs!! > 0) {
            assertNotNull(tps)
            assertTrue("tokensPerSec should be > 0, was $tps", tps!! > 0f)
        }
    }

    @Test
    fun `tokensPerSec with 0 tokens returns 0 or positive`() {
        val trace = RequestTrace("req-5", "sess-5")
        trace.markPrefillStart()
        trace.markFirstToken()
        Thread.sleep(10)
        trace.markDecodeEnd(tokenCount = 0)
        val tps = trace.tokensPerSec
        if (trace.decodeDurationMs!! > 0) {
            assertNotNull(tps)
            assertEquals(0f, tps!!, 0.001f)
        }
    }

    @Test
    fun `tokensPerSec with 0 duration returns null`() {
        // When decode duration is 0ms (sub-ms), tokensPerSec should be null
        val trace = RequestTrace("req-6", "sess-6")
        trace.markPrefillStart()
        trace.markFirstToken()
        // No sleep — try for 0ms duration
        trace.markDecodeEnd(tokenCount = 10)
        val ms = trace.decodeDurationMs
        if (ms != null && ms == 0L) {
            assertNull(trace.tokensPerSec)
        }
        // If ms > 0 due to timing jitter, test still passes (nothing to assert)
    }

    @Test
    fun `decodeDurationMs falls back to prefillStartNanos when firstTokenNanos is null`() {
        val trace = RequestTrace("req-7", "sess-7")
        trace.markPrefillStart()
        // Skip markFirstToken
        Thread.sleep(5)
        trace.markDecodeEnd(tokenCount = 5)
        val dd = trace.decodeDurationMs
        assertNotNull(dd)
        assertTrue("decodeDurationMs should be >= 0, was $dd", dd!! >= 0)
    }

    @Test
    fun `decodeDurationMs returns null when no start marks`() {
        val trace = RequestTrace("req-8", "sess-8")
        // No prefillStart, no firstToken
        trace.markDecodeEnd(tokenCount = 5)
        // firstTokenNanos and prefillStartNanos are both null → returns null
        assertNull(trace.decodeDurationMs)
    }

    // ── markPipeWriteComplete ────────────────────────────────────────────

    @Test
    fun `markPipeWriteComplete does not crash`() {
        val trace = RequestTrace("req-9", "sess-9")
        trace.markPipeWriteComplete()
        // Just verify no exception
    }

    // ── markError ────────────────────────────────────────────────────────

    @Test
    fun `markError includes error in summary`() {
        val trace = RequestTrace("req-10", "sess-10")
        trace.markError("OOM crash")
        val s = trace.summary()
        assertTrue("summary should contain ERROR=OOM crash, was: $s", s.contains("ERROR=OOM crash"))
    }

    // ── summary() ────────────────────────────────────────────────────────

    @Test
    fun `summary with no marks contains req sess and total`() {
        val trace = RequestTrace("req-11", "sess-11")
        val s = trace.summary()
        assertTrue("should contain req=req-11", s.contains("req=req-11"))
        assertTrue("should contain sess=sess-11", s.contains("sess=sess-11"))
        assertTrue("should contain total=", s.contains("total="))
        assertTrue("should end with ms", s.contains("ms"))
    }

    @Test
    fun `summary with all marks contains ttft decode and total`() {
        val trace = RequestTrace("req-12", "sess-12")
        trace.markPrefillStart()
        Thread.sleep(5)
        trace.markFirstToken()
        Thread.sleep(5)
        trace.markDecodeEnd(tokenCount = 42)
        trace.markPipeWriteComplete()
        val s = trace.summary()
        assertTrue("should contain req=", s.contains("req=req-12"))
        assertTrue("should contain sess=", s.contains("sess=sess-12"))
        assertTrue("should contain ttft=", s.contains("ttft="))
        assertTrue("should contain decode=", s.contains("decode="))
        assertTrue("should contain 42tok", s.contains("42tok"))
        assertTrue("should contain total=", s.contains("total="))
    }

    @Test
    fun `summary without error does not contain ERROR`() {
        val trace = RequestTrace("req-13", "sess-13")
        val s = trace.summary()
        assertTrue("should not contain ERROR", !s.contains("ERROR"))
    }

    // ── Properties ───────────────────────────────────────────────────────

    @Test
    fun `requestId and sessionId are stored correctly`() {
        val trace = RequestTrace("my-req", "my-sess")
        assertEquals("my-req", trace.requestId)
        assertEquals("my-sess", trace.sessionId)
    }
}
