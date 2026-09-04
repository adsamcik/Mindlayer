package com.adsamcik.mindlayer.service.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded-cost, affinity-aware admission for the single LiteRT-LM execution
 * slot.
 *
 * The service can accept several requests concurrently, but LiteRT-LM only
 * permits one active [com.google.ai.edge.litertlm.Conversation]. A plain
 * coroutine [Mutex] makes the next owner an implementation detail of arrival
 * order and needlessly swaps out a still-useful warm conversation. This
 * scheduler makes that decision explicit:
 *
 *  1. work that has exceeded [starvationMs] is served oldest-first;
 *  2. otherwise, work matching the currently loaded affinity is preferred;
 *  3. within that group, higher bounded client priority wins;
 *  4. equally important work runs from lowest to highest estimated cost;
 *  5. sequence number is the deterministic FIFO tie-breaker.
 *
 * [maxAffinityBurst] prevents a continuously busy warm session from retaining
 * the slot forever when another session is already queued. This is deliberately
 * non-preemptive: an admitted inference always runs to a terminal state unless
 * the caller cancels it. Reordering only happens between requests.
 */
internal class AdaptiveWorkloadScheduler(
    private val loadedAffinity: () -> String?,
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
    private val starvationMs: Long = DEFAULT_STARVATION_MS,
    private val maxAffinityBurst: Int = DEFAULT_MAX_AFFINITY_BURST,
) {
    init {
        require(starvationMs > 0L) { "starvationMs must be positive" }
        require(maxAffinityBurst > 0) { "maxAffinityBurst must be positive" }
    }

    data class Request(
        /** Session/model state whose reuse avoids a native warm-state swap. */
        val affinityKey: String,
        /** Validated wire hint. Larger values are more important. */
        val priority: Int = 0,
        /** Relative execution estimate. Only compared inside a priority band. */
        val estimatedCost: Int = 1,
    )

    data class Snapshot(
        val activeAffinity: String?,
        val queuedCount: Int,
    )

    private data class Ticket(
        val sequence: Long,
        val enqueuedAtMs: Long,
        val request: Request,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val stateMutex = Mutex()
    private val sequence = AtomicLong(0L)
    private val waiting = mutableListOf<Ticket>()
    private var active: Ticket? = null
    private var lastDispatchedAffinity: String? = null
    private var affinityBurst: Int = 0

    suspend fun <R> run(request: Request, block: suspend () -> R): R {
        val normalized = request.copy(
            priority = request.priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY),
            estimatedCost = request.estimatedCost.coerceAtLeast(1),
        )
        val ticket = Ticket(
            sequence = sequence.incrementAndGet(),
            enqueuedAtMs = clockMs(),
            request = normalized,
        )

        val admittedImmediately = stateMutex.withLock {
            if (active == null && waiting.isEmpty()) {
                admitLocked(ticket)
                true
            } else {
                waiting += ticket
                false
            }
        }

        try {
            if (!admittedImmediately) ticket.gate.await()
            return block()
        } finally {
            finishOrCancel(ticket)
        }
    }

    suspend fun snapshot(): Snapshot = stateMutex.withLock {
        Snapshot(
            activeAffinity = active?.request?.affinityKey,
            queuedCount = waiting.size,
        )
    }

    private suspend fun finishOrCancel(ticket: Ticket) {
        var next: Ticket? = null
        stateMutex.withLock {
            if (active === ticket) {
                active = null
                next = selectNextLocked()
                next?.let(::admitLocked)
            } else {
                // Cancellation before admission. If dispatch won the race,
                // `active === ticket` above releases the slot and advances the
                // queue; otherwise removal is sufficient.
                waiting.remove(ticket)
            }
        }
        next?.gate?.complete(Unit)
    }

    private fun selectNextLocked(): Ticket? {
        if (waiting.isEmpty()) return null
        val now = clockMs()

        val starved = waiting.filter { now - it.enqueuedAtMs >= starvationMs }
        val selected = if (starved.isNotEmpty()) {
            starved.minWithOrNull(
                compareBy<Ticket> { it.enqueuedAtMs }
                    .thenByDescending { it.request.priority }
                    .thenBy { it.request.estimatedCost }
                    .thenBy { it.sequence },
            )
        } else {
            selectNormalLocked()
        }

        if (selected != null) waiting.remove(selected)
        return selected
    }

    private fun selectNormalLocked(): Ticket? {
        val loaded = loadedAffinity()
        val matching = if (loaded == null) {
            emptyList()
        } else {
            waiting.filter { it.request.affinityKey == loaded }
        }
        val alternativesExist = loaded != null &&
            waiting.any { it.request.affinityKey != loaded }
        val allowAffinity = matching.isNotEmpty() &&
            (!alternativesExist || affinityBurst < maxAffinityBurst)
        val candidates = when {
            allowAffinity -> matching
            matching.isNotEmpty() && alternativesExist ->
                waiting.filter { it.request.affinityKey != loaded }
            else -> waiting
        }

        return candidates.minWithOrNull(
            compareByDescending<Ticket> { it.request.priority }
                .thenBy { it.request.estimatedCost }
                .thenBy { it.sequence },
        )
    }

    private fun admitLocked(ticket: Ticket) {
        check(active == null) { "scheduler already has an active ticket" }
        active = ticket
        if (ticket.request.affinityKey == lastDispatchedAffinity) {
            affinityBurst++
        } else {
            lastDispatchedAffinity = ticket.request.affinityKey
            affinityBurst = 1
        }
    }

    companion object {
        private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
        const val MIN_PRIORITY: Int = -10
        const val MAX_PRIORITY: Int = 10
        const val DEFAULT_STARVATION_MS: Long = 30_000L
        const val DEFAULT_MAX_AFFINITY_BURST: Int = 4
    }
}
