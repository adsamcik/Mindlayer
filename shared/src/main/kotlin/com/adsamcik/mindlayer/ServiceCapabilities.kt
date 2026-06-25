package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Snapshot of what features the connected Mindlayer service supports and
 * what limits it enforces for the calling app.
 *
 * # Probing
 *
 * The SDK calls [com.adsamcik.mindlayer.IMindlayerService.getCapabilities]
 * once after [com.adsamcik.mindlayer.sdk.ConnectionManager.awaitConnected]
 * succeeds and caches the result. Old services that don't implement the
 * method throw `NoSuchMethodError` / `AbstractMethodError` at the binder
 * stub; the SDK catches and falls back to a `v0Capabilities` baseline that
 * advertises only the v0.1 surface.
 *
 * # Forward compatibility
 *
 * - [schemaVersion] is the **first** field as required by the
 *   `docs/architecture/AIDL_STABILITY.md` schemaVersion convention. Bumping it lets a
 *   new SDK soft-degrade against an old service that emits a v0 parcelable.
 * - [supportedFeatures] is a `Set<String>` rather than a bitfield so the
 *   feature space can grow indefinitely; old SDKs ignore unknown strings.
 * - Numeric limits are wire-stable: callers must not assume any minimum.
 *
 * # Per-caller scoping
 *
 * Today every external caller sees the same capability set. Once the
 * third-party caller story (`docs/project/THIRD_PARTY_FUTURE.md`) lands,
 * [supportedFeatures] and the numeric limits will be filtered by the
 * caller's `AllowlistEntry.trustTier`. Self-UID dashboard always sees
 * effectively-infinite quotas (signalled by [Int.MAX_VALUE] / [Long.MAX_VALUE]).
 *
 * @property schemaVersion Wire-stable schema version. Currently `2` because
 *   the embedding limit fields were appended during the pre-1.0 compatibility
 *   window. Future layout changes require a v2 parcelable or new method per
 *   AIDL stability rules.
 * @property apiVersion Logical API version. Bumped whenever the AIDL
 *   surface gains a method — old SDKs use this to gate optional behavior.
 * @property supportedFeatures Stable string identifiers for available
 *   features. See `docs/architecture/AIDL_STABILITY.md` § Capability gating for the
 *   canonical registry.
 * @property pipeProtocol Pipe-stream protocol identifier (`mindlayer.stream.v1`
 *   today). The SDK validates this in [com.adsamcik.mindlayer.sdk.TokenStreamReader].
 * @property maxFrameBytes Maximum size of a single pipe frame in bytes.
 * @property maxToolRounds Maximum number of tool-call rounds per inference
 *   before the service forces a `tool_call_limit` finish.
 * @property maxToolArgsLen Maximum bytes of tool-call arguments JSON before
 *   truncation (which yields invalid JSON the SDK sees as a tool-call error).
 * @property maxRequestsPerMinute Per-UID rate limit cap on AIDL methods
 *   (cost-weighted; cheap calls consume less).
 * @property maxConcurrentInferences Per-UID concurrent inference cap.
 * @property maxConcurrentSessions Per-device concurrent session cap derived
 *   from the device memory tier.
 * @property maxSessionExpirationMs Maximum session expiration the service
 *   accepts in [com.adsamcik.mindlayer.SessionConfig.expirationMs].
 * @property maxMediaPartsPerRequest Maximum media attachments per
 *   `infer` call. Currently `1` (image XOR audio); will increase with
 *   the v0.4 `inferMulti` work.
 * @property maxTotalMediaBytesPerRequest Aggregate cap on media bytes per
 *   request, summed across all attachments.
 */
@Parcelize
data class ServiceCapabilities(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val apiVersion: Int,
    val supportedFeatures: Set<String>,
    val pipeProtocol: String,
    val maxFrameBytes: Int,
    val maxToolRounds: Int,
    val maxToolArgsLen: Int,
    val maxRequestsPerMinute: Int,
    val maxConcurrentInferences: Int,
    val maxConcurrentSessions: Int,
    val maxSessionExpirationMs: Long,
    val maxMediaPartsPerRequest: Int,
    val maxTotalMediaBytesPerRequest: Long,
    val maxEmbeddingBatchInline: Int = 0,
    val maxEmbeddingBatchShm: Int = 0,
    val maxEmbeddingBatchTotal: Int = 0,
    val maxEmbeddingInputBytes: Long = 0L,
    val embeddingModelIds: List<String> = emptyList(),
    val embeddingDims: List<Int> = emptyList(),
) : Parcelable {

    /**
     * True when the service advertises [feature] in [supportedFeatures].
     * Use this rather than `feature in capabilities.supportedFeatures` so
     * intent at the call site is obvious and the capability vocabulary is
     * lookup-friendly when grepping.
     */
    fun supports(feature: String): Boolean = feature in supportedFeatures

    companion object {
        /**
         * Current parcelable schema version. Bumped when the parcelable
         * layout itself changes (which is itself a wire-break — see
         * `docs/architecture/AIDL_STABILITY.md`).
         */
        const val CURRENT_SCHEMA_VERSION: Int = 2

        // ---- Canonical feature flag strings ---------------------------------
        // Append-only registry. Document each new flag in
        // docs/architecture/AIDL_STABILITY.md § Capability gating.

        /**
         * Service throws wire-prefixed [SecurityException]s that the SDK
         * chokepoint translates to [com.adsamcik.mindlayer.sdk.MindlayerException]
         * with a typed [com.adsamcik.mindlayer.shared.MindlayerErrorCode].
         */
        const val FEATURE_TYPED_ERRORS: String = "typed_errors"

        /**
         * SDK can validate the pipe `StreamHeader.protocol` against
         * `mindlayer.stream.v1` and reject mismatches.
         */
        const val FEATURE_PIPE_PROTO_V1: String = "pipe_proto_v1"

        /**
         * Service streams responses as length-prefixed JSON frames using the
         * v1 envelope shape.
         */
        const val FEATURE_PIPE_STREAM_V1: String = "pipe_stream_v1"

        /** Service supports image + audio media via [SharedMemory]-backed PFDs. */
        const val FEATURE_SHARED_MEMORY_MEDIA: String = "shared_memory_media"

        /** Service supports tool calling with `submitToolResult` round-trips. */
        const val FEATURE_TOOL_RESULTS: String = "tool_results"

        /**
         * Service accepts `SessionConfig.initialHistory` for OOM/eviction
         * recovery via the SDK's `SessionRecovery` helper.
         */
        const val FEATURE_HISTORY_RECOVERY: String = "history_recovery"

        /**
         * Service supports the `structured_output` envelope on
         * `extraContextJson` for JSON-schema-bounded responses.
         */
        const val FEATURE_STRUCTURED_OUTPUT: String = "structured_output"

        // ---- Future feature flags (documented but not advertised yet) -------
        // Each becomes part of supportedFeatures when the underlying
        // implementation lands. SDKs should only call into the corresponding
        // method when the flag is present.

        /** v0.4: `inferMulti(meta, List<MediaPart>, pfd)` is implemented. */
        const val FEATURE_MEDIA_LIST: String = "media_list"

        /** v0.4: `cancelInferenceV2` / `submitToolResultV2` return tri-state results. */
        const val FEATURE_DETAILED_CANCEL: String = "detailed_cancel"

        /** v0.4: `prewarmAndAwait(backend)` is non-`oneway` and waits for engine init. */
        const val FEATURE_PREWARM_AWAIT: String = "prewarm_await"

        /** v0.4: `getDiagnosticsTyped()` returns a typed snapshot parcelable. */
        const val FEATURE_TYPED_DIAGNOSTICS: String = "typed_diagnostics"

        /** v0.4: `subscribeEvictionNotices(IClientCallback)` is implemented. */
        const val FEATURE_EVICTION_CALLBACK: String = "eviction_callback"

        /** v0.5: pipe emits `mindlayer.stream.v2` with batched token deltas. */
        const val FEATURE_TOKEN_BATCH: String = "token_batch"

        /**
         * v1.1: Gemma 4 thinking mode. When the caller creates a session
         * with `extraContextJson.thinking = { "enable": true }`, the
         * service:
         *
         *  1. Configures a LiteRT-LM channel that intercepts the model's
         *     `<|channel>thought ... <channel|>` block and routes it
         *     away from the user-visible answer.
         *  2. Prepends the Gemma `<|think|>` system marker so the model
         *     enters thinking mode before producing the answer.
         *  3. Negotiates [com.adsamcik.mindlayer.shared.StreamProtocol.V3]
         *     on the pipe so the SDK reader can decode
         *     [com.adsamcik.mindlayer.shared.StreamEventType.THOUGHT_DELTA] /
         *     [com.adsamcik.mindlayer.shared.StreamEventType.THOUGHT_DELTA_BATCH]
         *     events.
         *
         * Thoughts are routed away from the user-visible answer at
         * the LiteRT-LM channel level and are never written to the
         * SDK history database. **KV-cache caveat (v1.1):** LiteRT-LM
         * retains channel content in the model's KV cache across user
         * turns by default — the Gemma "strip thoughts before next
         * turn" guidance is **not** satisfied automatically in this
         * release. A follow-up PR will enable
         * `ExperimentalFlags.filterChannelContentFromKvCache` after
         * verifying its behaviour around tool-round boundaries (Gemma
         * requires thoughts to stay in context across tool calls within
         * one turn). Callers with long thinking conversations should
         * recycle sessions to keep the working context fresh. See
         * `docs/engine/THINKING.md` for details.
         *
         * Old SDKs that pre-date this flag can still create sessions:
         * the opt-in JSON is ignored and they see a normal V1/V2
         * stream.
         *
         * Old services that don't advertise this flag will silently
         * ignore the `thinking` opt-in (no channel configured, no V3
         * negotiation, no THOUGHT_DELTA events) — capability-aware SDKs
         * should not assume the answer is thought-free unless they see
         * this flag.
         */
        const val FEATURE_THINKING_MODE: String = "thinking_mode"

        /** v0.6: durable deferred inference with fetch and completion callback. */
        const val FEATURE_DEFERRED_INFERENCE: String = "deferred_inference"

        /** Service exposes text embedding endpoints (embed, embedBatch, embedBatchShm, deferred). */
        const val FEATURE_EMBEDDINGS: String = "embeddings"

        // ---- v0.8 multi-frame OCR capability flags ------------------------
        // Numeric caps for OCR are advertised via the separate [OcrLimits]
        // parcelable fetched by [IMindlayerService.getOcrLimits], NOT as new
        // fields on this parcelable (which is wire-frozen per
        // docs/architecture/AIDL_STABILITY.md).

        /**
         * v0.8: multi-frame OCR session API (`createOcrSession`, `pushOcrFrame`,
         * `streamOcrEvents`, `finalizeOcrSession`, `closeOcrSession`,
         * `getOcrSessionState`, `getOcrLimits`). Pipe protocol
         * [StreamProtocol.OCR_V1]. Per-field weighted voting fusion.
         */
        const val FEATURE_OCR_SESSION: String = "ocr_session"

        /**
         * v0.8: service runs frame-quality presort (blur, exposure, motion,
         * dedup, text-density) before engine dispatch. When absent, the SDK
         * is expected to do its own presort and only push high-quality
         * frames.
         */
        const val FEATURE_OCR_PRESORT_SERVICE_SIDE: String = "ocr_presort_service_side"

        /**
         * v0.8: ZXing barcode anchor runs alongside the OCR pipeline; GTINs
         * surfaced in the evidence package's `barcode_anchor` field so
         * structured extraction can lock product identity early.
         */
        const val FEATURE_OCR_BARCODE_ANCHOR: String = "ocr_barcode_anchor"

        /**
         * v0.8: OCR extraction emits per-region bounding boxes alongside
         * recognized text in the evidence package (enables layout-aware
         * downstream consumers).
         */
        const val FEATURE_OCR_BOUNDING_BOXES: String = "ocr_bounding_boxes"

        /**
         * v0.9: single-image OCR — synchronous `ocrImage` AIDL method that
         * accepts one [MediaPart] and returns an [OcrImageResult] in the
         * same call. Opt-in LLM extraction via
         * [OcrImageOptions.runLlmExtraction]. Bypasses the multi-frame
         * session ceremony (`createOcrSession` / `pushOcrFrame` /
         * `streamOcrEvents` / `finalizeOcrSession`) for callers that just
         * have one image and want recognized lines back.
         *
         * Advertised only when the PaddleOCR engine is `Ready` *and* the
         * production-readiness gate is on — same conditions as
         * [FEATURE_OCR_SESSION]. SDKs that don't see this flag should not
         * call `ocrImage` (the binder stub raises
         * `NoSuchMethodError` / `AbstractMethodError` on old services).
         */
        const val FEATURE_OCR_IMAGE_ONESHOT: String = "ocr_image_oneshot"

        /**
         * v0.8.1: lightweight `ping()` health-check endpoint.
         *
         * Returns a [com.adsamcik.mindlayer.HealthCheck] parcelable
         * with server timestamp, service uptime, apiVersion, and
         * per-engine state. Designed for low-overhead liveness probes,
         * watchdog checks, and clock-skew detection.
         *
         * Distinct from [getStatus] (heavier dashboard snapshot) and
         * [getCapabilities] (one-time wire handshake). Bypasses the
         * allowlist gate so co-signed peers that still need consent can
         * confirm the service is alive; charges zero rate-limit cost.
         *
         * Old services that don't advertise this flag also won't
         * implement `ping()` — the SDK catches `NoSuchMethodError` /
         * `AbstractMethodError` and falls back to a heavier `getStatus`
         * call (or treats the connection as alive if `getStatus` also
         * fails).
         */
        const val FEATURE_HEALTH_CHECK: String = "health_check"

        /**
         * v1.0: single-clip audio input accepted via `infer(...)` /
         * `inferMulti(...)` with one [com.adsamcik.mindlayer.MediaPart]
         * of kind [com.adsamcik.mindlayer.MediaPart.KIND_AUDIO] (or
         * the legacy `AudioTransfer` shape). Clips are capped at
         * [com.adsamcik.mindlayer.GemmaAudioSpec.MAX_DURATION_MS] and
         * accept the MIME types listed in `docs/engine/AUDIO.md`.
         *
         * **Single-clip only.** Multi-audio prompts are not advertised;
         * `IpcInputValidator` rejects requests with `audioCount > 1`
         * until the multi-media engine path lands. SDKs that need to
         * stitch multiple clips must issue one inference per clip and
         * fold results client-side.
         *
         * Co-existing capabilities `FEATURE_SHARED_MEMORY_MEDIA` (the
         * transport) and `FEATURE_MEDIA_LIST` (the `inferMulti` shape)
         * remain orthogonal — `FEATURE_AUDIO_INPUT` specifically tells
         * callers that the engine *consumes* the audio modality and
         * respects the Gemma audio contract documented in
         * `docs/engine/AUDIO.md`.
         */
        const val FEATURE_AUDIO_INPUT: String = "audio_input"

        // ---- Future OCR flags (documented but not yet advertised) ---------

        /**
         * Future: fusion uses LiteRT-LM per-token logprobs instead of
         * verbalized confidence. Gated on upstream LiteRT-LM Kotlin API
         * exposing logprobs.
         */
        const val FEATURE_OCR_LOGPROB_FUSION: String = "ocr_logprob_fusion"

        /**
         * Future: service routes N frames as a single multi-image Gemma
         * prompt (post litert-lm #1874 resolution) rather than N sequential
         * single-image inferences. Wire surface unchanged; only quality +
         * latency change.
         */
        const val FEATURE_OCR_TRUE_MULTI_IMAGE: String = "ocr_true_multi_image"

        /**
         * Baseline capabilities advertised by an old service that predates
         * [IMindlayerService.getCapabilities]. Used by the SDK fallback path
         * when the AIDL call throws [NoSuchMethodError] / [AbstractMethodError].
         */
        @JvmStatic
        fun v0Baseline(): ServiceCapabilities = ServiceCapabilities(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            apiVersion = 0,
            supportedFeatures = setOf(
                FEATURE_PIPE_STREAM_V1,
                FEATURE_SHARED_MEMORY_MEDIA,
                FEATURE_TOOL_RESULTS,
                FEATURE_HISTORY_RECOVERY,
                FEATURE_STRUCTURED_OUTPUT,
            ),
            pipeProtocol = "mindlayer.stream.v1",
            // Conservative defaults for unknown servers.
            maxFrameBytes = 1_048_576,
            maxToolRounds = 1,
            maxToolArgsLen = 16 * 1024,
            maxRequestsPerMinute = 30,
            maxConcurrentInferences = 1,
            maxConcurrentSessions = 1,
            maxSessionExpirationMs = 7L * 24 * 60 * 60 * 1000,
            maxMediaPartsPerRequest = 1,
            maxTotalMediaBytesPerRequest = 100L * 1024 * 1024,
            maxEmbeddingBatchInline = 0,
            maxEmbeddingBatchShm = 0,
            maxEmbeddingBatchTotal = 0,
            maxEmbeddingInputBytes = 0L,
            embeddingModelIds = emptyList(),
            embeddingDims = emptyList(),
        )

        /** Baseline for v1 capability parcels before embedding limit fields existed. */
        @JvmStatic
        fun v1Baseline(): ServiceCapabilities = v0Baseline().copy(schemaVersion = 1)
    }
}
