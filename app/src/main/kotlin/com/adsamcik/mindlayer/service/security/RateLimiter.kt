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
 *
 * **First-call grant (F-027 refinement).** Brand-new buckets start with
 * exactly [initialFirstCallTokens] (default `2.0`), not the full capacity
 * and not zero. This lets the documented connect handshake
 * (`bindService` → `onServiceConnected` → `registerClient` cost 1.0 →
 * `getCapabilities` cost 0.25, optionally followed by a feature-gate
 * `getCapabilities`) succeed without waiting for the refill cadence
 * (~1 token/sec at the default 60 RPM), while still preventing the
 * "burst, idle past [idleEvictMs], burst again" evasion that motivated
 * F-027: the grant is two tokens, never the full capacity, and is
 * consumed by the opening handshake. From there only elapsed-time
 * refill governs, exactly as before.
 */
class RateLimiter(
    private val maxRequestsPerMinute: Int = DEFAULT_RPM,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val maxRejectionsPerMinute: Int = DEFAULT_REJECTIONS_PER_MINUTE,
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
    /**
     * F-027 refinement: number of tokens granted to a brand-new (or freshly
     * recreated after idle eviction) bucket. Default [INITIAL_FIRST_CALL_TOKENS]
     * (`2.0`) — enough for the documented connect handshake
     * (`registerClient` cost 1.0 + `getCapabilities` cost 0.25 ×
     * 2 follow-ups). Tests pin this to `0.0` for the historical "starts
     * empty" behaviour or to a larger value to experiment with looser
     * cold-start policy. Never raise the production default without
     * re-evaluating the burst-after-eviction calculation in
     * [newEmptyBucket]'s call site.
     */
    private val initialFirstCallTokens: Double = INITIAL_FIRST_CALL_TOKENS,
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
    private val pingBuckets = ConcurrentHashMap<Int, Bucket>()
    private val preConsentPingBuckets = ConcurrentHashMap<Int, Bucket>()

    /**
     * v0.10: per-UID bucket for `requestConsentChallenge`. Capacity
     * [DEFAULT_CONSENT_CHALLENGE_PER_HOUR] tokens, refilled over one hour.
     * This is the primary spam defence for the consent-Intent entry point —
     * an un-approved caller can request at most ~10 consent challenges/hour,
     * bounding how often it can pop the consent UI even before the per-
     * `(pkg,sig)` dismiss escalation and device-wide throttle kick in.
     */
    private val consentChallengeBuckets = ConcurrentHashMap<Int, Bucket>()

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
        // F-027 (refined): brand-new buckets start with exactly one token
        // ([initialFirstCallTokens] = 1.0), not the full capacity. That lets
        // a legitimate first-connect through — the documented
        // `bindService` → `onServiceConnected` → `registerClient` flow
        // always costs ≤ 1.0 — while still preventing burst-after-eviction:
        // the one-token grant amortises to the documented RPM the moment
        // the bucket is refilled past empty by the periodic eviction
        // sweeper, since the bucket can never accumulate beyond `capacity`
        // regardless of age. The pre-fix behaviour ("starts at 0") was
        // overshooting the F-027 intent — it rejected every legitimate
        // first-call from a new UID because the refill rate at the default
        // 60 RPM is ~1 token/sec, but `registerClient` fires within ms of
        // `bindService` returning.
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

    /**
     * Secondary bucket used to throttle the cost of REJECTING un-allowlisted
     * callers. A separate, smaller-capacity bucket so that an attacker
     * spamming allowlist misses cannot trigger unbounded `recordPending`
     * disk I/O / log writes via [com.adsamcik.mindlayer.service.security.AllowlistStore].
     *
     * Returns true if a "we'll do the expensive rejection bookkeeping"
     * token was available; false means swallow the rejection silently.
     */
    fun tryAcquireRejection(uid: Int): Boolean {
        val bucket = buckets.getOrPut(uid) { Bucket(capacity = maxRequestsPerMinute.toDouble()) }
        synchronized(bucket) {
            val now = timeSource()
            val elapsed = now - bucket.lastRejectionRefillMs
            if (elapsed > 0) {
                val refill = elapsed * maxRejectionsPerMinute / 60_000.0
                bucket.rejectionTokens = (bucket.rejectionTokens + refill)
                    .coerceAtMost(maxRejectionsPerMinute.toDouble())
                bucket.lastRejectionRefillMs = now
            }

            bucket.lastAccessMs = now
            return if (bucket.rejectionTokens >= 1.0) {
                bucket.rejectionTokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    fun tryAcquirePing(uid: Int): Boolean {
        val bucket = pingBuckets.getOrPut(uid) { Bucket(capacity = DEFAULT_PING_RPM.toDouble()) }
        synchronized(bucket) {
            val now = timeSource()
            val elapsed = now - bucket.lastRefillMs
            if (elapsed > 0) {
                val refill = elapsed * DEFAULT_PING_RPM / 60_000.0
                bucket.tokens = (bucket.tokens + refill).coerceAtMost(DEFAULT_PING_RPM.toDouble())
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

    /**
     * v0.10: try to consume one token from the per-UID consent-challenge
     * bucket (capacity [DEFAULT_CONSENT_CHALLENGE_PER_HOUR], refilled over
     * one hour). Returns `false` when the caller has exhausted its hourly
     * consent-challenge budget. The bucket starts FULL so the first consent
     * request from a fresh UID always succeeds.
     */
    fun tryAcquireConsentChallenge(uid: Int): Boolean {
        val cap = DEFAULT_CONSENT_CHALLENGE_PER_HOUR.toDouble()
        val bucket = consentChallengeBuckets.getOrPut(uid) { Bucket(capacity = cap) }
        synchronized(bucket) {
            val now = timeSource()
            val elapsed = now - bucket.lastRefillMs
            if (elapsed > 0) {
                val refill = elapsed * DEFAULT_CONSENT_CHALLENGE_PER_HOUR / 3_600_000.0
                bucket.tokens = (bucket.tokens + refill).coerceAtMost(cap)
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

    /**
     * v0.10: coarse pre-consent ping budget for un-approved callers
     * ([DEFAULT_PRE_CONSENT_PING_RPM] per minute). Separate from the
     * post-consent ping bucket so an un-approved caller cannot use the
     * generous 150 RPM budget, and so it only ever receives the coarse
     * `{alive, apiVersion}` response shape.
     */
    fun tryAcquirePreConsentPing(uid: Int): Boolean {
        val cap = DEFAULT_PRE_CONSENT_PING_RPM.toDouble()
        val bucket = preConsentPingBuckets.getOrPut(uid) { Bucket(capacity = cap) }
        synchronized(bucket) {
            val now = timeSource()
            val elapsed = now - bucket.lastRefillMs
            if (elapsed > 0) {
                val refill = elapsed * DEFAULT_PRE_CONSENT_PING_RPM / 60_000.0
                bucket.tokens = (bucket.tokens + refill).coerceAtMost(cap)
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
        // F-027 refinement: see [INITIAL_FIRST_CALL_TOKENS] / class doc.
        // Capped at capacity so a degenerate `maxRequestsPerMinute = 0`
        // bucket can never start non-empty.
        val grant = initialFirstCallTokens.coerceIn(0.0, maxRequestsPerMinute.toDouble())
        return Bucket(capacity = maxRequestsPerMinute.toDouble(), initialTokens = grant).also {
            it.lastRefillMs = now
            it.lastAccessMs = now
        }
    }

    private fun newEmptyRejectedBucket(): Bucket {
        val now = timeSource()
        // F-027 refinement: same one-token grant policy as the main bucket
        // applies to the rejected-callers bucket too — a brand-new unknown
        // caller gets exactly one bookkeeping action (so its first
        // `recordPending` lands), but cannot burst the disk-I/O budget on
        // cold start.
        val grant = initialFirstCallTokens.coerceIn(0.0, maxRejectedPerMinute.toDouble())
        return Bucket(capacity = maxRejectedPerMinute.toDouble(), initialTokens = grant).also {
            it.lastRefillMs = now
            it.lastAccessMs = now
        }
    }

    private class Bucket(capacity: Double, initialTokens: Double = capacity) {
        @JvmField var tokens: Double = initialTokens
        @JvmField var lastRefillMs: Long = 0L
        @JvmField var lastAccessMs: Long = 0L
        @JvmField var concurrent: Int = 0
        @JvmField var rejectionTokens: Double = DEFAULT_REJECTIONS_PER_MINUTE.toDouble()
        @JvmField var lastRejectionRefillMs: Long = 0L
    }

    companion object {
        const val DEFAULT_RPM = 300
        const val DEFAULT_MAX_CONCURRENT = 8
        const val DEFAULT_REJECTIONS_PER_MINUTE = 6
        const val DEFAULT_IDLE_EVICT_MS = 10 * 60 * 1000L
        /**
         * F-033: 6 rejected calls per minute per UID — one every 10 s
         * sustained. Enough headroom for a real first-launch race; not enough
         * for a flooder to drive disk I/O. Kept low on purpose — bumping this
         * weakens the F-033 flooder-resistance guarantee. Don't raise it
         * alongside the legitimate-traffic RPM bumps.
         */
        const val DEFAULT_REJECT_RPM = 6
        const val DEFAULT_PING_RPM = 150

        /**
         * v0.10: per-UID hourly budget for `requestConsentChallenge`. The
         * consent-Intent entry point is reachable by any un-approved caller
         * once the manifest permission is removed (Phase 5), so this is the
         * first line of spam defence. 10/hour is generous for a legitimate
         * app that retries a connect, but far too slow for a nag attack.
         */
        const val DEFAULT_CONSENT_CHALLENGE_PER_HOUR = 10

        /**
         * v0.10: coarse pre-consent ping budget. An un-approved caller may
         * ping at most this many times per minute and receives only a
         * `{alive, apiVersion}` response (no uptime, no engine state) so it
         * cannot be used for fine-grained fingerprinting.
         */
        const val DEFAULT_PRE_CONSENT_PING_RPM = 5
        /**
         * F-040: hard cap on simultaneous inferences across all UIDs. Set
         * to 4 × per-UID cap so up to four typical first-party clients
         * can each fully utilise their per-UID budget without colliding,
         * but a fleet of co-signed apps cannot collectively flood the
         * native engine.
         */
        const val DEFAULT_MAX_GLOBAL_CONCURRENT = 32
        private const val EVICT_SCAN_INTERVAL_MS = 30_000L

        /**
         * F-064: clamp window for caller-supplied per-method costs. The lower
         * bound (`0.05`) ensures a buggy or hostile caller cannot flood with
         * `cost = 0.0`; the upper bound (`8.0`) prevents a single call from
         * draining more than ~8× the per-call budget regardless of label.
         */
        const val MIN_COST: Double = 0.05
        const val MAX_COST: Double = 8.0

        /**
         * F-027 refinement: tokens granted to a brand-new (or freshly
         * recreated after idle eviction) bucket so the canonical first-
         * connect handshake succeeds without waiting on refill cadence.
         *
         * The documented startup pattern is:
         *   1. `registerClient(token)` — cost 1.0
         *   2. `getCapabilities()`     — cost 0.25
         *   3. (optional) a second `getCapabilities()` for feature gating
         *
         * Total ≈ 1.5. The grant is sized at 50.0 so the standard handshake
         * plus a SDK reconnect/retry storm during the ~30 s cold engine init
         * (each retry costs ~1.0) plus an immediate batch of inference calls
         * all fit in the bucket without tripping the rate limiter. A heavier
         * opening call (e.g. cost 4.0) still rejects after ~12 uses, locking
         * in F-027's burst-after-eviction protection: the grant is one-shot
         * and never approaches the full capacity (default 300 = 5 RPS
         * sustained).
         *
         * The earlier value of 10.0 was sized for the documented call
         * pattern but did NOT account for SDK rebind storms when the `:ml`
         * subprocess was being recreated (e.g. after a service force-stop
         * during dev iteration, or after a process restart from the
         * #2028 thermal-switch path). Bumped to 50.0 to cover that case.
         */
        const val INITIAL_FIRST_CALL_TOKENS: Double = 50.0
    }
}
