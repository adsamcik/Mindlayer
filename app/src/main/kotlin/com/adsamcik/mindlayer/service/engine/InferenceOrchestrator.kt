package com.adsamcik.mindlayer.service.engine

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.RequestTrace
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.logging.safeLabelWithDetail
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.StagedMedia
import com.adsamcik.mindlayer.service.ipc.TokenStreamWriter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import com.adsamcik.mindlayer.service.logging.LogCategory
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.LogEvent
import com.adsamcik.mindlayer.service.logging.LogExtras
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Central inference request handler.
 *
 * Receives requests from [ServiceBinder], delegates session lifecycle to
 * [SessionManager], streams tokens over pipes, and coordinates foreground
 * state with the service.
 *
 * Each session has a per-session mutex so sends are serialised
 * (single-writer), but distinct sessions can run concurrently.
 */
class InferenceOrchestrator(
    private val service: MindlayerMlService,
    private val sessionManager: SessionManager,
    private val sharedMemoryPool: SharedMemoryPool,
    private val logRepository: com.adsamcik.mindlayer.service.logging.LogRepository? = null,
    private val writerFactory: (ParcelFileDescriptor) -> TokenStreamWriter = ::TokenStreamWriter,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * F-061: total wall-clock cap on a single inference. Tests inject a
     * smaller value so the timeout path is exercised without waiting the
     * full production budget.
     */
    private val maxInferenceMs: Long = MAX_INFERENCE_MS,
    /**
     * F-041: thermal policy provider. Default reads the live policy from
     * the service's `ThermalMonitor` so the orchestrator can refuse GPU
     * work in CRITICAL and pace tool-round retries on HOT. Tests inject
     * a fixed policy via this lambda. Failures from the live read fall
     * back to a COOL policy so a misconfigured `service` mock or a
     * partially-initialised service can't accidentally close every
     * inference with `thermal_critical`.
     */
    private val thermalPolicy: () -> ThermalPolicy = {
        try { service.thermalMonitor.currentPolicy.value ?: COOL_POLICY }
        catch (_: Throwable) { COOL_POLICY }
    },
) {

    companion object {
        private const val TAG = "InferenceOrchestrator"
        /**
         * Hard cap on the number of tool-call rounds per inference. Surfaced
         * via `ServiceCapabilities.maxToolRounds` so client SDKs can plan
         * their tool-runner loops; bumped from internal `private const`
         * to `const` for the v0.2 capability handshake.
         */
        const val MAX_TOOL_ROUNDS = 25

        /**
         * F-041: fallback policy applied when the live ThermalMonitor read
         * fails (typically: a test using a relaxed `service` mock). COOL
         * means "no thermal interventions" so the orchestrator behaves as
         * it did before F-041.
         */
        private val COOL_POLICY = ThermalPolicy(
            band = ThermalBand.COOL,
            recommendedBackend = "GPU",
            burstSeconds = 12,
            restSeconds = 0,
            chunkTokens = 128,
        )

        /**
         * F-036: cap on the JSON-encoded length of model-emitted tool
         * arguments. The 1 MiB pipe-frame limit is the upstream constraint
         * (`MAX_FRAME_BYTES`); 64 KiB matches the symmetric incoming
         * limit `IpcInputValidator.MAX_TOOL_RESULT_LEN` and leaves
         * headroom for envelope overhead. Truncating raw JSON yields
         * invalid JSON — that is the desired UX: the SDK's tool runner
         * sees malformed JSON and treats the call as a tool error.
         */
        const val MAX_TOOL_ARGS_LEN = 64 * 1024

        /**
         * F-061: hard wall-clock cap on a single inference. The SDK's tool
         * round-trip cap (`ToolCallBridge.DEFAULT_TIMEOUT_MS`) bounds a
         * single tool round; this bounds the whole conversation turn
         * (prefill + decode + every tool round combined) so a
         * runaway-loopy model or a deadlocked tool client cannot pin
         * a session forever and leak its slot/concurrency budget.
         */
        const val MAX_INFERENCE_MS = 5L * 60L * 1000L
        const val INFERENCE_DEADLINE_MS = MAX_INFERENCE_MS

        private val gson = Gson()
    }

    /** Extract concatenated text from a [Message]'s contents. */
    private fun Message.text(): String? {
        val parts = contents.contents.filterIsInstance<Content.Text>()
        return if (parts.isEmpty()) null else parts.joinToString("") { it.text }
    }

    /**
     * v1.1: extract the Gemma 4 thinking-channel fragment from a chunk,
     * or `null` when the session is not thinking-enabled or the chunk
     * carried no channel content. The lookup uses
     * [SessionManager.THINKING_CHANNEL_NAME] so the wire key
     * (`"thought"`) stays in one place.
     *
     * Returns `null` rather than empty string so call sites can use a
     * single `if (!thoughtText.isNullOrEmpty())` guard.
     */
    private fun Message.thoughtText(thinkingEnabled: Boolean): String? {
        if (!thinkingEnabled) return null
        val fragment = channels[SessionManager.THINKING_CHANNEL_NAME]
        return if (fragment.isNullOrEmpty()) null else fragment
    }

    // F-009: orchestrator runs on Dispatchers.IO (default). The IO pool is
    // sized for blocking work (~64 threads on Android), so even a worst-case
    // wedged-pipe burst cannot exhaust workers within the writer's 5s
    // backpressure-timeout window. Tests inject StandardTestDispatcher.
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Active inference jobs keyed by **scoped key** (`uid:publicRequestId`,
     * see `ServiceBinder.inferenceKey`). Namespacing by UID prevents a
     * co-signed peer from cancelling another caller's inference by guessing
     * the public requestId (F-007).
     */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Typed cancellation reasons keyed by scoped request id. Presence means
     * the CancellationException handler must emit an ERROR frame instead of
     * the normal user-cancel DONE frame.
     *
     * M-E2: writes go through [primeTypedCancellationReason] so reasons
     * with higher priority (e.g. [MindlayerErrorCode.ALLOWLIST_REVOKED] —
     * a user action) cannot be clobbered by a racing lower-priority
     * reason (e.g. [MindlayerErrorCode.LOW_MEMORY] — resource pressure).
     */
    private val typedCancellationReasons = ConcurrentHashMap<String, Int>()

    /**
     * M-E2: relative ranking used by [primeTypedCancellationReason] to
     * resolve concurrent EMERGENCY-vs-revoke races. Higher = harder
     * priority. ALLOWLIST_REVOKED always wins because it is a deliberate
     * user/operator action ("this app is no longer permitted to run
     * inference"); resource-pressure reasons (LOW_MEMORY, MEMORY_PRESSURE,
     * THERMAL_CRITICAL) are recoverable and lower priority.
     */
    private fun cancellationReasonPriority(reason: Int): Int = when (reason) {
        MindlayerErrorCode.ALLOWLIST_REVOKED -> 100
        MindlayerErrorCode.SESSION_EVICTED -> 60
        MindlayerErrorCode.THERMAL_CRITICAL -> 40
        MindlayerErrorCode.LOW_MEMORY -> 30
        MindlayerErrorCode.MEMORY_PRESSURE -> 30
        else -> 10
    }

    /**
     * M-E2: install a typed cancellation reason for [scopedKey], keeping
     * whichever reason has higher priority via [cancellationReasonPriority].
     * Returns the reason that is actually stored after the merge. Safe to
     * call concurrently from EMERGENCY and revoke paths.
     */
    fun primeTypedCancellationReason(scopedKey: String, reason: Int): Int =
        typedCancellationReasons.merge(scopedKey, reason) { existing, incoming ->
            if (cancellationReasonPriority(incoming) > cancellationReasonPriority(existing)) {
                incoming
            } else {
                existing
            }
        } ?: reason

    init {
        sessionManager.setEmergencyStreamCanceller { reason ->
            cancelAllActiveStreams(reason)
        }
    }

    /** Bridge between the streaming coroutine and AIDL submitToolResult(). */
    val toolCallBridge = ToolCallBridge(logRepository)

    // ---- Session management (delegates to SessionManager) ------------------

    fun createSession(config: SessionConfig): String =
        sessionManager.createSession(config)

    fun createSession(config: SessionConfig, ownerToken: Any?): String =
        sessionManager.createSession(config, ownerToken)

    fun closeAllOwnedBy(ownerToken: Any): List<String> {
        sessionManager.activeRequestIdsOwnedBy(ownerToken).forEach { scopedKey ->
            cancelInference(scopedKey)
        }
        return sessionManager.closeAllOwnedBy(ownerToken)
    }

    fun closeAllOwnedByUid(ownerUid: Int): List<String> {
        sessionManager.activeRequestIdsOwnedByUid(ownerUid).forEach { scopedKey ->
            cancelInference(scopedKey)
        }
        return sessionManager.closeAllOwnedByUid(ownerUid)
    }

    fun closeAllOwnedByUidForRevoke(ownerUid: Int): List<String> {
        sessionManager.activeRequestIdsOwnedByUid(ownerUid).forEach { scopedKey ->
            // H-E3: prime the typed reason BEFORE cancelInference so the
            // CancellationException handler emits an
            // ALLOWLIST_REVOKED error frame instead of the generic
            // user-cancel DONE frame. Without this, callers see
            // `cancelled` and cannot distinguish a revoke from a normal
            // cancel.
            primeTypedCancellationReason(scopedKey, MindlayerErrorCode.ALLOWLIST_REVOKED)
            cancelInference(scopedKey)
        }
        return sessionManager.closeAllOwnedByUidForRevoke(ownerUid)
    }

    fun getSessionOwner(sessionId: String): Int? =
        sessionManager.getSessionOwner(sessionId)

    fun getSessionOwnerToken(sessionId: String): Any? =
        sessionManager.getSessionOwnerToken(sessionId)

    fun listSessionsOwnedBy(ownerUid: Int): List<SessionInfo> =
        sessionManager.listSessionsOwnedBy(ownerUid)

    fun destroySession(sessionId: String) {
        sessionManager.activeRequestIdForSession(sessionId)?.let { scopedKey ->
            cancelInference(scopedKey)
        }
        sessionManager.destroySession(sessionId)
    }

    fun getSessionInfo(sessionId: String): SessionInfo? =
        sessionManager.getSessionInfo(sessionId)

    fun listSessions(): List<SessionInfo> =
        sessionManager.listSessions()

    // ---- Inference ---------------------------------------------------------

    /**
     * Launch an inference request. Streams events to [pipeWriteEnd] and closes
     * it when generation completes (or fails).
     *
     * If [image] or [audio] are provided, they are staged to cache files and
     * included as multimodal [Content] parts alongside the text prompt.
     *
     * ⚠️ Gemma 4 multimodal is blocked by issue #1874 (missing prompt-template
     * override in LiteRT-LM). The wiring is architecturally correct but may not
     * produce valid output until that issue is resolved.
     */
    fun infer(
        scopedKey: String,
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        pipeWriteEnd: ParcelFileDescriptor,
    ) {
        infer(scopedKey, meta, image, audio, pipeWriteEnd, onComplete = null)
    }

    /**
     * Launch an inference with an optional [onComplete] callback invoked on
     * the orchestrator's coroutine scope when the request finishes (success,
     * error, or cancellation). Used by [ServiceBinder] to release per-UID
     * rate-limit concurrency slots.
     *
     * [scopedKey] is the binder-supplied namespaced key (`uid:publicRequestId`)
     * used for all in-process state (activeJobs, ToolCallBridge.pending,
     * SharedMemoryPool.stagedFiles, SessionHandle.activeRequestId). The
     * public `meta.requestId` continues to flow to the SDK in stream events
     * unmodified.
     */
    fun infer(
        scopedKey: String,
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        pipeWriteEnd: ParcelFileDescriptor,
        onComplete: (() -> Unit)?,
    ) {
        // F-072: synchronous KV-budget gate. Look up the session's
        // already-reserved overhead (system prompt + tool defs +
        // structured-output schema + initialHistory) and compare against
        // the estimated input cost (text + image + audio + per-turn
        // chat-template envelope). If the request would overflow the
        // KV ceiling, throw BEFORE `scope.launch { runInference }` so
        // the engine never sees the prompt and the SDK's `infer()` AIDL
        // call surfaces the typed error synchronously via
        // [com.adsamcik.mindlayer.service.ServiceBinder].
        //
        // When the session is unknown / expired (peekTokenBudget == null),
        // skip the gate and let `runInference` produce the standard
        // SESSION_NOT_FOUND_OR_NOT_OWNED pipe error.
        val budget = sessionManager.peekTokenBudget(meta.sessionId)
        if (budget != null) {
            val estimatedInputTokens = estimateInputTokens(
                text = meta.textContent,
                image = image,
                audio = audio,
            )
            if (budget.reservedTokens + estimatedInputTokens > budget.effectiveMaxTokens) {
                throw ContextOverflowException(
                    reservedTokens = budget.reservedTokens,
                    estimatedInputTokens = estimatedInputTokens,
                    effectiveMaxTokens = budget.effectiveMaxTokens,
                )
            }
        }
        // F-076: synchronous SharedMemoryPool bounds gate. Fails fast at
        // the binder thread when the pool is at capacity, BEFORE we
        // launch the inference coroutine that would block in
        // stageImageWithTimeout / stageAudioWithTimeout for up to 20 s
        // each. ServiceBinder maps the typed exception to a wire-prefixed
        // SecurityException with code TRANSIENT_RESOURCE_EXHAUSTED.
        if (image != null || audio != null) {
            val numImages = if (image != null) 1 else 0
            val numAudios = if (audio != null) 1 else 0
            val expectedBytes: Long = (image?.payloadBytes?.toLong()?.coerceAtLeast(1L) ?: 0L) +
                (if (audio != null) com.adsamcik.mindlayer.service.ipc.MAX_MEDIA_BYTES.toLong() else 0L)
            sharedMemoryPool.precheckBounds(numImages, numAudios, expectedBytes)?.let { throw it }
        }
        val job = scope.launch {
            runInference(scopedKey, meta, image, audio, pipeWriteEnd)
        }
        activeJobs[scopedKey] = job
        job.invokeOnCompletion {
            activeJobs.remove(scopedKey)
            onComplete?.invoke()
        }
    }

    fun cancelInference(scopedKey: String) {
        val publicRequestId = scopedKey.substringAfter(':', scopedKey)
        val handle = sessionManager.findSessionByActiveRequest(scopedKey)
        if (handle != null) {
            MindlayerLog.i(
                TAG,
                "Cancelling native inference",
                requestId = publicRequestId,
                sessionId = handle.sessionId,
            )
            try {
                handle.conversation.cancelProcess()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "cancelProcess() failed: ${t.safeLabel()}")
            }
        }
        sharedMemoryPool.cleanup(scopedKey)
        toolCallBridge.cancel(scopedKey)
        activeJobs[scopedKey]?.cancel()
        logRepository?.logRequestCancel(
            requestId = publicRequestId,
            sessionId = handle?.sessionId,
        )
        MindlayerLog.i(
            TAG,
            "Cancelled inference request",
            requestId = publicRequestId,
            sessionId = handle?.sessionId,
        )
    }

    fun shutdown() {
        activeJobs.values.forEach { it.cancel() }
        runBlocking { awaitAllJobs(timeoutMs = 5_000) }
        activeJobs.clear()
        sharedMemoryPool.cleanupAll()
        sessionManager.shutdown()
    }

    /**
     * F-043: cancel every in-flight inference. Used by [MindlayerMlService]
     * when handling the foreground notification's STOP action. Each entry
     * goes through the standard [cancelInference] path so native
     * `cancelProcess()` is called and the SDK sees a clean cancellation
     * frame on the pipe.
     */
    fun cancelAll() {
        activeJobs.keys.toList().forEach { scopedKey ->
            try {
                cancelInference(scopedKey)
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "cancelAll: cancelInference($scopedKey) raised ${t.safeLabel()}")
            }
        }
    }

    /**
     * Cancel every active stream with a typed resource-pressure error.
     *
     * The native worker is stopped before coroutine cancellation via
     * Conversation.cancelProcess(); Flow cancellation alone is insufficient.
     */
    fun cancelAllActiveStreams(reason: Int = MindlayerErrorCode.LOW_MEMORY) {
        activeJobs.keys.toList().forEach { scopedKey ->
            val publicRequestId = scopedKey.substringAfter(':', scopedKey)
            val handle = sessionManager.findSessionByActiveRequest(scopedKey)
            // M-E2: merge with the existing reason so a higher-priority
            // user action (e.g. ALLOWLIST_REVOKED) cannot be overwritten
            // by a racing resource-pressure cancel.
            primeTypedCancellationReason(scopedKey, reason)
            if (handle != null) {
                try {
                    handle.conversation.cancelProcess()
                } catch (t: Throwable) {
                    MindlayerLog.w(
                        TAG,
                        "cancelProcess() failed during low-memory cancellation: ${t.safeLabel()}",
                        requestId = publicRequestId,
                        sessionId = handle.sessionId,
                    )
                }
                logRepository?.logInferenceError(
                    publicRequestId,
                    handle.sessionId,
                    (MindlayerErrorCode.nameOf(reason) ?: "UNKNOWN").lowercase(),
                )
            }
            toolCallBridge.cancel(scopedKey)
            activeJobs[scopedKey]?.cancel()
        }
    }

    /**
     * Suspend until all active inference jobs finish (or [timeoutMs] elapses).
     *
     * Call this before any destructive engine operation (backend switch,
     * session shutdown) to ensure no in-flight [Conversation.sendMessageAsync]
     * calls race against teardown.
     */
    suspend fun awaitAllJobs(timeoutMs: Long = 5_000) {
        // ConcurrentHashMap's `values` view exposes a weakly-consistent
        // iterator whose `hasNext()` / `next()` aren't atomic under
        // concurrent `remove(...)` from completing jobs — calling
        // `toList()` here races teardown and can surface a benign
        // `NoSuchElementException`. Snapshot via the documented thread-safe
        // bulk traversal (`forEach`) instead.
        val jobs = ArrayList<Job>(activeJobs.size)
        activeJobs.forEach { _, job -> jobs.add(job) }
        if (jobs.isEmpty()) return
        try {
            withTimeout(timeoutMs) { jobs.joinAll() }
        } catch (_: TimeoutCancellationException) {
            MindlayerLog.w(TAG, "awaitAllJobs timed out; ${activeJobs.size} job(s) still active")
        }
    }

    // ---- Private -----------------------------------------------------------

    private suspend fun runInference(
        scopedKey: String,
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        pipeWriteEnd: ParcelFileDescriptor,
    ) {
        val handle = sessionManager.getSession(meta.sessionId) ?: run {
            val writer = writerFactory(pipeWriteEnd)
            writer.closeWithError(
                0,
                "Unknown session: ${meta.sessionId}",
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
            )
            return
        }

        val trace = RequestTrace(meta.requestId, meta.sessionId)

        // Stage media outside the session lock so PFD I/O doesn't block other
        // sessions. Staging is keyed by the scoped key (uid:requestId) so a
        // co-signed peer with the same public requestId cannot delete or
        // collide with another caller's staged files (F-007).
        var stagedImage: StagedMedia? = null
        var stagedAudio: StagedMedia? = null
        try {
            if (image != null) {
                stagedImage = sharedMemoryPool.stageImageWithTimeout(scopedKey, image)
                MindlayerLog.d(TAG, "Staged image: ${stagedImage.filePath}", requestId = meta.requestId, sessionId = meta.sessionId)
            }
            if (audio != null) {
                stagedAudio = sharedMemoryPool.stageAudioWithTimeout(scopedKey, audio)
                MindlayerLog.d(TAG, "Staged audio: ${stagedAudio.filePath}", requestId = meta.requestId, sessionId = meta.sessionId)
            }
        } catch (t: Throwable) {
            MindlayerLog.e(
                TAG,
                "Media staging failed: ${t.safeLabel()}",
                requestId = meta.requestId,
                sessionId = meta.sessionId,
            )
            sharedMemoryPool.cleanup(scopedKey)
            val writer = writerFactory(pipeWriteEnd)
            writer.closeWithError(
                0,
                "media_staging_failed: ${t.safeLabel()}",
                MindlayerErrorCode.INVALID_REQUEST,
            )
            return
        }

        val deadlineNs = System.nanoTime() + INFERENCE_DEADLINE_MS * 1_000_000L
        // Single-writer: serialize sends for this session
        handle.mutex.withLock {
            handle.activeRequestId = scopedKey
            handle.isStreaming = true
            val writer = writerFactory(pipeWriteEnd)
            // v0.5 / v1.1: opt the writer into TOKEN_DELTA_BATCH coalescing
            // and/or v3 thinking stream protocol BEFORE writeHeader so the
            // header advertises the right wire version.
            if (handle.preferBatchedDeltas) {
                writer.enableBatching()
            }
            if (handle.preferThinking) {
                writer.enableThinking()
            }
            var foregroundEntered = false
            val inferenceStartNs = System.nanoTime()
            try {
                service.enterForeground()
                foregroundEntered = true
                logRepository?.logInferenceStart(
                    meta.requestId, meta.sessionId, service.engineManager.currentBackend
                )
                logRepository?.logUserMessage(
                    meta.requestId,
                    meta.sessionId,
                    tokenCount = ((meta.textContent?.length ?: 0) / 4).coerceAtLeast(if (meta.textContent.isNullOrEmpty()) 0 else 1),
                )
                kotlinx.coroutines.withTimeout(maxInferenceMs) {
                // F-041: thermal duty-cycle gate. CRITICAL band on GPU
                // means the device is at the edge of throttling; refuse
                // new GPU work outright so the engine isn't asked to
                // sustain a long decode loop while the SoC is too hot.
                // The recommended-backend signal flips to CPU when
                // CRITICAL is reached, but the engine doesn't switch
                // mid-session — refusing here lets the client reconnect
                // when thermal headroom recovers.
                val initialPolicy = thermalPolicy()
                val currentBackend = service.engineManager.currentBackend
                if (initialPolicy.band == ThermalBand.CRITICAL && currentBackend == "GPU") {
                    MindlayerLog.w(
                        TAG,
                        "Refusing inference under CRITICAL thermal band on GPU backend",
                        requestId = meta.requestId,
                        sessionId = meta.sessionId,
                    )
                    writer.closeWithError(
                        0,
                        "thermal_critical",
                        MindlayerErrorCode.THERMAL_CRITICAL,
                    )
                    return@withTimeout
                }
                writer.writeHeader(meta.requestId)

                // Build multimodal content parts.
                //
                // Gemma 4 multimodal previously hit LiteRT-LM #1874 (Kotlin
                // API never set the prompt-template image placeholder) and
                // #1686 (`max_num_images` defaulted to 0 even when the vision
                // executor was loaded). Both are fixed upstream as of
                // litertlm 0.12.0 PLUS [EngineManager] now passes
                // `visionBackend` + explicit `maxNumImages` for any model
                // that advertises `ModelInfo.supportsVision = true`. The
                // wire below stays identical: text + image + audio parts
                // map 1:1 onto litertlm's `Content` discriminator.
                val parts = mutableListOf<Content>()
                val textContent = meta.textContent
                if (textContent != null) {
                    // F-069: defense-in-depth scrub of user-supplied text.
                    // LiteRT-LM's `Content.Text` is structurally separated
                    // from system/tool roles, but the chat template
                    // flattens to a single string at tokenisation time —
                    // strip Gemma turn / image / sentinel tokens and any
                    // C0 controls so a hostile client cannot smuggle a
                    // role-flip marker via `RequestMeta.textContent`.
                    parts.add(Content.Text(ToolOutputSanitizer.scrub(textContent)))
                }
                if (stagedImage != null) {
                    parts.add(Content.ImageFile(stagedImage.filePath))
                }
                if (stagedAudio != null) {
                    parts.add(Content.AudioFile(stagedAudio.filePath))
                }

                val contents = if (parts.isNotEmpty()) {
                    Contents.of(*parts.toTypedArray())
                } else {
                    Contents.of("")
                }

                // Rough input-token estimate (≈ 1 token per 4 chars for text)
                val prompt = meta.textContent ?: ""
                handle.estimatedTokens += (prompt.length / 4).coerceAtLeast(1)

                var seq = 1L
                var toolCallRound = 0
                var requestTokenCount = 0
                var firstTokenSeen = false
                val accumulatedToolCalls = mutableListOf<Pair<String, String>>()
                // H-E4: in TOOL_ROUTING mode the only allowed tool name is
                // `StructuredOutputHelper.TOOL_NAME`. Any other tool name
                // the model fabricates is unverifiable output — count it
                // here and fail-closed with INVALID_REQUEST after the
                // current collect window so the caller sees a typed error
                // instead of a silent `done(stop)`.
                var toolRoutingDisallowedCount = 0

                val soConfig = handle.structuredOutputConfig
                val isToolRouting =
                    soConfig?.strategy == StructuredOutputStrategy.TOOL_ROUTING
                val isPromptValidate =
                    soConfig?.strategy == StructuredOutputStrategy.PROMPT_AND_VALIDATE
                // Buffer response for PROMPT_AND_VALIDATE (skip streaming
                // so we can validate + retry before sending to the client)
                val responseBuffer =
                    if (isPromptValidate) StringBuilder() else null
                val toolRoutingProseBuffer =
                    if (isToolRouting) StringBuilder() else null

                // --- First round: stream user message -----------------------
                trace.markPrefillStart()
                // v1.1: when thinking is on, pass the chat-template
                // variable on the per-send extraContext too. LiteRT-LM
                // 0.12.0 reads `enable_thinking` from both the
                // ConversationConfig.extraContext (set at session
                // creation, applies to every send) AND the per-send
                // map; passing it in both places is the documented
                // belt-and-braces pattern for Gemma thinking templates
                // (the Jinja `{%- if enable_thinking ... -%}` block
                // is re-evaluated on every render).
                val perSendExtraContext: Map<String, Any> = if (handle.preferThinking) {
                    mapOf(SessionManager.THINKING_TEMPLATE_KEY to true)
                } else {
                    emptyMap()
                }
                handle.conversation.sendMessageAsync(contents, perSendExtraContext).collect { chunk ->
                    // v1.1: drain Gemma 4 thinking-channel fragments
                    // BEFORE answer text so the SDK sees thoughts and
                    // answer in the same order the model produced them.
                    // The thought channel is only configured on
                    // thinking-enabled sessions (handle.preferThinking
                    // == true); on every other session the channels map
                    // is empty and this is a no-op cost-free hot path.
                    val thoughtText = chunk.thoughtText(handle.preferThinking)
                    if (!thoughtText.isNullOrEmpty()) {
                        writer.writeThoughtDelta(seq, thoughtText)
                        seq++
                        handle.estimatedTokens++
                        if (!firstTokenSeen) {
                            trace.markFirstToken()
                            firstTokenSeen = true
                        }
                    }
                    val text = chunk.text()
                    if (!text.isNullOrEmpty()) {
                        if (isPromptValidate) {
                            responseBuffer!!.append(text)
                        } else if (isToolRouting) {
                            toolRoutingProseBuffer!!.append(text)
                        } else {
                            writer.writeTokenDelta(seq, text)
                            seq++
                        }
                        handle.estimatedTokens++
                        requestTokenCount++
                        if (!firstTokenSeen) {
                            trace.markFirstToken()
                            firstTokenSeen = true
                        }
                    }
                    acceptToolCalls(handle, chunk.toolCalls, accumulatedToolCalls, meta).let {
                        toolRoutingDisallowedCount += it
                    }
                }

                // H-E4: TOOL_ROUTING fail-closed on unknown tool name.
                // Check BEFORE the prose check so a model that emits ONLY
                // a disallowed tool (no prose, no valid structured call)
                // still surfaces as INVALID_REQUEST instead of done(stop).
                if (isToolRouting && toolRoutingDisallowedCount > 0) {
                    closeStructuredOutputFailClosed(
                        writer = writer,
                        meta = meta,
                        reason = "tool_routing_disallowed_tool",
                        logMessage = "TOOL_ROUTING model emitted disallowed tool name " +
                            "($toolRoutingDisallowedCount dropped); failing closed",
                    )
                    return@withTimeout
                }

                // --- Structured output: TOOL_ROUTING extraction -------------
                if (isToolRouting && !toolRoutingProseBuffer.isNullOrEmpty()) {
                    closeStructuredOutputFailClosed(writer, meta)
                    return@withTimeout
                }
                if (isToolRouting && accumulatedToolCalls.isNotEmpty()) {
                    val structuredResult = StructuredOutputHelper.extractStructuredResult(
                        accumulatedToolCalls,
                    )
                    if (structuredResult != null) {
                        accumulatedToolCalls.removeAll {
                            it.first == StructuredOutputHelper.TOOL_NAME
                        }
                        val validated = validateAndMaybeRetry(
                            handle = handle,
                            initialOutput = structuredResult,
                            config = soConfig,
                            meta = meta,
                            writer = writer,
                            isToolRouting = true,
                        )
                        if (validated == null) {
                            // Helper already emitted the closeWithError frame
                            // and logged. Fall through to finally for cleanup.
                            return@withTimeout
                        }
                        writer.writeTokenDelta(seq, validated)
                        seq++
                        handle.estimatedTokens++
                        requestTokenCount++
                    }
                }

                // --- Structured output: PROMPT_AND_VALIDATE retry -----------
                if (isPromptValidate && responseBuffer != null) {
                    val validated = validateAndMaybeRetry(
                        handle = handle,
                        initialOutput = responseBuffer.toString(),
                        config = soConfig,
                        meta = meta,
                        writer = writer,
                        isToolRouting = false,
                    )
                    if (validated == null) {
                        return@withTimeout
                    }
                    writer.writeTokenDelta(seq, validated)
                    seq++
                    handle.estimatedTokens++
                    requestTokenCount++
                }

                // --- Tool call loop -----------------------------------------
                while (accumulatedToolCalls.isNotEmpty() && toolCallRound < MAX_TOOL_ROUNDS) {
                    if (handle.estimatedTokens >= handle.effectiveMaxTokens) {
                        MindlayerLog.w(TAG, "Inference token budget exceeded (${handle.estimatedTokens}/${handle.effectiveMaxTokens})", requestId = meta.requestId, sessionId = meta.sessionId)
                        writer.writeDone(seq, "token_limit")
                        seq++
                        return@withTimeout
                    }
                    // F-041: between tool rounds, sample the live thermal
                    // policy and insert a `restSeconds` delay if the
                    // device is in HOT / CRITICAL. This duty-cycles GPU
                    // pressure across multi-round tool conversations
                    // without aborting the request — tools that aren't
                    // dominated by inference time will barely notice.
                    if (toolCallRound > 0) {
                        val pol = thermalPolicy()
                        if (pol.restSeconds > 0) {
                            MindlayerLog.d(
                                TAG,
                                "Thermal duty-cycle: pausing ${pol.restSeconds}s " +
                                    "(band=${pol.band}) before tool round ${toolCallRound + 1}",
                                requestId = meta.requestId,
                                sessionId = meta.sessionId,
                            )
                            kotlinx.coroutines.delay(pol.restSeconds * 1000L)
                        }
                    }
                    // TOOL_ROUTING: intercept synthetic tool before forwarding
                    if (isToolRouting) {
                        val structuredResult = StructuredOutputHelper
                            .extractStructuredResult(accumulatedToolCalls)
                        if (structuredResult != null) {
                            accumulatedToolCalls.removeAll {
                                it.first == StructuredOutputHelper.TOOL_NAME
                            }
                            val validated = validateAndMaybeRetry(
                                handle = handle,
                                initialOutput = structuredResult,
                                config = soConfig,
                                meta = meta,
                                writer = writer,
                                isToolRouting = true,
                            )
                            if (validated == null) {
                                return@withTimeout
                            }
                            writer.writeTokenDelta(seq, validated)
                            seq++
                            handle.estimatedTokens++
                            requestTokenCount++
                            if (accumulatedToolCalls.isEmpty()) break
                        }
                    }

                    toolCallRound++
                    MindlayerLog.i(
                        TAG,
                        "Tool call round $toolCallRound (${accumulatedToolCalls.size} call(s))",
                        requestId = meta.requestId,
                        sessionId = meta.sessionId,
                    )

                    val pending = toolCallBridge.registerPendingToolCalls(
                        scopedKey, accumulatedToolCalls.toList()
                    )
                    for (call in pending) {
                        writer.writeToolCall(seq, call.callId, call.toolName, call.arguments)
                        seq++
                        logRepository?.log(LogEntry(
                            timestampMs = System.currentTimeMillis(),
                            category = LogCategory.INFERENCE,
                            event = LogEvent.TOOL_CALL.key,
                            requestId = meta.requestId,
                            sessionId = meta.sessionId,
                            // F-044: build JSON via the kotlinx-serialization
                            // builder so attacker-controlled toolNames cannot
                            // corrupt the persisted log entry.
                            extraJson = kotlinx.serialization.json.buildJsonObject {
                                put("tool", kotlinx.serialization.json.JsonPrimitive(call.toolName))
                                put("round", kotlinx.serialization.json.JsonPrimitive(toolCallRound))
                            }.toString(),
                        ))
                    }
                    accumulatedToolCalls.clear()

                    // Suspend until client submits all tool results (or timeout)
                    val results = try {
                        toolCallBridge.awaitResults(scopedKey)
                    } catch (e: TimeoutCancellationException) {
                        logRepository?.logToolCallTimeout(
                            requestId = meta.requestId,
                            sessionId = meta.sessionId,
                            timeoutMs = ToolCallBridge.DEFAULT_TIMEOUT_MS,
                        )
                        logRepository?.logToolCallExit(
                            requestId = meta.requestId,
                            sessionId = meta.sessionId,
                            result = "timeout",
                            pendingCount = pending.size,
                        )
                        throw e
                    }
                    logRepository?.logToolCallExit(
                        requestId = meta.requestId,
                        sessionId = meta.sessionId,
                        result = "completed",
                        pendingCount = pending.size,
                    )

                    // F-035: scrub Gemma turn-tokens and wrap each tool
                    // result in a per-request-nonced envelope. The model's
                    // safety preamble (set in SessionManager) tells it that
                    // envelope contents are untrusted data, never instructions.
                    val toolResponses = results.map { (name, result) ->
                        Content.ToolResponse(name, ToolOutputSanitizer.wrap(name, result))
                    }
                    val response = handle.conversation.sendMessage(
                        Message.tool(Contents.of(toolResponses)),
                        perSendExtraContext,
                    )

                    // v1.1: emit any thinking-channel fragment the model
                    // produced while reasoning about the tool result
                    // BEFORE the answer continuation.
                    val responseThought = response.thoughtText(handle.preferThinking)
                    if (!responseThought.isNullOrEmpty()) {
                        writer.writeThoughtDelta(seq, responseThought)
                        seq++
                        handle.estimatedTokens++
                    }

                    // Write the model's continuation text
                    val text = response.text()
                    if (!text.isNullOrEmpty()) {
                        if (isToolRouting) {
                            closeStructuredOutputFailClosed(writer, meta)
                            return@withTimeout
                        }
                        writer.writeTokenDelta(seq, text)
                        seq++
                        handle.estimatedTokens++
                        requestTokenCount++
                    }

                    // Check whether the model wants more tool calls
                    val droppedRound = acceptToolCalls(handle, response.toolCalls, accumulatedToolCalls, meta)
                    if (isToolRouting && droppedRound > 0) {
                        // H-E4: same fail-closed contract for later tool
                        // rounds. Cancel the running native generation so
                        // we don't keep decoding past the point we know the
                        // request is invalid.
                        try {
                            handle.conversation.cancelProcess()
                        } catch (t: Throwable) {
                            MindlayerLog.w(
                                TAG,
                                "cancelProcess() after TOOL_ROUTING fail-closed raised ${t.safeLabel()}",
                                requestId = meta.requestId,
                                sessionId = meta.sessionId,
                            )
                        }
                        closeStructuredOutputFailClosed(
                            writer = writer,
                            meta = meta,
                            reason = "tool_routing_disallowed_tool",
                            logMessage = "TOOL_ROUTING model emitted disallowed tool name in round " +
                                "$toolCallRound ($droppedRound dropped); failing closed",
                        )
                        return@withTimeout
                    }
                }

                val finishReason = when {
                    System.nanoTime() > deadlineNs -> "wallclock_limit"
                    handle.estimatedTokens >= handle.effectiveMaxTokens -> "token_limit"
                    toolCallRound >= MAX_TOOL_ROUNDS -> "tool_call_limit"
                    else -> "stop"
                }
                trace.markDecodeEnd(requestTokenCount)
                val finalMetrics = kotlinx.serialization.json.buildJsonObject {
                    trace.timeToFirstTokenMs?.takeIf { it > 0 }?.let { ttftMs ->
                        val inputTokens = (prompt.length / 4).coerceAtLeast(if (prompt.isEmpty()) 0 else 1)
                        put("prefillToksPerSec", kotlinx.serialization.json.JsonPrimitive(inputTokens * 1000f / ttftMs))
                    }
                    trace.tokensPerSec?.let {
                        put("decodeToksPerSec", kotlinx.serialization.json.JsonPrimitive(it))
                    }
                    put("thermalBand", kotlinx.serialization.json.JsonPrimitive(thermalPolicy().band.name))
                    put("generatedTokens", kotlinx.serialization.json.JsonPrimitive(requestTokenCount))
                    put("durationMs", kotlinx.serialization.json.JsonPrimitive(trace.totalDurationMs))
                }
                writer.writeMetrics(seq, finalMetrics)
                seq++
                writer.writeDone(seq, finishReason)
                trace.markPipeWriteComplete()
                handle.turnCount++
                handle.recordAccess()

                // Log inference completion with metrics
                val durationMs = (System.nanoTime() - inferenceStartNs) / 1_000_000
                val tokensGenerated = handle.estimatedTokens
                val tokPerSec = if (durationMs > 0) tokensGenerated * 1000f / durationMs else 0f
                logRepository?.logModelResponse(
                    meta.requestId, meta.sessionId, tokenCount = requestTokenCount,
                )
                logRepository?.logInferenceComplete(
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                    backend = service.engineManager.currentBackend,
                    durationMs = durationMs,
                    tokensGenerated = tokensGenerated,
                    tokensPerSec = tokPerSec,
                    prefillTps = null,
                )
                MindlayerLog.i(TAG, trace.summary(), requestId = meta.requestId, sessionId = meta.sessionId)

                } // end withTimeout(MAX_INFERENCE_MS)

            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // F-061: total wall-clock budget for a single inference is
                // MAX_INFERENCE_MS. On expiry, cancel native generation,
                // mark the trace, log, and emit a terminal error frame so
                // the SDK reports a stable `inference_timeout` reason
                // instead of a generic cancellation.
                toolCallBridge.cancel(scopedKey)
                try {
                    handle.conversation.cancelProcess()
                } catch (t: Throwable) {
                    MindlayerLog.w(TAG, "cancelProcess() after timeout raised ${t.safeLabel()}", requestId = meta.requestId, sessionId = meta.sessionId)
                }
                trace.markError("inference_timeout")
                MindlayerLog.w(
                    TAG,
                    "Inference exceeded wall-clock cap (${maxInferenceMs} ms)",
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                )
                logRepository?.logInferenceError(
                    meta.requestId, meta.sessionId, "inference_timeout"
                )
                kotlinx.coroutines.withContext(NonCancellable) {
                    try {
                        writer.closeWithError(
                            0,
                            "inference_timeout",
                            MindlayerErrorCode.INTERNAL,
                        )
                    } catch (_: Throwable) { }
                }
                // Don't re-throw — caller treats timeout as a clean termination
                // for the slot/concurrency accounting. The job completes normally.
            } catch (e: CancellationException) {
                toolCallBridge.cancel(scopedKey)
                // Flow cancellation does NOT cancel native generation — the LiteRT-LM
                // engine keeps decoding in the background unless we ask it to stop.
                // This matters on broken-pipe (client died mid-stream) because the
                // writer raises CancellationException to unwind the coroutine.
                try {
                    handle.conversation.cancelProcess()
                } catch (t: Throwable) {
                    MindlayerLog.w(TAG, "cancelProcess() after CancellationException raised ${t.safeLabel()}", requestId = meta.requestId, sessionId = meta.sessionId)
                }
                val typedReason = typedCancellationReasons.remove(scopedKey)
                val terminalReason = typedReason?.let { (MindlayerErrorCode.nameOf(it) ?: "UNKNOWN").lowercase() } ?: "cancelled"
                trace.markError(terminalReason)
                MindlayerLog.i(
                    TAG,
                    "Inference cancelled",
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                )
                // F-009: writer terminal writes are suspend, but we are in a
                // CancellationException catch — coroutine is being torn
                // down. Wrap with NonCancellable so the terminal frame
                // can flush before the pipe closes.
                withContext(NonCancellable) {
                    try {
                        if (typedReason != null) {
                            writer.closeWithError(0, terminalReason, typedReason)
                        } else {
                            writer.writeDone(0, "cancelled")
                        }
                    } catch (_: Throwable) {
                        // Best-effort terminal frame; pipe may already be closed.
                    }
                }
                throw e
            } catch (t: Throwable) {
                toolCallBridge.cancel(scopedKey)
                // safeLabelWithDetail surfaces the native LiteRT-LM JNI
                // error message ("Failed to allocate KV cache", "Model
                // load failed", op-not-supported etc.) to both logcat
                // and the wire-level MindlayerException the SDK sees.
                // The allowlist confines this to provably-safe technical
                // exception classes — prompt fragments never reach here.
                val safe = t.safeLabelWithDetail()
                trace.markError(safe)
                MindlayerLog.e(
                    TAG,
                    "Inference failed: $safe",
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                )
                logRepository?.logInferenceError(
                    meta.requestId, meta.sessionId, safe
                )
                writer.closeWithError(0, safe, MindlayerErrorCode.INTERNAL)
                return
            } finally {
                typedCancellationReasons.remove(scopedKey)
                writer.close()
                handle.activeRequestId = null
                handle.isStreaming = false
                if (foregroundEntered) service.exitForeground()
                // Clean up staged media files regardless of outcome — keyed
                // by scopedKey so a co-signed peer with the same public
                // requestId cannot trigger early cleanup of our files.
                sharedMemoryPool.cleanup(scopedKey)
            }
        }
    }

    private suspend fun closeStructuredOutputFailClosed(
        writer: TokenStreamWriter,
        meta: RequestMeta,
        reason: String = "structured_output_fail_closed",
        logMessage: String =
            "TOOL_ROUTING emitted prose without a structured tool call; failing closed",
    ) {
        MindlayerLog.w(
            TAG,
            logMessage,
            requestId = meta.requestId,
            sessionId = meta.sessionId,
        )
        logRepository?.logInferenceError(
            meta.requestId,
            meta.sessionId,
            reason,
        )
        writer.closeWithError(
            0,
            reason,
            MindlayerErrorCode.INVALID_REQUEST,
        )
    }

    /**
     * F-036: filter the model-emitted tool calls in [toolCalls] against
     * [SessionManager.SessionHandle.allowedToolNames]. Unknown names are
     * dropped (and logged); known names whose serialised arguments exceed
     * [MAX_TOOL_ARGS_LEN] are truncated (yielding invalid JSON, which the
     * SDK treats as a tool error — fail-closed by design).
     *
     * H-E4: returns the number of tool calls that were dropped due to an
     * unknown name so callers running in TOOL_ROUTING mode can fail-closed
     * with a typed `INVALID_REQUEST` instead of falling through to a
     * silent `done(stop)` — a model that fabricates a tool name in
     * structured-output mode is producing unverifiable output, not a
     * normal completion.
     */
    private fun acceptToolCalls(
        handle: SessionManager.SessionHandle,
        toolCalls: List<com.google.ai.edge.litertlm.ToolCall>,
        accumulator: MutableList<Pair<String, String>>,
        meta: RequestMeta,
    ): Int {
        var dropped = 0
        for (tc in toolCalls) {
            if (tc.name !in handle.allowedToolNames) {
                dropped++
                val toolMetadata = LogExtras.toolNameMetadata(tc.name)
                MindlayerLog.w(
                    TAG,
                    "Dropped model-emitted tool call with unknown name metadata=$toolMetadata",
                    requestId = meta.requestId, sessionId = meta.sessionId,
                )
                logRepository?.log(com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.SECURITY,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.TOOL_CALL_REJECTED.key,
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                    extraJson = toolMetadata.toString(),
                ))
                continue
            }
            val argsJson = gson.toJson(tc.arguments)
            val cappedArgs = if (argsJson.length > MAX_TOOL_ARGS_LEN) {
                val toolMetadata = LogExtras.oversizeArgsMetadata(tc.name, argsJson.length)
                MindlayerLog.w(
                    TAG,
                    "Truncated oversize tool args metadata=$toolMetadata to $MAX_TOOL_ARGS_LEN bytes",
                    requestId = meta.requestId, sessionId = meta.sessionId,
                )
                logRepository?.log(com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.SECURITY,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.TOOL_CALL_REJECTED.key,
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                    extraJson = toolMetadata.toString(),
                ))
                // Intentionally produce invalid JSON: the SDK's tool runner
                // will fail to parse it and surface a tool-error to the
                // caller. Smart truncation could elide a closing brace and
                // produce shorter-but-still-parseable malicious args.
                argsJson.substring(0, MAX_TOOL_ARGS_LEN)
            } else argsJson
            accumulator.add(tc.name to cappedArgs)
        }
        return dropped
    }

    /**
     * F-038: shared validation + retry helper for both PROMPT_AND_VALIDATE
     * and TOOL_ROUTING. On retry exhaustion both strategies fail-closed —
     * the helper writes `closeWithError(seq, "structured_output_validation_failed")`
     * and returns `null` instead of leaking the last invalid output to the
     * client.
     *
     * For TOOL_ROUTING the retry loop re-prompts the model with the
     * standard retry prompt; the model is expected to invoke the
     * synthetic structured-output tool again. For PROMPT_AND_VALIDATE the
     * retry yields free-text JSON.
     *
     * @return validated JSON string on success, or `null` if the helper
     *         already emitted the terminal error frame and the caller
     *         must unwind the request.
     */
    private suspend fun validateAndMaybeRetry(
        handle: SessionManager.SessionHandle,
        initialOutput: String,
        config: StructuredOutputConfig,
        meta: RequestMeta,
        writer: TokenStreamWriter,
        isToolRouting: Boolean,
    ): String? {
        // v0.5: caller can opt out of server-side validation entirely via
        // JsonValidationDepth.NONE / CALLER_VALIDATES. The model output is
        // returned verbatim — caller is expected to validate locally.
        if (!config.serverValidate) {
            return initialOutput
        }
        var output = initialOutput
        for (attempt in 0..config.maxRetries) {
            when (val result = StructuredOutputHelper.validateJsonOutput(output, config.schema)) {
                is ValidationResult.Valid -> return result.json
                is ValidationResult.Invalid -> {
                    if (attempt >= config.maxRetries) {
                        MindlayerLog.w(
                            TAG,
                            "Structured output validation failed after $attempt retries; " +
                                "failing request (strategy=${if (isToolRouting) "tool_routing" else "prompt_and_validate"}, " +
                                "errorCount=${result.errors.size})",
                            requestId = meta.requestId, sessionId = meta.sessionId,
                        )
                        logRepository?.logInferenceError(
                            meta.requestId, meta.sessionId,
                            "structured_output_validation_failed",
                        )
                        writer.closeWithError(
                            0,
                            "structured_output_validation_failed",
                            MindlayerErrorCode.INVALID_REQUEST,
                        )
                        return null
                    }
                    MindlayerLog.w(
                        TAG,
                        "Structured output: validation failed " +
                            "(retry ${attempt + 1}/${config.maxRetries}, " +
                            "errorCount=${result.errors.size}, " +
                            "strategy=${if (isToolRouting) "tool_routing" else "prompt_and_validate"})",
                        requestId = meta.requestId, sessionId = meta.sessionId,
                    )
                    val retryPrompt = StructuredOutputHelper.buildRetryPrompt(
                        result.errors, config.schema,
                    )
                    val retryBuf = StringBuilder()
                    handle.conversation
                        .sendMessageAsync(Contents.of(retryPrompt))
                        .collect { chunk ->
                            chunk.text()?.let { retryBuf.append(it) }
                            if (isToolRouting) {
                                // The synthetic tool's arguments are what
                                // we want to validate. Capture every
                                // emitted call to the structured-output
                                // tool and prefer its arguments over any
                                // free-text output.
                                for (tc in chunk.toolCalls) {
                                    if (tc.name == StructuredOutputHelper.TOOL_NAME) {
                                        retryBuf.clear()
                                        retryBuf.append(gson.toJson(tc.arguments))
                                    }
                                }
                            }
                        }
                    output = retryBuf.toString()
                }
            }
        }
        // Unreachable: the loop body always returns or breaks via the
        // exhaustion branch above.
        return null
    }
}
