package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins SECURITY_REVIEW F-040 — global concurrency cap across all UIDs.
 *
 * Before this change, [RateLimiter.beginInference] only enforced a per-UID
 * concurrency limit; N authorised callers each running at their per-UID
 * limit could collectively pin more native engine slots than the device
 * could serve. Phase 3 introduces an `AtomicInteger` global counter that
 * is decremented in [RateLimiter.endInference]; both end-states (clean
 * `endInference` vs. the GC'd-bucket fallback path) are exercised here.
 */
class RateLimiterGlobalConcurrencyTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `global cap blocks even when per-uid budgets remain`() {
        val rl = RateLimiter(
            maxConcurrent = 4,
            maxGlobalConcurrent = 5,
            timeSource = FakeClock(),
        )
        // UID-A claims 4 (at per-UID cap).
        repeat(4) { assertTrue("A#$it", rl.beginInference(1000)) }
        assertFalse("A 5th rejected by per-UID cap", rl.beginInference(1000))
        // UID-B has plenty of per-UID budget but global is at 4/5.
        assertTrue("B 1st", rl.beginInference(2000))
        // Global cap is now 5/5 — UID-B's 2nd is rejected globally.
        assertFalse("B 2nd rejected globally", rl.beginInference(2000))
        assertEquals(5, rl.globalConcurrent())
    }

    @Test
    fun `endInference releases the global slot`() {
        val rl = RateLimiter(
            maxConcurrent = 4,
            maxGlobalConcurrent = 2,
            timeSource = FakeClock(),
        )
        assertTrue(rl.beginInference(1000))
        assertTrue(rl.beginInference(2000))
        assertFalse("global at cap", rl.beginInference(3000))
        rl.endInference(1000)
        assertEquals(1, rl.globalConcurrent())
        assertTrue("UID-3 acquires after release", rl.beginInference(3000))
    }

    @Test
    fun `endInference for unknown uid still decrements global counter`() {
        val rl = RateLimiter(
            maxConcurrent = 4,
            maxGlobalConcurrent = 2,
            timeSource = FakeClock(),
        )
        assertTrue(rl.beginInference(1000))
        // Simulating "bucket evicted between begin and end" (would normally
        // require a 10 min idle window). The counter must still drop.
        rl.endInference(9999)
        assertEquals(0, rl.globalConcurrent())
    }

    @Test
    fun `globalConcurrent never goes negative`() {
        val rl = RateLimiter(timeSource = FakeClock())
        rl.endInference(9999) // never began — safe
        rl.endInference(9999)
        assertEquals(0, rl.globalConcurrent())
    }
}
