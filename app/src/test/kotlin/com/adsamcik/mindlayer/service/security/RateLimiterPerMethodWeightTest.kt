package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-064: per-method cost-weighted acquire. Cheap calls consume a fractional
 * token so a polling dashboard can sustain them without burning an
 * inference's worth of budget.
 */
class RateLimiterPerMethodWeightTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    /**
     * Drive a fresh bucket from empty to fully-filled by primer + advance.
     * Prevents the F-027 "fresh uid burst" evasion: the bucket only fills
     * by elapsed-time refill from the lastRefillMs set on creation.
     */
    private fun primeBucket(rl: RateLimiter, clock: FakeClock, uid: Int) {
        rl.tryAcquire(uid) // creates bucket at lastRefillMs=clock.now (empty)
        clock.now += 60_000L // 60s of refill at any RPM tops the bucket
    }

    @Test
    fun `cheap call (cost=0_25) gets 4x more acquires than default`() {
        val clock = FakeClock(now = 1_000L)
        val rl = RateLimiter(maxRequestsPerMinute = 4, timeSource = clock)
        primeBucket(rl, clock, uid = 1)
        // Drain bucket with cheap calls — 4 capacity / 0.25 cost = 16 calls.
        repeat(16) {
            assertTrue("cheap call $it", rl.tryAcquire(uid = 1, cost = 0.25))
        }
        assertFalse("17th cheap call must fail", rl.tryAcquire(uid = 1, cost = 0.25))
    }

    @Test
    fun `expensive default call (cost=1_0) gets exactly capacity acquires`() {
        val clock = FakeClock(now = 1_000L)
        val rl = RateLimiter(maxRequestsPerMinute = 5, timeSource = clock)
        primeBucket(rl, clock, uid = 2)
        repeat(5) { assertTrue(rl.tryAcquire(uid = 2)) }
        assertFalse(rl.tryAcquire(uid = 2))
    }

    @Test
    fun `cheap and expensive calls share the same bucket`() {
        val clock = FakeClock(now = 1_000L)
        val rl = RateLimiter(maxRequestsPerMinute = 4, timeSource = clock)
        primeBucket(rl, clock, uid = 3)
        // 1 expensive (consumes 1.0) + 12 cheap (consumes 0.25 each = 3.0)
        // totals 4.0 — exactly capacity. The 13th cheap call must fail.
        assertTrue(rl.tryAcquire(uid = 3, cost = 1.0))
        repeat(12) { assertTrue("cheap $it", rl.tryAcquire(uid = 3, cost = 0.25)) }
        assertFalse(rl.tryAcquire(uid = 3, cost = 0.25))
        assertFalse(rl.tryAcquire(uid = 3, cost = 1.0))
    }

    @Test
    fun `cost is clamped so zero-cost cannot bypass`() {
        val clock = FakeClock(now = 1_000L)
        val rl = RateLimiter(maxRequestsPerMinute = 1, timeSource = clock)
        primeBucket(rl, clock, uid = 4)
        // cost = 0.0 should be clamped to MIN_COST so the bucket eventually
        // empties even under hostile callers.
        var allowed = 0
        repeat(1_000) {
            if (rl.tryAcquire(uid = 4, cost = 0.0)) allowed++
        }
        // Capacity 1 / MIN_COST 0.05 = up to 20 calls — we just need a finite cap.
        assertTrue("zero-cost flooder bounded, got $allowed", allowed in 1..40)
    }

    @Test
    fun `default tryAcquire(uid) is equivalent to cost=1_0`() {
        val clock = FakeClock(now = 1_000L)
        val rl = RateLimiter(maxRequestsPerMinute = 3, timeSource = clock)
        primeBucket(rl, clock, uid = 5)
        repeat(3) { assertTrue(rl.tryAcquire(uid = 5)) }
        assertFalse(rl.tryAcquire(uid = 5))
        // Same with explicit cost=1.0 on a fresh uid.
        primeBucket(rl, clock, uid = 6)
        repeat(3) { assertTrue(rl.tryAcquire(uid = 6, cost = 1.0)) }
        assertFalse(rl.tryAcquire(uid = 6, cost = 1.0))
    }
}
