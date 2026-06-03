package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinator for the LiteRT-LM engine's **single native session slot**.
 *
 * LiteRT-LM 0.12.0 enforces "at most one Conversation per Engine at a time"
 * via the JNI bridge — the second `engine.createConversation(...)` call
 * throws `LiteRtLmJniException: FAILED_PRECONDITION: A session already
 * exists. Only one session is supported at a time.` This invariant was
 * empirically verified by `KvCacheMemoryBenchmarkInstrumentedTest`'s
 * multi-session phase (see commit ad1e199 for the failure trace and
 * Phase-0 spike data).
 *
 * Before this coordinator, [SessionManager] tracked `maxSessions` up to
 * 6 per the device tier and tried to satisfy that with multiple parallel
 * Conversations — a design that worked only because every existing test
 * used `mockk<Engine>(relaxed = true)`. On a real device the second
 * `createSession` would crash inside native init.
 *
 * # Contract
 *
 *  * At most one [com.google.ai.edge.litertlm.Conversation] is alive on the
 *    engine at any moment. The session that owns it is the **warm**
 *    session; all others are **evicted** (their `SessionHandle` is removed
 *    from [SessionManager.sessions] and their `Conversation.close()` has
 *    been called).
 *  * When the client calls `createSession(B)` and session A is warm, this
 *    coordinator runs [evictWarmFor]: if A is mid-stream, it throws an
 *    [EngineBusyException] (the client retries after A finishes); otherwise
 *    A's Conversation is closed and A's `SessionHandle` is removed.
 *  * After the new Conversation for B is created, the caller invokes
 *    [claim] to make B the warm session.
 *  * On [release] (caller-initiated destroy of B), the slot is freed.
 *
 * # Future hot-swap
 *
 * Today eviction is **terminal** — the client must call `createSession`
 * again with the same sessionId (and `initialHistory`) to resume the
 * evicted session. A subsequent PR can use the per-session
 * `recordedTurns` from [SessionManager.SessionHandle] (added in commit
 * d56ec4b) to **automatically** re-create the prior Conversation on next
 * access. That requires making `SessionHandle.conversation` nullable and
 * funneling every access through `ensureWarm(handle)`, plus auditing
 * every `cancelProcess` / `close` call site for the cold case — out of
 * scope for this PR.
 *
 * # Locking
 *
 * One global [slotMutex] orders every slot-affecting operation. The
 * coordinator briefly enters per-handle `Mutex` instances ONLY via
 * `tryLock()` (to detect in-flight streams) — never blocking acquisition.
 * This makes the lock order strict (slotMutex → handle.mutex.tryLock) so
 * deadlock with [InferenceOrchestrator]'s per-handle `withLock` is
 * impossible.
 */
internal class WarmConversationSlot {

    private val slotMutex = Mutex()
    private val warmSessionId = AtomicReference<String?>(null)

    /** ID of the session that currently owns the native engine slot, or null. */
    val currentWarmSessionId: String? get() = warmSessionId.get()

    /**
     * Free the slot so a new session can claim it. If the warm session is
     * currently streaming (its [SessionManager.SessionHandle.mutex] is held
     * by the orchestrator), throws [EngineBusyException] with a retry-after
     * hint — the caller (typically `SessionManager.createSession`) should
     * translate this into the wire error and let the client retry once the
     * stream finishes.
     *
     * If the warm session is idle, its `Conversation` is closed and its
     * [SessionManager.SessionHandle] is removed from [sessionsForRemoval].
     * The slot is left empty so [claim] can install the new session.
     *
     * Caller MUST NOT hold any `SessionHandle.mutex` when invoking this.
     */
    fun evictWarmFor(
        newSessionId: String,
        sessionsForRemoval: MutableMap<String, SessionManager.SessionHandle>,
    ) = runBlocking {
        slotMutex.withLock {
            val priorId = warmSessionId.get() ?: return@withLock
            if (priorId == newSessionId) {
                // Re-claim by same session — leave slot in place; caller's
                // new Conversation will replace the existing one via the
                // engine's swap path.
                return@withLock
            }
            val prior = sessionsForRemoval[priorId]
            if (prior == null) {
                // Stale slot marker — slot tracked an ID that's no longer in
                // the sessions map (orphaned by a crash recovery or test
                // teardown). Clear it and move on.
                warmSessionId.compareAndSet(priorId, null)
                return@withLock
            }
            // tryLock: if the orchestrator holds the per-handle mutex, the
            // session is mid-stream and we cannot safely close the native
            // Conversation underneath it.
            if (!prior.mutex.tryLock()) {
                throw EngineBusyException(
                    busySessionId = priorId,
                    requestedSessionId = newSessionId,
                    retryAfterMs = ENGINE_BUSY_RETRY_MS,
                )
            }
            try {
                MindlayerLog.i(
                    TAG,
                    "Evicting warm session to make room for new session $newSessionId",
                    sessionId = priorId,
                )
                try { prior.conversation.close() } catch (t: Throwable) {
                    MindlayerLog.w(
                        TAG,
                        "Conversation.close() on warm-slot eviction raised: ${t.safeLabel()}",
                        sessionId = priorId,
                    )
                }
                sessionsForRemoval.remove(priorId)
                warmSessionId.compareAndSet(priorId, null)
            } finally {
                prior.mutex.unlock()
            }
        }
    }

    /**
     * Mark [sessionId] as the current warm session. Call after a successful
     * `engine.createConversation(...)` for the new session.
     */
    fun claim(sessionId: String) {
        warmSessionId.set(sessionId)
    }

    /**
     * Release the slot when the warm session is being destroyed (caller-
     * initiated `destroySession`). No-op if [sessionId] is not the current
     * warm session — a destroy of an already-evicted session has nothing
     * to release here.
     */
    fun release(sessionId: String) {
        warmSessionId.compareAndSet(sessionId, null)
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
 * Thrown by [WarmConversationSlot.evictWarmFor] when the engine's single
 * native session slot is held by another session that is currently
 * processing an inference. The client should wait [retryAfterMs] and
 * retry the request. Translated to [MindlayerErrorCode.ENGINE_BUSY] at
 * the AIDL boundary.
 */
class EngineBusyException(
    val busySessionId: String,
    val requestedSessionId: String,
    val retryAfterMs: Long,
) : IllegalStateException(
    "engine_busy: session '$requestedSessionId' cannot start while session " +
        "'$busySessionId' is mid-stream (retry after ${retryAfterMs}ms)"
)
