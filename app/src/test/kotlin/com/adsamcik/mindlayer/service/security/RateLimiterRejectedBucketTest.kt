package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dedicated rejected-bucket throttles flooders while remaining isolated
 * from the main-traffic bucket.
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
        // F-027 refinement: bucket is created lazily with a 1-token grant.
        // Trigger creation (consumes the grant → returns true), then
        // advance ≥60 s so the bucket refills to capacity.
        rl.tryAcquireRejected(uid = 1000) // creates bucket, consumes grant
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
    fun `rejected bucket starts with one-token grant - flooder cannot burst on cold start`() {
        var time = 0L
        val rl = RateLimiter(
            maxRejectedPerMinute = 6,
            initialFirstCallTokens = 2.0,
            timeSource = { time },
        )
        // F-027 refinement: the rejected-callers bucket follows the same
        // first-call grant policy as the main bucket — a brand-new unknown
        // caller gets limited rejection-bookkeeping budget. Pin grant=2.0
        // explicitly so the test's 3-call burst-blocked
        // assertion is independent of the default-tokens bump for the
        // legitimate-traffic bucket. The semantic being tested is "rejected
        // bucket grant has the same cap structure as the main one"; the
        // exact number is parameterised via the constructor.
        assertTrue("first-call grant", rl.tryAcquireRejected(1000))
        assertTrue("second call still within 2.0 grant", rl.tryAcquireRejected(1000))
        assertFalse("burst blocked - grant is bounded at 2.0", rl.tryAcquireRejected(1000))
    }
}
