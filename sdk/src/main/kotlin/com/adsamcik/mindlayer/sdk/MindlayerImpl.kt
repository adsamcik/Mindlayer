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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
 * mindlayer.awaitConnected()
 *
 * val sessionId = mindlayer.createSession {
 *     systemPrompt("You are a helpful assistant.")
 * }
 *
 * mindlayer.inferRealtime(sessionId, "Hello!").events.collect { event ->
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
 * All embedding entry points ([embedOne], [embedMany], [embed], [embedBatch],
 * [embedBatchLarge], [embedBatchDeferred], [fetchEmbeddingBatch],
 * [cancelEmbed], [cancelEmbeddingBatch], [acknowledgeEmbeddingBatch]) obey
 * the same contract:
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
 * Prefer the [embedOne] / [embedMany] facades for new code — they pick
 * the cheapest viable transport (inline / SharedMemory / deferred
 * fallback) automatically. See `docs/EMBEDDINGS_SDK_POLISH.md` for the
 * full proposal and rationale.
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
            val sessionId = createSession {
                scope.systemPrompt?.let { systemPrompt(it) }
                scope.maxTokens?.let { maxTokens(it) }
            }
            BridgeSession(SessionHandle(this, sessionId))
        }
    }

    /**
     * Behavioural body for [infer] / [MindlayerSession.infer]. Bridges the
     * canonical request onto the legacy one-shot generation path: an ephemeral
     * request runs through [generate] / [generateWithImage] / [generateWithAudio]
     * (create → chat → destroy); a named-session request runs through the
     * `*Once` chat bridges. The full response text is then replayed as a tiny
     * cold [InferenceEvent] stream so the terminals (`awaitText`/`awaitJson`)
     * and `events` collectors behave uniformly.
     *
     * C3 deviations (documented in `docs/SDK_V1_MIGRATION.md`):
     *  - eager, not token-streaming: the suspend returns after the one-shot
     *    completes; `events` replays a single [InferenceEvent.TextDelta]. Use
     *    the legacy streaming path for live token deltas.
     *  - tool-calling via `infer{}` is not wired (throws NOT_SUPPORTED).
     *  - [SamplerScope.seed] has no [SessionConfigBuilder] equivalent and is
     *    dropped.
     */
    private suspend fun runInferRequest(
        request: InferenceRequest.Builder,
        overrideSessionId: String?,
    ): InferenceHandle {
        if (request.outputMode is InferenceRequest.OutputMode.Tools) {
            throw MindlayerException(
                message = "Mindlayer v1 — tool-calling via infer{} is not wired in 1.0.0-alpha01; " +
                    "create a named session with createSession { tools { } } and collect the " +
                    "streaming events instead.",
                code = MindlayerErrorCode.NOT_SUPPORTED,
            )
        }

        val prompt = request.promptText.orEmpty()
        val bitmap = request.imageInputs.firstNotNullOfOrNull { (it as? ImageInput.Bitmap)?.bitmap }
        val audio = request.audioFile
        val sessionId = overrideSessionId ?: request.sessionId

        @Suppress("DEPRECATION")
        val text: String = if (sessionId != null) {
            when {
                bitmap != null -> chatWithImageOnce(sessionId, prompt, bitmap)
                audio != null -> chatWithAudioOnce(sessionId, prompt, audio)
                else -> chatOnce(sessionId, prompt)
            }
        } else {
            val configure = sessionConfigureFrom(request)
            when {
                bitmap != null -> generateWithImage(prompt, bitmap, configure)
                audio != null -> generateWithAudio(prompt, audio, configure)
                else -> generate(prompt, configure)
            }
        }

        val requestId = "infer-${UUID.randomUUID()}"
        return InferenceHandleImpl(
            requestId = requestId,
            events = flow {
                emit(InferenceEvent.Started(requestId))
                if (text.isNotEmpty()) emit(InferenceEvent.TextDelta(text))
                emit(InferenceEvent.Done(finishReason = "stop", fullText = text))
            },
            sessionId = sessionId.orEmpty(),
        )
    }

    /**
     * Translate the [SessionScope] / [SamplerScope] captured by an inference
     * request into a [SessionConfigBuilder] configure block for the ephemeral
     * one-shot session. [SamplerScope.seed] is dropped (no builder field).
     */
    private fun sessionConfigureFrom(request: InferenceRequest.Builder): SessionConfigBuilder.() -> Unit {
        val session = CapturedSessionScope().apply { request.sessionConfigure?.invoke(this) }
        val sampler = CapturedSamplerScope().apply { request.samplerConfigure?.invoke(this) }
        return {
            session.systemPrompt?.let { systemPrompt(it) }
            session.maxTokens?.let { maxTokens(it) }
            sampler.topK?.let { topK(it) }
            sampler.topP?.let { topP(it) }
            sampler.temperature?.let { temperature(it) }
        }
    }

    /**
     * Behavioural body for [ocr]. Converts the request image to encoded bytes,
     * runs the legacy one-shot [ocrAsync] bridge, and maps the
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
        @Suppress("DEPRECATION")
        val raw = ocrAsync(bytes, mimeType, options)
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
            )
        }
        val extractionJson = raw.extractionJson?.let { json ->
            runCatching { lenientJson.parseToJsonElement(json) as JsonObject }.getOrNull()
        }
        val fullJson = extractionJson ?: buildJsonObject {
            put("lines", buildJsonArray { lines.forEach { add(JsonPrimitive(it.text)) } })
        }
        val metrics = Metrics(totalDurationMs = raw.totalDurationMs.takeIf { it > 0L })
        return OcrResult(lines = lines, fullJson = fullJson, extractionJson = extractionJson, metrics = metrics)
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

        @Suppress("DEPRECATION")
        override suspend fun ask(prompt: String): String = handle.chatOnce(prompt)

        @Suppress("DEPRECATION")
        override suspend fun describe(prompt: String, image: Bitmap): String =
            handle.chatWithImageOnce(prompt, image)

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
            throw MindlayerException(
                message = "Mindlayer v1 — ocrSession behaviour lands in C3",
                code = MindlayerErrorCode.NOT_SUPPORTED,
            )
        }
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
            throw MindlayerException(
                message = "Mindlayer v1 — embed behaviour lands in C3",
                code = MindlayerErrorCode.NOT_SUPPORTED,
            )
        }
    }

    /** Observable connection state. */
    override val connectionState: StateFlow<ConnectionState>
        get() = connection.state

    internal var createSessionInitRetryTimeoutMs: Long =
        DEFAULT_CREATE_SESSION_INIT_RETRY_TIMEOUT_MS

    internal var createSessionInitRetryBackoffMs: List<Long> =
        DEFAULT_CREATE_SESSION_INIT_RETRY_BACKOFF_MS

    internal var createSessionInitRetryClockMs: () -> Long = { System.currentTimeMillis() }

    /** Suspend until the service binder is available. */
    override suspend fun awaitConnected() {
        connection.awaitConnected()
    }

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
    override suspend fun createSession(
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
    /** List all live server-side sessions owned by this caller. */
    override suspend fun listSessions(): List<SessionInfo> {
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
     * assistant turn is marked COMPLETED only after the [InferenceEvent.Done]
     * event.
     *
     * @deprecated Use [inferRealtime] for the canonical streaming entry
     *   point. Behavior is identical when no media is supplied — `inferRealtime`
     *   routes through the same legacy `infer(meta, null, null, pfd)`
     *   wire path. See `docs/INFERENCE_SDK_POLISH.md`.
     */
    @Deprecated(
        message = "Use inferRealtime(sessionId, text) — same behavior, canonical name.",
        replaceWith = ReplaceWith("inferRealtime(sessionId, text)"),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun chat(sessionId: String, text: String): InferenceHandle {
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
     *
     * @deprecated Use [inferRealtime] with a [MediaTransfer.imagePart] —
     *   same behavior, unified parameter shape. See
     *   `docs/INFERENCE_SDK_POLISH.md`.
     */
    @Deprecated(
        message = "Use inferRealtime(sessionId, text, MediaTransfer.imagePart(bitmap)).",
        replaceWith = ReplaceWith(
            "inferRealtime(sessionId, text, MediaTransfer.imagePart(bitmap))",
            "com.adsamcik.mindlayer.sdk.MediaTransfer",
        ),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun chatWithImage(
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
     *
     * @deprecated Use [inferRealtime] with a [MediaTransfer.audioPart] —
     *   same behavior, unified parameter shape. See
     *   `docs/INFERENCE_SDK_POLISH.md`.
     */
    @Deprecated(
        message = "Use inferRealtime(sessionId, text, MediaTransfer.audioPart(audioFile)).",
        replaceWith = ReplaceWith(
            "inferRealtime(sessionId, text, MediaTransfer.audioPart(audioFile))",
            "com.adsamcik.mindlayer.sdk.MediaTransfer",
        ),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun chatWithAudio(
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
     *
     * @deprecated Use [inferRealtime] with the same `vararg` parts — same
     *   behavior, canonical name. See `docs/INFERENCE_SDK_POLISH.md`.
     */
    @Deprecated(
        message = "Use inferRealtime(sessionId, text, *parts) — same behavior, canonical name.",
        replaceWith = ReplaceWith("inferRealtime(sessionId, text, *parts)"),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun chatWithMedia(
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

    // ── Polished entry points (feat/inference-sdk-polish) ────────────────────
    //
    // Three canonical top-level inference methods, all sharing the same
    //   (sessionId: String, text: String, vararg media: MediaPart)
    // shape. See docs/INFERENCE_SDK_POLISH.md for the full contract.
    //
    // These are thin facades over the existing (now @Deprecated) chat /
    // chatWithImage / chatWithAudio / chatWithMedia methods. They do not
    // change the AIDL wire shape, do not allocate extra structures, and
    // do not duplicate logic — the delegated methods own behavior; these
    // own naming + KDoc.

    /**
     * Stream inference events token-by-token.
     *
     * Canonical streaming entry point. Accepts zero or more media parts
     * (image / audio) built via [MediaTransfer]:
     *
     * ```kotlin
     * // Text-only
     * mindlayer.inferRealtime(sessionId, "Hello!").events.collect { ... }
     *
     * // With an image
     * mindlayer.inferRealtime(
     *     sessionId,
     *     "Describe this image",
     *     MediaTransfer.imagePart(bitmap),
     * ).events.collect { ... }
     *
     * // With image + audio (subject to the one-image / one-audio engine
     * // constraint documented on MediaPart)
     * mindlayer.inferRealtime(
     *     sessionId,
     *     "Caption this and transcribe the audio",
     *     MediaTransfer.imagePart(bitmap),
     *     MediaTransfer.audioPart(wav),
     * ).events.collect { ... }
     * ```
     *
     * **Routing:** when `media` is empty, this delegates to the legacy
     * `infer(meta, null, null, pfd)` AIDL path. When `media` is non-empty,
     * it routes through `inferMulti` and transparently falls back to the
     * v0.1 single-image / single-audio surface for services that do not
     * advertise [ServiceCapabilities.FEATURE_MEDIA_LIST]. Either way, the
     * returned [InferenceHandle] carries a stable [InferenceHandle.requestId]
     * generated synchronously before the suspend, so cancel wiring can be
     * set up before the request even ships.
     *
     * @return [InferenceHandle] streaming [InferenceEvent]s. Collect the
     *   handle's [events][InferenceHandle.events] flow and call
     *   [cancel][InferenceHandle.cancel] to abort.
     * @throws MindlayerException for typed service errors (rate limit,
     *   validation, missing-capability) or stream `ERROR` frames.
     * @throws SecurityException if the caller is not on the service's
     *   allowlist or its first-connect approval is still pending.
     */
    @Suppress("DEPRECATION")
    override suspend fun inferRealtime(
        sessionId: String,
        text: String,
        vararg media: com.adsamcik.mindlayer.MediaPart,
    ): InferenceHandle =
        if (media.isEmpty()) chat(sessionId, text)
        else chatWithMedia(sessionId, text, *media)

    /**
     * Run a single-shot inference and return the complete response text.
     *
     * Canonical "I just want the final string" entry point. Collects the
     * streaming [inferRealtime] flow to completion and returns the
     * accumulated text. Same media-parts shape as [inferRealtime].
     *
     * ```kotlin
     * val answer = mindlayer.inferAsync(sessionId, "What is the capital of France?")
     * println(answer)  // "Paris."
     * ```
     *
     * **Note:** This method always uses the streaming wire path under the
     * hood. A future revision may route through
     * [ServiceCapabilities.FEATURE_DEFERRED_INFERENCE] when advertised so
     * the call survives caller process death — see
     * `docs/INFERENCE_SDK_POLISH.md` follow-up #1. Today, callers that
     * need deferred semantics should keep using [chatDeferred] /
     * [awaitDeferred] directly.
     *
     * @return the full response text accumulated from
     *   [InferenceEvent.TextDelta] / [InferenceEvent.Done] events.
     * @throws MindlayerException if the service reports an error or sends
     *   an unexpected [InferenceEvent.ToolCall] (tool calling is not
     *   supported in async / one-shot mode — use [inferTools] instead).
     * @throws IllegalStateException if the stream ends without a terminal
     *   [InferenceEvent.Done] event.
     */
    override suspend fun inferAsync(
        sessionId: String,
        text: String,
        vararg media: com.adsamcik.mindlayer.MediaPart,
    ): String = collectHandleToString(
        inferRealtime(sessionId, text, *media),
        sessionId,
    )

    /**
     * Run an inference loop that may emit tool calls.
     *
     * Canonical entry point for tool-calling inference. Use this when the
     * session was configured with [SessionConfigBuilder.tools] and you
     * intend to handle [InferenceEvent.ToolCall] events by calling
     * [submitToolResultDetailed].
     *
     * The wire shape is the same as [inferRealtime] — both route through
     * the same `infer` / `inferMulti` AIDL methods. The distinction is
     * **intent**: a session opened with tools may interleave
     * [InferenceEvent.ToolCall] events into its event stream, and the
     * caller is expected to round-trip those via
     * [submitToolResultDetailed]. Outside a tools-enabled session this
     * method behaves identically to [inferRealtime].
     *
     * ```kotlin
     * val sessionId = mindlayer.createSession {
     *     tools {
     *         add("get_weather") { /* ... handler ... */ }
     *     }
     * }
     * val handle = mindlayer.inferTools(sessionId, "What's the weather in Prague?")
     * handle.events.collect { event ->
     *     when (event) {
     *         is InferenceEvent.ToolCall -> {
     *             val result = runMyHandler(event)
     *             mindlayer.submitToolResultDetailed(
     *                 sessionId, handle.requestId, event.callId, result,
     *             )
     *         }
     *         is InferenceEvent.TextDelta -> print(event.text)
     *         is InferenceEvent.Done -> println()
     *         else -> {}
     *     }
     * }
     * ```
     *
     * **Future direction:** a follow-up PR may add a higher-level
     * `inferTools(...)` overload that accepts a handler map and runs the
     * tool-call loop automatically. See `docs/INFERENCE_SDK_POLISH.md`
     * follow-up #2.
     *
     * @return [InferenceHandle] — identical contract to [inferRealtime].
     */
    override suspend fun inferTools(
        sessionId: String,
        text: String,
        vararg media: com.adsamcik.mindlayer.MediaPart,
    ): InferenceHandle = inferRealtime(sessionId, text, *media)


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
     * ```kotlin
     * val mindlayer = Mindlayer.connect(context); mindlayer.awaitConnected()
     * if (!mindlayer.getCapabilities().supports(ServiceCapabilities.FEATURE_EMBEDDINGS)) {
     *     // service doesn't ship embeddings — show a friendly message
     * }
     * val vec = mindlayer.embedOne("the cat sat on the mat")
     * val index = InMemoryVectorIndex()
     * index.put("doc1", vec)
     * val hits = index.search(mindlayer.embedOne("feline on textile"), k = 5)
     * ```
     *
     * Compute one L2-normalized embedding for [text]. Returns the bare
     * [FloatArray] — the typed [com.adsamcik.mindlayer.EmbeddingResult]
     * (with `tag`, `modelId`, `tokenCount`, `backend`, `durationMs`) is
     * reachable via [embed] (the [EmbeddingConfig] overload) if you need it.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old
     * service binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     *
     * @param text The text to embed. Sensitive input — never logged.
     * @param task Embedding task prefix (RetrievalDocument by default —
     *   the right choice for indexing documents you intend to search later).
     *   Use [EmbeddingTask.RetrievalQuery] for the query side.
     * @param modelId Override the service-default embedding model. `null` =
     *   service picks. v1 has only one model (`embedding-gemma-300m-v1`).
     * @param outputDim Matryoshka truncation: 768 / 512 / 256 / 128. `null`
     *   = use the model's native dimension (768).
     * @param normalize L2-normalize so cosine similarity = dot product.
     *   Almost always `true`; set `false` only if you need raw magnitudes.
     * @param tag Opaque caller-supplied tag echoed back in the typed result.
     *   Useful for batch correlation; unused for single embeddings.
     */
    @Suppress("DEPRECATION")
    override suspend fun embedOne(
        text: String,
        task: EmbeddingTask,
        modelId: String?,
        outputDim: Int?,
        normalize: Boolean,
        tag: String?,
    ): FloatArray = embed(
        EmbeddingConfig(
            text = text,
            task = task,
            modelId = modelId,
            outputDim = outputDim,
            normalize = normalize,
            tag = tag,
        ),
    ).vector

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
    override suspend fun embedMany(items: List<EmbeddingConfig>): EmbeddingBatch {
        require(items.isNotEmpty()) { "Embedding batch must be non-empty" }
        val caps = requireEmbeddingCapability()
        return when (selectEmbeddingTransport(caps, items)) {
            EmbeddingTransport.Inline -> embedManyInline(caps, items)
            EmbeddingTransport.SharedMemory -> embedManyShm(caps, items)
            EmbeddingTransport.DeferredFallback -> embedManyDeferredFallback(items)
        }
    }

    /**
     * String-convenience overload of [embedMany]. Wraps every [String] in
     * an [EmbeddingConfig] with the supplied [task] and [modelId]; for
     * per-item config (different tasks, tags, output dims), call the
     * `List<EmbeddingConfig>` overload directly.
     */
    override suspend fun embedMany(
        texts: List<String>,
        task: EmbeddingTask,
        modelId: String?,
    ): EmbeddingBatch = embedMany(
        texts.map { EmbeddingConfig(text = it, task = task, modelId = modelId) },
    )

    /**
     * Transport-selection rule for [embedMany]. Internal; exposed for tests
     * so the inline / SHM / deferred routing can be pinned without touching
     * a binder.
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
                val res = withTypedErrors { it.embedBatch(items.map { config -> config.toAidlRequest() }) }
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
                val transfer = withTypedErrors { it.embedBatchShm(items.map { config -> config.toAidlRequest() }) }
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

    @Suppress("DEPRECATION")
    private suspend fun embedManyDeferredFallback(items: List<EmbeddingConfig>): EmbeddingBatch {
        // Reuse the existing fallback chain in embedBatchLarge: it already
        // routes through embedBatch (if items fit inline) or deferred (if
        // not), and is the single source of truth for the SHM-unavailable
        // path. Batch-level metadata is best-effort here — durationMs is
        // tracked per-item; backend is read from the first item.
        val results = embedBatchLarge(items)
        return EmbeddingBatch(
            results = results,
            transport = EmbeddingTransport.DeferredFallback,
            totalDurationMs = 0L,
            backend = results.firstOrNull()?.backend ?: UNKNOWN_BACKEND,
        )
    }

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
    @Deprecated(
        "Use embedOne(text) — same semantics, named for symmetry with embedMany(). " +
            "See docs/EMBEDDINGS_SDK_POLISH.md.",
        ReplaceWith("embedOne(text)"),
        DeprecationLevel.WARNING,
    )
    override suspend fun embed(text: String): FloatArray = embed(EmbeddingConfig(text = text)).vector

    /**
     * Typed embedding. See [EmbeddingConfig] for full options.
     *
     * Capability-gated by [ServiceCapabilities.FEATURE_EMBEDDINGS]; old service
     * binaries throw [MindlayerException] with `NOT_SUPPORTED`.
     */
    override suspend fun embed(config: EmbeddingConfig): com.adsamcik.mindlayer.EmbeddingResult {
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
    @Deprecated(
        "Use embedMany(items) — picks the cheapest viable transport (inline, " +
            "SharedMemory, or deferred fallback) automatically. " +
            "See docs/EMBEDDINGS_SDK_POLISH.md.",
        ReplaceWith("embedMany(configs).results"),
        DeprecationLevel.WARNING,
    )
    override suspend fun embedBatch(configs: List<EmbeddingConfig>): List<com.adsamcik.mindlayer.EmbeddingResult> {
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
    @Deprecated(
        "Use embedMany(items) — picks SharedMemory automatically when the " +
            "batch needs it. See docs/EMBEDDINGS_SDK_POLISH.md.",
        ReplaceWith("embedMany(configs).results"),
        DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    override suspend fun embedBatchLarge(configs: List<EmbeddingConfig>): List<com.adsamcik.mindlayer.EmbeddingResult> {
        val caps = requireEmbeddingCapability()
        if (caps.maxEmbeddingBatchShm <= 0) {
            return if (configs.size <= caps.maxEmbeddingBatchInline) {
                embedBatch(configs)
            } else {
                validateEmbeddingBatchSize(configs, caps.maxEmbeddingBatchTotal, "deferred")
                awaitEmbeddingBatch(embedBatchDeferred(configs))
            }
        }
        validateEmbeddingBatchSize(configs, caps.maxEmbeddingBatchShm, "shared-memory")
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
    override suspend fun ping(): com.adsamcik.mindlayer.HealthCheck {
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
    override suspend fun getEngineInfo(): EngineInfo {
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
    override suspend fun getDiagnosticsTyped(): com.adsamcik.mindlayer.DiagnosticsSnapshot? {
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
    @Deprecated(
        message = "Use inferAsync(text) — canonical name that pairs " +
            "cleanly with inferRealtime() and matches the OCR realtime/" +
            "async API naming.",
        replaceWith = ReplaceWith("inferAsync(text)"),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun chat(text: String): String {
        awaitConnected()
        return generate(text)
    }

    /**
     * Send a message with an image and get the response. No session management needed.
     */
    @Deprecated(
        message = "Use inferAsync(text, MediaTransfer.imagePart(image)) — " +
            "canonical name + explicit media transport.",
        replaceWith = ReplaceWith("inferAsync(text, MediaTransfer.imagePart(image))"),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun chat(text: String, image: Bitmap): String {
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
    fun session(sessionId: String): SessionHandle =
        SessionHandle(this, sessionId)

    // -- One-shot convenience ------------------------------------------------

    /**
     * Send a text message and return the complete response text.
     *
     * Collects the streaming [chat] flow to completion and returns the
     * accumulated text. Throws [MindlayerException] on service errors.
     *
     * @throws MindlayerException if the service reports an error or sends
     *   an unexpected [InferenceEvent.ToolCall] (tool calling is not
     *   supported in one-shot mode).
     * @throws IllegalStateException if the stream ends without a terminal
     *   [InferenceEvent.Done] event.
     *
     * @deprecated Use [inferAsync] — same behavior, canonical name. See
     *   `docs/INFERENCE_SDK_POLISH.md`.
     */
    @Deprecated(
        message = "Use inferAsync(sessionId, text) — same behavior, canonical name.",
        replaceWith = ReplaceWith("inferAsync(sessionId, text)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    override suspend fun chatOnce(sessionId: String, text: String): String =
        collectHandleToString(chat(sessionId, text), sessionId)

    /**
     * Send a text + image message and return the complete response text.
     *
     * @see chatOnce for error semantics.
     *
     * @deprecated Use [inferAsync] with [MediaTransfer.imagePart]. See
     *   `docs/INFERENCE_SDK_POLISH.md`.
     */
    @Deprecated(
        message = "Use inferAsync(sessionId, text, MediaTransfer.imagePart(bitmap)).",
        replaceWith = ReplaceWith(
            "inferAsync(sessionId, text, MediaTransfer.imagePart(bitmap))",
            "com.adsamcik.mindlayer.sdk.MediaTransfer",
        ),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    override suspend fun chatWithImageOnce(
        sessionId: String,
        text: String,
        bitmap: Bitmap,
    ): String = collectHandleToString(chatWithImage(sessionId, text, bitmap), sessionId)

    /**
     * Send a text + audio message and return the complete response text.
     *
     * @see chatOnce for error semantics.
     *
     * @deprecated Use [inferAsync] with [MediaTransfer.audioPart]. See
     *   `docs/INFERENCE_SDK_POLISH.md`.
     */
    @Deprecated(
        message = "Use inferAsync(sessionId, text, MediaTransfer.audioPart(audioFile)).",
        replaceWith = ReplaceWith(
            "inferAsync(sessionId, text, MediaTransfer.audioPart(audioFile))",
            "com.adsamcik.mindlayer.sdk.MediaTransfer",
        ),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    override suspend fun chatWithAudioOnce(
        sessionId: String,
        text: String,
        audioFile: File,
    ): String = collectHandleToString(chatWithAudio(sessionId, text, audioFile), sessionId)

    /**
     * Stream just the text deltas from [chat] as a [Flow]&lt;String&gt;.
     *
     * Filters out non-text events (Started, Metrics) and converts terminal
     * frames into flow signals: [InferenceEvent.Done] completes the flow,
     * [InferenceEvent.Error] throws a [MindlayerException], and an
     * unexpected [InferenceEvent.ToolCall] throws with code
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
    override fun chatTextFlow(sessionId: String, text: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            @Suppress("DEPRECATION")
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
    override fun chatFullTextFlow(sessionId: String, text: String): kotlinx.coroutines.flow.Flow<String> =
        kotlinx.coroutines.flow.flow {
            val acc = StringBuilder()
            @Suppress("DEPRECATION")
            val handle = chat(sessionId, text)
            textDeltaFlow(handle, sessionId).collect { delta ->
                acc.append(delta)
                emit(acc.toString())
            }
        }

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
    override suspend fun generate(
        text: String,
        configure: SessionConfigBuilder.() -> Unit,
    ): String {
        val sessionId = createSession(configure)
        return try {
            @Suppress("DEPRECATION")
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
    override suspend fun generateWithImage(
        text: String,
        bitmap: Bitmap,
        configure: SessionConfigBuilder.() -> Unit,
    ): String {
        val sessionId = createSession(configure)
        return try {
            @Suppress("DEPRECATION")
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
    override suspend fun generateWithAudio(
        text: String,
        audioFile: File,
        configure: SessionConfigBuilder.() -> Unit,
    ): String {
        val sessionId = createSession(configure)
        return try {
            @Suppress("DEPRECATION")
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
    //  getOcrLimits / ocrImage) are unchanged. Old method names
    //  ([ocrSession], [ocrImage]) remain as ``@Deprecated`` delegating
    //  aliases for one minor cycle.
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
                withTypedErrors { it.ocrLimits }
            }
        } catch (_: NoSuchMethodError) {
            com.adsamcik.mindlayer.OcrLimits.zeroBaseline()
        } catch (_: AbstractMethodError) {
            com.adsamcik.mindlayer.OcrLimits.zeroBaseline()
        }
    }

    /**
     * **Realtime OCR** — open a multi-frame OCR session using a
     * built-in [OcrProfile].
     *
     * Use this when you have a live camera feed and want the service
     * to fuse multiple frames into a single high-confidence result
     * (cross-frame voting, K-consecutive field locking, optional
     * barcode anchor, structured schema-shaped output). The session
     * pipeline also runs the service-side quality presort so blurry
     * / dark frames are rejected before reaching the engine.
     *
     * ```kotlin
     * mindlayer.ocrRealtime(OcrProfile.Receipt) {
     *     languageHints = listOf("en", "de-DE")
     *     maxFrames = 30
     * }.use { session ->
     *     // Attach the event stream BEFORE pushing the first frame —
     *     // otherwise intake is rejected with STATUS_REJECTED_STREAM_NOT_ATTACHED.
     *     val job = launch {
     *         session.events.collect { event -> /* ... */ }
     *     }
     *     session.pushFrame(meta, yPlane, w, h)
     *     // ... push more frames as the user re-aims ...
     *     session.finalize()
     *     job.join()
     * }
     * ```
     *
     * # Capability
     *
     * Requires [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_SESSION].
     * Throws [MindlayerException] with
     * [com.adsamcik.mindlayer.shared.MindlayerErrorCode.FEATURE_NOT_SUPPORTED]
     * when the connected service does not advertise the flag (e.g. the
     * production-readiness gate is still off, the OCR model bundle is
     * missing, or the service binary predates v0.8).
     *
     * @param profile the OCR profile preset (GeneralDocument / Receipt
     *   / IdCard / Whiteboard / ScreenCapture).
     * @param configure optional builder block to override profile
     *   defaults (custom schema, language hints, fps cap, etc.).
     * @throws MindlayerException with a typed error code on service
     *   rejection (LOW_MEMORY, INVALID_REQUEST, SERVICE_UNAVAILABLE,
     *   CONCURRENT_LIMIT).
     */
    override suspend fun ocrRealtime(
        profile: OcrProfile,
        configure: OcrSessionConfigBuilder.() -> Unit,
    ): OcrSession {
        val builder = OcrSessionConfigBuilder(profile)
        builder.configure()
        return ocrRealtime(builder.build())
    }

    /**
     * **Realtime OCR** — open a multi-frame OCR session with a
     * pre-built [OcrSessionConfig].
     *
     * Use this overload when you have a serialised config (e.g. from
     * process recovery or persistent settings) and don't want the
     * builder DSL. See [ocrRealtime] (with profile + DSL block) for
     * the common case.
     *
     * Capability + error semantics match the DSL overload above.
     */
    override suspend fun ocrRealtime(config: com.adsamcik.mindlayer.OcrSessionConfig): OcrSession {
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
     * **Async OCR** — recognise text in a single captured image and
     * return synchronously. The natural shape for callers that have
     * one final image already (gallery picker, sharesheet target,
     * screenshot text-extraction, "scan this receipt" one-shot) and
     * don't want session ceremony.
     *
     * Pass [options].`runLlmExtraction = true` (plus
     * [com.adsamcik.mindlayer.OcrImageOptions.extractionSchemaJson]) to
     * also run the structured-extraction Gemma pass and receive
     * [com.adsamcik.mindlayer.OcrImageResult.extractionFields] +
     * [com.adsamcik.mindlayer.OcrImageResult.extractionJson]. Adds the
     * LLM decode latency (~2-5s) to the call.
     *
     * The bytes path picks SharedMemory or pipe transport automatically
     * based on size — payloads under
     * `OCR_INLINE_PIPE_THRESHOLD_BYTES` use a PFD pipe; larger payloads
     * use SharedMemory on API 27+. Caller does not need to manage the
     * file descriptor.
     *
     * # Capability
     *
     * Requires [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT].
     * Throws [MindlayerException] with
     * [com.adsamcik.mindlayer.shared.MindlayerErrorCode.FEATURE_NOT_SUPPORTED]
     * when the connected service does not advertise the flag (e.g. the
     * production-readiness gate is still off, the model bundle is
     * missing, or the service is older than v0.9).
     *
     * @param bytes encoded image bytes (JPEG / PNG / WEBP). Must be
     *   non-empty.
     * @param mimeType the image MIME type — one of `image/jpeg`,
     *   `image/png`, `image/webp`.
     * @param options recognition + extraction toggles.
     * @throws MindlayerException with a typed error code on service
     *   rejection (LOW_MEMORY, INVALID_REQUEST, SERVICE_UNAVAILABLE).
     */
    override suspend fun ocrAsync(
        bytes: ByteArray,
        mimeType: String,
        options: com.adsamcik.mindlayer.OcrImageOptions,
    ): com.adsamcik.mindlayer.OcrImageResult {
        requireOcrImageCapability()
        val part = MediaTransfer.ocrEncodedImagePart(
            requestId = "ocr-image-${java.util.UUID.randomUUID()}",
            bytes = bytes,
            mimeType = mimeType,
            // Bug #7: thread the bound application Context through so the
            // transport selector takes the regular-file PFD path (not the
            // pipe path that the service rejects with H5's "Unsupported
            // source PFD type"). Null only on the never-connected fast
            // path, where the call below would fail anyway.
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

    // ─────────────────────────────────────────────────────────────────────
    //  Deprecated OCR aliases — kept for one minor cycle so existing
    //  callers don't break on the rename. Delegate to the new names.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Open a multi-frame OCR session using a built-in [OcrProfile].
     *
     * @deprecated Renamed to [ocrRealtime] in v0.10 — the
     *   "realtime / async" pair makes the live-camera vs single-image
     *   decision explicit. Behaviour is unchanged; this overload
     *   delegates to [ocrRealtime].
     */
    @Deprecated(
        message = "Use ocrRealtime() — the new name pairs with ocrAsync() and " +
            "makes the live-camera intent explicit. Behaviour is unchanged.",
        replaceWith = ReplaceWith("ocrRealtime(profile, configure)"),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun ocrSession(
        profile: OcrProfile,
        configure: OcrSessionConfigBuilder.() -> Unit,
    ): OcrSession = ocrRealtime(profile, configure)

    /**
     * Open a multi-frame OCR session with a pre-built [OcrSessionConfig].
     *
     * @deprecated Renamed to [ocrRealtime] in v0.10. Behaviour is
     *   unchanged; this overload delegates to [ocrRealtime].
     */
    @Deprecated(
        message = "Use ocrRealtime() — the new name pairs with ocrAsync() and " +
            "makes the live-camera intent explicit. Behaviour is unchanged.",
        replaceWith = ReplaceWith("ocrRealtime(config)"),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun ocrSession(config: com.adsamcik.mindlayer.OcrSessionConfig): OcrSession =
        ocrRealtime(config)

    /**
     * Single-image OCR.
     *
     * @deprecated Renamed to [ocrAsync] in v0.10 — the
     *   "realtime / async" pair makes the live-camera vs single-image
     *   decision explicit. Behaviour is unchanged; this overload
     *   delegates to [ocrAsync].
     */
    @Deprecated(
        message = "Use ocrAsync() — the new name pairs with ocrRealtime() and " +
            "makes the single-image intent explicit. Behaviour is unchanged.",
        replaceWith = ReplaceWith("ocrAsync(bytes, mimeType, options)"),
        level = DeprecationLevel.WARNING,
    )
    override suspend fun ocrImage(
        bytes: ByteArray,
        mimeType: String,
        options: com.adsamcik.mindlayer.OcrImageOptions,
    ): com.adsamcik.mindlayer.OcrImageResult = ocrAsync(bytes, mimeType, options)

    private suspend fun requireOcrImageCapability() {
        val caps = getCapabilities()
        if (ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT !in caps.supportedFeatures) {
            throw ocrImageNotSupported()
        }
    }

    private fun ocrImageNotSupported(): MindlayerException = MindlayerException(
        message = "Connected Mindlayer service does not support single-image OCR (ocrAsync)",
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
