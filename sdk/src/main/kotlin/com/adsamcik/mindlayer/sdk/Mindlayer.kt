package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.time.Duration

/**
 * Public client surface for the on-device Mindlayer LLM service (Mindlayer v1).
 *
 * # Two API tiers
 *
 * **Canonical, builder-based** (the recommended surface): [infer], [ocr],
 * [ocrSession], [embed]. Each accepts a DSL block that records intent into a
 * typed [com.adsamcik.mindlayer.sdk.InferenceRequest] / [OcrRequest] /
 * [OcrSessionRequest] / [EmbeddingRequest] and returns a cold handle. Spike-E
 * §0/§1 surface; behaviour lands in C2.
 *
 * **High-level helpers** ([ask], [describe], [transcribe], [extractJson],
 * [vector], [vectors], [readText], [readStructuredJson], [withSession],
 * [openSession]) wrap the canonical primitives for common 1-liner shapes.
 *
 * # Legacy methods
 *
 * Every method declared via `@Deprecated(level = DeprecationLevel.HIDDEN)` on
 * this interface is a v0.x carry-over (`chat*`, `*Once`, `infer*`, `generate*`,
 * `embed*`, `ocrRealtime`/`ocrAsync`/`ocrImage`/`ocrSession(profile,…)`). They
 * remain implemented by [MindlayerImpl] for binary compatibility with existing
 * binders and the SDK test suite, but are invisible to new compilations of
 * Mindlayer-typed consumers — the canonical builder methods are the only
 * source-visible inference / OCR / embedding entry points for new code.
 *
 * # Construction
 *
 * Always via [Mindlayer.connect]. The returned instance binds asynchronously;
 * gate calls on [connectionState] or use [awaitConnected].
 */
interface Mindlayer {

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Observable connection state. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Suspend until the service binder is available and return its negotiated
     * [Capabilities]. Throws on timeout.
     *
     * Note: no default value on [timeout] — the impl carries a separate no-arg
     * `awaitConnected()` for internal use; defaulting here would cause an
     * overload-ambiguity error against that carrier.
     */
    suspend fun awaitConnected(timeout: Duration): Capabilities

    @Deprecated(
        "Use awaitConnected(timeout) — defaults to Duration.INFINITE until C2.",
        ReplaceWith("awaitConnected(kotlin.time.Duration.INFINITE)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun awaitConnected()

    /** Unbind from the service and release SDK-side resources. Idempotent. */
    fun disconnect()

    // ── Engine control / introspection ─────────────────────────────────────
    //
    // C3 addition (deviation from Spike-E §0/§1, which sketched no engine-
    // control surface on the interface): these utility methods already existed
    // as public members on the impl, but consumers reach the service only
    // through this interface (connect() returns Mindlayer). Surfacing them here
    // unblocks the migration of warm-up and diagnostics call sites without a
    // redesign. They are not part of the canonical builder API.

    /**
     * Pre-warm the inference engine on [backend]. Returns immediately,
     * regardless of whether engine init has finished.
     */
    suspend fun prewarm(backend: InferenceBackend = InferenceBackend.GPU)

    /** Engine introspection: selected model, perf stats, backend, etc. */
    suspend fun getEngineInfo(): com.adsamcik.mindlayer.EngineInfo

    /**
     * Negotiated service capabilities including [com.adsamcik.mindlayer.ServiceCapabilities.apiVersion],
     * supported features, rate limits, and embedding model metadata. Cached by
     * default with a short TTL; pass [forceRefresh] = `true` after engines have
     * had time to warm up so feature flags gated on init (OCR, embeddings)
     * appear once they're really up.
     *
     * Distinct from [awaitConnected]'s [Capabilities] return: that's a thin
     * supported-features-only view; this returns the full AIDL parcelable for
     * consumers that need the lower-level fields (rate limits, dims, etc.).
     */
    suspend fun getCapabilities(
        forceRefresh: Boolean = false,
    ): com.adsamcik.mindlayer.ServiceCapabilities

    /** Lightweight service status snapshot (lighter than [getEngineInfo]). */
    suspend fun getStatus(): com.adsamcik.mindlayer.ServiceStatus

    /** Round-trip health probe. Returns the service's reply. */
    suspend fun ping(): com.adsamcik.mindlayer.HealthCheck

    /**
     * Typed diagnostics snapshot — engine, service, OCR, embedding sub-metrics —
     * or `null` if the service is too old or the call was refused.
     */
    suspend fun getDiagnosticsTyped(): com.adsamcik.mindlayer.DiagnosticsSnapshot?

    /** Live server-side sessions owned by this caller. See [com.adsamcik.mindlayer.SessionInfo]. */
    suspend fun listSessions(): List<com.adsamcik.mindlayer.SessionInfo>

    /** Destroy a single live session and release server-side resources. */
    suspend fun destroySession(sessionId: String)

    // ── Canonical builder-based API (Spike-E §0/§1) ────────────────────────

    suspend fun infer(build: InferenceRequest.Builder.() -> Unit): InferenceHandle =
        error("Mindlayer v1 — C2 lands behaviour")

    suspend fun ocr(build: OcrRequest.Builder.() -> Unit): OcrHandle.OneShot =
        error("Mindlayer v1 — C2 lands behaviour")

    suspend fun ocrSession(build: OcrSessionRequest.Builder.() -> Unit): OcrHandle.MultiFrame =
        error("Mindlayer v1 — C2 lands behaviour")

    suspend fun embed(build: EmbeddingRequest.Builder.() -> Unit): EmbeddingHandle =
        error("Mindlayer v1 — C2 lands behaviour")

    // ── High-level helpers (Spike-E §2) ────────────────────────────────────

    suspend fun ask(prompt: String, configure: SessionScope.() -> Unit = {}): String =
        error("Mindlayer v1 — C2 lands behaviour")

    suspend fun describe(
        prompt: String,
        image: Bitmap,
        configure: SessionScope.() -> Unit = {},
    ): String = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun transcribe(
        prompt: String,
        audio: File,
        configure: SessionScope.() -> Unit = {},
    ): String = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun extractJson(
        prompt: String,
        schema: JsonSchema,
        image: Bitmap? = null,
        audio: File? = null,
        configure: SessionScope.() -> Unit = {},
    ): JsonObject = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun vector(
        text: String,
        task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
    ): FloatArray = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun vectors(items: List<EmbeddingItem>): List<EmbeddingResultItem> =
        error("Mindlayer v1 — C2 lands behaviour")

    suspend fun readText(
        image: Bitmap,
        profile: OcrProfile = OcrProfile.GeneralDocument,
    ): String = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun readText(
        bytes: ByteArray,
        mimeType: String,
        profile: OcrProfile = OcrProfile.GeneralDocument,
    ): String = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun readStructuredJson(
        image: Bitmap,
        schema: JsonSchema,
        profile: OcrProfile = OcrProfile.GeneralDocument,
    ): JsonObject = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun <R> withSession(
        configure: SessionScope.() -> Unit = {},
        block: suspend MindlayerSession.() -> R,
    ): R = error("Mindlayer v1 — C2 lands behaviour")

    suspend fun openSession(configure: SessionScope.() -> Unit = {}): MindlayerSession =
        error("Mindlayer v1 — C2 lands behaviour")

    // ── Legacy HIDDEN methods (impl-only; invisible to new source) ─────────

    @Deprecated(
        message = "Use infer { session(...) } / openSession { } (Mindlayer v1). " +
            "Kept visible for testers and consumers that need explicit session lifecycle.",
        replaceWith = ReplaceWith("openSession { /* configure */ }"),
        level = DeprecationLevel.WARNING,
    )
    suspend fun createSession(
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String

    @Deprecated(
        message = "Use inferRealtime(sessionId, text) (Mindlayer v1)",
        replaceWith = ReplaceWith("inferRealtime(sessionId, text)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chat(sessionId: String, text: String): InferenceHandle

    @Deprecated(
        message = "Use inferRealtime(sessionId, text, image) (Mindlayer v1)",
        replaceWith = ReplaceWith("inferRealtime(sessionId, text, MediaPart.image(bitmap))"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chatWithImage(sessionId: String, text: String, bitmap: Bitmap): InferenceHandle

    @Deprecated(
        message = "Use inferRealtime(sessionId, text, audio) (Mindlayer v1)",
        replaceWith = ReplaceWith("inferRealtime(sessionId, text, MediaPart.audio(audioFile))"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chatWithAudio(sessionId: String, text: String, audioFile: File): InferenceHandle

    @Deprecated(
        message = "Use inferRealtime(sessionId, text, *parts) (Mindlayer v1)",
        replaceWith = ReplaceWith("inferRealtime(sessionId, text, *parts)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chatWithMedia(
        sessionId: String,
        text: String,
        vararg parts: com.adsamcik.mindlayer.MediaPart,
    ): InferenceHandle

    @Deprecated(
        message = "Use infer { } (Mindlayer v1)",
        replaceWith = ReplaceWith("infer { session(sessionId); prompt(text); media(*media) }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun inferRealtime(
        sessionId: String,
        text: String,
        vararg media: com.adsamcik.mindlayer.MediaPart,
    ): InferenceHandle

    @Deprecated(
        message = "Use ask { } / infer { } (Mindlayer v1). " +
            "Kept visible for testers and consumers that drive named sessions explicitly.",
        replaceWith = ReplaceWith("ask(text)"),
        level = DeprecationLevel.WARNING,
    )
    suspend fun inferAsync(
        sessionId: String,
        text: String,
        vararg media: com.adsamcik.mindlayer.MediaPart,
    ): String

    @Deprecated(
        message = "Use infer { } with tools(...) (Mindlayer v1)",
        replaceWith = ReplaceWith("infer { session(sessionId); prompt(text); media(*media) }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun inferTools(
        sessionId: String,
        text: String,
        vararg media: com.adsamcik.mindlayer.MediaPart,
    ): InferenceHandle

    @Deprecated(
        message = "Use vector(text) / embed { } (Mindlayer v1)",
        replaceWith = ReplaceWith("vector(text, task)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun embedOne(
        text: String,
        task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
        modelId: String? = null,
        outputDim: Int? = null,
        normalize: Boolean = true,
        tag: String? = null,
    ): FloatArray

    @Deprecated(
        message = "Use embed { } (Mindlayer v1)",
        replaceWith = ReplaceWith("embed { items.forEach { add(it) } }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun embedMany(items: List<EmbeddingConfig>): EmbeddingBatch

    @Deprecated(
        message = "Use embed { } / vectors(...) (Mindlayer v1)",
        replaceWith = ReplaceWith("embed { texts.forEach { add(it, task) } }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun embedMany(
        texts: List<String>,
        task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
        modelId: String? = null,
    ): EmbeddingBatch

    @Deprecated(
        message = "Use embedOne(text) (Mindlayer v1)",
        replaceWith = ReplaceWith("embedOne(text)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun embed(text: String): FloatArray

    @Deprecated(
        message = "Use embed { } (Mindlayer v1)",
        replaceWith = ReplaceWith("embed { add(config) }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun embed(config: EmbeddingConfig): com.adsamcik.mindlayer.EmbeddingResult

    @Deprecated(
        message = "Use embedMany(configs) (Mindlayer v1)",
        replaceWith = ReplaceWith("embedMany(configs)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun embedBatch(configs: List<EmbeddingConfig>): List<com.adsamcik.mindlayer.EmbeddingResult>

    @Deprecated(
        message = "Use embedMany(configs) (Mindlayer v1)",
        replaceWith = ReplaceWith("embedMany(configs)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun embedBatchLarge(configs: List<EmbeddingConfig>): List<com.adsamcik.mindlayer.EmbeddingResult>

    @Deprecated(
        message = "Use ask(text) (Mindlayer v1)",
        replaceWith = ReplaceWith("ask(text)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chat(text: String): String

    @Deprecated(
        message = "Use describe(text, image) (Mindlayer v1)",
        replaceWith = ReplaceWith("describe(text, image)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chat(text: String, image: Bitmap): String

    @Deprecated(
        message = "Use inferAsync(sessionId, text) (Mindlayer v1)",
        replaceWith = ReplaceWith("inferAsync(sessionId, text)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chatOnce(sessionId: String, text: String): String

    @Deprecated(
        message = "Use inferAsync(sessionId, text, image) (Mindlayer v1)",
        replaceWith = ReplaceWith("inferAsync(sessionId, text, MediaPart.image(bitmap))"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chatWithImageOnce(sessionId: String, text: String, bitmap: Bitmap): String

    @Deprecated(
        message = "Use inferAsync(sessionId, text, audio) (Mindlayer v1)",
        replaceWith = ReplaceWith("inferAsync(sessionId, text, MediaPart.audio(audioFile))"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun chatWithAudioOnce(sessionId: String, text: String, audioFile: File): String

    @Deprecated(
        message = "Use infer { }.events.textDeltas() (Mindlayer v1)",
        replaceWith = ReplaceWith("infer { session(sessionId); prompt(text) }.events"),
        level = DeprecationLevel.HIDDEN,
    )
    fun chatTextFlow(sessionId: String, text: String): kotlinx.coroutines.flow.Flow<String>

    @Deprecated(
        message = "Use infer { }.events (Mindlayer v1)",
        replaceWith = ReplaceWith("infer { session(sessionId); prompt(text) }.events"),
        level = DeprecationLevel.HIDDEN,
    )
    fun chatFullTextFlow(sessionId: String, text: String): kotlinx.coroutines.flow.Flow<String>

    @Deprecated(
        message = "Use ask(text) (Mindlayer v1)",
        replaceWith = ReplaceWith("ask(text)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun generate(
        text: String,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String

    @Deprecated(
        message = "Use describe(text, bitmap) (Mindlayer v1)",
        replaceWith = ReplaceWith("describe(text, bitmap)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun generateWithImage(
        text: String,
        bitmap: Bitmap,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String

    @Deprecated(
        message = "Use transcribe(text, audioFile) (Mindlayer v1)",
        replaceWith = ReplaceWith("transcribe(text, audioFile)"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun generateWithAudio(
        text: String,
        audioFile: File,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String

    @Deprecated(
        message = "Use ocrSession { } (Mindlayer v1). Kept visible for consumers that " +
            "drive the legacy OcrSession type (e.g. via OcrImageAnalyzer in :sdk-camerax).",
        replaceWith = ReplaceWith("ocrSession { profile(profile) }"),
        level = DeprecationLevel.WARNING,
    )
    suspend fun ocrRealtime(
        profile: OcrProfile,
        configure: OcrSessionConfigBuilder.() -> Unit = {},
    ): OcrSession

    @Deprecated(
        message = "Use ocrSession { } (Mindlayer v1)",
        replaceWith = ReplaceWith("ocrSession { config(config) }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun ocrRealtime(config: com.adsamcik.mindlayer.OcrSessionConfig): OcrSession

    @Deprecated(
        message = "Use ocr { } / readText(...) (Mindlayer v1). Kept visible for consumers " +
            "that need the legacy OcrImageResult shape with explicit ocrDurationMs / " +
            "llmDurationMs / extractionFields fields.",
        replaceWith = ReplaceWith("ocr { image(bytes, mimeType) }.awaitResult()"),
        level = DeprecationLevel.WARNING,
    )
    suspend fun ocrAsync(
        bytes: ByteArray,
        mimeType: String,
        options: com.adsamcik.mindlayer.OcrImageOptions = com.adsamcik.mindlayer.OcrImageOptions(),
    ): com.adsamcik.mindlayer.OcrImageResult

    @Deprecated(
        message = "Use ocrSession { } (Mindlayer v1)",
        replaceWith = ReplaceWith("ocrSession { profile(profile) }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun ocrSession(
        profile: OcrProfile,
        configure: OcrSessionConfigBuilder.() -> Unit = {},
    ): OcrSession

    @Deprecated(
        message = "Use ocrSession { } (Mindlayer v1)",
        replaceWith = ReplaceWith("ocrSession { config(config) }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun ocrSession(config: com.adsamcik.mindlayer.OcrSessionConfig): OcrSession

    @Deprecated(
        message = "Use ocr { } / readText(...) (Mindlayer v1)",
        replaceWith = ReplaceWith("ocr { bytes(bytes, mimeType); options(options) }"),
        level = DeprecationLevel.HIDDEN,
    )
    suspend fun ocrImage(
        bytes: ByteArray,
        mimeType: String,
        options: com.adsamcik.mindlayer.OcrImageOptions = com.adsamcik.mindlayer.OcrImageOptions(),
    ): com.adsamcik.mindlayer.OcrImageResult

    companion object {
        /**
         * Construct a [Mindlayer] client bound to the on-device service and
         * begin the asynchronous bind handshake.
         *
         * @param observer optional observability hook (Spike-E §3). As of C2 it
         *   is wired into the canonical call path: every [infer] / [ocr] /
         *   [ocrSession] / [embed] is bracketed by one
         *   [MindlayerObserver.onCallStart] / [MindlayerObserver.onCallEnd]
         *   pair carrying redacted params only (sizes / shapes / flags).
         */
        fun connect(
            context: Context,
            historyPolicy: HistoryPolicy = HistoryPolicy.METADATA_ONLY,
            observer: MindlayerObserver? = null,
        ): Mindlayer {
            val mgr = ConnectionManager()
            mgr.connect(context)
            val store = HistoryStore(context, historyPolicy)
            return MindlayerImpl(mgr, store).also { it.observer = observer }
        }
    }
}
