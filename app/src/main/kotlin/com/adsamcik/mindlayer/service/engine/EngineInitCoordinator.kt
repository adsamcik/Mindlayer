package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates the lifecycle of the background LiteRT-LM engine init job
 * on behalf of [SessionManager]:
 *
 *  * Dispatcher: a dedicated single-threaded coroutine scope so binder
 *    threads never block on the 5–10 s native init path (F-018).
 *  * Coalescing: at most one init job is in flight at a time; concurrent
 *    callers all see the same job (CAS race on [initJob]).
 *  * Failure cache: the most recent terminal failure plus a per-variant
 *    `retryAfterMs` TTL. Callers can ask whether a cached failure is still
 *    blocking them, and the cache is lazily cleared once the TTL elapses
 *    so a recovered situation (user closed background apps, model SHA
 *    fix-up) self-heals without a service restart (F-071, H-4, H-E1).
 *  * Restart intent: honours an [EngineRestartStore] intent persisted by
 *    the previous process so a thermal-driven or memory-driven restart
 *    re-attaches the right backend.
 *
 * Extracted from `SessionManager` in Phase 1c of the SessionManager
 * decomposition. Behaviour is byte-identical to the in-class version —
 * only the ownership and naming changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineInitCoordinator(
    private val engineManager: EngineManager,
    initDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {

    companion object {
        private const val TAG = "EngineInitCoordinator"

        // H-E1: per-variant retry policy. Transient causes get a finite window
        // so the service recovers in-process; structural causes (missing
        // model file, integrity mismatch) are effectively permanent because
        // retrying without operator action would just churn.
        private const val LOW_MEMORY_RETRY_MS = 30_000L
        private const val BACKEND_UNAVAILABLE_RETRY_MS = 60_000L
        private const val NATIVE_ERROR_RETRY_MS = 60_000L
        private const val DEFAULT_RETRY_MS = 60_000L
    }

    /**
     * Snapshot of a cached terminal init failure, returned by
     * [pollCachedFailure]. Callers should throw [throwable] (which is the
     * original exception caught from `engineManager.initialize(...)`) so the
     * SDK sees the same typed error it would have seen during the original
     * failed attempt.
     */
    data class CachedInitError(
        val throwable: Throwable,
        val failedAtElapsedMs: Long,
        val retryAfterMs: Long,
    )

    // F-018: dedicated single-threaded slot for engine init. Coalesces
    // concurrent first callers so binder threads never block on the
    // ~5-10 s LiteRT-LM init path.
    private val initScope = CoroutineScope(SupervisorJob() + initDispatcher)
    private val initJob = AtomicReference<Job?>(null)
    private val lastInitError = AtomicReference<CachedInitError?>(null)

    /**
     * If a terminal init failure is cached AND the per-variant TTL has not
     * elapsed, returns it so the caller can rethrow. Otherwise lazily
     * clears the cache (so a recovered situation triggers a fresh attempt)
     * and returns `null`.
     */
    fun pollCachedFailure(): CachedInitError? {
        val cached = lastInitError.get() ?: return null
        val ageMs = SystemClock.elapsedRealtime() - cached.failedAtElapsedMs
        if (ageMs < cached.retryAfterMs) {
            return cached
        }
        // TTL elapsed: discard so the next caller triggers a fresh init.
        // CAS so a concurrent successful init that already nulled the
        // field is not clobbered.
        lastInitError.compareAndSet(cached, null)
        return null
    }

    /** Direct accessor for the current cached failure without TTL check. Used
     *  by [SessionManager] in the awaitReady-Failed recovery path. */
    fun peekCachedFailure(): CachedInitError? = lastInitError.get()

    /**
     * F-018: idempotently kick off a background engine init. If a job is
     * already in flight, do nothing. The CAS race handler guarantees that
     * two threads hitting this simultaneously only spawn one underlying
     * init.
     *
     * Honours any persisted [EngineRestartStore] intent — if the previous
     * process recorded a thermal-driven or memory-driven restart, the
     * intent's backend + maxTokens override the caller's preference.
     */
    fun startInitIfNeeded(preferredBackend: String?, maxTokens: Int) {
        if (initJob.get()?.isActive == true) return
        val job = initScope.launch(start = CoroutineStart.LAZY) {
            try {
                // LiteRT-LM #2028 process-restart workaround. See full
                // rationale in ServiceBinder.startEngineWarmup. A prior
                // process may have recorded an EngineRestartStore intent
                // (thermal switch or memory-pressure unload) just before
                // killing itself.
                //
                // Defensive against:
                //  - store-IO failure (catch any throwable → fall back
                //    to the caller's preferredBackend)
                //  - mockk(relaxed = true) of EngineManager that
                //    auto-generates a relaxed-mock RestartIntent instead
                //    of returning null. attemptCount > 0 guard rejects
                //    those (EngineRestartStore.record always writes
                //    attemptCount.coerceAtLeast(1), so 0 is impossible
                //    for a real persisted intent).
                val intent = @Suppress("TooGenericExceptionCaught") try {
                    engineManager.consumePendingRestartIntent()?.takeIf { it.attemptCount > 0 }
                } catch (_: Throwable) {
                    null
                }
                val backend = intent?.targetBackend ?: preferredBackend
                val tokens = intent?.maxTokens ?: maxTokens
                if (intent != null) {
                    MindlayerLog.i(
                        TAG,
                        "startInitIfNeeded honoring restart intent: reason=${intent.reason}, " +
                            "targetBackend=${intent.targetBackend ?: "<default>"}, " +
                            "attempt=${intent.attemptCount}",
                    )
                }
                MindlayerLog.i(TAG, "Background engine init starting (backend=$backend, maxTokens=$tokens)")
                engineManager.initialize(
                    preferredBackend = backend,
                    maxTokens = tokens,
                )
                if (intent != null) {
                    @Suppress("TooGenericExceptionCaught")
                    try { engineManager.clearPendingRestartIntent() } catch (_: Throwable) { /* best-effort */ }
                }
                // F-071: clear any previously-cached terminal failure so
                // a recovered situation (e.g. user closed background apps
                // between attempts) can serve sessions normally.
                lastInitError.set(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // H-4 / H-E1: cache every terminal init failure variant with
                // a per-category TTL so callers see a deterministic typed
                // error instead of looping on ENGINE_INITIALIZING — and so
                // a recoverable failure (LowMemory, transient native) gets
                // retried automatically once the TTL elapses.
                val retryAfterMs = retryAfterMsFor(engineManager.lastInitFailure)
                lastInitError.set(
                    CachedInitError(
                        throwable = t,
                        failedAtElapsedMs = SystemClock.elapsedRealtime(),
                        retryAfterMs = retryAfterMs,
                    )
                )
                MindlayerLog.w(
                    TAG,
                    "Background engine init failed (retryAfterMs=$retryAfterMs): ${t.safeLabel()}",
                    throwable = null,
                )
            }
        }
        if (initJob.compareAndSet(null, job)) {
            job.invokeOnCompletion { initJob.compareAndSet(job, null) }
            job.start()
        } else {
            // Lost the race — another thread already started a job.
            job.cancel()
        }
    }

    /**
     * Block until the currently-running init job completes (or returns
     * immediately if none is in flight). Used by callers that saw
     * [EngineState.Failed] from `engineManager.awaitReady(...)` and want to
     * be sure the failure has been recorded before they rethrow.
     */
    fun awaitCurrentInitJob() {
        runBlocking { initJob.get()?.join() }
    }

    /**
     * Returns `true` if [failure] is the placeholder produced by
     * [EngineManager.awaitReady]'s own timeout — distinct from a genuine
     * native init failure. Callers use this to translate the synthetic
     * timeout into [EngineNotReadyException] (retry-after) instead of
     * surfacing the timeout as a terminal failure.
     */
    fun isSyntheticInitTimeout(failure: InitFailure): Boolean =
        failure is InitFailure.NativeError && failure.safeLabel == "init timeout"

    /**
     * Cancel any in-flight init job, drop the dispatcher scope, and clear
     * the cached failure. Called during service teardown so the worker
     * thread isn't leaked and a fresh service instance starts clean.
     */
    fun shutdown() {
        // F-018: cancel any pending init job and tear down the dedicated
        // dispatcher scope so we don't leak the worker thread.
        initScope.cancel()
        initJob.set(null)
        // F-071: drop any cached terminal init failure so a fresh
        // service instance can re-attempt cleanly.
        lastInitError.set(null)
    }

    private fun retryAfterMsFor(failure: InitFailure?): Long = when (failure) {
        is InitFailure.LowMemory -> LOW_MEMORY_RETRY_MS
        is InitFailure.BackendUnavailable -> BACKEND_UNAVAILABLE_RETRY_MS
        is InitFailure.NativeError -> NATIVE_ERROR_RETRY_MS
        InitFailure.ModelMissing -> Long.MAX_VALUE
        InitFailure.IntegrityMismatch -> Long.MAX_VALUE
        null -> DEFAULT_RETRY_MS
    }
}
