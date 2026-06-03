package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `allows requests up to burst capacity once bucket fills`() {
        val clock = FakeClock()
        // Pin grant=2.0 so the test's semantic assertions (3rd call rejects)
        // are independent of any future DEFAULT bump.
        val rl = RateLimiter(maxRequestsPerMinute = 5, initialFirstCallTokens = 2.0, timeSource = clock)
        // F-027 refinement: fresh buckets start with the 2-token first-
        // call grant so the documented connect handshake (registerClient
        // + getCapabilities) succeeds without waiting on refill.
        assertTrue("first-call grant allows fresh uid through", rl.tryAcquire(1000))
        assertTrue("second handshake call still within grant", rl.tryAcquire(1000))
        // Drain that grant immediately — third call without refill fails.
        assertFalse("grant is bounded at 2.0, not full capacity", rl.tryAcquire(1000))
        // After one full minute the bucket refills to capacity (5).
        clock.now = 60_000
        repeat(5) { assertTrue("call $it", rl.tryAcquire(1000)) }
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `fresh uid gets two-token grant then must wait for refill (F-027 refinement)`() {
        val clock = FakeClock(now = 123_456L)
        // Pin grant=2.0 — this test is specifically about the F-027 refinement
        // semantic at grant=2.0, not about the current default.
        val rl = RateLimiter(maxRequestsPerMinute = 60, initialFirstCallTokens = 2.0, timeSource = clock)
        // Brand-new UID gets 2.0 tokens — enough for the canonical
        // connect handshake (registerClient cost 1.0 + getCapabilities
        // cost 0.25 + a follow-up feature check). NOT enough to burst:
        // the third cost-1.0 call without refill rejects, closing the
        // original F-027 evasion ("burst, idle past 10-min eviction,
        // repeat").
        assertTrue("first-call grant", rl.tryAcquire(1000))
        assertTrue("second handshake call within grant", rl.tryAcquire(1000))
        assertFalse("third call without refill rejects", rl.tryAcquire(1000))
        // After 1s of refill (60/min => 1 token/s), one more acquire succeeds.
        clock.now += 1_000
        assertTrue(rl.tryAcquire(1000))
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `refills tokens over time`() {
        val clock = FakeClock()
        // Pin grant=2.0; the test asserts specific token counts that depend on
        // the grant size.
        val rl = RateLimiter(maxRequestsPerMinute = 60, initialFirstCallTokens = 2.0, timeSource = clock)
        // F-027 refinement: 2-token first-call grant. Drain it then advance
        // the clock to test refill.
        assertTrue("first-call grant", rl.tryAcquire(1000))
        assertTrue("second handshake call inside grant", rl.tryAcquire(1000))
        assertFalse("grant exhausted", rl.tryAcquire(1000))
        // Fill the bucket by advancing the clock after bucket creation.
        clock.now = 60_000
        repeat(60) { assertTrue(rl.tryAcquire(1000)) }
        assertFalse(rl.tryAcquire(1000))
        // After another 1s at 60/min, one token refills.
        clock.now += 1_000
        assertTrue(rl.tryAcquire(1000))
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `per-uid buckets are independent`() {
        val clock = FakeClock()
        // Pin grant=2.0 so the per-uid drain logic below is deterministic
        // regardless of future DEFAULT bumps.
        val rl = RateLimiter(maxRequestsPerMinute = 2, initialFirstCallTokens = 2.0, timeSource = clock)
        // F-027 refinement: each UID gets its own 2-token grant (clamped at
        // capacity=2); drain both so the remainder of the test exercises
        // the refilled state.
        assertTrue(rl.tryAcquire(1000))
        assertTrue(rl.tryAcquire(2000))
        assertTrue(rl.tryAcquire(1000))
        assertTrue(rl.tryAcquire(2000))
        assertFalse(rl.tryAcquire(1000))
        assertFalse(rl.tryAcquire(2000))
        // Fully refill BOTH uids' buckets after their (now-drained) buckets exist.
        clock.now = 60_000
        assertTrue(rl.tryAcquire(1000))
        assertTrue(rl.tryAcquire(1000))
        assertFalse(rl.tryAcquire(1000))
        assertTrue(rl.tryAcquire(2000))
    }

    @Test
    fun `beginInference enforces concurrency cap`() {
        val rl = RateLimiter(maxConcurrent = 2, timeSource = FakeClock())
        assertTrue(rl.beginInference(1000))
        assertTrue(rl.beginInference(1000))
        assertFalse(rl.beginInference(1000))
        assertEquals(2, rl.concurrentFor(1000))
    }

    @Test
    fun `endInference releases a concurrency slot`() {
        val rl = RateLimiter(maxConcurrent = 2, timeSource = FakeClock())
        rl.beginInference(1000)
        rl.beginInference(1000)
        rl.endInference(1000)
        assertTrue(rl.beginInference(1000))
    }

    @Test
    fun `endInference tolerates unknown uid`() {
        val rl = RateLimiter(timeSource = FakeClock())
        rl.endInference(9999)
        assertEquals(0, rl.concurrentFor(9999))
    }

    @Test
    fun `concurrency is per-uid`() {
        val rl = RateLimiter(maxConcurrent = 1, timeSource = FakeClock())
        assertTrue(rl.beginInference(1000))
        assertFalse(rl.beginInference(1000))
        assertTrue(rl.beginInference(2000))
    }
}
