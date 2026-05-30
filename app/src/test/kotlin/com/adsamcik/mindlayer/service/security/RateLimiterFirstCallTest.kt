package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the first-call grant policy ([RateLimiter.INITIAL_FIRST_CALL_TOKENS])
 * that refines F-027.
 *
 * The pre-fix `initialTokens = 0.0` rejected every legitimate first-connect
 * from a new caller UID because the default 60 RPM refills at ~1 token/sec
 * but `registerClient` fires within ms of `bindService` returning. The fix
 * starts brand-new buckets with exactly one token — enough for the canonical
 * `registerClient` (cost 1.0) — without re-opening the F-027 burst-after-
 * eviction hole, since the grant is one-shot and never the full capacity.
 */
class RateLimiterFirstCallTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `F-027 pin - frozen clock, second call without refill rejects (grant is one-shot)`() {
        // Capacity 60, refill 0/min (frozen clock). The 1-token grant
        // lets the first call through; the second immediately after must
        // fail. This pins the burst-prevention contract: the grant does
        // NOT silently reopen the F-027 evasion hole — once consumed,
        // refill governs as today.
        val clock = FakeClock(now = 555_000L)
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        assertTrue("first call consumes the grant", rl.tryAcquire(1000, cost = 1.0))
        assertFalse("second call without refill rejects", rl.tryAcquire(1000, cost = 1.0))
    }

    @Test
    fun `first-call cost 1_0 succeeds with default INITIAL_FIRST_CALL_TOKENS`() {
        // Default constructor → INITIAL_FIRST_CALL_TOKENS = 1.0.
        // The canonical registerClient first-call (cost 1.0) succeeds
        // without waiting on refill cadence.
        val clock = FakeClock(now = 1L)
        val rl = RateLimiter(timeSource = clock)
        assertTrue("first registerClient call must succeed", rl.tryAcquire(uid = 10_251, cost = 1.0))
    }

    @Test
    fun `first-call cost 0_25 succeeds and leaves 0_75 for an immediate follow-up`() {
        // Cheap polling call (cost 0.25) — bucket grant 1.0 consumed
        // down to 0.75. A second cheap call must still fit (cost 0.25);
        // three more after that drain to 0; a sixth rejects.
        val clock = FakeClock(now = 1L)
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        // 4 × 0.25 = 1.0 exactly fits the grant.
        repeat(4) { i ->
            assertTrue("cheap call #$i must fit in the 1.0 grant", rl.tryAcquire(uid = 1000, cost = 0.25))
        }
        assertFalse("5th cheap call exceeds the grant", rl.tryAcquire(uid = 1000, cost = 0.25))
    }

    @Test
    fun `first-call cost 2_0 rejects (grant is exactly 1_0)`() {
        // An expensive opening call exceeds the 1.0 grant. Acceptable:
        // the documented first call is registerClient at cost 1.0;
        // anything heavier is by definition not the first call.
        val clock = FakeClock(now = 1L)
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        assertFalse(rl.tryAcquire(uid = 1000, cost = 2.0))
    }

    @Test
    fun `backward-compat opt-out - INITIAL_FIRST_CALL_TOKENS = 0_0 reproduces pre-fix behaviour`() {
        // A future PR may want to re-tighten the policy without ripping
        // out the constructor knob; pin the historical "starts empty"
        // shape so that change is intentional. With grant=0.0 on a
        // frozen clock the first call fails — exactly the pre-fix
        // production-bug behaviour.
        val clock = FakeClock(now = 555_000L)
        val rl = RateLimiter(
            maxRequestsPerMinute = 60,
            initialFirstCallTokens = 0.0,
            timeSource = clock,
        )
        assertFalse("pre-fix behaviour reproduced", rl.tryAcquire(1000))
        // Same refill semantics as before: 1 s at 60 RPM = 1 token.
        clock.now += 1_000
        assertTrue(rl.tryAcquire(1000))
        assertFalse(rl.tryAcquire(1000))
    }

    @Test
    fun `experimental opt-in - INITIAL_FIRST_CALL_TOKENS = 5_0 grants five tokens up front`() {
        // The knob is genuinely tunable upward as well — confirms it
        // isn't accidentally hard-coded. Capped at capacity by
        // newEmptyBucket so a > capacity grant cannot manufacture
        // headroom beyond what refill can sustain.
        val clock = FakeClock(now = 1L)
        val rl = RateLimiter(
            maxRequestsPerMinute = 60,
            initialFirstCallTokens = 5.0,
            timeSource = clock,
        )
        repeat(5) { i ->
            assertTrue("call #$i within the 5.0 grant", rl.tryAcquire(1000, cost = 1.0))
        }
        assertFalse("6th call exceeds the grant on a frozen clock", rl.tryAcquire(1000, cost = 1.0))
    }

    @Test
    fun `grant clamps at capacity so degenerate config cannot manufacture headroom`() {
        // initialFirstCallTokens > capacity must be capped at capacity
        // — never let a knob raise the burst ceiling beyond what refill
        // can sustain (capacity = maxRequestsPerMinute tokens).
        val clock = FakeClock(now = 1L)
        val rl = RateLimiter(
            maxRequestsPerMinute = 3,
            initialFirstCallTokens = 99.0,
            timeSource = clock,
        )
        repeat(3) { i ->
            assertTrue("call #$i within capped grant", rl.tryAcquire(1000, cost = 1.0))
        }
        assertFalse("4th call rejected - grant capped at capacity=3", rl.tryAcquire(1000, cost = 1.0))
    }

    @Test
    fun `idle-eviction-then-recreate - UID re-incurs the grant but pays the eviction wait`() {
        // Sequence: bucket created → grant consumed → idle past
        // idleEvictMs + EVICT_SCAN_INTERVAL_MS → eviction sweep removes
        // bucket → next call creates a fresh bucket with a fresh grant.
        //
        // Two things to confirm:
        //  1. The eviction doesn't deny a legitimate first-call after
        //     a long idle (the bug we're fixing).
        //  2. The grant doesn't compound unfairly with eviction — the
        //     UID still has to wait out the full idleEvictMs window;
        //     it can't burst at the cap every 10 minutes.
        val clock = FakeClock(now = 1L)
        val rl = RateLimiter(
            maxRequestsPerMinute = 60,
            idleEvictMs = 10 * 60 * 1000L, // 10 min — production default
            timeSource = clock,
        )
        // First call gets the grant.
        assertTrue("initial first-call grant", rl.tryAcquire(uid = 1000, cost = 1.0))
        // Idle past eviction window AND past the 30 s scan interval.
        clock.now += 11 * 60 * 1000L
        // Trigger eviction sweep + the post-eviction first call. (The
        // sweep runs opportunistically inside tryAcquire — a second UID
        // call would trip it; here the UID-1000 call itself is enough
        // because the cache miss recreates the bucket either way.)
        assertTrue("post-eviction call gets a fresh grant", rl.tryAcquire(uid = 1000, cost = 1.0))
        // Crucially: the post-recreation grant is STILL 1 token, not
        // capacity. So the UID cannot burst at the cap right after
        // returning from idle.
        assertFalse(
            "post-recreation grant is one-shot, NOT a free 60-call burst",
            rl.tryAcquire(uid = 1000, cost = 1.0),
        )
    }

    @Test
    fun `independent UIDs each get their own first-call grant`() {
        // Two different UIDs binding for the first time at the same
        // instant must both succeed. Confirms the grant is per-bucket,
        // not a global allowance.
        val clock = FakeClock(now = 42L)
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        assertTrue(rl.tryAcquire(uid = 1001))
        assertTrue(rl.tryAcquire(uid = 1002))
        assertTrue(rl.tryAcquire(uid = 1003))
        // Each UID's grant is independently one-shot.
        assertFalse(rl.tryAcquire(uid = 1001))
        assertFalse(rl.tryAcquire(uid = 1002))
        assertFalse(rl.tryAcquire(uid = 1003))
    }

    @Test
    fun `cost is clamped before grant check - first call with cost = 0_0 still bounded`() {
        // cost = 0.0 is clamped to MIN_COST (0.05). The grant is 1.0,
        // so up to 20 zero-cost-but-clamped first calls can squeeze
        // through before the grant exhausts. Bounded by the clamp,
        // which is the F-064 invariant.
        val clock = FakeClock(now = 1L)
        val rl = RateLimiter(maxRequestsPerMinute = 60, timeSource = clock)
        var allowed = 0
        repeat(100) {
            if (rl.tryAcquire(uid = 1000, cost = 0.0)) allowed++
        }
        // 1.0 grant / 0.05 clamped cost = 20 calls. Bound it loosely
        // to keep the test robust to MIN_COST tweaks.
        assertTrue("zero-cost flooder bounded by grant + MIN_COST, got=$allowed", allowed in 1..40)
    }

    @Test
    fun `default constructor exposes INITIAL_FIRST_CALL_TOKENS = 1_0`() {
        // Lock the production default so an accidental bump to the
        // companion const fails CI loudly. The bound here is what F-027
        // permits: anything > 1.0 starts re-opening the burst-after-
        // eviction calculation.
        assertEquals(1.0, RateLimiter.INITIAL_FIRST_CALL_TOKENS, 0.0)
    }
}
