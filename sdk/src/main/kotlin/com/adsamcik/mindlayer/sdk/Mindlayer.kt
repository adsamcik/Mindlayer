package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.EngineInfo
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.io.File
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

        /**
         * Create a [Mindlayer] instance and start binding to the service.
         * Use [awaitConnected] or observe [connectionState] before issuing RPCs.
         *
         * History is metadata-only by default. Pass [HistoryPolicy.FULL_CONTENT]
         * only if the client explicitly needs local replay/recovery and has its
         * own consent/retention controls for prompt and model-output content.
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

    /** Suspend until the service binder is available. */
    suspend fun awaitConnected() {
        connection.awaitConnected()
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
        val service = connection.awaitConnected()
        service.prewarm(backend.value)
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
    ): String {
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

        return sessionId
    }

    private suspend fun createSessionWithInitRetry(configWithId: SessionConfig): String {
        // Backoff schedule: 50ms → 200ms → 800ms (caps total wait ≈ 10 s
        // when combined with the underlying engine init time).
        val backoffMs = longArrayOf(50L, 200L, 800L)
        var attempt = 0
        val deadline = System.currentTimeMillis() + 10_000L
        while (true) {
            try {
                return connection.awaitConnected().createSession(configWithId)
            } catch (e: SecurityException) {
                val typed = MindlayerException.fromAidlSecurityException(
                    e,
                    sessionId = configWithId.sessionId,
                ) ?: throw e
                if (typed.code == com.adsamcik.mindlayer.shared.MindlayerErrorCode.ENGINE_INITIALIZING &&
                    attempt < backoffMs.size &&
                    System.currentTimeMillis() < deadline
                ) {
                    kotlinx.coroutines.delay(backoffMs[attempt])
                    attempt++
                    continue
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
     * Creates a reliable pipe, hands the write end to the service via AIDL,
     * and returns an [InferenceHandle] that provides the [requestId], event
     * [Flow], and a [cancel][InferenceHandle.cancel] function that reaches
     * through to the service's native cancel.
     *
     * History persistence: the user turn is saved BEFORE IPC and the
     * assistant turn is marked COMPLETED only after the [MindlayerEvent.Done]
     * event.
     */
    fun chat(sessionId: String, text: String): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            image = null,
            audio = null,
        )
        return buildHandle(requestId, flow)
    }

    // -- Chat with image ------------------------------------------------------

    /**
     * Send a text + Bitmap message and stream back inference events.
     */
    fun chatWithImage(
        sessionId: String,
        text: String,
        bitmap: Bitmap,
    ): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val imageTransfer = MediaTransfer.fromBitmap(requestId, bitmap)
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            image = imageTransfer,
            audio = null,
        )
        return buildHandle(requestId, flow)
    }

    // -- Chat with audio ------------------------------------------------------

    /**
     * Send a text + audio file message and stream back inference events.
     */
    fun chatWithAudio(
        sessionId: String,
        text: String,
        audioFile: File,
    ): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val audioTransfer = MediaTransfer.fromAudioFile(requestId, audioFile)
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            image = null,
            audio = audioTransfer,
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
    fun chatWithMedia(
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
        val summaries = historyStore?.listConversations(limit, offset) ?: emptyList()
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
        return historyStore?.getConversationHistory(conversationId) ?: emptyList()
    }

    /**
     * Delete conversations older than [maxAgeDays] days.
     * Returns count of deleted conversations.
     */
    suspend fun pruneHistory(maxAgeDays: Int = 30): Int {
        val maxAgeMs = maxAgeDays.toLong() * 24 * 60 * 60 * 1000
        return historyStore?.pruneOlderThan(maxAgeMs) ?: 0
    }

    /**
     * Count total conversations in history.
     */
    suspend fun historyCount(): Int {
        return historyStore?.conversationCount() ?: 0
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
        textDeltaFlow(chat(sessionId, text), sessionId)

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
            textDeltaFlow(chat(sessionId, text), sessionId).collect { delta ->
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
     * Creates an [InferenceHandle] with a cancel callback that reaches
     * through to the service's [IMindlayerService.cancelInference].
     */
    private fun buildHandle(requestId: String, flow: Flow<MindlayerEvent>): InferenceHandle {
        return InferenceHandle(requestId, flow).also { handle ->
            handle.setCancelCallback {
                try {
                    connection.awaitConnected().cancelInference(requestId)
                } catch (_: Exception) {
                    // Best-effort cancel — service may be disconnected
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
    private fun startTrackedInference(
        sessionId: String,
        userText: String,
        meta: RequestMeta,
        image: com.adsamcik.mindlayer.ImageTransfer?,
        audio: com.adsamcik.mindlayer.AudioTransfer?,
    ): Flow<MindlayerEvent> {
        if (historyStore == null) {
            return startInference(meta, image, audio)
        }

        return flow {
            val userTurnId = historyStore.persistUserTurn(sessionId, userText)
            var assistantTurnId: String? = null
            val textAccumulator = StringBuilder()
            var completed = false

            try {
                startInference(meta, image, audio)
                    .collect { event ->
                        when (event) {
                            is MindlayerEvent.Started -> {
                                historyStore.markUserTurnCompleted(userTurnId)
                                assistantTurnId = historyStore.beginAssistantTurn(sessionId)
                            }
                            is MindlayerEvent.TextDelta -> {
                                textAccumulator.append(event.text)
                            }
                            is MindlayerEvent.Done -> {
                                val finalText = event.fullText
                                    ?: textAccumulator.toString()
                                val aid = assistantTurnId
                                if (aid != null) {
                                    historyStore.markTurnCompleted(aid, finalText)
                                }
                                completed = true
                            }
                            is MindlayerEvent.Error -> {
                                val aid = assistantTurnId
                                if (aid != null) {
                                    historyStore.markTurnInterrupted(aid)
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
                        historyStore.markTurnInterrupted(aid)
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
     */
    private fun startInference(
        meta: RequestMeta,
        image: com.adsamcik.mindlayer.ImageTransfer?,
        audio: com.adsamcik.mindlayer.AudioTransfer?,
    ): Flow<MindlayerEvent> {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        try {
            val service = connection.requireService()
            withTypedErrorsSync(
                service,
                requestId = meta.requestId,
                sessionId = meta.sessionId,
            ) { it.infer(meta, image, audio, writeEnd) }
        } catch (e: Exception) {
            readEnd.close()
            throw e
        } finally {
            // Always close our copy of the write end — the service has dup'd it
            writeEnd.close()
        }

        return TokenStreamReader.readStream(readEnd)
    }

    /**
     * v0.4 inference setup that uses [IMindlayerService.inferMulti] with an
     * ordered list of media parts. Falls back to the legacy [startInference]
     * path if the service throws [NoSuchMethodError] / [AbstractMethodError]
     * (talking to a pre-v0.4 binary that doesn't implement `inferMulti`) —
     * the fallback caps at one image + one audio per the legacy shape.
     */
    private fun startInferenceMulti(
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>,
    ): Flow<MindlayerEvent> {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        try {
            val service = connection.requireService()
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
    private fun startTrackedInferenceMulti(
        sessionId: String,
        userText: String,
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>,
    ): Flow<MindlayerEvent> {
        if (historyStore == null) {
            return startInferenceMulti(meta, media)
        }

        return flow {
            val userTurnId = historyStore.persistUserTurn(sessionId, userText)
            var assistantTurnId: String? = null
            val textAccumulator = StringBuilder()
            var completed = false

            try {
                startInferenceMulti(meta, media)
                    .collect { event ->
                        when (event) {
                            is MindlayerEvent.Started -> {
                                historyStore.markUserTurnCompleted(userTurnId)
                                assistantTurnId = historyStore.beginAssistantTurn(sessionId)
                            }
                            is MindlayerEvent.TextDelta -> {
                                textAccumulator.append(event.text)
                            }
                            is MindlayerEvent.Done -> {
                                val finalText = event.fullText
                                    ?: textAccumulator.toString()
                                val aid = assistantTurnId
                                if (aid != null) {
                                    historyStore.markTurnCompleted(aid, finalText)
                                }
                                completed = true
                            }
                            is MindlayerEvent.Error -> {
                                val aid = assistantTurnId
                                if (aid != null) {
                                    historyStore.markTurnInterrupted(aid)
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
                        historyStore.markTurnInterrupted(aid)
                    }
                }
                throw e
            }
        }
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
     * Register tools (function calling) for this session.
     * Pass a JSON array of OpenAPI-style tool definitions.
     * See [MindlayerEvent.ToolCall] for handling tool invocations.
     */
    fun tools(json: String) { toolsJson = json }

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
     * Pre-populate conversation history for session recovery.
     * Turns are injected into the model's context at creation time.
     */
    fun initialHistory(history: List<HistoryTurn>) { initialHistory = history }

    /** Set session expiration in milliseconds. Internal — consumers use [ConversationBuilder.expiration]. */
    internal fun expirationMs(ms: Long) { expirationMs = ms }

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
