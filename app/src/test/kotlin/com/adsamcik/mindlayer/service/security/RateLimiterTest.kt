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
        val rl = RateLimiter(maxRequestsPerMinute = 5, timeSource = clock)
        // F-027 refinement: fresh buckets now start with the 1-token
        // first-call grant (default INITIAL_FIRST_CALL_TOKENS = 1.0), so
        // the very first acquire succeeds without waiting on refill.
        assertTrue("first-call grant allows fresh uid through", rl.tryAcquire(1000))
        // Drain that grant immediately — second call without refill fails.
        assertFalse("grant is one-shot, not full capacity", rl.tryAcquire(1000))
        // After one full minute the bucket refills to capacity (5).
        clock.now = 60_000
        repeat(5) { assertTrue("call $it", rl.tryAcquire(1000)) }
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `fresh uid gets one-token grant then must wait for refill (F-027 refinement)`() {
        val clock = FakeClock(now = 123_456L)
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        // Brand-new UID gets exactly one token — enough for the canonical
        // registerClient first-call (cost 1.0) but NOT enough to burst:
        // the second call without refill rejects, closing the original
        // F-027 evasion ("burst, idle past 10-min eviction, repeat").
        assertTrue("first-call grant", rl.tryAcquire(1000))
        assertFalse("second call without refill rejects", rl.tryAcquire(1000))
        // After 1s of refill (60/min => 1 token/s), one more acquire succeeds.
        clock.now += 1_000
        assertTrue(rl.tryAcquire(1000))
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `refills tokens over time`() {
        val clock = FakeClock()
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        // F-027 refinement: first-call grant means the opening tryAcquire
        // succeeds; drain it then advance the clock to test refill.
        assertTrue("first-call grant", rl.tryAcquire(1000))
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
        val rl = RateLimiter(maxRequestsPerMinute = 2, timeSource = clock)
        // F-027 refinement: each UID gets its own one-token grant; drain
        // both so the remainder of the test exercises the refilled state.
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
