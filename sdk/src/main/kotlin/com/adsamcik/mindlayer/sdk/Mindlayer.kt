package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.EngineInfo
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.IClientCallback
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Client for the Mindlayer on-device LLM service.
 *
 * Mindlayer runs large language models (Gemma, etc.) locally on the device
 * as a separate Android service. This class manages the connection lifecycle,
 * session creation, and inference requests.
 *
 * **Quick start:**
 * ```kotlin
 * val mindlayer = Mindlayer.connect(context)
 * mindlayer.awaitConnected()
 *
 * val sessionId = mindlayer.createSession {
 *     systemPrompt("You are a helpful assistant.")
 * }
 *
 * mindlayer.chat(sessionId, "Hello!").events.collect { event ->
 *     when (event) {
 *         is MindlayerEvent.TextDelta -> print(event.text)
 *         is MindlayerEvent.Done -> println()
 *         else -> {}
 *     }
 * }
 *
 * mindlayer.disconnect()
 * ```
 *
 * **Lifecycle:** Call [connect] to bind to the service, [awaitConnected] to
 * wait for the connection, and [disconnect] to release resources. The service
 * continues running independently — reconnecting will find existing sessions.
 *
 * **Models:** Mindlayer discovers available models automatically.
 * The best available model is always used.
 *
 * **Thread safety:** All methods are safe to call from any thread.
 * Suspend methods use the caller's coroutine context.
 */
/** Inference backend for engine pre-warming. */
enum class InferenceBackend(internal val value: String) {
    /** GPU acceleration (default, recommended). */
    GPU("GPU"),
    /** CPU fallback. */
    CPU("CPU"),
    /** Neural Processing Unit (device-specific). */
    NPU("NPU"),
}

class Mindlayer private constructor(
    internal val connection: ConnectionManager,
    private val historyStore: HistoryStore?,
) {

    companion object {
        private const val TAG = "Mindlayer"

        internal const val TOOL_CALL_IN_ONESHOT_MSG =
            "Tool calls are not supported in one-shot mode. " +
            "Use the streaming chat() API with a ToolCall handler instead."

        private const val DEFAULT_CREATE_SESSION_INIT_RETRY_TIMEOUT_MS = 10_000L
        private val DEFAULT_CREATE_SESSION_INIT_RETRY_BACKOFF_MS = listOf(50L, 200L, 800L)

        /**
         * Per-instance buffer for [evictionNotices]. 64 entries is enough to
         * absorb a device-wide memory-pressure storm (which evicts every
         * non-streaming session, typically ≤8) plus normal expiration churn
         * without dropping anything in steady-state.
         */
        const val EVICTION_BUFFER: Int = 64

        /**
         * Create a [Mindlayer] instance and start binding to the service.
         * Use [awaitConnected] or observe [connectionState] before issuing RPCs.
         *
         * History is metadata-only by default. Pass [HistoryPolicy.FULL_CONTENT]
         * only if the client explicitly needs local replay/recovery and has its
         * own consent/retention controls for prompt and model-output content.
         *
         * @param historyPolicy controls what — if anything — is persisted in the
         *   SQLCipher-encrypted Room database at
         *   `context.getDatabasePath("mindlayer_history.db")`. Defaults to
         *   [HistoryPolicy.METADATA_ONLY] (privacy-by-default; metadata only,
         *   no prompt or model-output content). Pass [HistoryPolicy.FULL_CONTENT]
         *   only when the host app explicitly opts in to full conversation
         *   history (to power a history UI or enable session recovery after
         *   process death) and has its own consent/retention controls.
         */
        fun connect(
            context: Context,
            historyPolicy: HistoryPolicy = HistoryPolicy.METADATA_ONLY,
        ): Mindlayer {
            val mgr = ConnectionManager()
            mgr.connect(context)
            val store = HistoryStore(context, historyPolicy)
            return Mindlayer(mgr, store)
        }
    }

    /**
     * Session recovery helper. `null` when [historyStore] is disabled.
     */
    val recovery: SessionRecovery? =
        historyStore?.let { SessionRecovery(this, it) }

    /** Observable connection state. */
    val connectionState: StateFlow<ConnectionState>
        get() = connection.state

    internal var createSessionInitRetryTimeoutMs: Long =
        DEFAULT_CREATE_SESSION_INIT_RETRY_TIMEOUT_MS

    internal var createSessionInitRetryBackoffMs: List<Long> =
        DEFAULT_CREATE_SESSION_INIT_RETRY_BACKOFF_MS

    internal var createSessionInitRetryClockMs: () -> Long = { System.currentTimeMillis() }

    /** Suspend until the service binder is available. */
    suspend fun awaitConnected() {
        connection.awaitConnected()
    }

    // -- v0.4 eviction-callback subscription ---------------------------------

    /**
     * Bounded notice buffer — coalesces rapid eviction storms (memory
     * pressure can retire several sessions in quick succession) and
     * drops the oldest if no consumer is reading.
     */
    /**
     * Replay buffer for deferred completions.
     *
     * `replay = 1` so the documented pattern in `SDK_INTEGRATION.md`
     * (`chatDeferred(...)` immediately followed by `deferredCompletions()
     * .collect { ... }`) catches the notice when the inference completes
     * between submit and collect. Multiple concurrent `awaitDeferred`
     * collectors filter by `requestId`, so seeing the same replayed last
     * notice is benign.
     */
    private val _deferredCompletionFlow = MutableSharedFlow<DeferredCompletionNotice>(
        replay = 1,
        extraBufferCapacity = EVICTION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _evictionFlow = MutableSharedFlow<EvictionNotice>(
        replay = 0,
        extraBufferCapacity = EVICTION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _embeddingBatchCompleteFlow = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = EVICTION_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Shared callback instance — registered exactly once per Mindlayer
     * lifetime. Service-side idempotency keys on `asBinder()` so
     * resubscribing this same instance after a reconnect is a no-op.
     *
     * Lazy because [IClientCallback.Stub]'s constructor calls
     * [android.os.Binder.attachInterface], which is only mocked under the
     * Robolectric framework. Plain-JUnit tests that never reach a
     * CONNECTED transition (and therefore never invoke
     * [maybeSubscribeEvictions]) skip the Binder dependency entirely.
     */
    private val evictionCallback: IClientCallback by lazy {
        object : IClientCallback.Stub() {
            override fun onSessionEvicted(sessionId: String?, reasonCode: Int) {
                val sid = sessionId ?: return
                _evictionFlow.tryEmit(EvictionNotice(sid, reasonCode))
            }

            override fun onDeferredInferenceComplete(requestId: String?, statusCode: Int) {
                val rid = requestId ?: return
                _deferredCompletionFlow.tryEmit(DeferredCompletionNotice(rid, statusCode))
            }

            override fun onEmbeddingBatchComplete(requestId: String?) {
                val rid = requestId ?: return
                _embeddingBatchCompleteFlow.tryEmit(rid)
            }
        }
    }

    /**
     * Coroutine scope owning the connection-state observer that handles
     * automatic re-subscription on each fresh CONNECTED transition.
     * Cancelled by [disconnect]; survives transient disconnects.
     */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var lastSubscribedState: ConnectionState? = null

    init {
        // Auto-subscribe on every new CONNECTED transition so the eviction
        // flow stays live across rebind cycles. The service-side registry
        // is per-binder-keyed and survives only as long as the binder does
        // — a fresh binder means a fresh subscription.
        lifecycleScope.launch {
            connection.state.collect { state ->
                // Invalidate capability cache whenever we leave CONNECTED so
                // the next `getCapabilities()` re-probes against the new
                // binder rather than serving a stale cap set.
                if (state != ConnectionState.CONNECTED) {
                    cachedCapabilities = null
                }
                if (state == ConnectionState.CONNECTED && lastSubscribedState != ConnectionState.CONNECTED) {
                    lastSubscribedState = ConnectionState.CONNECTED
                    maybeSubscribeEvictions()
                } else if (state != ConnectionState.CONNECTED) {
                    lastSubscribedState = state
                }
            }
        }
    }

    /**
     * Stream of eviction notices for sessions owned by this caller.
     *
     * Always-on; subscription is automatic and survives reconnects. Emissions
     * are bounded to the most-recent [EVICTION_BUFFER] items per consumer
     * with `DROP_OLDEST` overflow, so a slow collector cannot back-pressure
     * the service-side dispatcher.
     *
     * Returns an empty (never-emitting) flow when the connected service does
     * not advertise [ServiceCapabilities.FEATURE_EVICTION_CALLBACK]. SDK
     * consumers can additionally check `getCapabilities()` to surface a
     * "no eviction notices on this service version" diagnostic.
     */
    fun evictionNotices(): Flow<EvictionNotice> = _evictionFlow.asSharedFlow()

    fun deferredCompletions(): Flow<DeferredCompletionNotice> = _deferredCompletionFlow.asSharedFlow()

    /**
     * Cold flow of deferred embedding batch completion push events.
     *
     * Emits each requestId received from IClientCallback.onEmbeddingBatchComplete.
     * Collection first verifies [ServiceCapabilities.FEATURE_EMBEDDINGS]; old
     * service binaries throw [MindlayerException] with NOT_SUPPORTED.
     */
    fun embeddingBatchCompletions(): Flow<String> = flow {
        requireEmbeddingCapability()
        _embeddingBatchCompleteFlow.collect { emit(it) }
    }

    /**
     * Best-effort subscribe — invoked on every fresh CONNECTED transition.
     *
     * Intentionally does **not** pre-check capabilities to avoid racing
     * test fixtures that mock the service: the [getCapabilities] call would
     * populate the cache before the test gets to override the mock.
     * Old services without `subscribeEvictionNotices` throw
     * `AbstractMethodError` / `NoSuchMethodError`; both are swallowed so
     * the SDK still works without push notifications, the user just polls
     * instead.
     */
    private fun maybeSubscribeEvictions() {
        try {
            val service = connection.getService() ?: return
            service.subscribeEvictionNotices(evictionCallback)
        } catch (_: NoSuchMethodError) {
            // Old AIDL stub; nothing to do.
        } catch (_: AbstractMethodError) {
            // Old service implementation without this method.
        } catch (_: SecurityException) {
            // Auth gate refused — handled by the regular RPC path; ignore here.
        } catch (_: android.os.RemoteException) {
            // Binder went away mid-subscribe; the next CONNECTED transition
            // will re-subscribe.
        } catch (_: Throwable) {
            // Defensive: never let an eviction-channel hiccup propagate.
        }
    }

    // -- Capabilities ---------------------------------------------------------

    @Volatile private var cachedCapabilities: ServiceCapabilities? = null

    /**
     * Probe and cache the [ServiceCapabilities] of the connected service.
     *
     * The first call after [awaitConnected] performs the AIDL handshake and
     * caches the result for the lifetime of this [Mindlayer] instance.
     * Subsequent calls return the cached value without crossing the wire.
     *
     * **Old service compatibility**: if the connected service predates the
     * `getCapabilities` AIDL method (built before v0.2), this falls back to
     * [ServiceCapabilities.v0Baseline] so feature-gated SDK code can still
     * make conservative decisions.
     *
     * Use [ServiceCapabilities.supports] to test a specific feature flag:
     *
     * ```kotlin
     * val caps = mindlayer.getCapabilities()
     * if (caps.supports(ServiceCapabilities.FEATURE_TOKEN_BATCH)) {
     *     // request batched token deltas
     * }
     * ```
     */
    suspend fun getCapabilities(): ServiceCapabilities {
        cachedCapabilities?.let { return it }
        val service = connection.awaitConnected()
        val caps = try {
            service.capabilities
        } catch (_: NoSuchMethodError) {
            // AIDL stub from an older SDK; binder dispatch returned no
            // implementation for this method on the remote side.
            ServiceCapabilities.v0Baseline()
        } catch (_: AbstractMethodError) {
            // Same shape, different exception class on some Android versions.
            ServiceCapabilities.v0Baseline()
        } catch (e: SecurityException) {
            // Auth-gate refusal — propagate. Capabilities are NOT typed-error
            // gated; an un-prefixed SecurityException here means the caller
            // isn't on the allowlist and this binding is going to be torn down.
            throw e
        }
        cachedCapabilities = caps
        return caps
    }

    /**
     * Cheap kernel-level liveness probe. Returns `true` if the service binder
     * is alive and accepting transactions, `false` if the binder is dead, the
     * remote process has crashed, or this client has not yet connected.
     *
     * Implemented via [android.os.IBinder.pingBinder] so it does **not**
     * consume rate-limit budget and does not exercise the service's own
     * threading — a deadlocked service may still pass this check. Use
     * [getStatus] (which goes through the auth gate at quarter-cost) when you
     * need real service-state liveness.
     */
    fun isAlive(): Boolean = connection.getService()?.asBinder()?.pingBinder() == true

    /**
     * Unbind from the Mindlayer service and release resources.
     *
     * Active inference flows will complete with [MindlayerEvent.Error] (code: "DISCONNECTED").
     * Blocking one-shot methods ([chatOnce], [generate]) will throw [MindlayerException].
     * Sessions are preserved on the service side and can be resumed after [connect].
     *
     * Safe to call from any thread. Idempotent — multiple calls are harmless.
     */
    fun disconnect() {
        cachedCapabilities = null
        // v0.4: cancel the eviction-resubscribe observer so it doesn't try
        // to re-register against a binding we're tearing down. The service
        // side will GC the registration when its binder dies anyway, but
        // an explicit unsubscribe is cleaner and keeps the registry small
        // for long-lived service processes serving many short-lived clients.
        // Guard the lazy access — if the callback was never materialized
        // (no CONNECTED transition ever fired), there's nothing to unsubscribe.
        if (lastSubscribedState == ConnectionState.CONNECTED) {
            try {
                val svc = connection.getService()
                svc?.unsubscribeEvictionNotices(evictionCallback)
            } catch (_: Throwable) {
                // ignore — disconnect must always succeed.
            }
        }
        lifecycleScope.cancel()
        connection.disconnect()
    }

    // -- Internal: AIDL error chokepoint --------------------------------------

    /**
     * Suspend chokepoint that converts service-thrown
     * Mindlayer-coded Binder runtime exceptions into typed [MindlayerException]s.
     *
     * Wrap every public AIDL call site through this so callers see one error
     * vocabulary regardless of which method threw. [SecurityException] from the
     * auth gate (allowlist rejection, registration precondition) propagates
     * unchanged — it remains the canonical "you don't have the right to do
     * this" signal so IDS / Play Protect doesn't lose meaning.
     */
    private suspend inline fun <R> withTypedErrors(
        requestId: String? = null,
        sessionId: String? = null,
        crossinline block: suspend (com.adsamcik.mindlayer.IMindlayerService) -> R,
    ): R = try {
        block(connection.awaitConnected())
    } catch (e: SecurityException) {
        throw MindlayerException.fromAidlSecurityException(e, requestId, sessionId) ?: throw e
    }

    /**
     * Non-suspend variant for paths that already hold a connected service
     * reference (e.g. [startInference], where the AIDL call kicks off a cold
     * flow and must run synchronously to produce a request handle).
     */
    private inline fun <R> withTypedErrorsSync(
        service: com.adsamcik.mindlayer.IMindlayerService,
        requestId: String? = null,
        sessionId: String? = null,
        block: (com.adsamcik.mindlayer.IMindlayerService) -> R,
    ): R = try {
        block(service)
    } catch (e: SecurityException) {
        throw MindlayerException.fromAidlSecurityException(e, requestId, sessionId) ?: throw e
    }

    // -- Prewarm --------------------------------------------------------------

    /**
     * Pre-warm the LLM engine in the background (fire-and-forget). Call this
     * early (e.g. when an inference-bound screen opens) so the first
     * [createSession] doesn't pay the ~5-10 s cold-start cost. Safe to call
     * multiple times — subsequent calls are no-ops if the engine is already
     * loaded.
     *
     * **Returns immediately** regardless of whether the engine has finished
     * initializing — use [prewarmAndAwait] if you need to know when init
     * completes or which fallback backend was selected.
     *
     * @param backend the preferred backend to initialize.
     */
    suspend fun prewarm(backend: InferenceBackend = InferenceBackend.GPU) {
        withContext(Dispatchers.IO) {
            val service = connection.awaitConnected()
            service.prewarm(backend.value)
        }
    }

    /**
     * Synchronously pre-warm the engine and return the actually-active
     * [InferenceBackend]. Suspends until either init completes or the
     * service-side timeout (clamped to ≤30 s) elapses.
     *
     * If the connected service does not advertise
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_PREWARM_AWAIT]
     * (talking to a pre-v0.4 binary), this falls back to the legacy
     * fire-and-forget [prewarm] path and returns the requested [backend]
     * without waiting.
     *
     * @param backend the preferred backend.
     * @param timeoutMs upper bound on how long the SDK is willing to wait
     *   for the service to confirm engine readiness. Service may clamp
     *   this further to its own MIN/MAX bounds. Default 15 s.
     */
    suspend fun prewarmAndAwait(
        backend: InferenceBackend = InferenceBackend.GPU,
        timeoutMs: Long = 15_000L,
    ): InferenceBackend {
        val caps = getCapabilities()
        if (!caps.supports(com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_PREWARM_AWAIT)) {
            // Old service — fire-and-forget and return the requested backend.
            prewarm(backend)
            return backend
        }
        val activeBackend = try {
            withTypedErrors {
                it.prewarmAndAwait(backend.value, timeoutMs)
            }
        } catch (_: NoSuchMethodError) {
            prewarm(backend)
            return backend
        } catch (_: AbstractMethodError) {
            prewarm(backend)
            return backend
        }
        return InferenceBackend.values().firstOrNull { it.value == activeBackend }
            ?: backend
    }

    // -- Session management ---------------------------------------------------

    /**
     * Create a new inference session.
     *
     * Uses a local-first saga: persist locally as CREATING, then create the
     * remote session, then confirm locally as READY. If the remote call fails,
     * the local CREATING record is cleaned up immediately.
     *
     * @param configure optional DSL block to customise [SessionConfig].
     * @return the server-assigned session ID.
     */
    suspend fun createSession(
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val config = SessionConfigBuilder().apply(configure).build()

        // 1. Persist locally as CREATING (survives process death)
        val tentativeId = config.sessionId ?: java.util.UUID.randomUUID().toString()
        val configWithId = if (config.sessionId == null) {
            config.copy(sessionId = tentativeId)
        } else {
            config
        }
        historyStore?.prepareConversation(tentativeId, configWithId)

        // 2. Create remote session.
        // F-018: if the service responds with `engine_initializing`, the
        // engine is doing its (~5–10 s) cold-start init on a dedicated
        // background slot. Retry with exponential backoff up to ~10 s
        // total before giving up so first-launch UX matches the
        // documented "5–10 s cold-start wait" contract.
        val sessionId = try {
            createSessionWithInitRetry(configWithId)
        } catch (e: Exception) {
            // Remote creation failed — clean up local CREATING record
            historyStore?.cleanupConversation(tentativeId)
            throw e
        }

        // 3. Confirm local record
        historyStore?.confirmConversation(sessionId)

        sessionId
    }

    private suspend fun createSessionWithInitRetry(configWithId: SessionConfig): String {
        // Backoff schedule: 50ms → 200ms → 800ms → 800ms ... until the
        // documented cold-start retry window expires.
        val retryBackoffMs = createSessionInitRetryBackoffMs.ifEmpty {
            DEFAULT_CREATE_SESSION_INIT_RETRY_BACKOFF_MS
        }
        var attempt = 0
        val deadline = createSessionInitRetryClockMs() +
            createSessionInitRetryTimeoutMs.coerceAtLeast(0L)
        while (true) {
            try {
                return connection.awaitConnected().createSession(configWithId)
            } catch (e: SecurityException) {
                val typed = MindlayerException.fromAidlSecurityException(
                    e,
                    sessionId = configWithId.sessionId,
                ) ?: throw e
                if (typed.code ==
                    com.adsamcik.mindlayer.shared.MindlayerErrorCode.ENGINE_INITIALIZING
                ) {
                    val remainingMs = deadline - createSessionInitRetryClockMs()
                    if (remainingMs > 0L) {
                        val backoffMs = retryBackoffMs[
                            attempt.coerceAtMost(retryBackoffMs.lastIndex)
                        ].coerceAtLeast(1L)
                        delay(backoffMs.coerceAtMost(remainingMs))
                        attempt++
                        continue
                    }
                }
                throw typed
            }
        }
    }

    /** Destroy a session and free server-side resources. */
    suspend fun destroySession(sessionId: String) {
        withTypedErrors(sessionId = sessionId) { it.destroySession(sessionId) }
    }

    /** Get info for a single session. */
    suspend fun getSessionInfo(sessionId: String): SessionInfo {
        return withTypedErrors(sessionId = sessionId) { it.getSessionInfo(sessionId) }
    }

    /** List all live server-side sessions owned by this caller.
     *
     *  This goes to the **service** and returns only sessions that the
     *  service still knows about — it does **not** include conversations
     *  whose sessions were destroyed, evicted, or expired. For the durable
     *  view of every conversation this app has tracked locally, see
     *  [listHistory]. The two views are intentionally distinct: live
     *  sessions are server state, history is encrypted local persistence
     *  with a different threat model.
     */
    suspend fun listSessions(): List<SessionInfo> {
        return withTypedErrors { it.listSessions() }
    }

    // -- Chat (text only) -----------------------------------------------------

    /**
     * Send a text message and stream back inference events.
     *
     * Suspends until the service is connected, then issues the inference
     * request and returns an [InferenceHandle]. The handle's [requestId]
     * is generated synchronously before the suspend, so callers can wire
     * up cancel callbacks ahead of time even before this returns.
     *
     * Creates a reliable pipe, hands the write end to the service via AIDL,
     * and returns an [InferenceHandle] that provides the [requestId], event
     * [Flow], and a [cancel][InferenceHandle.cancel] function that reaches
     * through to the service's native cancel.
     *
     * History persistence: the user turn is saved BEFORE IPC and the
     * assistant turn is marked COMPLETED only after the [MindlayerEvent.Done]
     * event.
     */
    suspend fun chat(sessionId: String, text: String): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            imageProvider = { null },
            audioProvider = { null },
        )
        return buildHandle(requestId, flow)
    }

    // -- Chat with image ------------------------------------------------------

    /**
     * Send a text + Bitmap message and stream back inference events.
     */
    suspend fun chatWithImage(
        sessionId: String,
        text: String,
        bitmap: Bitmap,
    ): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            imageProvider = { MediaTransfer.fromBitmap(requestId, bitmap) },
            audioProvider = { null },
        )
        return buildHandle(requestId, flow)
    }

    // -- Chat with audio ------------------------------------------------------

    /**
     * Send a text + audio file message and stream back inference events.
     */
    suspend fun chatWithAudio(
        sessionId: String,
        text: String,
        audioFile: File,
    ): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            imageProvider = { null },
            audioProvider = { MediaTransfer.fromAudioFile(requestId, audioFile) },
        )
        return buildHandle(requestId, flow)
    }

    /**
     * Send a text message with an ordered list of media attachments. v0.4
     * successor to [chatWithImage] / [chatWithAudio] — accepts varargs of
     * [com.adsamcik.mindlayer.MediaPart] so future engines can consume
     * multi-image / video / document inputs without another wire-break.
     *
     * Build parts via the helpers on [MediaTransfer]:
     *
     * ```kotlin
     * mindlayer.chatWithMedia(
     *     sessionId,
     *     "Compare these two coffee bags",
     *     MediaTransfer.imagePart(bagA),
     *     MediaTransfer.imagePart(bagB),
     * ).events.collect { ... }
     * ```
     *
     * **Today's engine constraint** (see `MediaPart` KDoc): at most one
     * image + one audio per request. The wire allows ordered lists for
     * forward compatibility but the service rejects multi-image with
     * `INVALID_REQUEST`. Order between the kinds is wire-stable from day
     * one — clients should pass parts in their preferred order.
     *
     * **Old service compatibility**: if the connected service does not
     * advertise [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_MEDIA_LIST],
     * this method delegates to the legacy [chat] / [chatWithImage] /
     * [chatWithAudio] surface (with the corresponding 1-image / 1-audio
     * limit). Callers should still build via this entry point — the
     * fallback is transparent.
     */
    suspend fun chatWithMedia(
        sessionId: String,
        text: String,
        vararg parts: com.adsamcik.mindlayer.MediaPart,
    ): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        // Re-tag every part with this requestId so the service's
        // requestId-cross-check passes — callers built parts via the
        // MediaTransfer helpers without knowing this requestId yet.
        val rebound = parts.map { it.copy(requestId = requestId) }
        val flow = startTrackedInferenceMulti(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            media = rebound,
        )
        return buildHandle(requestId, flow)
    }


    private suspend fun requireDeferredCapability() {
        val caps = getCapabilities()
        if (!caps.supports(com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_DEFERRED_INFERENCE)) {
            throw MindlayerException(
                message = "Connected Mindlayer service does not support deferred inference",
                code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED,
            )
        }
    }

    suspend fun chatDeferred(
        sessionId: String,
        text: String,
        media: List<com.adsamcik.mindlayer.MediaPart> = emptyList(),
    ): com.adsamcik.mindlayer.DeferredHandle {
        requireDeferredCapability()
        val requestId = UUID.randomUUID().toString()
        val rebound = media.map { it.copy(requestId = requestId) }
        // H-D2: deferred submit mirrors `startInferenceMulti`'s FD-cleanup
        // pattern from PR #37. The service has dup'd the media descriptors
        // by the time `inferDeferred` returns (success) or throws, so we
        // always close our copies. Use an IdentityHashMap-backed set so a
        // caller passing the same source twice (legal — two MediaParts can
        // share an offset/length view) only gets one close.
        val mediaSources = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<ParcelFileDescriptor, Boolean>(),
        )
        rebound.forEach { mediaSources.add(it.source) }
        return try {
            withTypedErrors(requestId = requestId, sessionId = sessionId) {
                it.inferDeferred(
                    RequestMeta(requestId = requestId, sessionId = sessionId, textContent = text),
                    rebound,
                )
            }
        } catch (_: NoSuchMethodError) {
            throw MindlayerException(
                message = "Connected Mindlayer service does not implement deferred inference",
                code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED,
                requestId = requestId,
                sessionId = sessionId,
            )
        } catch (_: AbstractMethodError) {
            throw MindlayerException(
                message = "Connected Mindlayer service does not implement deferred inference",
                code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED,
                requestId = requestId,
                sessionId = sessionId,
            )
        } finally {
            mediaSources.forEach { it.closeQuietly() }
        }
    }

    suspend fun fetchDeferredResult(requestId: String): com.adsamcik.mindlayer.DeferredResult {
        requireDeferredCapability()
        return withTypedErrors(requestId = requestId) { it.fetchDeferredResult(requestId) }
    }

    suspend fun cancelDeferred(requestId: String): com.adsamcik.mindlayer.CancelResult {
        requireDeferredCapability()
        return withTypedErrors(requestId = requestId) { it.cancelDeferredInference(requestId) }
    }

    suspend fun acknowledgeDeferred(requestId: String) {
        requireDeferredCapability()
        withTypedErrors(requestId = requestId) { it.acknowledgeDeferredResult(requestId) }
    }

    suspend fun awaitDeferred(
        requestId: String,
        pollIntervalMs: Long = 250,
        timeoutMs: Long = 60_000,
    ): com.adsamcik.mindlayer.DeferredResult = withTimeout(timeoutMs) {
        // M-D7: prefer the push notice over busy-polling. Subscribe BEFORE
        // the first fetch so a completion landing between fetch and
        // collect is still observed via the `replay = 1` buffer on
        // `_deferredCompletionFlow`. The poll loop remains as a fallback
        // for services that don't deliver `onDeferredInferenceComplete`
        // (older builds, or transient binder death + reconnect where the
        // pre-reconnect notice was dropped).
        coroutineScope {
            val pushSignal = async {
                deferredCompletions()
                    .filter { it.requestId == requestId }
                    .first()
            }
            try {
                // First fetch up front — if the result is already terminal,
                // skip the wait entirely.
                val initial = fetchDeferredResult(requestId)
                if (initial.status != com.adsamcik.mindlayer.DeferredResult.STILL_RUNNING) {
                    return@coroutineScope initial
                }
                // Slow-path fallback poll. Far less aggressive than the
                // previous busy-poll because the push signal usually races
                // ahead — see M-D7. Cap minimum to keep one round-trip per
                // 250ms in the worst case.
                val pollJob = launch {
                    while (isActive) {
                        delay(pollIntervalMs.coerceAtLeast(1L))
                        val result = fetchDeferredResult(requestId)
                        if (result.status != com.adsamcik.mindlayer.DeferredResult.STILL_RUNNING) {
                            return@launch
                        }
                    }
                }
                // Either the push signal fires (preferred) or the poll
                // job exits because it observed a terminal status — in
                // both cases the next fetch returns the terminal result.
                select<Unit> {
                    pushSignal.onAwait { }
                    pollJob.onJoin { }
                }
                fetchDeferredResult(requestId)
            } finally {
                pushSignal.cancel()
            }
        }
    }

    // -- Embeddings -----------------------------------------------------------

    private suspend fun requireEmbeddingCapability(): ServiceCapabilities {
        val caps = getCapabilities()
        if (!caps.supports(ServiceCapabilities.FEATURE_EMBEDDINGS)) {
            throw MindlayerException(
                message = "Connected Mindlayer service does not support embeddings",
                code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED,
            )
        }
        return caps
    }

    private fun embeddingNotSupported(requestId: String? = null): MindlayerException = MindlayerException(
        message = "Connected Mindlayer service does not implement embeddings",
        code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED,
        requestId = requestId,
    )

    /**
     * ```kotlin
     * val mindlayer = Mindlayer.connect(context); mindlayer.awaitConnected()
     * if (!mindlayer.getCapabilities().supports(ServiceCapabilities.FEATURE_EMBEDDINGS)) {
     *     // service doesn't ship embeddings — show a friendly message
     * }
     * val vec = mindlayer.embed("the cat sat on the mat")
     * val index = InMemoryVectorIndex()
     * index.put("doc1", vec)
     * val hits = index.search(mindlayer.embed("feline on textile"), k = 5)
     * ```
     *
     * Compute a 768-dim L2-normalized embedding for [text] using the default
     * embedding model and the RETRIEVAL_DOCUMENT task prefix.
     *
     * Blocks until the embedding service is ready or fails. Throws
     * [MindlayerException] with `NOT_SUPPORTED` if the connected service doesn't
     * advertise [ServiceCapabilities.FEATURE_EMBEDDINGS] or lacks the Phase-B
     * AIDL method.
     *
     * For non-default config see [embed] ([EmbeddingConfig] overload).
     */
    suspend fun embed(text: String): FloatArray = embed(EmbeddingConfig(text = text)).vector

    /**
     * Typed embedding. See [EmbeddingConfig] for full options.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun embed(config: EmbeddingConfig): com.adsamcik.mindlayer.EmbeddingResult {
        requireEmbeddingCapability()
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors { it.embed(config.toAidlRequest()) }
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported()
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported()
        }
    }

    /**
     * Compute embeddings for [configs] in one trip. Caps at 64 inputs (inline
     * binder transport) per the negotiated capabilities. For larger batches use
     * [embedBatchLarge] (SHM transport, up to 4096) or [embedBatchDeferred]
     * (durable, push notification, up to 4096).
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun embedBatch(configs: List<EmbeddingConfig>): List<com.adsamcik.mindlayer.EmbeddingResult> {
        val caps = requireEmbeddingCapability()
        validateEmbeddingBatchSize(configs, caps.maxEmbeddingBatchInline, "inline")
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors { it.embedBatch(configs.map { config -> config.toAidlRequest() }).results }
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported()
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported()
        }
    }

    /**
     * Compute embeddings for up to 4096 inputs via SharedMemory transport.
     * Service writes the vectors to a SharedMemory region, SDK reads them
     * directly. Synchronous: blocks until all embeddings are computed.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun embedBatchLarge(configs: List<EmbeddingConfig>): List<com.adsamcik.mindlayer.EmbeddingResult> {
        val caps = requireEmbeddingCapability()
        validateEmbeddingBatchSize(configs, caps.maxEmbeddingBatchShm.takeIf { it > 0 } ?: caps.maxEmbeddingBatchTotal, "shared-memory")
        return try {
            withContext(Dispatchers.IO) {
                parseEmbeddingTransfer(withTypedErrors { it.embedBatchShm(configs.map { config -> config.toAidlRequest() }) })
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported()
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported()
        }
    }

    /**
     * Submit a large batch for durable async computation. Returns a handle
     * to fetch results later. Receives push notification when complete via
     * the existing IClientCallback (no polling needed).
     *
     * The service persists this in its DeferredStore — survives client process
     * death. Results are TTL'd (default 24h) and quota-bound per UID.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun embedBatchDeferred(configs: List<EmbeddingConfig>): EmbeddingBatchHandle {
        val caps = requireEmbeddingCapability()
        validateEmbeddingBatchSize(configs, caps.maxEmbeddingBatchTotal, "deferred")
        return try {
            withContext(Dispatchers.IO) {
                val handle = withTypedErrors { it.embedBatchDeferred(configs.map { config -> config.toAidlRequest() }) }
                EmbeddingBatchHandle(handle.requestId, handle.expiresAtMs)
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported()
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported()
        }
    }

    /**
     * Fetch the result of a deferred batch. Returns an [EmbeddingBatchOutcome]
     * variant for still-running, ready, failed, cancelled, expired, or unknown
     * requests.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun fetchEmbeddingBatch(handle: EmbeddingBatchHandle): EmbeddingBatchOutcome {
        requireEmbeddingCapability()
        return try {
            withContext(Dispatchers.IO) {
                mapVectorBlobHandle(withTypedErrors(requestId = handle.requestId) { it.fetchEmbeddingBatchResult(handle.requestId) })
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported(handle.requestId)
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported(handle.requestId)
        }
    }

    /**
     * Cancel an in-flight deferred batch. Returns the typed result.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun cancelEmbeddingBatch(handle: EmbeddingBatchHandle): EmbeddingCancelResult {
        requireEmbeddingCapability()
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors(requestId = handle.requestId) { it.cancelEmbeddingBatch(handle.requestId) }.toEmbeddingCancelResult()
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported(handle.requestId)
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported(handle.requestId)
        }
    }

    /**
     * Tell the service the client has consumed this result and it can be deleted.
     * Optional — results expire naturally — but explicit ack frees quota faster.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun acknowledgeEmbeddingBatch(handle: EmbeddingBatchHandle) {
        requireEmbeddingCapability()
        try {
            withContext(Dispatchers.IO) {
                withTypedErrors(requestId = handle.requestId) { it.acknowledgeEmbeddingBatchResult(handle.requestId) }
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported(handle.requestId)
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported(handle.requestId)
        }
    }

    /**
     * Cancel an in-flight non-deferred embedding by requestId.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    suspend fun cancelEmbed(requestId: String): EmbeddingCancelResult {
        requireEmbeddingCapability()
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors(requestId = requestId) { it.cancelEmbed(requestId) }.toEmbeddingCancelResult()
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported(requestId)
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported(requestId)
        }
    }

    private fun validateEmbeddingBatchSize(configs: List<EmbeddingConfig>, max: Int, label: String) {
        require(configs.isNotEmpty()) { "Embedding $label batch must be non-empty" }
        if (max > 0) {
            require(configs.size <= max) { "Embedding $label batch size ${configs.size} exceeds service limit $max" }
        }
    }

    private fun mapVectorBlobHandle(handle: com.adsamcik.mindlayer.VectorBlobHandle): EmbeddingBatchOutcome =
        when (handle.status) {
            com.adsamcik.mindlayer.DeferredResult.STILL_RUNNING -> EmbeddingBatchOutcome.StillRunning
            com.adsamcik.mindlayer.DeferredResult.READY -> {
                val transfer = handle.transfer
                if (transfer == null) {
                    EmbeddingBatchOutcome.Failed(
                        com.adsamcik.mindlayer.shared.MindlayerErrorCode.UNKNOWN,
                        "missing_transfer",
                    )
                } else {
                    EmbeddingBatchOutcome.Ready(
                        results = parseEmbeddingTransfer(transfer),
                        totalDurationMs = transfer.totalDurationMs,
                        backend = transfer.backend,
                    )
                }
            }
            com.adsamcik.mindlayer.DeferredResult.FAILED -> EmbeddingBatchOutcome.Failed(handle.errorCodeInt, handle.errorCodeName)
            com.adsamcik.mindlayer.DeferredResult.CANCELLED -> EmbeddingBatchOutcome.Cancelled
            com.adsamcik.mindlayer.DeferredResult.EXPIRED -> EmbeddingBatchOutcome.Expired
            com.adsamcik.mindlayer.DeferredResult.NOT_FOUND_OR_NOT_OWNED -> EmbeddingBatchOutcome.NotFound
            else -> EmbeddingBatchOutcome.Failed(handle.errorCodeInt, handle.errorCodeName)
        }

    private fun com.adsamcik.mindlayer.CancelResult.toEmbeddingCancelResult(): EmbeddingCancelResult = when (outcome) {
        com.adsamcik.mindlayer.CancelResult.CANCELLED -> EmbeddingCancelResult.Cancelled
        com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED -> EmbeddingCancelResult.AlreadyFinished
        else -> EmbeddingCancelResult.Unknown
    }

    private fun parseEmbeddingTransfer(transfer: com.adsamcik.mindlayer.EmbeddingBatchTransfer): List<com.adsamcik.mindlayer.EmbeddingResult> {
        val pfd = transfer.pfd
        return try {
            val count = transfer.count
            val dim = transfer.dim
            require(count >= 0 && dim >= 0) { "Invalid embedding transfer shape count=$count dim=$dim" }
            val expectedBytesLong = 8L + count.toLong() * dim.toLong() * 4L
            require(expectedBytesLong <= Int.MAX_VALUE) { "Embedding transfer too large" }
            val bytes = ByteArray(expectedBytesLong.toInt())
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
                require(offset == bytes.size) { "Embedding transfer truncated: read $offset of ${bytes.size} bytes" }
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val encodedCount = buffer.int
            val encodedDim = buffer.int
            require(encodedCount == count && encodedDim == dim) {
                "Embedding transfer header count=$encodedCount dim=$encodedDim did not match metadata count=$count dim=$dim"
            }
            require(transfer.perItemMetadata.size == count) {
                "Embedding metadata count ${transfer.perItemMetadata.size} did not match vector count $count"
            }
            List(count) { index ->
                val vector = FloatArray(dim) { buffer.float }
                val metadata = transfer.perItemMetadata[index]
                com.adsamcik.mindlayer.EmbeddingResult(
                    tag = metadata.tag,
                    vector = vector,
                    dim = dim,
                    modelId = transfer.modelId,
                    tokenCount = metadata.tokenCount,
                    truncated = metadata.truncated,
                    backend = transfer.backend,
                    durationMs = 0L,
                )
            }
        } finally {
            try { pfd.close() } catch (_: Throwable) { }
        }
    }
    // -- Tool calling ---------------------------------------------------------

    /**
     * Submit a tool result back to the service for continued inference.
     *
     * Use the [MindlayerEvent.ToolCall.callId] from the tool-call event being answered.
     */
    suspend fun submitToolResult(
        requestId: String,
        callId: String,
        toolName: String,
        resultJson: String,
    ) {
        val result = ToolResult(
            requestId = requestId,
            callId = callId,
            toolName = toolName,
            resultJson = resultJson,
        )
        withTypedErrors(requestId = requestId) { it.submitToolResult(requestId, result) }
    }

    /**
     * Submit a tool result and receive a tri-state outcome (`ACCEPTED` /
     * `NO_PENDING_CALL` / `REQUEST_GONE`). Capability-gated via
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_DETAILED_CANCEL];
     * falls back to the legacy [submitToolResult] (which always reports
     * success) when the connected service is older.
     */
    suspend fun submitToolResultDetailed(
        requestId: String,
        callId: String,
        toolName: String,
        resultJson: String,
    ): com.adsamcik.mindlayer.ToolSubmitResult {
        val caps = getCapabilities()
        val result = ToolResult(
            requestId = requestId,
            callId = callId,
            toolName = toolName,
            resultJson = resultJson,
        )
        if (!caps.supports(com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_DETAILED_CANCEL)) {
            withTypedErrors(requestId = requestId) { it.submitToolResult(requestId, result) }
            return com.adsamcik.mindlayer.ToolSubmitResult(
                outcome = com.adsamcik.mindlayer.ToolSubmitResult.ACCEPTED,
            )
        }
        return try {
            withTypedErrors(requestId = requestId) {
                it.submitToolResultV2(requestId, result)
            }
        } catch (_: NoSuchMethodError) {
            withTypedErrors(requestId = requestId) { it.submitToolResult(requestId, result) }
            com.adsamcik.mindlayer.ToolSubmitResult(
                outcome = com.adsamcik.mindlayer.ToolSubmitResult.ACCEPTED,
            )
        } catch (_: AbstractMethodError) {
            withTypedErrors(requestId = requestId) { it.submitToolResult(requestId, result) }
            com.adsamcik.mindlayer.ToolSubmitResult(
                outcome = com.adsamcik.mindlayer.ToolSubmitResult.ACCEPTED,
            )
        }
    }

    /** Cancel an in-flight inference request. */
    suspend fun cancelInference(requestId: String) {
        withTypedErrors(requestId = requestId) { it.cancelInference(requestId) }
    }

    /**
     * Cancel an in-flight inference and receive a tri-state outcome
     * (`CANCELLED` / `ALREADY_FINISHED` / `UNKNOWN`). Capability-gated via
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_DETAILED_CANCEL];
     * falls back to legacy [cancelInference] (which silently swallows all
     * outcomes) when the connected service is older.
     *
     * `UNKNOWN` covers both "we never saw this requestId" and "owned by
     * another UID" — the anti-enumeration property is preserved.
     */
    suspend fun cancelInferenceDetailed(requestId: String): com.adsamcik.mindlayer.CancelResult {
        val caps = getCapabilities()
        if (!caps.supports(com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_DETAILED_CANCEL)) {
            withTypedErrors(requestId = requestId) { it.cancelInference(requestId) }
            return com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.UNKNOWN,
            )
        }
        return try {
            withTypedErrors(requestId = requestId) { it.cancelInferenceV2(requestId) }
        } catch (_: NoSuchMethodError) {
            withTypedErrors(requestId = requestId) { it.cancelInference(requestId) }
            com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.UNKNOWN,
            )
        } catch (_: AbstractMethodError) {
            withTypedErrors(requestId = requestId) { it.cancelInference(requestId) }
            com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.UNKNOWN,
            )
        }
    }

    // -- Service status -------------------------------------------------------

    /** Get the current service status (engine loaded, thermals, etc.). */
    suspend fun getStatus(): ServiceStatus {
        return withTypedErrors { it.status }
    }

    /**
     * Lightweight liveness probe — Phase 3 #8 (`p3-health-check`).
     *
     * Returns a [com.adsamcik.mindlayer.HealthCheck] with the server's
     * wall-clock, service uptime, [com.adsamcik.mindlayer.ServiceCapabilities.apiVersion],
     * and per-engine state. Designed for low-overhead round-trip
     * checks, watchdog probes, and clock-skew detection.
     *
     * Cheaper than [getStatus] (which produces a richer
     * [com.adsamcik.mindlayer.ServiceStatus] snapshot with memory
     * pressure, thermal band, backend, etc.). The service bypasses
     * the allowlist gate and rate-limit cost for `ping()` so a
     * co-signed peer in pending-approval can still confirm the
     * service is alive.
     *
     * Capability-gated via
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_HEALTH_CHECK].
     * When talking to an older service that doesn't implement
     * `ping()` (`NoSuchMethodError` / `AbstractMethodError` on the
     * binder stub), this method falls back to issuing a `getStatus`
     * call and synthesising a [com.adsamcik.mindlayer.HealthCheck]
     * from the result — apiVersion is read from the cached
     * capabilities, engine states are derived from the status's
     * `isEngineLoaded` / `engineWarming` fields (best-effort).
     *
     * Throws the same typed errors as any other AIDL call (network
     * down, service crashed, etc.) on persistent failure.
     */
    suspend fun ping(): com.adsamcik.mindlayer.HealthCheck {
        return try {
            withTypedErrors { it.ping() }
        } catch (_: NoSuchMethodError) {
            synthesiseHealthCheckFallback()
        } catch (_: AbstractMethodError) {
            synthesiseHealthCheckFallback()
        }
    }

    /**
     * Fallback for pre-v0.8.1 services that don't implement `ping()`.
     * Derives a best-effort [com.adsamcik.mindlayer.HealthCheck] from
     * the heavier [getStatus] surface so callers don't have to handle
     * the version skew themselves.
     */
    private suspend fun synthesiseHealthCheckFallback(): com.adsamcik.mindlayer.HealthCheck {
        val status = getStatus()
        val caps = getCapabilities()
        val llmState = when {
            status.isEngineLoaded -> com.adsamcik.mindlayer.HealthCheck.ENGINE_STATE_READY
            status.engineWarming -> com.adsamcik.mindlayer.HealthCheck.ENGINE_STATE_INITIALIZING
            else -> com.adsamcik.mindlayer.HealthCheck.ENGINE_STATE_IDLE
        }
        return com.adsamcik.mindlayer.HealthCheck(
            serverTimestampMs = System.currentTimeMillis(),
            serviceUptimeMs = status.uptimeMs,
            apiVersion = caps.apiVersion,
            llmEngineState = llmState,
            embeddingEngineState = com.adsamcik.mindlayer.HealthCheck.ENGINE_STATE_IDLE,
            ocrEngineState = com.adsamcik.mindlayer.HealthCheck.ENGINE_STATE_IDLE,
            extensionsJson = null,
        )
    }

    /** Get engine info (selected model, perf stats, etc.). */
    suspend fun getEngineInfo(): EngineInfo {
        return withTypedErrors { it.engineInfo }
    }

    /** Get a diagnostic JSON dump for bug reports and troubleshooting. */
    suspend fun getDiagnostics(): String {
        return withTypedErrors { it.diagnostics }
    }

    /**
     * v0.4 typed diagnostics snapshot. Returns a small struct
     * ([com.adsamcik.mindlayer.DiagnosticsSnapshot]) suitable for
     * dashboard polling and external monitoring. Capability-gated via
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS];
     * returns `null` when talking to a pre-v0.4 service. Callers wanting
     * the human-readable JSON dump should use [getDiagnostics].
     */
    suspend fun getDiagnosticsTyped(): com.adsamcik.mindlayer.DiagnosticsSnapshot? {
        val caps = getCapabilities()
        if (!caps.supports(com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS)) {
            return null
        }
        return try {
            withTypedErrors { it.diagnosticsTyped }
        } catch (_: NoSuchMethodError) {
            null
        } catch (_: AbstractMethodError) {
            null
        }
    }

    // ── Simple API ──────────────────────────────────────────────────────

    /**
     * Start a multi-turn conversation. The session is created lazily on first message.
     *
     * ```kotlin
     * val conv = mindlayer.conversation {
     *     systemPrompt("You are a helpful barista.")
     * }
     * val response = conv.chat("What's a cortado?")
     * conv.close()
     * ```
     */
    fun conversation(configure: ConversationBuilder.() -> Unit = {}): Conversation {
        val config = ConversationBuilder().apply(configure).build()
        return Conversation(this, config)
    }

    /**
     * Send a single message and get the response. No session management needed.
     * Creates a temporary session, sends the message, destroys the session.
     *
     * For multi-turn conversations, use [conversation] instead.
     *
     * ```kotlin
     * val answer = mindlayer.chat("What is specialty coffee?")
     * ```
     */
    suspend fun chat(text: String): String {
        awaitConnected()
        return generate(text)
    }

    /**
     * Send a message with an image and get the response. No session management needed.
     */
    suspend fun chat(text: String, image: Bitmap): String {
        awaitConnected()
        return generateWithImage(text, image)
    }

    // ── History ─────────────────────────────────────────────

    /**
     * List past conversations from the SDK's encrypted local history database.
     *
     * This is the **durable view**: every conversation this app has tracked
     * locally, including ones whose service-side sessions were evicted,
     * expired, destroyed, or never re-bound after a service restart. Each
     * entry's [ConversationSummary.isActive] is augmented from a fresh
     * [listSessions] call so callers can tell which conversations still have
     * a live remote session attached.
     *
     * **Compare to [listSessions]**:
     * - `listHistory` = "every conversation this app remembers" (local-only,
     *   survives service restart, lost on reinstall because the SQLCipher
     *   keystore key doesn't move with backups).
     * - `listSessions` = "live remote sessions the service has for me right
     *   now" (subset of `listHistory`'s active rows).
     *
     * The two have different threat models. **Do not unify** — a future
     * third-party caller story will preserve this split.
     *
     * ```kotlin
     * val history = mindlayer.listHistory(limit = 20)
     * history.forEach { conv ->
     *     println("${conv.conversationId}: ${conv.turnCount} turns, active=${conv.isActive}")
     * }
     * ```
     */
    suspend fun listHistory(limit: Int = 50, offset: Int = 0): List<ConversationSummary> {
        val summaries = withContext(Dispatchers.IO) {
            historyStore?.listConversations(limit, offset) ?: emptyList()
        }
        val activeSessions = try {
            if (connectionState.value == ConnectionState.CONNECTED) {
                listSessions().map { it.sessionId }.toSet()
            } else emptySet()
        } catch (_: Exception) { emptySet() }

        return summaries.map { it.copy(isActive = it.conversationId in activeSessions) }
    }

    /**
     * Get the full turn history for a single conversation from local storage.
     *
     * Returns turns in chronological order, including completed assistant
     * responses, pending user turns, and any interrupted streaming turns. The
     * service is not consulted — this is a pure local read.
     *
     * @return the turn list, or an empty list if [conversationId] is unknown
     *   to the local history store (the SDK was constructed without history,
     *   or the conversation was pruned).
     */
    suspend fun getHistory(conversationId: String): List<TurnPreview> {
        return withContext(Dispatchers.IO) {
            historyStore?.getConversationHistory(conversationId) ?: emptyList()
        }
    }

    /**
     * Delete conversations older than [maxAgeDays] days.
     * Returns count of deleted conversations.
     */
    suspend fun pruneHistory(maxAgeDays: Int = 30): Int {
        val maxAgeMs = maxAgeDays.toLong() * 24 * 60 * 60 * 1000
        return withContext(Dispatchers.IO) {
            historyStore?.pruneOlderThan(maxAgeMs) ?: 0
        }
    }

    /**
     * Count total conversations in history.
     */
    suspend fun historyCount(): Int {
        return withContext(Dispatchers.IO) {
            historyStore?.conversationCount() ?: 0
        }
    }

    /**
     * Erase all conversation history from the local database.
     *
     * This is a destructive operation. It deletes all conversations and their
     * turns from the encrypted history DB on this device. The service-side
     * sessions are NOT affected — only the SDK-side persistence is cleared.
     *
     * No-op when [connect] was called without `persistHistory = true`.
     */
    suspend fun eraseAllHistory() {
        withContext(Dispatchers.IO) {
            historyStore?.clearAll()
        }
    }

    // -- Convenience ----------------------------------------------------------

    // ── Advanced API ──────────────────────────────────────────────────────

    /**
     * Create a [MindlayerSession] scoped to a single session for a more
     * ergonomic chat API.
     */
    fun session(sessionId: String): MindlayerSession =
        MindlayerSession(this, sessionId)

    // -- One-shot convenience ------------------------------------------------

    /**
     * Send a text message and return the complete response text.
     *
     * Collects the streaming [chat] flow to completion and returns the
     * accumulated text. Throws [MindlayerException] on service errors.
     *
     * @throws MindlayerException if the service reports an error or sends
     *   an unexpected [MindlayerEvent.ToolCall] (tool calling is not
     *   supported in one-shot mode).
     * @throws IllegalStateException if the stream ends without a terminal
     *   [MindlayerEvent.Done] event.
     */
    suspend fun chatOnce(sessionId: String, text: String): String =
        collectHandleToString(chat(sessionId, text), sessionId)

    /**
     * Send a text + image message and return the complete response text.
     *
     * @see chatOnce for error semantics.
     */
    suspend fun chatWithImageOnce(
        sessionId: String,
        text: String,
        bitmap: Bitmap,
    ): String = collectHandleToString(chatWithImage(sessionId, text, bitmap), sessionId)

    /**
     * Send a text + audio message and return the complete response text.
     *
     * @see chatOnce for error semantics.
     */
    suspend fun chatWithAudioOnce(
        sessionId: String,
        text: String,
        audioFile: File,
    ): String = collectHandleToString(chatWithAudio(sessionId, text, audioFile), sessionId)

    /**
     * Stream just the text deltas from [chat] as a [Flow]&lt;String&gt;.
     *
     * Filters out non-text events (Started, Metrics) and converts terminal
     * frames into flow signals: [MindlayerEvent.Done] completes the flow,
     * [MindlayerEvent.Error] throws a [MindlayerException], and an
     * unexpected [MindlayerEvent.ToolCall] throws with code
     * `UNSUPPORTED_TOOL_CALL` (silently dropping it would deadlock the
     * inference because no [submitToolResult] would follow).
     *
     * Useful for streaming text directly into a `TextField` without
     * writing the event-handling boilerplate by hand.
     *
     * ```kotlin
     * mindlayer.chatTextFlow(sessionId, "Tell me a story").collect { delta ->
     *     textFieldState.value += delta
     * }
     * ```
     */
    fun chatTextFlow(sessionId: String, text: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val handle = chat(sessionId, text)
            textDeltaFlow(handle, sessionId).collect { emit(it) }
        }

    /**
     * Like [chatTextFlow] but emits the **cumulative** text after each delta.
     * Each emission is a complete prefix of the response so far — useful for
     * UIs that want to display the growing answer with simple "set this whole
     * string" semantics rather than appending.
     *
     * The final emission is the full response text. Errors and tool calls
     * are surfaced the same way as [chatTextFlow].
     */
    fun chatFullTextFlow(sessionId: String, text: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val acc = StringBuilder()
            val handle = chat(sessionId, text)
            textDeltaFlow(handle, sessionId).collect { delta ->
                acc.append(delta)
                emit(acc.toString())
            }
        }

    /**
     * Internal helper: collect an [InferenceHandle] to a complete response
     * string. Used by [chatOnce], [chatWithImageOnce], [chatWithAudioOnce],
     * and (transitively) [generate] / [generateWithImage] / [generateWithAudio].
     *
     * Replaces four near-identical inline implementations with one source of
     * truth so the error mapping (Error → typed MindlayerException;
     * ToolCall → `UNSUPPORTED_TOOL_CALL`) cannot drift between call sites.
     */
    private suspend fun collectHandleToString(
        handle: InferenceHandle,
        sessionId: String,
    ): String {
        var result: String? = null
        val accumulator = StringBuilder()
        handle.events.collect { event ->
            when (event) {
                is MindlayerEvent.TextDelta -> accumulator.append(event.text)
                is MindlayerEvent.Done -> {
                    result = event.fullText ?: accumulator.toString()
                }
                is MindlayerEvent.Error -> throw MindlayerException.fromStreamError(
                    message = event.message,
                    codeName = event.code,
                    codeInt = event.codeInt,
                    seq = event.seq,
                    tsMs = event.tsMs,
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                is MindlayerEvent.ToolCall -> throw MindlayerException(
                    message = TOOL_CALL_IN_ONESHOT_MSG,
                    codeName = "UNSUPPORTED_TOOL_CALL",
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                else -> { /* Started, Metrics — ignored */ }
            }
        }
        return result ?: throw IllegalStateException(
            "Inference stream ended without a Done event"
        )
    }

    /**
     * Internal helper: filter an [InferenceHandle]'s events down to a flow
     * of text deltas, raising on Error/ToolCall and completing on Done.
     */
    private fun textDeltaFlow(
        handle: InferenceHandle,
        sessionId: String,
    ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        handle.events.collect { event ->
            when (event) {
                is MindlayerEvent.TextDelta -> emit(event.text)
                is MindlayerEvent.Done -> return@collect
                is MindlayerEvent.Error -> throw MindlayerException.fromStreamError(
                    message = event.message,
                    codeName = event.code,
                    codeInt = event.codeInt,
                    seq = event.seq,
                    tsMs = event.tsMs,
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                is MindlayerEvent.ToolCall -> throw MindlayerException(
                    message = TOOL_CALL_IN_ONESHOT_MSG,
                    codeName = "UNSUPPORTED_TOOL_CALL",
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                else -> { /* Started, Metrics — silently dropped */ }
            }
        }
    }

    /**
     * Stateless one-shot generation: creates a temporary session, sends
     * the prompt, collects the full response, and destroys the session.
     *
     * Use this when each inference is independent (no conversation
     * context needed). Session cleanup is best-effort — a service crash
     * during cleanup will not mask the successful response.
     *
     * @param text the prompt to send.
     * @param configure optional DSL block to customise session parameters
     *   (temperature, topK, maxTokens, systemPrompt, etc.).
     * @return the complete generated text.
     * @throws MindlayerException on service errors.
     */
    suspend fun generate(
        text: String,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String {
        val sessionId = createSession(configure)
        return try {
            chatOnce(sessionId, text)
        } finally {
            // Best-effort cleanup — don't mask a successful result or
            // a meaningful exception if the service has disconnected.
            try {
                destroySession(sessionId)
            } catch (_: Exception) {
                // Session will be cleaned up server-side on timeout
            }
        }
    }

    /**
     * Stateless one-shot image + text generation.
     *
     * @see generate for lifecycle semantics.
     */
    suspend fun generateWithImage(
        text: String,
        bitmap: Bitmap,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String {
        val sessionId = createSession(configure)
        return try {
            chatWithImageOnce(sessionId, text, bitmap)
        } finally {
            try {
                destroySession(sessionId)
            } catch (_: Exception) {
                // Best-effort cleanup
            }
        }
    }

    /**
     * Stateless one-shot text + audio generation.
     *
     * @see generate for lifecycle semantics.
     */
    suspend fun generateWithAudio(
        text: String,
        audioFile: File,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String {
        val sessionId = createSession(configure)
        return try {
            chatWithAudioOnce(sessionId, text, audioFile)
        } finally {
            try {
                destroySession(sessionId)
            } catch (_: Exception) {
                // Best-effort cleanup
            }
        }
    }

    // -- Internals ------------------------------------------------------------

    /**
     * Creates an [InferenceHandle] with cancel callbacks that reach through
     * to the service's [IMindlayerService.cancelInference].
     *
     * Two callbacks are wired:
     *  - `cancelCallback` (suspend): used by user-driven [InferenceHandle.cancel].
     *    Waits for an active connection via [awaitConnected] so the cancel
     *    survives a transient disconnect.
     *  - `syncCancelCallback` (non-suspend): used by cleanup paths such as
     *    [Conversation.close]. Best-effort against the currently-cached
     *    binder; silently drops the cancel when no connection is available.
     */
    private fun buildHandle(requestId: String, flow: Flow<MindlayerEvent>): InferenceHandle {
        return InferenceHandle(requestId, flow).also { handle ->
            handle.setCancelCallback {
                try {
                    withContext(Dispatchers.IO) {
                        connection.awaitConnected().cancelInference(requestId)
                    }
                } catch (_: Exception) {
                    // Best-effort cancel — service may be disconnected
                }
            }
            handle.setSyncCancelCallback {
                try {
                    connection.getService()?.cancelInference(requestId)
                } catch (_: Exception) {
                    // Best-effort cancel — service died or rejected the call
                }
            }
        }
    }

    /**
     * Wraps [startInference] with history persistence.
     *
     * 1. Persist user turn as PENDING before IPC.
     * 2. On [MindlayerEvent.Started]: mark user turn COMPLETED, begin
     *    assistant turn as STREAMING.
     * 3. On [MindlayerEvent.Done]: mark assistant turn COMPLETED with full
     *    text.
     * 4. On error/cancellation: mark assistant turn INTERRUPTED.
     */
    private suspend fun startTrackedInference(
        sessionId: String,
        userText: String,
        meta: RequestMeta,
        imageProvider: suspend () -> com.adsamcik.mindlayer.ImageTransfer?,
        audioProvider: suspend () -> com.adsamcik.mindlayer.AudioTransfer?,
    ): Flow<MindlayerEvent> {
        if (historyStore == null) {
            return startInference(meta, imageProvider, audioProvider)
        }
        val historyStoreLocal = historyStore
        return flow {
            val userTurnId = withContext(Dispatchers.IO) {
                historyStoreLocal.persistUserTurn(sessionId, userText)
            }
            var assistantTurnId: String? = null
            val textAccumulator = StringBuilder()
            var completed = false

            try {
                startInference(meta, imageProvider, audioProvider)
                    .collect { event ->
                        when (event) {
                            is MindlayerEvent.Started -> {
                                assistantTurnId = withContext(Dispatchers.IO) {
                                    historyStoreLocal.markUserTurnCompleted(userTurnId)
                                    historyStoreLocal.beginAssistantTurn(sessionId)
                                }
                            }
                            is MindlayerEvent.TextDelta -> {
                                textAccumulator.append(event.text)
                            }
                            is MindlayerEvent.Done -> {
                                val finalText = event.fullText
                                    ?: textAccumulator.toString()
                                val aid = assistantTurnId
                                if (aid != null) {
                                    withContext(Dispatchers.IO) {
                                        historyStoreLocal.markTurnCompleted(aid, finalText)
                                    }
                                }
                                completed = true
                            }
                            is MindlayerEvent.Error -> {
                                val aid = assistantTurnId
                                if (aid != null) {
                                    withContext(Dispatchers.IO) {
                                        historyStoreLocal.markTurnInterrupted(aid)
                                    }
                                }
                            }
                            else -> { /* ToolCall, Metrics — pass through */ }
                        }
                        emit(event)
                    }
            } catch (e: Exception) {
                if (!completed) {
                    val aid = assistantTurnId
                    if (aid != null) {
                        withContext(Dispatchers.IO) {
                            historyStoreLocal.markTurnInterrupted(aid)
                        }
                    }
                }
                throw e
            }
        }
    }

    /**
     * Core inference pipe setup used by all chat variants.
     *
     * 1. Creates a reliable pipe pair.
     * 2. Passes the write end to the service via [IMindlayerService.infer].
     * 3. Returns a [Flow] reading typed events from the read end.
     *
     * The write-end PFD is closed immediately after handing it off (the
     * service duplicates the fd internally). The read-end PFD is closed
     * by [TokenStreamReader] when the flow completes or is cancelled.
     *
     * Media source FDs (image.source / audio.source) are closed in the
     * finally block after the binder call — by that point the service has
     * already dup'd them into its own process.
     */
    private suspend fun startInference(
        meta: RequestMeta,
        imageProvider: suspend () -> com.adsamcik.mindlayer.ImageTransfer?,
        audioProvider: suspend () -> com.adsamcik.mindlayer.AudioTransfer?,
    ): Flow<MindlayerEvent> {
        // Preserve the public chat* contract: do not return a handle until a binder is available.
        connection.awaitConnected()
        return flow {
            val readEnd = withContext(Dispatchers.IO) {
                val image = imageProvider()
                val audio = try {
                    audioProvider()
                } catch (e: Throwable) {
                    // audioProvider failed after imageProvider may have allocated an FD
                    image?.source?.closeQuietly()
                    throw e
                }
                val pipe = ParcelFileDescriptor.createReliablePipe()
                val readEnd = pipe[0]
                val writeEnd = pipe[1]

                try {
                    val service = connection.awaitConnected()
                    withTypedErrorsSync(
                        service = service,
                        requestId = meta.requestId,
                        sessionId = meta.sessionId,
                    ) { svc ->
                        svc.infer(meta, image, audio, writeEnd)
                    }
                    readEnd
                } catch (e: Exception) {
                    readEnd.close()
                    throw e
                } finally {
                    // Always close our copies — the service has dup'd all three.
                    writeEnd.close()
                    image?.source?.closeQuietly()
                    audio?.source?.closeQuietly()
                }
            }

            TokenStreamReader.readStream(readEnd).collect { emit(it) }
        }
    }

    private fun ParcelFileDescriptor.closeQuietly() = try { close() } catch (_: Exception) {}

    /**
     * v0.4 inference setup that uses [IMindlayerService.inferMulti] with an
     * ordered list of media parts. Falls back to the legacy [startInference]
     * path if the service throws [NoSuchMethodError] / [AbstractMethodError]
     * (talking to a pre-v0.4 binary that doesn't implement `inferMulti`) —
     * the fallback caps at one image + one audio per the legacy shape.
     */
    private suspend fun startInferenceMulti(
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>,
    ): Flow<MindlayerEvent> {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val mediaSources = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<ParcelFileDescriptor, Boolean>(),
        )
        media.forEach { mediaSources.add(it.source) }

        try {
            val service = connection.awaitConnected()
            try {
                withTypedErrorsSync(
                    service,
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                ) { it.inferMulti(meta, media, writeEnd) }
            } catch (_: NoSuchMethodError) {
                // Old service without the v0.4 method — fall back.
                inferMultiLegacyFallback(service, meta, media, writeEnd)
            } catch (_: AbstractMethodError) {
                inferMultiLegacyFallback(service, meta, media, writeEnd)
            }
        } catch (e: Exception) {
            readEnd.close()
            throw e
        } finally {
            writeEnd.close()
            mediaSources.forEach { it.closeQuietly() }
        }

        return TokenStreamReader.readStream(readEnd)
    }

    /**
     * Pre-v0.4 fallback: extract the first image and first audio from the
     * [media] list and call legacy [IMindlayerService.infer]. Same engine
     * constraints apply as `chatWithImage` / `chatWithAudio`.
     */
    private fun inferMultiLegacyFallback(
        service: com.adsamcik.mindlayer.IMindlayerService,
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>,
        writeEnd: ParcelFileDescriptor,
    ) {
        val imagePart = media.firstOrNull {
            it.kind == com.adsamcik.mindlayer.MediaPart.KIND_IMAGE
        }
        val audioPart = media.firstOrNull {
            it.kind == com.adsamcik.mindlayer.MediaPart.KIND_AUDIO
        }
        val image = imagePart?.let {
            com.adsamcik.mindlayer.ImageTransfer(
                requestId = it.requestId,
                width = it.width,
                height = it.height,
                pixelFormat = it.pixelFormat,
                rowStride = it.rowStride,
                payloadBytes = it.payloadBytes.toInt(),
                source = it.source,
                isSharedMemory = it.isSharedMemory,
                mimeType = it.mimeType,
            )
        }
        val audio = audioPart?.let {
            com.adsamcik.mindlayer.AudioTransfer(
                requestId = it.requestId,
                mimeType = it.mimeType ?: "audio/wav",
                source = it.source,
                isSharedMemory = it.isSharedMemory,
                durationMs = it.durationMs,
            )
        }
        withTypedErrorsSync(
            service,
            requestId = meta.requestId,
            sessionId = meta.sessionId,
        ) { it.infer(meta, image, audio, writeEnd) }
    }

    /**
     * Variant of [startTrackedInference] for the [chatWithMedia] entry
     * point. Same history-persistence semantics; just routes through the
     * new ordered-media wire shape.
     */
    private suspend fun startTrackedInferenceMulti(
        sessionId: String,
        userText: String,
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>,
    ): Flow<MindlayerEvent> {
        if (historyStore == null) {
            return startInferenceMulti(meta, media)
        }

        val historyStoreLocal = historyStore
        val innerFlow = startInferenceMulti(meta, media)
        return flow {
            val userTurnId = historyStoreLocal.persistUserTurn(sessionId, userText)
            var assistantTurnId: String? = null
            val textAccumulator = StringBuilder()
            var completed = false

            try {
                innerFlow.collect { event ->
                    when (event) {
                        is MindlayerEvent.Started -> {
                            historyStoreLocal.markUserTurnCompleted(userTurnId)
                            assistantTurnId = historyStoreLocal.beginAssistantTurn(sessionId)
                        }
                        is MindlayerEvent.TextDelta -> {
                            textAccumulator.append(event.text)
                        }
                        is MindlayerEvent.Done -> {
                            val finalText = event.fullText
                                ?: textAccumulator.toString()
                            val aid = assistantTurnId
                            if (aid != null) {
                                historyStoreLocal.markTurnCompleted(aid, finalText)
                            }
                            completed = true
                        }
                        is MindlayerEvent.Error -> {
                            val aid = assistantTurnId
                            if (aid != null) {
                                historyStoreLocal.markTurnInterrupted(aid)
                            }
                        }
                        else -> { /* ToolCall, Metrics — pass through */ }
                    }
                    emit(event)
                }
            } catch (e: Exception) {
                if (!completed) {
                    val aid = assistantTurnId
                    if (aid != null) {
                        historyStoreLocal.markTurnInterrupted(aid)
                    }
                }
                throw e
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  v0.8 multi-frame OCR — SDK DSL (Phase 1 PR D).
    //
    //  Wraps the 7 AIDL OCR methods (createOcrSession / pushOcrFrame /
    //  streamOcrEvents / getOcrSessionState / finalizeOcrSession /
    //  closeOcrSession / getOcrLimits) behind a Kotlin-idiomatic
    //  surface. See OcrSession / OcrProfile / OcrEvent.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Live OCR limits the connected service advertises. SDKs should
     * read these before opening a session and surface ``DROPPED_BUSY``
     * / ``REJECTED_QUALITY`` backpressure to the user UI.
     *
     * Returns [OcrLimits.zeroBaseline()][com.adsamcik.mindlayer.OcrLimits.zeroBaseline]
     * if the service is too old to support OCR (`getOcrLimits` not
     * implemented → ``NoSuchMethodError`` / ``AbstractMethodError``).
     */
    suspend fun ocrLimits(): com.adsamcik.mindlayer.OcrLimits {
        val service = connection.awaitConnected()
        return try {
            service.ocrLimits
        } catch (_: NoSuchMethodError) {
            com.adsamcik.mindlayer.OcrLimits.zeroBaseline()
        } catch (_: AbstractMethodError) {
            com.adsamcik.mindlayer.OcrLimits.zeroBaseline()
        }
    }

    /**
     * Open a multi-frame OCR session using a built-in [OcrProfile].
     *
     * ```kotlin
     * mindlayer.ocrSession(OcrProfile.Receipt) {
     *     languageHints = listOf("en", "de-DE")
     *     maxFrames = 30
     * }.use { session ->
     *     session.pushFrame(meta)
     *     ...
     *     session.finalize()
     * }
     * ```
     *
     * @param profile the OCR profile preset.
     * @param configure optional builder block to override profile
     *   defaults (schema, language hints, fps cap, etc.).
     * @throws SecurityException with wire-prefixed message when the
     *   service rejects the session (e.g., ``CONCURRENT_LIMIT``).
     */
    suspend fun ocrSession(
        profile: OcrProfile,
        configure: OcrSessionConfigBuilder.() -> Unit = {},
    ): OcrSession {
        val builder = OcrSessionConfigBuilder(profile)
        builder.configure()
        return ocrSession(builder.build())
    }

    /**
     * Open a multi-frame OCR session with a pre-built [OcrSessionConfig].
     * Use this when you have a serialized config (e.g. from process
     * recovery) and don't want the builder DSL.
     */
    suspend fun ocrSession(config: com.adsamcik.mindlayer.OcrSessionConfig): OcrSession {
        val service = connection.awaitConnected()
        val sessionId = service.createOcrSession(config)
        return OcrSession(sessionId = sessionId, config = config, mindlayer = this)
    }

    /** Internal: forward push to the AIDL stub. */
    internal suspend fun pushOcrFrameMetadataOnly(
        sessionId: String,
        meta: com.adsamcik.mindlayer.OcrFrameMeta,
    ): com.adsamcik.mindlayer.OcrFrameAck {
        val service = connection.awaitConnected()
        // The AIDL signature requires a MediaPart; in Phase 1 PR D
        // we provide a stub MediaPart wrapping an empty pipe so the
        // service-side metadata-only path accepts the call. A
        // follow-up wires real Y-plane staging via MediaTransfer.
        val pipe = ParcelFileDescriptor.createPipe()
        pipe[1].closeQuietly()
        val mediaPart = com.adsamcik.mindlayer.MediaPart(
            requestId = "ocr-frame-${UUID.randomUUID()}",
            kind = com.adsamcik.mindlayer.MediaPart.KIND_IMAGE,
            mimeType = "application/octet-stream",
            source = pipe[0],
            isSharedMemory = false,
            payloadBytes = 0L,
        )
        return try {
            service.pushOcrFrame(sessionId, mediaPart, meta)
        } finally {
            pipe[0].closeQuietly()
        }
    }

    /** Internal: forward state query. */
    internal suspend fun getOcrSessionState(sessionId: String): com.adsamcik.mindlayer.OcrSessionState {
        val service = connection.awaitConnected()
        return service.getOcrSessionState(sessionId)
    }

    /** Internal: forward finalize call. */
    internal suspend fun finalizeOcrSession(sessionId: String) {
        val service = connection.awaitConnected()
        service.finalizeOcrSession(sessionId)
    }

    /** Internal: fire-and-forget close; idempotent on the service side. */
    internal fun closeOcrSessionFireAndForget(sessionId: String) {
        val service = connection.getService() ?: return
        try {
            service.closeOcrSession(sessionId)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    /** Internal: attach the OCR_V1 event-stream write-end. */
    internal suspend fun attachOcrEventStream(
        sessionId: String,
        writeEnd: ParcelFileDescriptor,
    ) {
        val service = connection.awaitConnected()
        service.streamOcrEvents(sessionId, writeEnd)
    }
}

/**
 * DSL builder for configuring a Mindlayer inference session.
 *
 * Example:
 * ```
 * val sessionId = mindlayer.createSession {
 *     systemPrompt("You are a coffee expert.")
 *     maxTokens(2048)
 *     temperature(0.7f)
 * }
 * ```
 */
class SessionConfigBuilder {
    private var sessionId: String? = null
    private var systemPrompt: String? = null
    private var maxTokens: Int = 4096
    private var backend: String = "GPU"
    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 0.7f
    private var toolsJson: String? = null
    private var extraContextJson: String? = null
    private var initialHistory: List<HistoryTurn>? = null
    private var expirationMs: Long = 14L * 24 * 60 * 60 * 1000

    /**
     * Set a custom session ID. If not set, a UUID is generated automatically.
     * Use this for deterministic session tracking or reconnection after OOM.
     */
    fun sessionId(id: String) { sessionId = id }

    /**
     * System instruction that defines the model's behavior and persona.
     * This is injected before the first user message and persists for the session.
     */
    fun systemPrompt(prompt: String) { systemPrompt = prompt }

    /**
     * Maximum number of tokens (input + output) for this session's KV cache.
     * Higher values allow longer conversations but consume more memory.
     * Valid range: 128–8192. Default: 4096.
     */
    fun maxTokens(n: Int) {
        require(n in 128..8192) { "maxTokens must be between 128 and 8192, got $n" }
        maxTokens = n
    }

    /**
     * Set the compute backend for inference.
     * Supported values: "GPU" (default), "CPU", "NPU".
     */
    internal fun backend(b: String) { backend = b }

    /**
     * Top-K sampling: only consider the K most likely tokens at each step.
     * Lower values = more focused, higher values = more diverse.
     * Default: 40. Must be >= 1.
     */
    fun topK(k: Int) {
        require(k >= 1) { "topK must be >= 1, got $k" }
        topK = k
    }

    /**
     * Top-P (nucleus) sampling: only consider tokens whose cumulative
     * probability reaches P. Complements top-K.
     * Default: 0.95. Must be in (0.0, 1.0].
     */
    fun topP(p: Float) {
        require(p > 0.0f && p <= 1.0f) { "topP must be in (0.0, 1.0], got $p" }
        topP = p
    }

    /**
     * Sampling temperature controlling response randomness.
     * - 0.0 = deterministic (always pick most likely token)
     * - 0.7 = balanced creativity (default, recommended for most uses)
     * - 1.0+ = highly random
     * Must be >= 0.0.
     */
    fun temperature(t: Float) {
        require(t >= 0.0f) { "temperature must be >= 0.0, got $t" }
        temperature = t
    }

    /**
     * Register tools (function calling) for this session via the typed DSL.
     * Each [ToolsBuilder.tool] call validates the name and parameters shape
     * at builder time so malformed configs fail fast instead of being
     * silently dropped at session-create time.
     *
     * ```kotlin
     * mindlayer.createSession {
     *     tools {
     *         tool("get_weather") {
     *             description("Get current weather for a city")
     *             parameters("""{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}""")
     *         }
     *     }
     * }
     * ```
     *
     * See [MindlayerEvent.ToolCall] for handling tool invocations.
     */
    fun tools(configure: ToolsBuilder.() -> Unit) {
        toolsJson = ToolsBuilder().apply(configure).build()
    }

    /**
     * Register tools (function calling) for this session via raw JSON.
     * Pass a JSON array of OpenAPI-style tool definitions. Prefer the
     * typed [tools] DSL for in-code tool catalogs — this overload is the
     * escape hatch for callers that already have a JSON tool registry.
     *
     * @throws IllegalArgumentException if [json] is not a valid JSON array
     *   of objects with `name` and `parameters` keys.
     */
    fun tools(json: String) {
        val parsed = try {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
        } catch (t: Throwable) {
            throw IllegalArgumentException("tools(json) is not valid JSON", t)
        }
        require(parsed is kotlinx.serialization.json.JsonArray) {
            "tools(json) must be a JSON array"
        }
        require(parsed.size <= ToolsBuilder.MAX_TOOLS) {
            "too many tools (${parsed.size} > ${ToolsBuilder.MAX_TOOLS})"
        }
        for ((i, tool) in parsed.withIndex()) {
            require(tool is kotlinx.serialization.json.JsonObject) {
                "tools[$i] must be a JSON object"
            }
            val nameElement = tool["name"] as? kotlinx.serialization.json.JsonPrimitive
            val name = nameElement?.content
            require(!name.isNullOrBlank()) { "tools[$i] missing 'name'" }
            require(!name.startsWith(ToolsBuilder.RESERVED_PREFIX)) {
                "tools[$i].name '$name' uses reserved prefix"
            }
            // parameters is optional — service substitutes an empty
            // {"type":"object"} schema for missing/null. Validate type
            // when present.
            tool["parameters"]?.let {
                require(it is kotlinx.serialization.json.JsonObject) {
                    "tools[$i].parameters must be a JSON object when present"
                }
            }
        }
        toolsJson = json
    }

    /**
     * Additional context passed to the model as grounding data.
     * This is injected alongside the system prompt.
     */
    fun extraContext(json: String) { extraContextJson = json }

    /**
     * Request structured JSON output that conforms to a schema.
     *
     * The service validates the model's response against the supplied schema
     * and (for [JsonOutputStrategy.PromptAndValidate]) retries on mismatch.
     * Validation is shallow: parseable JSON, top-level required fields, and
     * top-level property types. Clients that rely on schema output as a
     * security or business-rule boundary must run full validation locally.
     * If the connected Mindlayer service predates this feature, the config
     * is silently ignored and generation proceeds normally — making this a
     * zero-risk opt-in.
     *
     * Merges with any `structured_output` key already present on
     * [extraContext]; other keys on [extraContext] are preserved.
     *
     * ```kotlin
     * jsonOutput {
     *     schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
     *     strategy(JsonOutputStrategy.PromptAndValidate)
     * }
     * ```
     */
    fun jsonOutput(block: JsonOutputBuilder.() -> Unit) {
        val envelope = JsonOutputBuilder().apply(block).build()
        extraContextJson = mergeExtraContext(extraContextJson, envelope)
    }

    /**
     * Opt this session into v0.5 [com.adsamcik.mindlayer.shared.StreamEventType.TOKEN_DELTA_BATCH]
     * coalescing. The service writer accumulates up to 8 tokens or 16 ms
     * (whichever comes first) into a single batched frame, cutting wire
     * overhead at high token rates. The SDK reader expands each batched
     * frame back into per-token [com.adsamcik.mindlayer.sdk.MindlayerEvent.TextDelta]
     * emissions so consumers see no API change — only fewer syscalls and
     * lower CPU on both sides.
     *
     * Capability-gated via
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TOKEN_BATCH].
     * If the connected service does not advertise the flag, this call is
     * still safe — the service ignores the unknown opt-in and the stream
     * stays on `mindlayer.stream.v1`. Callers that care about confirming
     * the optimization is live should check capabilities first.
     *
     * Default: off (single-token deltas, current behavior).
     */
    fun tokenBatching(enabled: Boolean = true) {
        val envelope = kotlinx.serialization.json.buildJsonObject {
            put(
                "token_batch",
                kotlinx.serialization.json.JsonPrimitive(enabled),
            )
        }
        extraContextJson = mergeExtraContext(extraContextJson, envelope)
    }

    /**
     * Pre-populate conversation history for session recovery.
     * Turns are injected into the model's context at creation time.
     */
    fun initialHistory(history: List<HistoryTurn>) { initialHistory = history }

    /** Set session expiration in milliseconds. Internal — consumers use [ConversationBuilder.expiration]. */
    internal fun expirationMs(ms: Long) { expirationMs = ms }

    /**
     * Internal escape hatch: set `toolsJson` directly without running the
     * v0.3 client-side shape validation. Used by [SessionRecovery] to
     * preserve a previously-stored tools config across recovery — that
     * JSON was already accepted by some SDK version (possibly older,
     * less strict) and re-validating could reject otherwise-fine data.
     *
     * **Do not** expose this on the public API surface — public callers
     * should go through [tools] so they get the shape check.
     */
    internal fun toolsJsonRaw(json: String?) { toolsJson = json }

    internal fun build(): SessionConfig = SessionConfig(
        sessionId = sessionId,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        backend = backend,
        samplerTopK = topK,
        samplerTopP = topP,
        samplerTemperature = temperature,
        toolsJson = toolsJson,
        extraContextJson = extraContextJson,
        initialHistory = initialHistory,
        expirationMs = expirationMs,
    )
}
