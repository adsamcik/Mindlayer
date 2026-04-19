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
    fun `allows requests up to burst capacity`() {
        val clock = FakeClock()
        val rl = RateLimiter(maxRequestsPerMinute = 5, timeSource = clock)
        repeat(5) { assertTrue("call $it", rl.tryAcquire(1000)) }
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `refills tokens over time`() {
        val clock = FakeClock()
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        repeat(60) { assertTrue(rl.tryAcquire(1000)) }
        assertFalse(rl.tryAcquire(1000))
        // After 1s at 60/min, one token refills.
        clock.now += 1_000
        assertTrue(rl.tryAcquire(1000))
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `per-uid buckets are independent`() {
        val clock = FakeClock()
        val rl = RateLimiter(maxRequestsPerMinute = 2, timeSource = clock)
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
