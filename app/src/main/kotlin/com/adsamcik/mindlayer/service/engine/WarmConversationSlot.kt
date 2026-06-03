package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinator for the LiteRT-LM engine's **single native session slot**.
 *
 * LiteRT-LM 0.12.0 enforces "at most one Conversation per Engine at a
 * time" via the JNI bridge — the second `engine.createConversation(...)`
 * call throws `LiteRtLmJniException: FAILED_PRECONDITION: A session
 * already exists. Only one session is supported at a time.` Empirically
 * verified by `KvCacheMemoryBenchmarkInstrumentedTest`'s multi-session
 * phase (see PR #142 commit ad1e199 for the failure trace and the
 * Phase-0 spike data).
 *
 * # Contract — warm-conversation lease
 *
 * The primary surface is [lease], a scoped-lock API that owns the engine
 * slot for the **full duration** of an inference (including tool loops,
 * structured-output retries, and the cancellation cleanup). Callers
 * (typically [InferenceOrchestrator]) wrap their entire native
 * interaction in:
 *
 * ```kotlin
 * warmSlot.lease(handle, sessions, createConversation = { ... }) { conv ->
 *     conv.sendMessageAsync(...).collect { ... }
 *     // tool calls, retries, etc. — all under the same lease
 * }
 * ```
 *
 * Lease semantics:
 *
 *  * **Same-session re-entry**: if `handle` is already the warm session
 *    and its `conversation` field is non-null, the lease block runs
 *    immediately with the existing Conversation.
 *  * **Cold session, no current warm**: the [createConversation] factory
 *    is called to materialise a fresh `Conversation` seeded with
 *    `handle.recordedTurns`. The slot becomes warm for `handle`.
 *  * **Cross-session swap**: if a different session is currently warm
 *    AND idle (its per-handle `Mutex` can be `tryLock`-acquired), the
 *    prior `Conversation` is closed, the prior handle is marked cold
 *    (its `conversation` field set to `null`), and `handle` becomes the
 *    new warm session via the factory.
 *  * **Cross-session swap blocked**: if the prior warm session is mid-
 *    stream (its per-handle `Mutex` is held by the orchestrator), the
 *    lease throws [EngineBusyException]. The caller retries after the
 *    `retryAfterMs` hint.
 *
 * The slot's [Mutex] is held for the entire block — this means
 * **at most one inference is in flight globally**. v1 trades multi-
 * inference throughput for correctness against the native invariant; a
 * future iteration can add per-session inference queueing once the
 * tool-loop / structured-output state-machine is provably restartable
 * across swaps.
 *
 * # Locking order (canonical)
 *
 * 1. `slotMutex.withLock { ... }` (inside [lease])
 * 2. `priorHandle.mutex.tryLock()` (eviction safety check, non-blocking)
 * 3. `handle.mutex.withLock { ... }` (caller's per-session serialisation;
 *    acquired by [InferenceOrchestrator] AFTER lease has returned the
 *    Conversation — but the lease holds the slot mutex for the full block,
 *    so the orchestrator's per-handle mutex is effectively nested INSIDE
 *    the slot mutex).
 *
 * Destroy/cancel paths that need to close a Conversation outside an
 * active lease MUST go through [tryEvictIdle] (non-blocking) or
 * [forceEvict] (blocks until the slot is acquirable) — never reach into
 * `handle.conversation` directly except from a cleanup-on-error path
 * where the handle is provably no longer warm.
 */
internal class WarmConversationSlot {

    private val slotMutex = Mutex()
    private val warmSessionId = AtomicReference<String?>(null)

    /** ID of the session that currently owns the native engine slot, or null. */
    val currentWarmSessionId: String? get() = warmSessionId.get()

    /**
     * Run [block] with the warm `Conversation` for [handle] guaranteed
     * valid for the block's duration. Performs a swap (close the prior
     * warm session's Conversation + create a new one for [handle]) if
     * necessary.
     *
     * @param handle the session that wants the warm slot.
     * @param sessions live session map; used to look up the prior warm
     *   handle for eviction.
     * @param createConversation factory invoked when [handle] is currently
     *   cold and needs a fresh `Conversation`. Called under [slotMutex]
     *   so the factory should be quick (it's typically a single
     *   `engine.createConversation(...)` call). The result is stored in
     *   `handle.conversation` and returned to [block].
     * @param block the user code that uses the warm `Conversation`. The
     *   slot stays acquired for the block's entire suspension lifetime.
     *
     * @throws EngineBusyException if the prior warm session is mid-stream
     *   and cannot be safely evicted.
     */
    suspend fun <R> lease(
        handle: SessionManager.SessionHandle,
        sessions: Map<String, SessionManager.SessionHandle>,
        createConversation: suspend () -> Conversation,
        block: suspend (Conversation) -> R,
    ): R = slotMutex.withLock {
        val priorId = warmSessionId.get()

        // Path 1: re-entry on the same warm session.
        if (priorId == handle.sessionId && handle.conversation != null) {
            return@withLock block(handle.conversation!!)
        }

        // Path 2: a different session is warm — evict it (or refuse if it's busy).
        if (priorId != null && priorId != handle.sessionId) {
            evictPriorWarmUnderLock(priorId, handle.sessionId, sessions)
        }

        // Path 3: handle is cold — materialise its Conversation via factory.
        if (handle.conversation == null) {
            val newConv = try {
                createConversation()
            } catch (t: Throwable) {
                // Factory failure leaves the slot empty so the next lease
                // attempt can retry without inheriting stale state.
                warmSessionId.set(null)
                throw t
            }
            handle.conversation = newConv
        }
        warmSessionId.set(handle.sessionId)

        block(handle.conversation!!)
    }

    /**
     * Close + null the warm Conversation IF the warm session is idle
     * (its per-handle mutex is acquirable via `tryLock` AND the slot
     * mutex is acquirable). Returns `true` when the slot was freed,
     * `false` when it was held by an active lease/stream (no exception
     * thrown — caller decides whether to wait or skip).
     *
     * Both locks are non-blocking acquisitions so destroy/eviction
     * paths never wedge behind a competing inference. If the slot is
     * busy, [releaseMarker] should be the fallback once the caller
     * closes the Conversation through its own per-handle mutex.
     *
     * Used by [SessionManager.destroySessionInternal] to release the
     * warm slot when the warm session is being destroyed, and by
     * memory-pressure eviction paths that should not block on a stream.
     */
    fun tryEvictIdle(
        priorId: String,
        sessions: Map<String, SessionManager.SessionHandle>,
    ): Boolean {
        // Non-blocking acquire on the slot mutex — if a lease is in
        // flight, we refuse rather than wait.
        if (!slotMutex.tryLock()) return false
        try {
            val currentWarm = warmSessionId.get() ?: return true
            if (currentWarm != priorId) return true
            val prior = sessions[priorId] ?: run {
                warmSessionId.compareAndSet(priorId, null)
                return true
            }
            if (!prior.mutex.tryLock()) {
                // Per-handle mutex is held (likely by the caller's own
                // destroy path that wraps this call); return false so
                // the caller knows to close+null the Conversation
                // through their own held mutex, then call
                // releaseMarker() to clear the slot marker.
                return false
            }
            try {
                try {
                    prior.conversation?.close()
                } catch (t: Throwable) {
                    MindlayerLog.w(
                        TAG,
                        "Conversation.close() during tryEvictIdle raised: ${t.safeLabel()}",
                        sessionId = priorId,
                    )
                }
                prior.conversation = null
                warmSessionId.compareAndSet(priorId, null)
                return true
            } finally {
                prior.mutex.unlock()
            }
        } finally {
            slotMutex.unlock()
        }
    }

    /**
     * Release the slot marker without closing the Conversation. Use when
     * the caller has already closed the Conversation through another path
     * (e.g. destroy-while-warm with the per-handle mutex held by the
     * caller). No-op when [sessionId] is not currently warm.
     */
    fun releaseMarker(sessionId: String) {
        warmSessionId.compareAndSet(sessionId, null)
    }

    /**
     * Best-effort eviction used during graceful shutdown — closes the
     * warm Conversation if one exists and both the slot and per-handle
     * mutex are acquirable via `tryLock`. Does NOT throw or block on a
     * busy session; the shutdown path is best-effort by design.
     */
    fun shutdown(sessions: Map<String, SessionManager.SessionHandle>) {
        val priorId = warmSessionId.get() ?: return
        tryEvictIdle(priorId, sessions)
    }

    /**
     * Internal: evict the prior warm session under [slotMutex]. Caller
     * MUST hold [slotMutex]. Throws [EngineBusyException] if the prior
     * session is mid-stream.
     */
    private fun evictPriorWarmUnderLock(
        priorId: String,
        requestedId: String,
        sessions: Map<String, SessionManager.SessionHandle>,
    ) {
        val prior = sessions[priorId]
        if (prior == null) {
            // Stale slot marker — sessions map no longer contains the
            // tracked ID. Clear and proceed.
            warmSessionId.compareAndSet(priorId, null)
            return
        }
        if (!prior.mutex.tryLock()) {
            throw EngineBusyException(
                busySessionId = priorId,
                requestedSessionId = requestedId,
                retryAfterMs = ENGINE_BUSY_RETRY_MS,
            )
        }
        try {
            MindlayerLog.i(
                TAG,
                "Hot-swap: evicting warm session to make room for $requestedId",
                sessionId = priorId,
            )
            try {
                prior.conversation?.close()
            } catch (t: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "Conversation.close() during hot-swap eviction raised: ${t.safeLabel()}",
                    sessionId = priorId,
                )
            }
            prior.conversation = null
            warmSessionId.compareAndSet(priorId, null)
        } finally {
            prior.mutex.unlock()
        }
    }

    companion object {
        private const val TAG = "WarmConversationSlot"

        /**
         * Wire-level retry-after for [EngineBusyException]. 500 ms is a
         * compromise: long enough to let a typical decode loop produce
         * several tokens; short enough that interactive UX still feels
         * responsive when the user expects a session switch.
         */
        const val ENGINE_BUSY_RETRY_MS: Long = 500L
    }
}

/**
 * Thrown by [WarmConversationSlot.lease] when the engine's single native
 * session slot is held by another session that is currently processing an
 * inference. The client should wait [retryAfterMs] and retry. Translated
 * to [MindlayerErrorCode.ENGINE_BUSY] at the AIDL boundary.
 */
class EngineBusyException(
    val busySessionId: String,
    val requestedSessionId: String,
    val retryAfterMs: Long,
) : IllegalStateException(
    "engine_busy: session '$requestedSessionId' cannot start while session " +
        "'$busySessionId' is mid-stream (retry after ${retryAfterMs}ms)"
)
