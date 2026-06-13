package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
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
 * # No legacy surface
 *
 * As of Mindlayer v1 (1.0.0-alpha.2) every v0.x `@Deprecated` carry-over has
 * been removed — the canonical builder methods ([infer], [ocr], [ocrSession],
 * [embed]) and the high-level helpers are the only inference / OCR / embedding
 * entry points. The canonical surface is a strict superset of the removed
 * methods (see `docs/SDK_V1_MIGRATION.md`):
 *  - [embed] / [vector] / [vectors] carry per-item `modelId` / `outputDim` /
 *    `normalize` config on [EmbeddingItem] and full per-item telemetry (`dim`,
 *    `modelId`, `tokenCount`, `truncated`, `backend`, `durationMs`) on
 *    [EmbeddingResultItem] (replacing `embedOne`/`embedMany`/`embedBatch*`/`embed`).
 *  - [ocr] / [ocrSession] return an [OcrResult] enriched with per-pass timing
 *    (`metrics.ocrDurationMs` / `llmDurationMs`), `backend`, `extractionFields`,
 *    and per-line rotated `boundingBoxQuad` / `orientationDegrees` (replacing
 *    `ocrRealtime`/`ocrAsync`); [OcrHandle.MultiFrame] takes raw Y-plane frames.
 *  - [infer] streams token-by-token and accepts tool sessions
 *    ([SessionScope.toolsJson]) and opaque context ([SessionScope.extraContextJson])
 *    (replacing `chat*`/`*Once`/`infer*`/`generate*`/`createSession`).
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

    /**
     * Transcribe the speech in [audio] using Gemma 4's documented ASR
     * prompt — no newlines, digits for numbers.
     * Source: https://ai.google.dev/gemma/docs/capabilities/audio
     *
     * @param language Human-readable language name passed verbatim into
     *   the recommended `"in {LANGUAGE} into {LANGUAGE} text"` phrasing
     *   (e.g. `"English"`, `"German"`). When `null` (the default), the
     *   model receives the "in its original language" variant and
     *   autodetects.
     *
     * Audio is capped at
     * [com.adsamcik.mindlayer.GemmaAudioSpec.MAX_DURATION_MS] per clip;
     * longer recordings must be chunked caller-side. See `docs/AUDIO.md`
     * for the full supported/not-yet-supported split.
     *
     * Equivalent to `transcribe(GemmaAudioPrompts.transcriptionPrompt(language), audio, configure)`.
     */
    suspend fun transcribe(
        audio: File,
        language: String? = null,
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

    companion object {
        /**
         * Construct a [Mindlayer] client bound to the on-device service and
         * begin the asynchronous bind handshake.
         *
         * **Reuse one instance per process.** A single [Mindlayer] routes *all*
         * features — [infer], [ocr], [ocrSession], [embed] — through one
         * binding. Creating a separate client per feature (e.g. one per
         * ViewModel) opens a *separate* Binder connection, [registerClient]
         * handshake, and [connectionState] for each, so consent/resume must be
         * driven independently on each. Prefer [shared] when several call sites
         * need the service; reserve [connect] for cases that genuinely need an
         * isolated client (a distinct [historyPolicy]/[observer], or an
         * independent disconnect lifetime).
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

        @Volatile
        private var sharedInstance: Mindlayer? = null

        @Volatile
        private var sharedHistoryPolicy: HistoryPolicy? = null

        private val sharedLock = Any()

        /**
         * Test seam: when non-null, [shared] builds the singleton from this
         * factory instead of [connect], so the singleton lifecycle can be
         * exercised without a real Binder bind / history DB. Production never
         * sets this.
         */
        @VisibleForTesting
        internal var sharedConnectFactory: ((Context, HistoryPolicy) -> Mindlayer)? = null

        /**
         * Returns a **process-wide singleton** [Mindlayer] so every feature
         * (LLM, OCR, embeddings) shares one binding. This is the "can't
         * half-share" entry point: call it from each ViewModel / coordinator and
         * they all get the same client, the same [connectionState], and a single
         * consent/resume flow.
         *
         * Lifecycle:
         * - The shared client lives for the **process** (it is *not* reference
         *   counted), bound to the application context. Tear it down only at app
         *   shutdown or in tests via [disconnectShared] — do **not** call
         *   [disconnect] on it (that would strand the singleton; as a safety net
         *   the next [shared] call rebuilds a disconnected one).
         * - [historyPolicy] is honored on the **first** call that creates the
         *   singleton. Because the policy is privacy-sensitive, a later call that
         *   passes a *different* policy throws [IllegalStateException] rather
         *   than silently ignoring it — re-initialize via [disconnectShared]
         *   first if you must change it.
         * - There is intentionally no `observer` parameter: a process-lifetime
         *   singleton must not retain a UI-scoped [MindlayerObserver]. Use
         *   [connect] if you need per-instance observability.
         *
         * Resuming after consent: if a call fails with `CONSENT_REQUIRED` (the
         * client lands in [ConnectionState.REJECTED_NOT_APPROVED]), just call
         * [awaitConnected] again once the user has granted consent (e.g. from an
         * "Enable AI" button) — it rebinds once and re-asks the service. No
         * separate "resume" call is needed.
         *
         * Caveat: "shared" is **per Android process**. An app that hosts
         * consumers in multiple processes still gets one client per process.
         */
        fun shared(
            context: Context,
            historyPolicy: HistoryPolicy = HistoryPolicy.METADATA_ONLY,
        ): Mindlayer {
            sharedInstance?.let { existing ->
                if (existing.connectionState.value != ConnectionState.DISCONNECTED) {
                    requireMatchingSharedPolicy(historyPolicy)
                    return existing
                }
            }
            return synchronized(sharedLock) {
                val existing = sharedInstance
                if (existing != null &&
                    existing.connectionState.value != ConnectionState.DISCONNECTED
                ) {
                    requireMatchingSharedPolicy(historyPolicy)
                    existing
                } else {
                    val appContext = context.applicationContext
                    val created = sharedConnectFactory?.invoke(appContext, historyPolicy)
                        ?: connect(appContext, historyPolicy)
                    sharedInstance = created
                    sharedHistoryPolicy = historyPolicy
                    created
                }
            }
        }

        /**
         * Disconnects and clears the process-shared client created by [shared].
         * The next [shared] call builds a fresh one. Use at app shutdown or in
         * tests; ordinary feature code should not need it.
         */
        fun disconnectShared() {
            synchronized(sharedLock) {
                sharedInstance?.disconnect()
                sharedInstance = null
                sharedHistoryPolicy = null
            }
        }

        private fun requireMatchingSharedPolicy(requested: HistoryPolicy) {
            val current = sharedHistoryPolicy
            if (current != null && current != requested) {
                throw IllegalStateException(
                    "Mindlayer.shared() is already initialized with historyPolicy=$current " +
                        "but was called with $requested. The process-shared client uses a single " +
                        "history policy; pass the same value, or call disconnectShared() first to " +
                        "re-initialize.",
                )
            }
        }
    }
}
