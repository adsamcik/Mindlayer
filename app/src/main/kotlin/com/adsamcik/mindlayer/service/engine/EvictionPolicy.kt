package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock

/**
 * Pure-function helpers for session-eviction decisions. Extracted from
 * [SessionManager] so the policy can be unit-tested without an engine or
 * `Context`, and so [SessionManager] retains only the *execution* of
 * eviction (calling [SessionManager.destroySessionInternal], firing
 * listeners, etc.).
 *
 * All members are stateless and side-effect-free. The policy operates on
 * snapshots of [SessionManager.SessionHandle] state — callers are
 * responsible for taking the snapshot under whatever lock guards
 * concurrent mutation.
 *
 * # Priority formula (higher = safer from eviction)
 *
 *   streaming  +1000   (must never lose a live stream)
 *   pinned      +400   (client declared "keep alive")
 *   accessed <30 s   +300   (hot session)
 *   accessed <120 s  +150   (warm session)
 *   client hint    +0–100  (small caller-supplied bias)
 */
internal object EvictionPolicy {

    private const val STREAMING_BONUS = 1000
    private const val PINNED_BONUS = 400
    private const val HOT_RECENCY_BONUS = 300
    private const val WARM_RECENCY_BONUS = 150
    private const val HOT_RECENCY_MS = 30_000L
    private const val WARM_RECENCY_MS = 120_000L
    private const val MAX_CLIENT_HINT = 100

    /**
     * Compute a numeric priority for [handle]. Higher values mean the
     * session is more important and should be evicted last.
     */
    fun calculatePriority(handle: SessionManager.SessionHandle): Int {
        var p = 0
        if (handle.isStreaming) p += STREAMING_BONUS
        if (handle.isPinned) p += PINNED_BONUS
        val recencyMs = SystemClock.elapsedRealtime() - handle.lastAccessedElapsedMs
        if (recencyMs < HOT_RECENCY_MS) p += HOT_RECENCY_BONUS
        else if (recencyMs < WARM_RECENCY_MS) p += WARM_RECENCY_BONUS
        p += handle.clientPriorityHint.coerceIn(0, MAX_CLIENT_HINT)
        return p
    }

    /**
     * Select the lowest-priority candidate suitable for eviction, or `null`
     * if no handle matches the filter.
     *
     * @param handles snapshot of handles to consider.
     * @param excludeStreaming when `true` (default), streaming sessions are
     *   excluded — never evict a live stream.
     * @param excludePinned when `true` (default), pinned sessions are excluded.
     * @param ownerUid when non-null, only consider handles whose `ownerUid`
     *   matches — used for caller-capacity eviction.
     */
    fun selectLowestPriorityVictim(
        handles: Collection<SessionManager.SessionHandle>,
        excludeStreaming: Boolean = true,
        excludePinned: Boolean = false,
        ownerUid: Int? = null,
    ): SessionManager.SessionHandle? {
        return handles.asSequence()
            .filter { !excludeStreaming || !it.isStreaming }
            .filter { !excludePinned || !it.isPinned }
            .filter { ownerUid == null || it.ownerUid == ownerUid }
            .minByOrNull { calculatePriority(it) }
    }

    /**
     * Select every handle that should be shed under broad memory pressure:
     * all non-streaming, non-pinned handles **except** the single
     * highest-priority survivor. Returns an empty list when there are
     * fewer than 2 candidates (nothing to shed).
     */
    fun selectPressureEvictionVictims(
        handles: Collection<SessionManager.SessionHandle>,
    ): List<SessionManager.SessionHandle> {
        val candidates = handles.asSequence()
            .filter { !it.isStreaming && !it.isPinned }
            .sortedBy { calculatePriority(it) }
            .toList()
        return if (candidates.size > 1) candidates.dropLast(1) else emptyList()
    }
}
