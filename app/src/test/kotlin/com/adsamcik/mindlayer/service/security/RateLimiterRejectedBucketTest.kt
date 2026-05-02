package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-033: dedicated rejected-bucket throttles flooders BEFORE
 * [AllowlistStore.recordPending] hits disk, while remaining isolated from the
 * main-traffic bucket.
 */
class RateLimiterRejectedBucketTest {

    @Test
    fun `default rejected RPM permits exactly DEFAULT_REJECT_RPM consecutive calls`() {
        var time = 0L
        val rl = RateLimiter(
            maxRequestsPerMinute = 60,
            maxRejectedPerMinute = RateLimiter.DEFAULT_REJECT_RPM,
            timeSource = { time },
        )
        // Bucket is created lazily on first call (per F-027 anti-burst:
        // empty start). Trigger creation, then advance ≥60s so it refills
        // to capacity.
        rl.tryAcquireRejected(uid = 1000) // creates bucket, returns false
        time += 60_000L
        var successes = 0
        for (i in 0 until 10) {
            if (rl.tryAcquireRejected(uid = 1000)) successes++
        }
        assertEquals(RateLimiter.DEFAULT_REJECT_RPM, successes)

        // Next attempt must fail until refill.
        assertFalse(rl.tryAcquireRejected(uid = 1000))

        // Advance 10 s — that's 1 token at 6 RPM. Allow one more.
        time += 10_000L
        assertTrue(rl.tryAcquireRejected(uid = 1000))
        assertFalse(rl.tryAcquireRejected(uid = 1000))
    }

    @Test
    fun `rejected bucket and main bucket are independent`() {
        var time = 0L
        val rl = RateLimiter(
            maxRequestsPerMinute = 60,
            maxRejectedPerMinute = 6,
            timeSource = { time },
        )
        // Drive both buckets past the F-027 cold-start by creating them
        // and then advancing.
        rl.tryAcquireRejected(1000)
        rl.tryAcquire(1000)
        time += 60_000L

        // Drain rejected bucket.
        repeat(6) { assertTrue(rl.tryAcquireRejected(1000)) }
        assertFalse(rl.tryAcquireRejected(1000))

        // Main bucket still has full capacity (was at 1 token short due to
        // the priming call; after refill it's at full minus 1 ≈ 60).
        var mainSuccesses = 0
        for (i in 0 until 70) {
            if (rl.tryAcquire(1000)) mainSuccesses++
        }
        // Anti-burst means we won't hit the full 60 because of the priming
        // call, but we get at least 59 — confirming independence (not 0).
        assertTrue(
            "Main bucket should retain near-full capacity; got=$mainSuccesses",
            mainSuccesses >= 59,
        )
    }

    @Test
    fun `independent UIDs do not share rejected quota`() {
        var time = 0L
        val rl = RateLimiter(
            maxRejectedPerMinute = 6,
            timeSource = { time },
        )
        rl.tryAcquireRejected(1000)
        rl.tryAcquireRejected(2000)
        time += 60_000L

        repeat(6) { assertTrue(rl.tryAcquireRejected(1000)) }
        assertFalse(rl.tryAcquireRejected(1000))
        // Different UID has its own bucket.
        assertTrue(rl.tryAcquireRejected(2000))
    }

    @Test
    fun `rejected bucket starts empty - flooder cannot burst on cold start`() {
        var time = 0L
        val rl = RateLimiter(
            maxRejectedPerMinute = 6,
            timeSource = { time },
        )
        // No time elapsed yet — bucket is empty.
        assertFalse(rl.tryAcquireRejected(1000))
    }
}
