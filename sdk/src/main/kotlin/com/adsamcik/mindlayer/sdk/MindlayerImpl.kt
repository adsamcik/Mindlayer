package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
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
 * mindlayer.awaitConnected(30.seconds)
 *
 * mindlayer.infer {
 *     ephemeralSession { systemPrompt = "You are a helpful assistant." }
 *     text("Hello!")
 * }.events.collect { event ->
 *     when (event) {
 *         is InferenceEvent.TextDelta -> print(event.text)
 *         is InferenceEvent.Done -> println()
 *         else -> {}
 *     }
 * }
 *
 * mindlayer.disconnect()
 * ```
 *
 * # Inference API — three canonical entry points
 *
 * As of `feat/inference-sdk-polish`, the inference surface consolidates around
 * three top-level methods. All three accept the same
 * `(sessionId, text, vararg media)` shape so consumers can swap shapes
 * without re-learning parameters.
 *
 * | Method | Returns | Use when |
 * |---|---|---|
 * | [inferRealtime] | [InferenceHandle] streaming `Flow<InferenceEvent>` | UI wants token-by-token rendering, or you need fine-grained control over the event stream |
 * | [inferAsync] | `String` (collected to completion) | You want the final response as a single value, no tool-call round-trips |
 * | [inferTools] | [InferenceHandle] | Session was configured with [SessionConfigBuilder.tools]; you intend to handle [InferenceEvent.ToolCall] events and call [submitToolResultDetailed] |
 *
 * Build media attachments via
 * [`MediaTransfer.imagePart(...)`][MediaTransfer.imagePart] /
 * [`MediaTransfer.audioPart(...)`][MediaTransfer.audioPart] and pass them
 * as `vararg media` to any of the three. The SDK handles transparent
 * fallback to the v0.1 single-image / single-audio wire shape when the
 * connected service does not advertise
 * [ServiceCapabilities.FEATURE_MEDIA_LIST].
 *
 * The legacy method families (`chat*`, `*Once`) remain available and
 * carry `@Deprecated(ReplaceWith = …)` annotations so Android Studio's
 * "Replace with new API" intention works in consumer projects. See
 * `docs/INFERENCE_SDK_POLISH.md` for the deprecation timeline.
 *
 * # Error and capability contract
 *
 * Every inference method may throw one of:
 *
 * - [MindlayerException] — typed errors carrying a stable
 *   [com.adsamcik.mindlayer.shared.MindlayerErrorCode]. Produced by the
 *   service's auth gate (rate limit, ownership, validation) and by the
 *   SDK's pipe stream when it sees an `ERROR` frame.
 * - [SecurityException] — auth-gate refusal (allowlist rejection,
 *   first-time pending-approval). Propagated unchanged so platform-level
 *   IDS / Play Protect does not lose signal.
 * - [android.os.RemoteException] — binder transport failure (service
 *   crashed, disconnected mid-call).
 *
 * | Method | Capability gate | Fallback when capability missing |
 * |---|---|---|
 * | [inferRealtime] / [inferAsync] / [inferTools] | [ServiceCapabilities.FEATURE_MEDIA_LIST] when `media.isNotEmpty()` | Transparent — routes through legacy `infer(meta, image, audio, pfd)`; rejects multi-image with `INVALID_REQUEST` |
 * | [chatDeferred] / [fetchDeferredResult] / [cancelDeferred] / [acknowledgeDeferred] / [awaitDeferred] | [ServiceCapabilities.FEATURE_DEFERRED_INFERENCE] | None — throws [MindlayerException] with `NOT_SUPPORTED` |
 * | [submitToolResultDetailed] / [cancelInferenceDetailed] | [ServiceCapabilities.FEATURE_DETAILED_CANCEL] | Transparent — routes through the v0.1 surface and reports the synthesized result |
 * | [prewarmAndAwait] | [ServiceCapabilities.FEATURE_PREWARM_AWAIT] | Transparent — issues fire-and-forget [prewarm] and returns the requested backend |
 * | [getDiagnosticsTyped] | [ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS] | Returns `null` |
 * | [ping] | [ServiceCapabilities.FEATURE_HEALTH_CHECK] | Synthesizes a [com.adsamcik.mindlayer.HealthCheck] from [getStatus] + cached caps |
 * | [evictionNotices] | [ServiceCapabilities.FEATURE_EVICTION_CALLBACK] | Empty flow (never emits) |
 *
 * The full error code vocabulary lives in
 * [`MindlayerErrorCode`][com.adsamcik.mindlayer.shared.MindlayerErrorCode].
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
 *
 * **Embeddings — error and capability contract**
 *
 * The canonical embedding entry point [embed] (and the [embedBatchDeferred] /
 * [fetchEmbeddingBatch] / [cancelEmbed] / [cancelEmbeddingBatch] /
 * [acknowledgeEmbeddingBatch] composers built on top of it) obeys the
 * following contract:
 *
 * | Failure scenario                                              | `MindlayerException.code`         |
 * |---------------------------------------------------------------|-----------------------------------|
 * | Service doesn't advertise `FEATURE_EMBEDDINGS`                | `NOT_SUPPORTED` (5006)            |
 * | Old service stub (`NoSuchMethodError`/`AbstractMethodError`)  | `NOT_SUPPORTED` (5006)            |
 * | Empty `items` / batch over the active cap                     | `IllegalArgumentException` (eager)|
 * | Service-side batch too large past validation                  | `EMBEDDING_BATCH_TOO_LARGE` (5011)|
 * | Service-side input bytes too long                             | `EMBEDDING_INPUT_TOO_LONG` (5013) |
 * | Embedding model unavailable on device                         | `EMBEDDING_MODEL_UNAVAILABLE` (5012) |
 * | Embeddings disabled by policy                                 | `EMBEDDING_DISABLED` (5014)       |
 * | Deferred batch fetched after TTL                              | `DEFERRED_EXPIRED` (5008)         |
 * | Deferred batch not owned by this UID                          | `SESSION_NOT_FOUND_OR_NOT_OWNED` (5004) |
 * | Per-UID rate limit exceeded                                   | `RATE_LIMITED` (5002)             |
 *
 * The canonical [embed] DSL picks the cheapest viable transport (inline /
 * SharedMemory / deferred fallback) automatically based on batch size,
 * payload estimate, API level, and service-advertised caps — callers do
 * not choose. The high-level [vector] / [vectors] helpers compose on top
 * of [embed].
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

internal class MindlayerImpl(
    internal val connection: ConnectionManager,
    private val historyStore: HistoryStore?,
) : MindlayerHelpers() {

    companion object {
        private const val TAG = "MindlayerImpl"

        internal const val TOOL_CALL_IN_ONESHOT_MSG =
            "Tool calls are not supported in one-shot mode. " +
            "Use the streaming chat() API with a ToolCall handler instead."

        private const val DEFAULT_CREATE_SESSION_INIT_RETRY_TIMEOUT_MS = 10_000L
        private val DEFAULT_CREATE_SESSION_INIT_RETRY_BACKOFF_MS = listOf(50L, 200L, 800L)

        /**
         * Number of transparent retries an *idempotent* AIDL call gets when the
         * `:ml` process dies mid-transaction ([android.os.DeadObjectException]).
         * One retry is enough to ride out a single OS low-memory kill followed
         * by the `BIND_AUTO_CREATE` restart; persistent death surfaces a typed
         * [MindlayerErrorCode.SERVICE_UNAVAILABLE] so callers can back off.
         * Non-idempotent calls never retry — they map straight to the typed
         * error to avoid duplicating a possibly-applied side effect.
         */
        private const val MAX_SERVICE_DEATH_RETRIES = 1

        /**
         * Per-instance buffer for [evictionNotices]. 64 entries is enough to
         * absorb a device-wide memory-pressure storm (which evicts every
         * non-streaming session, typically ≤8) plus normal expiration churn
         * without dropping anything in steady-state.
         */
        const val EVICTION_BUFFER: Int = 64

        /**
         * TTL for the [getCapabilities] cache. Capabilities are dynamic —
         * the service's on-device engines warm up asynchronously after
         * first connect (PaddleOCR ~200ms, Gemma 4 E2B ~1-3s, EmbeddingGemma
         * ~500ms), unload on memory pressure, and reload lazily on the
         * next call. Caching forever would silently pin the very first
         * reply (often `FEATURE_OCR_* = false` during init) and would
         * stop capability-aware clients from ever seeing features come
         * online (Bug #6). 5s is short enough that a feature flipping
         * on is visible within a polling iteration, long enough to absorb
         * tight back-to-back gets without redundant binder round-trips.
         * Tests may pass `forceRefresh = true` to bypass entirely.
         */
        const val CAPABILITIES_CACHE_TTL_MS: Long = 5_000L

        /**
         * Maximum estimated reply-parcel bytes before [embedMany] upgrades from
         * inline binder transport to SharedMemory. Half of the documented
         * 1 MB binder transaction limit, leaving generous headroom for parcel
         * overhead and any other in-flight transactions on the same binder.
         * Internal so tests can pin selection-rule behavior without bringing
         * real binders into the loop.
         */
        internal const val INLINE_REPLY_BYTE_BUDGET: Long = 512L * 1024L

        /** Native output dimension of EmbeddingGemma-300M. */
        internal const val DEFAULT_EMBEDDING_DIM: Int = 768

        /**
         * Conservative per-item parcel overhead used when estimating reply
         * bytes for transport selection. Covers tag, modelId, tokenCount,
         * truncated, backend, durationMs, schemaVersion serialization.
         */
        internal const val PARCEL_OVERHEAD_BYTES_PER_ITEM: Long = 256L

        /** Sentinel backend reported when the deferred-fallback path produced no items. */
        internal const val UNKNOWN_BACKEND: String = "unknown"
    }

    /**
     * Session recovery helper. `null` when [historyStore] is disabled.
     */
    val recovery: SessionRecovery? =
        historyStore?.let { SessionRecovery(this, it) }

    /**
     * Optional observability hook (Spike-E §3). Set by [Mindlayer.connect] right
     * after construction rather than via the constructor, because the SDK test
     * suite reflectively resolves the 2-arg `(ConnectionManager, HistoryStore)`
     * constructor; adding a third constructor parameter — even a defaulted one —
     * would change the reflected signature and break those tests. A mutable
     * property keeps construction unchanged while still allowing
     * `connect(observer = …)` to wire telemetry exactly once.
     */
    internal var observer: MindlayerObserver? = null

    /**
     * Bracket a public canonical call with observer start/end events. Pure
     * delegation to [instrumentCall]; defined here so the canonical overrides
     * below read as one-liners. Zero overhead when [observer] is unset.
     */
    private suspend fun <R> instrument(
        method: String,
        params: Map<String, String>,
        summarise: (R) -> String? = { null },
        block: suspend () -> R,
    ): R = instrumentCall(observer, method, params, summarise, block)

    // ── Canonical builder-based API (Spike-E §0/§1) ────────────────────────
    //
    // C2 wires the observer chokepoint around each canonical entry point. The
    // request builder is materialised eagerly so redacted telemetry params
    // (sizes / shapes / flags only — never prompt text or bytes) can be derived
    // before dispatch. The behavioural body that turns a recorded request into
    // an AIDL round-trip lands in C3; until then each call reports start/end and
    // then surfaces a typed NOT_SUPPORTED failure (so the observer still sees a
    // well-formed Failure outcome rather than a raw `error()`/ISE).

    override suspend fun infer(build: InferenceRequest.Builder.() -> Unit): InferenceHandle {
        val request = InferenceRequest.Builder().apply(build)
        return instrument(
            method = "infer",
            params = mapOf(
                "promptLen" to CallParams.len(request.promptText),
                "images" to request.imageInputs.size.toString(),
                "hasAudio" to CallParams.has(request.audioFile),
                "session" to CallParams.idPrefix(request.sessionId),
            ),
        ) {
            runInferRequest(request, overrideSessionId = null)
        }
    }

    override suspend fun ocr(build: OcrRequest.Builder.() -> Unit): OcrHandle.OneShot {
        val request = OcrRequest.Builder().apply(build)
        return instrument(
            method = "ocr",
            params = mapOf(
                "hasImage" to CallParams.has(request.image),
                "extract" to CallParams.has(request.extractionSchema),
                "boxes" to request.emitBoundingBoxes.toString(),
            ),
        ) {
            runOcrRequest(request)
        }
    }

    override suspend fun openSession(configure: SessionScope.() -> Unit): MindlayerSession {
        return instrument(method = "openSession", params = emptyMap()) {
            val scope = CapturedSessionScope().apply(configure)
            val sessionId = createSessionInternal {
                scope.systemPrompt?.let { systemPrompt(it) }
                scope.maxTokens?.let { maxTokens(it) }
                scope.toolsJson?.let { toolsJsonRaw(it) }
                scope.extraContextJson?.let { extraContext(it) }
            }
            BridgeSession(SessionHandle(this, sessionId))
        }
    }

    /**
     * Behavioural body for [infer] / [MindlayerSession.infer]. Routes the
     * canonical request onto the real streaming inference path.
     *
     * - Named session: streams token-by-token via [runStreamingInference].
     * - Ephemeral session: creates a session (with tools if OutputMode.Tools),
     *   streams via [runStreamingInference], destroys the session on completion.
     * - OutputMode.Tools: supported — ToolCall events surface to the caller.
     *   The auto tool-handler loop is not wired (only ToolCall event emission).
     */
    private suspend fun runInferRequest(
        request: InferenceRequest.Builder,
        overrideSessionId: String?,
    ): InferenceHandle {
        val prompt = request.promptText.orEmpty()
        val sessionId = overrideSessionId ?: request.sessionId

        if (sessionId != null) {
            // Named session: stream directly, caller owns session lifecycle
            return runStreamingInference(
                sessionId = sessionId,
                text = prompt,
                imageInputs = request.imageInputs,
                audioFile = request.audioFile,
                mediaParts = request.mediaParts,
            )
        }

        // Ephemeral session: create → stream → destroy on completion
        val configure = sessionConfigureFrom(request)
        val ephemeralId = createSessionInternal(configure)
        val requestId = "infer-${UUID.randomUUID()}"

        val innerHandle = runStreamingInference(
            sessionId = ephemeralId,
            text = prompt,
            imageInputs = request.imageInputs,
            audioFile = request.audioFile,
            mediaParts = request.mediaParts,
            requestId = requestId,
        )

        // Wrap the event flow with ephemeral session cleanup on terminal/error/cancel
        val cleanupFlow = innerHandle.events.onCompletion {
            runCatching { destroySession(ephemeralId) }
        }

        return buildHandle(requestId, cleanupFlow)
    }

    /**
     * Translate the [SessionScope] / [SamplerScope] captured by an inference
     * request into a [SessionConfigBuilder] configure block for the ephemeral
     * one-shot session. [SamplerScope.seed] is dropped (no builder field).
     */
    private fun sessionConfigureFrom(request: InferenceRequest.Builder): SessionConfigBuilder.() -> Unit {
        val session = CapturedSessionScope().apply { request.sessionConfigure?.invoke(this) }
        val sampler = CapturedSamplerScope().apply { request.samplerConfigure?.invoke(this) }
        val toolsJsonFromOutput = when (val mode = request.outputMode) {
            is InferenceRequest.OutputMode.Tools -> toolSpecListToJson(mode.tools)
            else -> null
        }
        return {
            session.systemPrompt?.let { systemPrompt(it) }
            session.maxTokens?.let { maxTokens(it) }
            sampler.topK?.let { topK(it) }
            sampler.topP?.let { topP(it) }
            sampler.temperature?.let { temperature(it) }
            // Tools from SessionScope take priority; fall back to OutputMode.Tools
            val effectiveToolsJson = session.toolsJson ?: toolsJsonFromOutput
            effectiveToolsJson?.let { toolsJsonRaw(it) }
            session.extraContextJson?.let { extraContext(it) }
        }
    }

    /**
     * Behavioural body for [ocr]. Converts the request image to encoded bytes,
     * runs the shared one-shot OCR helper, and maps the
     * [com.adsamcik.mindlayer.OcrImageResult] onto the canonical [OcrResult].
     */
    private suspend fun runOcrRequest(request: OcrRequest.Builder): OcrHandle.OneShot {
        val image = request.image ?: throw MindlayerException(
            message = "Mindlayer v1 — ocr{} requires an image(...) input.",
            code = MindlayerErrorCode.INVALID_REQUEST,
        )
        val (bytes, mimeType) = imageInputToBytes(image)
        val (width, height) = decodeImageSize(bytes)
        val options = com.adsamcik.mindlayer.OcrImageOptions(
            emitBoundingBoxes = request.emitBoundingBoxes,
            languageHints = request.languageHints,
            runLlmExtraction = request.extractionSchema != null,
            extractionSchemaJson = request.extractionSchema?.json?.toString(),
        )
        val raw = ocrImageInternal(bytes, mimeType, options)
        return OneShotOcrHandle(mapOcrImageResult(raw, width, height))
    }

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun imageInputToBytes(image: ImageInput): Pair<ByteArray, String> = when (image) {
        is ImageInput.Bytes -> image.bytes to image.mimeType
        is ImageInput.Bitmap -> bitmapToPng(image.bitmap) to "image/png"
        is ImageInput.File -> image.file.readBytes() to guessImageMime(image.file.name)
        is ImageInput.Uri -> {
            val resolver = image.context.contentResolver
            val data = resolver.openInputStream(image.uri)?.use { it.readBytes() }
                ?: throw MindlayerException(
                    message = "Mindlayer v1 — ocr{} could not open image Uri.",
                    code = MindlayerErrorCode.INVALID_REQUEST,
                )
            data to (resolver.getType(image.uri) ?: "image/*")
        }
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    private fun guessImageMime(name: String): String = when {
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }

    private fun decodeImageSize(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    private fun mapOcrImageResult(
        raw: com.adsamcik.mindlayer.OcrImageResult,
        width: Int,
        height: Int,
    ): OcrResult {
        val lines = raw.lines.map { line ->
            OcrLine(
                text = line.text,
                boundingBox = denormalizeQuad(line.boundingBox, width, height),
                confidence = confidenceToFloat(line.confidence),
                boundingBoxQuad = line.boundingBox?.toList(),
                orientationDegrees = line.orientationDegrees,
            )
        }
        val extractionJson = raw.extractionJson?.let { json ->
            runCatching { lenientJson.parseToJsonElement(json) as JsonObject }.getOrNull()
        }
        val fullJson = extractionJson ?: buildJsonObject {
            put("lines", buildJsonArray { lines.forEach { add(JsonPrimitive(it.text)) } })
        }
        val metrics = Metrics(
            totalDurationMs = raw.totalDurationMs.takeIf { it > 0L },
            ocrDurationMs = raw.ocrDurationMs.takeIf { it > 0L },
            llmDurationMs = raw.llmDurationMs.takeIf { it > 0L },
            backend = raw.backend.takeIf { it != "NONE" },
        )
        val extractionFields = raw.extractionFields.map {
            OcrExtractedField(
                name = it.name,
                value = it.value,
                confidence = confidenceToFloat(it.confidence),
            )
        }
        return OcrResult(
            lines = lines,
            fullJson = fullJson,
            extractionJson = extractionJson,
            metrics = metrics,
            extractionFields = extractionFields,
        )
    }

    /**
     * Collapse a normalised 8-float quad ([x1,y1,…,x4,y4] in 0..1) onto the
     * axis-aligned `[left, top, right, bottom]` pixel rectangle [OcrLine] uses.
     * Returns `null` when no quad was emitted or the image size is unknown.
     */
    private fun denormalizeQuad(quad: FloatArray?, width: Int, height: Int): List<Int>? {
        if (quad == null || quad.size < 8 || width <= 0 || height <= 0) return null
        val xs = listOf(quad[0], quad[2], quad[4], quad[6])
        val ys = listOf(quad[1], quad[3], quad[5], quad[7])
        val left = (xs.min() * width).toInt()
        val top = (ys.min() * height).toInt()
        val right = (xs.max() * width).toInt()
        val bottom = (ys.max() * height).toInt()
        return listOf(left, top, right, bottom)
    }

    private fun confidenceToFloat(confidence: Int): Float? = when (confidence) {
        com.adsamcik.mindlayer.OcrImageLine.CONFIDENCE_HIGH -> 1.0f
        com.adsamcik.mindlayer.OcrImageLine.CONFIDENCE_MEDIUM -> 0.66f
        com.adsamcik.mindlayer.OcrImageLine.CONFIDENCE_LOW -> 0.33f
        else -> null
    }

    /** Mutable [SessionScope] capture used to translate configure blocks. */
    private class CapturedSessionScope : SessionScope {
        override var systemPrompt: String? = null
        override var maxTokens: Int? = null
        override var historyPolicy: HistoryPolicy = HistoryPolicy.METADATA_ONLY
        override var toolsJson: String? = null
        override var extraContextJson: String? = null
    }

    /** Mutable [SamplerScope] capture used to translate sampling blocks. */
    private class CapturedSamplerScope : SamplerScope {
        override var topK: Int? = null
        override var topP: Float? = null
        override var temperature: Float? = null
        override var seed: Int? = null
    }

    /** One-shot [OcrHandle.OneShot] backed by an already-computed [OcrResult]. */
    private class OneShotOcrHandle(private val result: OcrResult) : OcrHandle.OneShot {
        override val events: Flow<OcrEvent> = flow {
            emit(OcrEvent.ResultFinalized(result.fullJson.toString()))
        }

        override suspend fun awaitResult(): OcrResult = result
    }

    /**
     * [MindlayerSession] bridging the canonical session surface onto a legacy
     * [SessionHandle] (create → chat*Once → destroy). [close] is fire-and-forget
     * on [lifecycleScope]; [closeAsync] awaits the destroy round-trip.
     */
    private inner class BridgeSession(private val handle: SessionHandle) : MindlayerSession {
        override val id: String get() = handle.sessionId

        override suspend fun ask(prompt: String): String =
            collectStreamingInference(handle.sessionId, prompt)

        override suspend fun describe(prompt: String, image: Bitmap): String =
            collectStreamingInference(handle.sessionId, prompt, imageInputs = listOf(ImageInput.Bitmap(image)))

        override suspend fun infer(build: InferenceRequest.Builder.() -> Unit): InferenceHandle =
            runInferRequest(InferenceRequest.Builder().apply(build), overrideSessionId = handle.sessionId)

        override fun close() {
            lifecycleScope.launch { runCatching { handle.delete() } }
        }

        override suspend fun closeAsync() {
            handle.delete()
        }
    }

    override suspend fun ocrSession(build: OcrSessionRequest.Builder.() -> Unit): OcrHandle.MultiFrame {
        val request = OcrSessionRequest.Builder().apply(build)
        return instrument(
            method = "ocrSession",
            params = mapOf(
                "maxFrames" to (request.maxFrames?.toString() ?: ""),
                "extract" to CallParams.has(request.extractionSchema),
            ),
        ) {
            val config = OcrSessionConfigBuilder(request.profile).apply {
                (request.extractionSchema ?: request.schema)?.let {
                    outputSchemaJson = it.json.toString()
                }
                languageHints = request.languageHints
                request.maxFrames?.let { maxFrames = it }
                request.frameRateLimit?.let { frameRateLimitFps = it }
            }.build()
            val session = openOcrSessionInternal(config)
            BridgeOcrSession(session, extractionRequested = request.extractionSchema != null)
        }
    }

    /**
     * [OcrHandle.MultiFrame] bridging the canonical streaming OCR surface onto
     * the working multi-frame [OcrSession] plumbing (`createOcrSession` →
     * push / finalize / stream over AIDL). [events] delegates straight to the
     * live session stream (same [OcrEvent] type — no remapping); [finalize]
     * attaches a dedicated event pipe, drains the session, and maps the
     * terminal [OcrEvent.ResultFinalized] into a typed [OcrResult] reusing the
     * same lenient JSON mapping the one-shot path uses.
     */
    private inner class BridgeOcrSession(
        private val session: OcrSession,
        private val extractionRequested: Boolean,
    ) : OcrHandle.MultiFrame {
        override val sessionId: String get() = session.sessionId

        override val events: Flow<OcrEvent> get() = session.events

        override suspend fun pushFrame(
            meta: com.adsamcik.mindlayer.OcrFrameMeta,
            image: ImageInput,
        ): com.adsamcik.mindlayer.OcrFrameAck {
            val (bytes, mimeType) = imageInputToBytes(image)
            return session.pushEncodedFrame(meta, bytes, mimeType)
        }

        override suspend fun pushFrame(
            meta: com.adsamcik.mindlayer.OcrFrameMeta,
            yPlane: ByteArray,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
        ): com.adsamcik.mindlayer.OcrFrameAck {
            return session.pushFrame(meta, yPlane, width, height, rowStride, pixelStride)
        }

        override suspend fun state(): com.adsamcik.mindlayer.OcrSessionState {
            return session.state()
        }

        override suspend fun finalize(): OcrResult {
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            try {
                attachOcrEventStream(session.sessionId, writeEnd)
            } catch (t: Throwable) {
                readEnd.closeQuietly()
                writeEnd.closeQuietly()
                throw t
            }
            // The service has dup'd the write-end via the binder call; drop our
            // local copy so the reader sees EOF once the service-side writer
            // closes its dup at session end.
            writeEnd.closeQuietly()
            var finalJson: String? = null
            try {
                session.finalize()
                OcrTokenStreamReader.readStream(readEnd).collect { event ->
                    if (event is OcrEvent.ResultFinalized) finalJson = event.fullJson
                }
            } catch (t: Throwable) {
                readEnd.closeQuietly()
                throw t
            }
            return buildOcrResultFromFinalized(finalJson, extractionRequested)
        }

        override suspend fun closeAsync() {
            withContext(Dispatchers.IO) { session.close() }
        }

        override fun close() {
            session.close()
        }
    }

    /**
     * Map a terminal OCR-session [OcrEvent.ResultFinalized] JSON payload into a
     * typed [OcrResult]. Mirrors [mapOcrImageResult]'s lenient parsing:
     * best-effort `lines[]` extraction, a parsed [JsonObject] for
     * [OcrResult.fullJson] (falling back to `{"lines":[]}`), and
     * [OcrResult.extractionJson] only when extraction was requested. Session
     * event streams carry no timing, so metrics are [Metrics.EMPTY].
     */
    private fun buildOcrResultFromFinalized(
        finalJson: String?,
        extractionRequested: Boolean,
    ): OcrResult {
        val parsed = finalJson?.let {
            runCatching { lenientJson.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        val lines = (parsed?.get("lines") as? JsonArray)?.mapNotNull { el ->
            (el as? JsonPrimitive)?.let { OcrLine(text = it.content) }
        } ?: emptyList()
        val fullJson = parsed ?: buildJsonObject {
            put("lines", buildJsonArray { lines.forEach { add(JsonPrimitive(it.text)) } })
        }
        val extractionJson = if (extractionRequested) parsed else null
        return OcrResult(
            lines = lines,
            fullJson = fullJson,
            extractionJson = extractionJson,
            metrics = Metrics.EMPTY,
        )
    }

    override suspend fun embed(build: EmbeddingRequest.Builder.() -> Unit): EmbeddingHandle {
        val request = EmbeddingRequest.Builder().apply(build)
        return instrument(
            method = "embed",
            params = mapOf(
                "single" to CallParams.has(request.singleItem),
                "batch" to (request.items?.size?.toString() ?: ""),
                "deferred" to request.deferred.toString(),
            ),
        ) {
            runEmbedRequest(request)
        }
    }

    /**
     * Behavioural body for [embed]. Bridges the canonical builder onto the
     * single-shot or batch embedding internals:
     *  - `text(...)` → [embedConfigInternal] (single inline)
     *  - `items(...)` → [embedManyConfigsInternal] (batch; picks the cheapest
     *    viable transport — inline, SharedMemory, or deferred — automatically).
     *
     * C3 deviations (documented in `docs/SDK_V1_MIGRATION.md`):
     *  - `deferred()` is accepted but currently materialises to an inline batch.
     *    True deferred wiring will land alongside the canonical builder for
     *    [EmbeddingHandle.Deferred].
     *  - `deadline(...)` is recorded for telemetry but not enforced at this
     *    layer; the AIDL call inherits the service-side default budget.
     */
    private suspend fun runEmbedRequest(request: EmbeddingRequest.Builder): EmbeddingHandle {
        val single = request.singleItem
        val items = request.items
        return when {
            single != null -> {
                val config = EmbeddingConfig(
                    text = single.text,
                    task = single.task,
                    tag = single.tag,
                    modelId = single.modelId,
                    outputDim = single.outputDim,
                    normalize = single.normalize,
                )
                val result = embedConfigInternal(config)
                CompletedEmbeddingSingle(
                    vector = result.vector,
                    resultItem = EmbeddingResultItem(
                        tag = result.tag,
                        vector = EmbeddingVector(result.vector),
                        dim = result.dim,
                        modelId = result.modelId,
                        tokenCount = result.tokenCount,
                        truncated = result.truncated,
                        backend = result.backend,
                        durationMs = result.durationMs,
                    ),
                )
            }
            items != null -> {
                val configs = items.map { item ->
                    EmbeddingConfig(
                        text = item.text,
                        task = item.task,
                        tag = item.tag,
                        modelId = item.modelId,
                        outputDim = item.outputDim,
                        normalize = item.normalize,
                    )
                }
                val resultItems = embedManyConfigsInternal(configs).results.map { result ->
                    EmbeddingResultItem(
                        tag = result.tag,
                        vector = EmbeddingVector(result.vector),
                        dim = result.dim,
                        modelId = result.modelId,
                        tokenCount = result.tokenCount,
                        truncated = result.truncated,
                        backend = result.backend,
                        durationMs = result.durationMs,
                    )
                }
                CompletedEmbeddingBatch(resultItems)
            }
            else -> throw MindlayerException(
                message = "Mindlayer v1 — embed{} requires text(...) or items(...).",
                code = MindlayerErrorCode.INVALID_REQUEST,
            )
        }
    }

    /** Cold [EmbeddingHandle.Single] backed by an already-computed vector. */
    private class CompletedEmbeddingSingle(
        private val vector: FloatArray,
        private val resultItem: EmbeddingResultItem,
    ) : EmbeddingHandle.Single {
        override val events: Flow<EmbeddingEvent> = flow {
            emit(EmbeddingEvent.Completed(items = listOf(resultItem)))
        }

        override suspend fun awaitVector(): FloatArray = vector
    }

    /** Cold [EmbeddingHandle.Batch] backed by an already-computed result list. */
    private class CompletedEmbeddingBatch(private val items: List<EmbeddingResultItem>) : EmbeddingHandle.Batch {
        override val events: Flow<EmbeddingEvent> = flow { emit(EmbeddingEvent.Completed(items)) }

        override suspend fun awaitVectors(): List<EmbeddingResultItem> = items
    }

    /** Observable connection state. */
    override val connectionState: StateFlow<ConnectionState>
        get() = connection.state

    internal var createSessionInitRetryTimeoutMs: Long =
        DEFAULT_CREATE_SESSION_INIT_RETRY_TIMEOUT_MS

    internal var createSessionInitRetryBackoffMs: List<Long> =
        DEFAULT_CREATE_SESSION_INIT_RETRY_BACKOFF_MS

    internal var createSessionInitRetryClockMs: () -> Long = { System.currentTimeMillis() }

    override suspend fun awaitConnected(timeout: kotlin.time.Duration): Capabilities {
        require(timeout > kotlin.time.Duration.ZERO) {
            "awaitConnected timeout must be positive (use Duration.INFINITE to wait indefinitely)"
        }
        // getCapabilities() awaits the binder before reading the feature set, so
        // a single call both waits for the connection and negotiates capabilities.
        // INFINITE means "no deadline"; any finite bound wraps the whole wait.
        val caps = if (timeout == kotlin.time.Duration.INFINITE) {
            getCapabilities()
        } else {
            withTimeout(timeout) { getCapabilities() }
        }
        return Capabilities.from(caps)
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

    @Volatile private var cachedCapabilitiesAtMs: Long = 0L

    /**
     * Probe and cache the [ServiceCapabilities] of the connected service.
     *
     * The first call after [awaitConnected] performs the AIDL handshake and
     * caches the result for [CAPABILITIES_CACHE_TTL_MS] ms. Within that
     * window subsequent calls return the cached value without crossing the
     * wire; after it expires the next call re-probes. Cache expiry matters
     * because capabilities are dynamic — the on-device engine warms up
     * asynchronously after first connect (PaddleOCR / Gemma model load),
     * unloads on memory pressure, and reloads lazily on the next call.
     * The pre-fix lifetime-of-instance cache silently pinned the very
     * first reply (often `FEATURE_OCR_* = false` during the ~200ms init
     * window) forever and meant capability-aware clients never saw
     * features come online (Bug #6).
     *
     * Pass `forceRefresh = true` to bypass the cache (e.g. inside a retry
     * loop polling for a specific feature to appear). The forced re-probe
     * still passes through the service's quarter-cost rate-limit gate, so
     * tight polling loops should add a small `delay` between iterations.
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
    override suspend fun getCapabilities(forceRefresh: Boolean): ServiceCapabilities {
        if (!forceRefresh) {
            val cached = cachedCapabilities
            if (cached != null) {
                val ageMs = System.currentTimeMillis() - cachedCapabilitiesAtMs
                if (ageMs in 0..CAPABILITIES_CACHE_TTL_MS) {
                    return cached
                }
            }
        }
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
        cachedCapabilitiesAtMs = System.currentTimeMillis()
        return caps
    }

    /** Stable feature identifiers advertised by the currently connected service. */
    suspend fun connectedFeatures(): Set<String> = getCapabilities().supportedFeatures

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
     * Active inference flows will complete with [InferenceEvent.Error] (code: "DISCONNECTED").
     * Blocking one-shot methods ([chatOnce], [generate]) will throw [MindlayerException].
     * Sessions are preserved on the service side and can be resumed after [connect].
     *
     * Safe to call from any thread. Idempotent — multiple calls are harmless.
     */
    override fun disconnect() {
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
     *
     * ### Service-process death
     *
     * If the `:ml` process dies mid-transaction the binder throws
     * [android.os.DeadObjectException] (a subclass of
     * [android.os.RemoteException]). The async death signals
     * (`onServiceDisconnected` / `onBindingDied` / `linkToDeath`) may not have
     * fired yet, so this chokepoint eagerly invalidates the stale binder via
     * [ConnectionManager.reportBinderDeath] — that guarantees the next
     * [ConnectionManager.awaitConnected] blocks for a freshly re-delivered
     * binder instead of handing back the dead one. It then either:
     *
     *  - **retries once** when [retryOnServiceDeath] is `true` — reserved for
     *    *idempotent* operations (embeddings, status / health reads) where a
     *    repeat after the `BIND_AUTO_CREATE` restart is side-effect-free; or
     *  - **throws** a typed [MindlayerException] with
     *    [MindlayerErrorCode.SERVICE_UNAVAILABLE] for non-idempotent calls, so
     *    a partially-applied side effect is never silently duplicated.
     *
     * Any other [android.os.RemoteException] (non-fatal transport failure) maps
     * to the same typed error without retry. A raw
     * [android.os.DeadObjectException] therefore never escapes to callers.
     *
     * @param retryOnServiceDeath opt-in transparent retry after process death.
     *   Only pass `true` for operations that are safe to run twice.
     */
    private suspend inline fun <R> withTypedErrors(
        requestId: String? = null,
        sessionId: String? = null,
        retryOnServiceDeath: Boolean = false,
        crossinline block: suspend (com.adsamcik.mindlayer.IMindlayerService) -> R,
    ): R {
        var deathRetries = 0
        while (true) {
            val service = connection.awaitConnected()
            try {
                return block(service)
            } catch (e: SecurityException) {
                throw MindlayerException.fromAidlSecurityException(e, requestId, sessionId) ?: throw e
            } catch (e: android.os.DeadObjectException) {
                // Invalidate the stale binder so the next awaitConnected()
                // waits for a fresh one rather than re-failing instantly.
                connection.reportBinderDeath(service)
                if (retryOnServiceDeath && deathRetries < MAX_SERVICE_DEATH_RETRIES) {
                    deathRetries++
                    continue
                }
                throw serviceDied(e, requestId, sessionId)
            } catch (e: android.os.RemoteException) {
                // Not a definitive process death — surface a typed transport
                // error but never auto-retry (the call may have applied).
                connection.reportBinderDeath(service)
                throw serviceDied(e, requestId, sessionId)
            }
        }
    }

    /**
     * Map a dead / failed binder transaction to the typed
     * [MindlayerErrorCode.SERVICE_UNAVAILABLE] error. [cause] is always a
     * framework transport exception ([android.os.RemoteException] /
     * [android.os.DeadObjectException]) — never an engine-originated
     * `Throwable`, so attaching it as the cause cannot leak prompt or
     * model-output text (see [MindlayerException]'s cause contract).
     */
    private fun serviceDied(
        cause: Throwable,
        requestId: String?,
        sessionId: String?,
    ): MindlayerException = MindlayerException(
        message = "Mindlayer service connection lost (process died or binder transport failure)",
        code = MindlayerErrorCode.SERVICE_UNAVAILABLE,
        requestId = requestId,
        sessionId = sessionId,
        cause = cause,
    )

    /**
     * Non-suspend variant for paths that already hold a connected service
     * reference (e.g. [startInference], where the AIDL call kicks off a cold
     * flow and must run synchronously to produce a request handle).
     *
     * Cannot reconnect (no suspension point), so a dead binder is mapped
     * straight to the typed [MindlayerErrorCode.SERVICE_UNAVAILABLE] without
     * retry; recovery happens on the caller's next suspendable AIDL call.
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
    } catch (e: android.os.DeadObjectException) {
        connection.reportBinderDeath(service)
        throw serviceDied(e, requestId, sessionId)
    } catch (e: android.os.RemoteException) {
        connection.reportBinderDeath(service)
        throw serviceDied(e, requestId, sessionId)
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
    override suspend fun prewarm(backend: InferenceBackend) {
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
            withTypedErrors(retryOnServiceDeath = true) {
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
     * Internal session creation workhorse: local saga + init retry.
     * [openSession] delegates here; accessible to same-module code
     * (e.g. [Conversation]) via `internal`.
     */
    internal suspend fun createSessionInternal(
        configure: SessionConfigBuilder.() -> Unit,
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

        // 3. Confirm local record — Bug #9: the service may have rewritten
        // our tentative id (external callers' ids are stripped for security).
        // confirmConversationWithRename handles both equal-id (fast path) and
        // rename (FOREIGN KEY-safe atomic rekey) so subsequent
        // persistUserTurn calls always find a parent conversation row.
        historyStore?.confirmConversationWithRename(tentativeId, sessionId)

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
    override suspend fun destroySession(sessionId: String) {
        withTypedErrors(sessionId = sessionId) { it.destroySession(sessionId) }
    }

    /** Get info for a single session. */
    suspend fun getSessionInfo(sessionId: String): SessionInfo {
        return withTypedErrors(sessionId = sessionId, retryOnServiceDeath = true) { it.getSessionInfo(sessionId) }
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
    /** List all live server-side sessions owned by this caller. */
    override suspend fun listSessions(): List<SessionInfo> {
        return withTypedErrors(retryOnServiceDeath = true) { it.listSessions() }
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

    /**
     * Awaits a deferred (async) inference result, preferring the push notice
     * over busy-polling.
     *
     * @param timeoutMs upper bound on how long the SDK waits for the deferred
     *   result. Defaults to 5 minutes to match the service-side per-inference
     *   wall-clock cap (`InferenceOrchestrator.MAX_INFERENCE_MS`); a shorter
     *   default would abandon long-but-valid generations that the service is
     *   still actively producing. Pass a smaller value for latency-sensitive
     *   callers.
     */
    suspend fun awaitDeferred(
        requestId: String,
        pollIntervalMs: Long = 250,
        timeoutMs: Long = 5L * 60L * 1000L,
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

    private suspend fun requireOcrCapability(): ServiceCapabilities {
        val caps = getCapabilities()
        if (ServiceCapabilities.FEATURE_OCR_SESSION !in connectedFeatures()) {
            throw MindlayerException(
                message = "Connected Mindlayer service does not support OCR sessions",
                code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.FEATURE_NOT_SUPPORTED,
            )
        }
        return caps
    }

    private fun ocrNotSupported(sessionId: String? = null): MindlayerException = MindlayerException(
        message = "Connected Mindlayer service does not implement OCR sessions",
        code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.FEATURE_NOT_SUPPORTED,
        sessionId = sessionId,
    )

    private fun embeddingNotSupported(requestId: String? = null): MindlayerException = MindlayerException(
        message = "Connected Mindlayer service does not implement embeddings",
        code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED,
        requestId = requestId,
    )

    /**
     * Compute embeddings for [items] in one trip. The SDK picks transport
     * (inline binder, SharedMemory, or a deferred-batch fallback) based on
     * batch size, payload estimate, API level, and service-advertised caps
     * — consumers do not choose. The transport that was used is reported
     * on [EmbeddingBatch.transport].
     *
     * For durable batches that must survive client-process death, use
     * [embedBatchDeferred] (push notification via [embeddingBatchCompletions]
     * + manual fetch / ack).
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old
     * service binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     *
     * @throws IllegalArgumentException if [items] is empty, or if the batch
     *   size exceeds the active cap for the chosen transport.
     * @throws MindlayerException on capability mismatch, rate-limit, or any
     *   of the embedding-specific service errors (see the class-level KDoc
     *   error/capability table).
     */
    private suspend fun embedManyConfigsInternal(items: List<EmbeddingConfig>): EmbeddingBatch {
        require(items.isNotEmpty()) { "Embedding batch must be non-empty" }
        val caps = requireEmbeddingCapability()
        return when (selectEmbeddingTransport(caps, items)) {
            EmbeddingTransport.Inline -> embedManyInline(caps, items)
            EmbeddingTransport.SharedMemory -> embedManyShm(caps, items)
            EmbeddingTransport.DeferredFallback -> embedManyDeferredFallback(items)
        }
    }

    /**
     * Transport-selection rule for [embedManyConfigsInternal]. Internal;
     * exposed for tests so the inline / SHM / deferred routing can be pinned
     * without touching a binder.
     */
    internal fun selectEmbeddingTransport(
        caps: ServiceCapabilities,
        items: List<EmbeddingConfig>,
    ): EmbeddingTransport {
        // SharedMemory requires API 27 (android.os.SharedMemory) AND service
        // advertising a non-zero SHM cap. The service zeros maxEmbeddingBatchShm
        // on API 26 already; we re-check Build.VERSION here as a defense-in-depth
        // gate so a misadvertised cap never tries to allocate SharedMemory.
        val shmSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            && caps.maxEmbeddingBatchShm > 0
        val needsLargeTransport = items.size > caps.maxEmbeddingBatchInline
            || estimatedReplyBytes(caps, items) > INLINE_REPLY_BYTE_BUDGET
        return when {
            !needsLargeTransport -> EmbeddingTransport.Inline
            shmSupported && items.size <= caps.maxEmbeddingBatchShm -> EmbeddingTransport.SharedMemory
            else -> EmbeddingTransport.DeferredFallback
        }
    }

    private fun estimatedReplyBytes(caps: ServiceCapabilities, items: List<EmbeddingConfig>): Long {
        // Upper bound: each item contributes (dim * 4 bytes) + parcel overhead.
        // Use the largest advertised output dim because per-item outputDim is
        // optional. 256-byte overhead covers tag/modelId/tokenCount/backend
        // string headers — generous, but inline transport is cheap to
        // upgrade to SHM so over-budgeting is safe.
        val maxDim = caps.embeddingDims.maxOrNull() ?: DEFAULT_EMBEDDING_DIM
        return items.size.toLong() * (maxDim.toLong() * 4L + PARCEL_OVERHEAD_BYTES_PER_ITEM)
    }

    private suspend fun embedManyInline(
        caps: ServiceCapabilities,
        items: List<EmbeddingConfig>,
    ): EmbeddingBatch {
        validateEmbeddingBatchSize(items, caps.maxEmbeddingBatchInline, "inline")
        return try {
            withContext(Dispatchers.IO) {
                val res = withTypedErrors(retryOnServiceDeath = true) { it.embedBatch(items.map { config -> config.toAidlRequest() }) }
                EmbeddingBatch(
                    results = res.results,
                    transport = EmbeddingTransport.Inline,
                    totalDurationMs = res.totalDurationMs,
                    backend = res.backend,
                )
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported()
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported()
        }
    }

    private suspend fun embedManyShm(
        caps: ServiceCapabilities,
        items: List<EmbeddingConfig>,
    ): EmbeddingBatch {
        validateEmbeddingBatchSize(items, caps.maxEmbeddingBatchShm, "shared-memory")
        return try {
            withContext(Dispatchers.IO) {
                val transfer = withTypedErrors(retryOnServiceDeath = true) { it.embedBatchShm(items.map { config -> config.toAidlRequest() }) }
                // Read the batch-level fields *before* parseEmbeddingTransfer
                // drains and closes the PFD — the parcelable fields themselves
                // survive close, but reading them eagerly keeps the surface
                // symmetric with the inline path.
                val totalDurationMs = transfer.totalDurationMs
                val backend = transfer.backend
                val results = parseEmbeddingTransfer(transfer)
                EmbeddingBatch(
                    results = results,
                    transport = EmbeddingTransport.SharedMemory,
                    totalDurationMs = totalDurationMs,
                    backend = backend,
                )
            }
        } catch (_: NoSuchMethodError) {
            throw embeddingNotSupported()
        } catch (_: AbstractMethodError) {
            throw embeddingNotSupported()
        }
    }

    private suspend fun embedManyDeferredFallback(items: List<EmbeddingConfig>): EmbeddingBatch {
        // Single source of truth for the SHM-unavailable path: route through
        // inline `embedBatch` when items fit inline, otherwise submit a
        // deferred batch and await its completion. Batch-level metadata is
        // best-effort here — durationMs is tracked per-item; backend is read
        // from the first item.
        val caps = requireEmbeddingCapability()
        val results = if (items.size <= caps.maxEmbeddingBatchInline) {
            try {
                withContext(Dispatchers.IO) {
                    withTypedErrors(retryOnServiceDeath = true) {
                        it.embedBatch(items.map { config -> config.toAidlRequest() }).results
                    }
                }
            } catch (_: NoSuchMethodError) {
                throw embeddingNotSupported()
            } catch (_: AbstractMethodError) {
                throw embeddingNotSupported()
            }
        } else {
            validateEmbeddingBatchSize(items, caps.maxEmbeddingBatchTotal, "deferred")
            awaitEmbeddingBatch(embedBatchDeferred(items))
        }
        return EmbeddingBatch(
            results = results,
            transport = EmbeddingTransport.DeferredFallback,
            totalDurationMs = 0L,
            backend = results.firstOrNull()?.backend ?: UNKNOWN_BACKEND,
        )
    }

    /**
     * Single-shot typed embedding. Internal — exposed via the canonical
     * `embed { text(...) }` entry point. Capability-gated by
     * [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service binaries throw
     * [MindlayerException] with `NOT_SUPPORTED`.
     */
    private suspend fun embedConfigInternal(config: EmbeddingConfig): com.adsamcik.mindlayer.EmbeddingResult {
        requireEmbeddingCapability()
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors(retryOnServiceDeath = true) { it.embed(config.toAidlRequest()) }
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

    private suspend fun awaitEmbeddingBatch(
        handle: EmbeddingBatchHandle,
        pollIntervalMs: Long = 250L,
    ): List<com.adsamcik.mindlayer.EmbeddingResult> = coroutineScope {
        val pushSignal = async {
            embeddingBatchCompletions()
                .filter { it == handle.requestId }
                .first()
        }
        try {
            val pollJob = async {
                while (isActive) {
                    delay(pollIntervalMs)
                    val outcome = fetchEmbeddingBatch(handle)
                    if (outcome !is EmbeddingBatchOutcome.StillRunning) {
                        return@async outcome
                    }
                }
                EmbeddingBatchOutcome.StillRunning
            }
            val selectedOutcome = select<EmbeddingBatchOutcome?> {
                pushSignal.onAwait { null }
                pollJob.onAwait { it }
            }
            pollJob.cancel()
            when (val outcome = selectedOutcome ?: fetchEmbeddingBatch(handle)) {
                is EmbeddingBatchOutcome.Ready -> outcome.results
                is EmbeddingBatchOutcome.Failed -> throw MindlayerException(
                    message = "Deferred embedding batch failed",
                    code = outcome.errorCode,
                    codeName = outcome.errorName,
                    requestId = handle.requestId,
                )
                is EmbeddingBatchOutcome.Cancelled -> throw MindlayerException(
                    message = "Deferred embedding batch was cancelled",
                    codeName = "CANCELLED",
                    requestId = handle.requestId,
                )
                is EmbeddingBatchOutcome.Expired -> throw MindlayerException(
                    message = "Deferred embedding batch expired",
                    code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.DEFERRED_EXPIRED,
                    requestId = handle.requestId,
                )
                is EmbeddingBatchOutcome.NotFound -> throw MindlayerException(
                    message = "Deferred embedding batch not found or not owned",
                    code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                    requestId = handle.requestId,
                )
                is EmbeddingBatchOutcome.StillRunning -> throw MindlayerException(
                    message = "Deferred embedding batch did not reach a terminal result",
                    code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.PROTOCOL_VIOLATION,
                    requestId = handle.requestId,
                )
            }
        } finally {
            pushSignal.cancel()
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
     * Use the [InferenceEvent.ToolCall.callId] from the tool-call event being answered.
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
    override suspend fun getStatus(): ServiceStatus {
        return withTypedErrors(retryOnServiceDeath = true) { it.status }
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
    override suspend fun ping(): com.adsamcik.mindlayer.HealthCheck {
        return try {
            withTypedErrors(retryOnServiceDeath = true) { it.ping() }
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
    override suspend fun getEngineInfo(): EngineInfo {
        return withTypedErrors(retryOnServiceDeath = true) { it.engineInfo }
    }

    /** Get a diagnostic JSON dump for bug reports and troubleshooting. */
    suspend fun getDiagnostics(): String {
        return withTypedErrors(retryOnServiceDeath = true) { it.diagnostics }
    }

    /**
     * v0.4 typed diagnostics snapshot. Returns a small struct
     * ([com.adsamcik.mindlayer.DiagnosticsSnapshot]) suitable for
     * dashboard polling and external monitoring. Capability-gated via
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS];
     * returns `null` when talking to a pre-v0.4 service. Callers wanting
     * the human-readable JSON dump should use [getDiagnostics].
     */
    override suspend fun getDiagnosticsTyped(): com.adsamcik.mindlayer.DiagnosticsSnapshot? {
        val caps = getCapabilities()
        if (!caps.supports(com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS)) {
            return null
        }
        return try {
            withTypedErrors(retryOnServiceDeath = true) { it.diagnosticsTyped }
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
    fun session(sessionId: String): SessionHandle =
        SessionHandle(this, sessionId)

    // -- One-shot convenience (removed — use collectStreamingInference) --------

    /**
     * Internal helper: collect an [InferenceHandle] to a complete response
     * string. Used by [chatOnce], [chatWithImageOnce], [chatWithAudioOnce],
     * [inferAsync], and (transitively) [generate] / [generateWithImage] /
     * [generateWithAudio].
     *
     * Replaces several near-identical inline implementations with one source
     * of truth so the error mapping (Error → typed MindlayerException;
     * ToolCall → `UNSUPPORTED_TOOL_CALL`) cannot drift between call sites.
     */
    internal suspend fun collectHandleToString(
        handle: InferenceHandle,
        sessionId: String,
    ): String {
        var result: String? = null
        val accumulator = StringBuilder()
        handle.events.collect { event ->
            when (event) {
                is InferenceEvent.TextDelta -> accumulator.append(event.text)
                is InferenceEvent.Done -> {
                    result = event.fullText ?: accumulator.toString()
                }
                is InferenceEvent.Error -> throw MindlayerException.fromStreamError(
                    message = event.message,
                    codeName = event.code,
                    codeInt = event.codeInt,
                    seq = event.seq,
                    tsMs = event.tsMs,
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                is InferenceEvent.ToolCall -> throw MindlayerException(
                    message = TOOL_CALL_IN_ONESHOT_MSG,
                    codeName = "UNSUPPORTED_TOOL_CALL",
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                else -> { /* Started, Metrics — ignored */ }
            }
        }
        return result ?: throw MindlayerException(
            message = "Inference stream ended without a Done event",
            code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.PROTOCOL_VIOLATION,
            requestId = handle.requestId,
            sessionId = sessionId,
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
                is InferenceEvent.TextDelta -> emit(event.text)
                is InferenceEvent.Done -> return@collect
                is InferenceEvent.Error -> throw MindlayerException.fromStreamError(
                    message = event.message,
                    codeName = event.code,
                    codeInt = event.codeInt,
                    seq = event.seq,
                    tsMs = event.tsMs,
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                is InferenceEvent.ToolCall -> throw MindlayerException(
                    message = TOOL_CALL_IN_ONESHOT_MSG,
                    codeName = "UNSUPPORTED_TOOL_CALL",
                    requestId = handle.requestId,
                    sessionId = sessionId,
                )
                else -> { /* Started, Metrics — silently dropped */ }
            }
        }
    }

    // -- Internals ------------------------------------------------------------

    /**
     * Convert a [List] of [ToolSpec] to the JSON array wire format expected by
     * [SessionConfigBuilder.tools]. Produces the same shape as [ToolsBuilder.build].
     */
    private fun toolSpecListToJson(specs: List<ToolSpec>): String {
        val array = buildJsonArray {
            for (spec in specs) {
                add(buildJsonObject {
                    put("name", JsonPrimitive(spec.name))
                    put("description", JsonPrimitive(spec.description))
                    put("parameters", spec.parametersSchema.json)
                })
            }
        }
        return Json.encodeToString(JsonArray.serializer(), array)
    }

    /**
     * Consolidated streaming dispatch: builds the tracked inference flow with
     * full history persistence, multi-media support, and requestId re-tagging.
     *
     * This is the single internal streaming path that all legacy and canonical
     * entry points route through for named-session inference.
     */
    private suspend fun runStreamingInference(
        sessionId: String,
        text: String,
        imageInputs: List<ImageInput> = emptyList(),
        audioFile: File? = null,
        mediaParts: List<com.adsamcik.mindlayer.MediaPart> = emptyList(),
        requestId: String = UUID.randomUUID().toString(),
    ): InferenceHandle {
        val meta = RequestMeta(
            requestId = requestId,
            sessionId = sessionId,
            textContent = text,
        )

        // Determine if we need the multi-media path
        val bitmap = imageInputs.firstNotNullOfOrNull { (it as? ImageInput.Bitmap)?.bitmap }
        val imageFile = imageInputs.firstNotNullOfOrNull { (it as? ImageInput.File)?.file }
        val hasExplicitMedia = mediaParts.isNotEmpty()
        val hasMultipleInputs = (imageInputs.size + (if (audioFile != null) 1 else 0) + mediaParts.size) > 1

        val flow: Flow<InferenceEvent> = if (hasExplicitMedia || hasMultipleInputs || imageFile != null) {
            // Multi-media path: assemble all inputs as MediaParts
            val allMedia = buildList {
                for (input in imageInputs) {
                    when (input) {
                        is ImageInput.Bitmap -> add(MediaTransfer.imagePart(input.bitmap))
                        is ImageInput.File -> add(MediaTransfer.imagePart(input.file))
                        is ImageInput.Uri -> {
                            // Read URI to bytes, decode to Bitmap for MediaTransfer
                            val bytes = input.context.contentResolver
                                .openInputStream(input.uri)?.use { it.readBytes() }
                                ?: continue
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: continue
                            add(MediaTransfer.imagePart(bmp))
                        }
                        is ImageInput.Bytes -> {
                            val bmp = BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size)
                                ?: continue
                            add(MediaTransfer.imagePart(bmp))
                        }
                    }
                }
                if (audioFile != null) {
                    add(MediaTransfer.audioPart(audioFile))
                }
                addAll(mediaParts)
            }
            // Re-tag every part with this requestId (same as chatWithMedia)
            val rebound = allMedia.map { it.copy(requestId = requestId) }
            startTrackedInferenceMulti(
                sessionId = sessionId,
                userText = text,
                meta = meta,
                media = rebound,
            )
        } else {
            // Simple path: at most one bitmap + one audio via legacy providers
            startTrackedInference(
                sessionId = sessionId,
                userText = text,
                meta = meta,
                imageProvider = {
                    bitmap?.let { MediaTransfer.fromBitmap(requestId, it) }
                },
                audioProvider = {
                    audioFile?.let { MediaTransfer.fromAudioFile(requestId, it) }
                },
            )
        }

        return buildHandle(requestId, flow)
    }

    /**
     * Internal entry point for [SessionHandle] and [BridgeSession] — streaming
     * inference that does not go through the deprecated public methods.
     */
    internal suspend fun streamInference(
        sessionId: String,
        text: String,
        imageInputs: List<ImageInput> = emptyList(),
        audioFile: File? = null,
        mediaParts: List<com.adsamcik.mindlayer.MediaPart> = emptyList(),
    ): InferenceHandle = runStreamingInference(sessionId, text, imageInputs, audioFile, mediaParts)

    /**
     * Internal entry point for [SessionHandle] and [BridgeSession] — collect
     * streaming inference to a single string result.
     */
    internal suspend fun collectStreamingInference(
        sessionId: String,
        text: String,
        imageInputs: List<ImageInput> = emptyList(),
        audioFile: File? = null,
    ): String = collectHandleToString(
        runStreamingInference(sessionId, text, imageInputs, audioFile),
        sessionId,
    )

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
    private fun buildHandle(requestId: String, flow: Flow<InferenceEvent>): InferenceHandle {
        return InferenceHandleImpl(requestId, flow).also { handle ->
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
     * 2. On [InferenceEvent.Started]: mark user turn COMPLETED, begin
     *    assistant turn as STREAMING.
     * 3. On [InferenceEvent.Done]: mark assistant turn COMPLETED with full
     *    text.
     * 4. On error/cancellation: mark assistant turn INTERRUPTED.
     */
    private suspend fun startTrackedInference(
        sessionId: String,
        userText: String,
        meta: RequestMeta,
        imageProvider: suspend () -> com.adsamcik.mindlayer.ImageTransfer?,
        audioProvider: suspend () -> com.adsamcik.mindlayer.AudioTransfer?,
    ): Flow<InferenceEvent> {
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
                            is InferenceEvent.Started -> {
                                assistantTurnId = withContext(Dispatchers.IO) {
                                    historyStoreLocal.markUserTurnCompleted(userTurnId)
                                    historyStoreLocal.beginAssistantTurn(sessionId)
                                }
                            }
                            is InferenceEvent.TextDelta -> {
                                textAccumulator.append(event.text)
                            }
                            is InferenceEvent.Done -> {
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
                            is InferenceEvent.Error -> {
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
    ): Flow<InferenceEvent> {
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
    ): Flow<InferenceEvent> {
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
    ): Flow<InferenceEvent> {
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
                        is InferenceEvent.Started -> {
                            historyStoreLocal.markUserTurnCompleted(userTurnId)
                            assistantTurnId = historyStoreLocal.beginAssistantTurn(sessionId)
                        }
                        is InferenceEvent.TextDelta -> {
                            textAccumulator.append(event.text)
                        }
                        is InferenceEvent.Done -> {
                            val finalText = event.fullText
                                ?: textAccumulator.toString()
                            val aid = assistantTurnId
                            if (aid != null) {
                                historyStoreLocal.markTurnCompleted(aid, finalText)
                            }
                            completed = true
                        }
                        is InferenceEvent.Error -> {
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
    //  Public OCR API — two surfaces, one engine.
    //
    //  • [ocrRealtime] — multi-frame streaming session. Push N frames,
    //    finalize, read fused result. For live camera feeds where
    //    cross-frame fusion meaningfully improves quality (receipts,
    //    ID cards, anything you point at and re-aim).
    //
    //  • [ocrAsync]    — single-image, single-call. Pass JPEG/PNG/WEBP
    //    bytes in, get a result back. For gallery pickers, sharesheet
    //    targets, screenshots, "scan this one image" flows.
    //
    //  Decision rule:
    //    – Have a live camera and the user can re-aim?  → [ocrRealtime]
    //    – Have one final image already captured?       → [ocrAsync]
    //    – Need a turn-key camera UI?                   → use the
    //      ``:sdk-camera-launcher`` module — it wraps either surface
    //      behind a single Activity-result contract so consumers never
    //      touch CameraX or permissions directly.
    //
    //  Both surfaces:
    //    – throw [MindlayerException] with [MindlayerErrorCode] codes
    //      (FEATURE_NOT_SUPPORTED for missing capability, LOW_MEMORY /
    //      SERVICE_UNAVAILABLE / INVALID_REQUEST for engine rejections),
    //    – share the same on-device PaddleOCR + LiteRT engine and per-
    //      engine mutex (concurrent calls queue rather than race),
    //    – are gated by the same production-readiness feature flag —
    //      ``FEATURE_OCR_SESSION`` for realtime, ``FEATURE_OCR_IMAGE_ONESHOT``
    //      for async. They flip together.
    //
    //  Wire-stability note: this section is purely an SDK-side rename;
    //  the underlying AIDL methods (createOcrSession / pushOcrFrame /
    //  streamOcrEvents / finalizeOcrSession / closeOcrSession /
    //  getOcrLimits / ocrImage) are unchanged.
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
        requireOcrCapability()
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors(retryOnServiceDeath = true) { it.ocrLimits }
            }
        } catch (_: NoSuchMethodError) {
            com.adsamcik.mindlayer.OcrLimits.zeroBaseline()
        } catch (_: AbstractMethodError) {
            com.adsamcik.mindlayer.OcrLimits.zeroBaseline()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Private OCR helpers — hold the real AIDL logic.
    //  Canonical methods (ocrSession{}, runOcrRequest) call these.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Open a multi-frame OCR session with a pre-built [OcrSessionConfig].
     * This is the single AIDL call-site for `createOcrSession`.
     */
    private suspend fun openOcrSessionInternal(config: com.adsamcik.mindlayer.OcrSessionConfig): OcrSession {
        requireOcrCapability()
        val sessionId = try {
            withContext(Dispatchers.IO) {
                withTypedErrors { it.createOcrSession(config) }
            }
        } catch (_: NoSuchMethodError) {
            throw ocrNotSupported()
        } catch (_: AbstractMethodError) {
            throw ocrNotSupported()
        }
        return OcrSession(sessionId = sessionId, config = config, mindlayer = this)
    }

    /**
     * Single-image OCR — the single AIDL call-site for `ocrImage`.
     */
    private suspend fun ocrImageInternal(
        bytes: ByteArray,
        mimeType: String,
        options: com.adsamcik.mindlayer.OcrImageOptions,
    ): com.adsamcik.mindlayer.OcrImageResult {
        requireOcrImageCapability()
        val part = MediaTransfer.ocrEncodedImagePart(
            requestId = "ocr-image-${java.util.UUID.randomUUID()}",
            bytes = bytes,
            mimeType = mimeType,
            context = connection.getContext(),
        )
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors { it.ocrImage(part, options) }
            }
        } catch (_: NoSuchMethodError) {
            throw ocrImageNotSupported()
        } catch (_: AbstractMethodError) {
            throw ocrImageNotSupported()
        } finally {
            part.source.closeQuietly()
        }
    }

    private suspend fun requireOcrImageCapability() {
        val caps = getCapabilities()
        if (ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT !in caps.supportedFeatures) {
            throw ocrImageNotSupported()
        }
    }

    private fun ocrImageNotSupported(): MindlayerException = MindlayerException(
        message = "Connected Mindlayer service does not support single-image OCR (ocr { image(...) })",
        code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.FEATURE_NOT_SUPPORTED,
    )

    /** Internal: forward a fully-staged OCR frame to the AIDL stub. */
    internal suspend fun pushOcrFrame(
        sessionId: String,
        mediaPart: com.adsamcik.mindlayer.MediaPart,
        meta: com.adsamcik.mindlayer.OcrFrameMeta,
    ): com.adsamcik.mindlayer.OcrFrameAck {
        requireOcrCapability()
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors(sessionId = sessionId) { it.pushOcrFrame(sessionId, mediaPart, meta) }
            }
        } catch (_: NoSuchMethodError) {
            throw ocrNotSupported(sessionId)
        } catch (_: AbstractMethodError) {
            throw ocrNotSupported(sessionId)
        } finally {
            mediaPart.source.closeQuietly()
        }
    }

    /** Internal: forward state query. */
    internal suspend fun getOcrSessionState(sessionId: String): com.adsamcik.mindlayer.OcrSessionState {
        requireOcrCapability()
        return try {
            withContext(Dispatchers.IO) {
                withTypedErrors(sessionId = sessionId) { it.getOcrSessionState(sessionId) }
            }
        } catch (_: NoSuchMethodError) {
            throw ocrNotSupported(sessionId)
        } catch (_: AbstractMethodError) {
            throw ocrNotSupported(sessionId)
        }
    }

    /** Internal: forward finalize call. */
    internal suspend fun finalizeOcrSession(sessionId: String) {
        requireOcrCapability()
        try {
            withContext(Dispatchers.IO) {
                withTypedErrors(sessionId = sessionId) { it.finalizeOcrSession(sessionId) }
            }
        } catch (_: NoSuchMethodError) {
            throw ocrNotSupported(sessionId)
        } catch (_: AbstractMethodError) {
            throw ocrNotSupported(sessionId)
        }
    }

    /** Internal: fire-and-forget close; idempotent on the service side. */
    internal fun closeOcrSessionFireAndForget(sessionId: String) {
        if (!hasOcrCapabilitySync()) return
        val service = connection.getService() ?: return
        try {
            withTypedErrorsSync(service, sessionId = sessionId) {
                it.closeOcrSession(sessionId)
            }
        } catch (_: Throwable) {
            // best-effort
        }
    }

    /** Internal: attach the OCR_V1 event-stream write-end. */
    internal suspend fun attachOcrEventStream(
        sessionId: String,
        writeEnd: ParcelFileDescriptor,
    ) {
        requireOcrCapability()
        try {
            withContext(Dispatchers.IO) {
                withTypedErrors(sessionId = sessionId) { it.streamOcrEvents(sessionId, writeEnd) }
            }
        } catch (_: NoSuchMethodError) {
            throw ocrNotSupported(sessionId)
        } catch (_: AbstractMethodError) {
            throw ocrNotSupported(sessionId)
        }
    }

    private fun hasOcrCapabilitySync(): Boolean {
        cachedCapabilities?.let { return it.supports(ServiceCapabilities.FEATURE_OCR_SESSION) }
        val service = connection.getService() ?: return false
        return try {
            val caps = service.capabilities
            cachedCapabilities = caps
            caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION)
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * DSL builder for configuring a Mindlayer inference session.
 *
 * Example:
 * ```
 * val session = mindlayer.openSession {
 *     systemPrompt = "You are a coffee expert."
 *     maxTokens = 2048
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
     * mindlayer.openSession {
     *     toolsJson = """[{"name":"get_weather","description":"Get current weather for a city","parameters":{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}}]"""
     * }
     * ```
     *
     * See [InferenceEvent.ToolCall] for handling tool invocations.
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
     * frame back into per-token [com.adsamcik.mindlayer.sdk.InferenceEvent.TextDelta]
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
     * v1.1: opt this session into Gemma 4 thinking mode. When enabled,
     * the service prepends the Gemma `<|think|>` system marker and
     * configures a LiteRT-LM channel that routes the model's
     * `<|channel>thought ... <channel|>` block away from the
     * user-visible answer. The SDK pipe negotiates
     * [com.adsamcik.mindlayer.shared.StreamProtocol.V3] so the reader
     * can decode the new
     * [com.adsamcik.mindlayer.sdk.InferenceEvent.ThoughtDelta] events
     * alongside the existing [com.adsamcik.mindlayer.sdk.InferenceEvent.TextDelta]
     * stream.
     *
     * Callers that only want the final answer can keep collecting
     * [com.adsamcik.mindlayer.sdk.InferenceHandle.events] as today —
     * the per-subtype `await*()` terminals already discard
     * [com.adsamcik.mindlayer.sdk.InferenceEvent.ThoughtDelta] by
     * default. To render the reasoning trace, pipe events through
     * [com.adsamcik.mindlayer.sdk.thoughtDeltas] or filter the raw
     * flow yourself.
     *
     * Capability-gated via
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_THINKING_MODE].
     * If the connected service does not advertise the flag this call
     * is still safe — the opt-in JSON is ignored, the stream stays on
     * v1/v2, and no `ThoughtDelta` events are emitted. Callers that
     * care about confirming thinking mode is actually live should
     * check capabilities first.
     *
     * Default: off (no separate thought channel).
     */
    fun enableThinking(enabled: Boolean = true) {
        val envelope = kotlinx.serialization.json.buildJsonObject {
            put(
                "thinking",
                kotlinx.serialization.json.buildJsonObject {
                    put(
                        "enable",
                        kotlinx.serialization.json.JsonPrimitive(enabled),
                    )
                },
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
