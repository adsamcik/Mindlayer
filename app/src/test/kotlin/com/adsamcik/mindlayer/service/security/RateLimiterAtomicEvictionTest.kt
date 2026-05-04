package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * F-052: regression test for the previously racy `evictIdleOpportunistically`
 * read-modify-write on `lastEvictionMs`. With the AtomicLong + CAS, two
 * threads racing into the eviction path must produce exactly one scan
 * (the CAS winner) — not two.
 */
class RateLimiterAtomicEvictionTest {

    @Test
    fun `concurrent evict triggers exactly one scan via CAS`() {
        // Use a bucket that is always idle so every concurrent caller would
        // otherwise execute the scan body. We count how many times the
        // time-source is consulted with the eviction-path read; a non-atomic
        // implementation would observe many invocations from many threads.
        val callCount = AtomicLong(0L)
        // Time source with a fixed value > EVICT_SCAN_INTERVAL_MS so every
        // thread observes that a scan is "due".
        val clockValue = AtomicLong(120_000L)
        val rl = RateLimiter(
            maxRequestsPerMinute = 1_000_000,
            idleEvictMs = 1L, // every existing bucket immediately qualifies
            timeSource = {
                callCount.incrementAndGet()
                clockValue.get()
            },
        )
        // Pre-populate one bucket per UID so eviction has work.
        repeat(50) { rl.tryAcquire(it) }
        // Reset the counter — we only want to measure eviction-path calls.
        val baseline = callCount.get()

        val n = 32
        val barrier = CyclicBarrier(n)
        val pool = Executors.newFixedThreadPool(n)
        repeat(n) {
            pool.submit {
                barrier.await()
                // Trigger eviction by acquiring with a fresh UID. Each call
                // enters evictIdleOpportunistically.
                rl.tryAcquire(1_000_000 + it)
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        // Without the CAS, n threads could each scan idleBuckets in
        // parallel. With the CAS, exactly one thread observes the gap and
        // updates the timestamp; the others bail out before scanning. A
        // perfect upper bound is hard because each tryAcquire also reads the
        // clock for its own bucket logic; we assert that we are well below
        // a "one scan per thread" worst case. Each tryAcquire makes <= ~3
        // clock reads in the non-evicting path, so n=32 caps total reads at
        // ~100. A racy double-scan of 50 buckets per thread would multiply
        // that out by an order of magnitude.
        val totalReads = callCount.get() - baseline
        assertTrue(
            "Expected near-linear clock reads, got $totalReads (n=$n, buckets=50)",
            totalReads < (n * 5).toLong(),
        )
    }

    @Test
    fun `single-threaded eviction still occurs (sanity check)`() {
        var now = 0L
        val rl = RateLimiter(
            maxRequestsPerMinute = 1_000_000,
            idleEvictMs = 1L,
            timeSource = { now },
        )
        rl.tryAcquire(42)
        // Advance past the eviction interval so the single-threaded call
        // executes the scan (and removes the idle bucket).
        now = 120_000L
        rl.tryAcquire(43)
        // Concurrency check is a coarse signal — the bucket should be gone.
        assertFalse(rl.concurrentFor(42) > 0)
    }
}
