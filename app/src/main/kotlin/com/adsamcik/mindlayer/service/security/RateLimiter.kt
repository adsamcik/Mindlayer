package com.adsamcik.mindlayer.service.security

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-UID rate limiter combining a token bucket for request rate and a simple
 * counter for concurrent in-flight inferences.
 *
 * Thread-safe: per-bucket state mutations are guarded by synchronizing on the
 * bucket object. The bucket map itself is a [ConcurrentHashMap].
 */
class RateLimiter(
    private val maxRequestsPerMinute: Int = DEFAULT_RPM,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val idleEvictMs: Long = DEFAULT_IDLE_EVICT_MS,
    private val maxRejectedPerMinute: Int = DEFAULT_REJECT_RPM,
    /**
     * F-040: hard cap on the total number of in-flight inferences across
     * all UIDs combined. A single UID is already bounded by [maxConcurrent];
     * this prevents N authorized callers from each saturating their own
     * per-UID budget and collectively pinning more native engine slots
     * than the device can actually serve. The default is generous (the
     * project's per-UID `MAX_CONCURRENT = 4` × 4 typical clients = 16)
     * and is the upper bound on what `MAX_TOOL_ROUNDS × tool wait` can
     * pin even after F-061's wall-clock cap.
     */
    private val maxGlobalConcurrent: Int = DEFAULT_MAX_GLOBAL_CONCURRENT,
    private val timeSource: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val buckets = ConcurrentHashMap<Int, Bucket>()

    /**
     * F-033: separate token bucket for callers that fail identity / allowlist.
     * Consulted BEFORE [AllowlistStore.recordPending] so a hostile caller
     * cannot saturate FileLock+disk by repeatedly tripping the gate. Capacity
     * is small ([DEFAULT_REJECT_RPM] tokens/minute) and isolated from the
     * normal-traffic bucket so legitimate callers never share quota with a
     * flooding attacker on the same UID.
     */
    private val rejectedBuckets = ConcurrentHashMap<Int, Bucket>()

    fun tryAcquire(uid: Int): Boolean = tryAcquire(uid, cost = 1.0)

    /**
     * F-064: per-method cost-weighted acquire. Cheap calls (e.g. `getStatus`,
     * `getEngineInfo`) pass `cost = 0.25` so a polling dashboard can sustain
     * 4× the documented RPM without bumping the cap; expensive calls pass
     * `1.0`. Caller-supplied costs are clamped to a sane window so a buggy
     * caller can't flood through with `cost = 0.0`.
     */
    fun tryAcquire(uid: Int, cost: Double): Boolean {
        val effectiveCost = cost.coerceIn(MIN_COST, MAX_COST)
        evictIdleOpportunistically()
        // F-027: brand-new buckets must NOT start full — that lets a UID
        // burst the documented RPM, idle past the 10-min eviction, then
        // burst again past the cap. We start at 0 and refill from there.
        val bucket = buckets.getOrPut(uid) { newEmptyBucket() }
        synchronized(bucket) {
            val now = timeSource()
            // Refill: capacity tokens per 60s => capacity/60000 tokens per ms
            val elapsed = now - bucket.lastRefillMs
            if (elapsed > 0) {
                val refill = elapsed * maxRequestsPerMinute / 60_000.0
                bucket.tokens = (bucket.tokens + refill).coerceAtMost(maxRequestsPerMinute.toDouble())
                bucket.lastRefillMs = now
            }
            bucket.lastAccessMs = now
            return if (bucket.tokens >= effectiveCost) {
                bucket.tokens -= effectiveCost
                true
            } else {
                false
            }
        }
    }

    fun beginInference(uid: Int): Boolean {
        // F-040: check the global cap first so a single UID's per-UID
        // budget cannot let it claim a slot that has already been
        // collectively exhausted by other UIDs. If the global cap is
        // tripped we leave the per-UID counter alone (no false positive).
        val nextGlobal = globalConcurrent.incrementAndGet()
        if (nextGlobal > maxGlobalConcurrent) {
            globalConcurrent.decrementAndGet()
            return false
        }
        val bucket = buckets.getOrPut(uid) { newEmptyBucket() }
        synchronized(bucket) {
            bucket.lastAccessMs = timeSource()
            if (bucket.concurrent >= maxConcurrent) {
                globalConcurrent.decrementAndGet()
                return false
            }
            bucket.concurrent += 1
            return true
        }
    }

    fun endInference(uid: Int) {
        val bucket = buckets[uid] ?: run {
            // Even if the per-UID bucket has been GC'd, we still owe a
            // global decrement (e.g. inference completed after a long
            // idle window where opportunistic eviction removed the UID).
            // Decrement defensively but never below zero.
            globalConcurrent.updateAndGet { (it - 1).coerceAtLeast(0) }
            return
        }
        synchronized(bucket) {
            bucket.concurrent = (bucket.concurrent - 1).coerceAtLeast(0)
            bucket.lastAccessMs = timeSource()
        }
        globalConcurrent.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun concurrentFor(uid: Int): Int = buckets[uid]?.let { synchronized(it) { it.concurrent } } ?: 0

    /** F-040: current count of inferences in-flight across all UIDs. */
    fun globalConcurrent(): Int = globalConcurrent.get()

    private val globalConcurrent: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * F-033: try to consume one token from the per-UID *rejected* bucket.
     * Returns `false` when the bucket is empty (rate-limit the rate-limit
     * caller). Does not interact with the main-traffic bucket — see class
     * doc.
     */
    fun tryAcquireRejected(uid: Int): Boolean {
        val bucket = rejectedBuckets.getOrPut(uid) { newEmptyRejectedBucket() }
        synchronized(bucket) {
            val now = timeSource()
            val elapsed = now - bucket.lastRefillMs
            if (elapsed > 0) {
                val refill = elapsed * maxRejectedPerMinute / 60_000.0
                bucket.tokens = (bucket.tokens + refill).coerceAtMost(maxRejectedPerMinute.toDouble())
                bucket.lastRefillMs = now
            }
            bucket.lastAccessMs = now
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    private fun evictIdleOpportunistically() {
        val now = timeSource()
        // F-052: race-free eviction. If two threads both observe a stale
        // `lastEvictionMs` and race into the scan, only the CAS winner
        // proceeds — the loser exits without re-scanning and without
        // writing back a regressed timestamp.
        val prev = lastEvictionMs.get()
        if (now - prev < EVICT_SCAN_INTERVAL_MS) return
        if (!lastEvictionMs.compareAndSet(prev, now)) return
        val cutoff = now - idleEvictMs
        val iter = buckets.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val b = entry.value
            val shouldRemove = synchronized(b) { b.concurrent == 0 && b.lastAccessMs < cutoff }
            if (shouldRemove) iter.remove()
        }
    }

    private val lastEvictionMs: AtomicLong = AtomicLong(0L)

    private fun newEmptyBucket(): Bucket {
        val now = timeSource()
        return Bucket(capacity = maxRequestsPerMinute.toDouble(), initialTokens = 0.0).also {
            it.lastRefillMs = now
            it.lastAccessMs = now
        }
    }

    private fun newEmptyRejectedBucket(): Bucket {
        val now = timeSource()
        return Bucket(capacity = maxRejectedPerMinute.toDouble(), initialTokens = 0.0).also {
            it.lastRefillMs = now
            it.lastAccessMs = now
        }
    }

    private class Bucket(capacity: Double, initialTokens: Double = capacity) {
        @JvmField var tokens: Double = initialTokens
        @JvmField var lastRefillMs: Long = 0L
        @JvmField var lastAccessMs: Long = 0L
        @JvmField var concurrent: Int = 0
    }

    companion object {
        const val DEFAULT_RPM = 60
        const val DEFAULT_MAX_CONCURRENT = 4
        const val DEFAULT_IDLE_EVICT_MS = 10 * 60 * 1000L
        /**
         * F-033: 6 rejected calls per minute per UID — one every 10 s
         * sustained. Enough headroom for a real first-launch race; not enough
         * for a flooder to drive disk I/O.
         */
        const val DEFAULT_REJECT_RPM = 6
        /**
         * F-040: hard cap on simultaneous inferences across all UIDs. Set
         * to 4 × per-UID cap so up to four typical first-party clients
         * can each fully utilise their per-UID budget without colliding,
         * but a fleet of co-signed apps cannot collectively flood the
         * native engine.
         */
        const val DEFAULT_MAX_GLOBAL_CONCURRENT = 16
        private const val EVICT_SCAN_INTERVAL_MS = 30_000L

        /**
         * F-064: clamp window for caller-supplied per-method costs. The lower
         * bound (`0.05`) ensures a buggy or hostile caller cannot flood with
         * `cost = 0.0`; the upper bound (`8.0`) prevents a single call from
         * draining more than ~8× the per-call budget regardless of label.
         */
        const val MIN_COST: Double = 0.05
        const val MAX_COST: Double = 8.0
    }
}
