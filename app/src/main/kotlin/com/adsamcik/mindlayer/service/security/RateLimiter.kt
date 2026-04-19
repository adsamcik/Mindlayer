package com.adsamcik.mindlayer.service.security

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

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
    private val timeSource: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val buckets = ConcurrentHashMap<Int, Bucket>()

    fun tryAcquire(uid: Int): Boolean {
        evictIdleOpportunistically()
        val bucket = buckets.getOrPut(uid) { Bucket(capacity = maxRequestsPerMinute.toDouble()) }
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
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    fun beginInference(uid: Int): Boolean {
        val bucket = buckets.getOrPut(uid) { Bucket(capacity = maxRequestsPerMinute.toDouble()) }
        synchronized(bucket) {
            bucket.lastAccessMs = timeSource()
            if (bucket.concurrent >= maxConcurrent) return false
            bucket.concurrent += 1
            return true
        }
    }

    fun endInference(uid: Int) {
        val bucket = buckets[uid] ?: return
        synchronized(bucket) {
            bucket.concurrent = (bucket.concurrent - 1).coerceAtLeast(0)
            bucket.lastAccessMs = timeSource()
        }
    }

    fun concurrentFor(uid: Int): Int = buckets[uid]?.let { synchronized(it) { it.concurrent } } ?: 0

    private fun evictIdleOpportunistically() {
        val now = timeSource()
        if (now - lastEvictionMs < EVICT_SCAN_INTERVAL_MS) return
        lastEvictionMs = now
        val cutoff = now - idleEvictMs
        val iter = buckets.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val b = entry.value
            val shouldRemove = synchronized(b) { b.concurrent == 0 && b.lastAccessMs < cutoff }
            if (shouldRemove) iter.remove()
        }
    }

    @Volatile private var lastEvictionMs: Long = 0L

    private class Bucket(capacity: Double) {
        @JvmField var tokens: Double = capacity
        @JvmField var lastRefillMs: Long = 0L
        @JvmField var lastAccessMs: Long = 0L
        @JvmField var concurrent: Int = 0
    }

    companion object {
        const val DEFAULT_RPM = 60
        const val DEFAULT_MAX_CONCURRENT = 4
        const val DEFAULT_IDLE_EVICT_MS = 10 * 60 * 1000L
        private const val EVICT_SCAN_INTERVAL_MS = 30_000L
    }
}
