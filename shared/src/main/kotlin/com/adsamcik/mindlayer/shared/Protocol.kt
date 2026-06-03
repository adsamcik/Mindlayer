package com.adsamcik.mindlayer.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire protocol for pipe-based streaming
// Frame format: 4-byte LE u32 length + UTF-8 JSON payload

@Serializable
data class StreamEvent(
    val seq: Long,
    val type: String,
    val tsMs: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
)

object StreamEventType {
    const val TOKEN_DELTA = "token_delta"

    /**
     * v0.5 batched token delta. Payload contains:
     *  - `texts`: JSON array of token text fragments in emission order.
     *
     * Per-token sequence numbers are **synthesized** by the reader: the
     * outer envelope's `seq` is the seq of the LAST token in the batch,
     * and the reader assigns earlier-token seqs by counting backward.
     * Saves wire bytes; documented as the contract for batched streams.
     *
     * Only emitted on streams whose [StreamHeader.protocol] is
     * `mindlayer.stream.v2` or `mindlayer.stream.v3`. v1 streams must
     * NEVER carry this event type (old readers see it as
     * `InferenceEvent.Unknown` and silently drop the contained text —
     * that's the only place silent text loss is possible, and the
     * v1/v2 split prevents it).
     */
    const val TOKEN_DELTA_BATCH = "token_delta_batch"

    /**
     * v1.1 Gemma 4 thinking-mode delta. Carries one fragment of the
     * model's internal reasoning trace (the content the model produced
     * inside its `<|channel>thought ... <channel|>` block, surfaced
     * separately from the user-visible answer).
     *
     * Payload contains:
     *  - `text`: the thought-text fragment.
     *
     * **Privacy + separation contract:**
     *  - Thought events are NEVER written to the SDK history database
     *    (the service's KV cache also never replays them — LiteRT-LM
     *    keeps channel content out of stored conversation state).
     *  - Thought text is NEVER logged at INFO/DEBUG (only token counts
     *    + delimiter ratios in metrics frames).
     *  - The service emits THOUGHT_DELTA only when the session was
     *    created with `extraContextJson.thinking = { "enable": true }`
     *    AND the negotiated stream protocol is
     *    [StreamProtocol.V3] — capability-gated via
     *    [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_THINKING_MODE].
     *
     * Old readers parsing a v3 stream that asked for thinking will see
     * THOUGHT_DELTA as `InferenceEvent.Unknown` and skip the fragment —
     * matching the documented "answer-only" view that callers get when
     * they opted out of thoughts at the SDK surface.
     */
    const val THOUGHT_DELTA = "thought_delta"

    /**
     * v1.1 batched thinking-mode delta. Mirrors [TOKEN_DELTA_BATCH] but
     * for thought fragments emitted inside the model's reasoning channel.
     * Payload contains:
     *  - `texts`: JSON array of thought-text fragments in emission order.
     *
     * Per-fragment sequence numbers are synthesised by the reader the
     * same way [TOKEN_DELTA_BATCH] does (envelope `seq` is the last
     * fragment's seq; earlier fragments count backwards).
     *
     * Only emitted on [StreamProtocol.V3] streams.
     */
    const val THOUGHT_DELTA_BATCH = "thought_delta_batch"

    const val TOOL_CALL = "tool_call"
    const val METRICS = "metrics"
    /**
     * Terminal error. Payload contains `message`, optional symbolic `code`,
     * and optional stable integer `codeInt` from [MindlayerErrorCode].
     */
    const val ERROR = "error"
    const val DONE = "done"

    // ---- v0.8 multi-frame OCR events --------------------------------------
    //
    // Emitted ONLY on streams whose [StreamHeader.protocol] is
    // [StreamProtocol.OCR_V1]. Token-stream readers must never see them.
    // None of these are terminal — `DONE` / `ERROR` still close the stream.

    /**
     * Service accepted a pushed frame into the engine intake queue.
     * Payload: `{frameId: Long, queueDepth: Int}`.
     */
    const val OCR_FRAME_RECEIVED = "ocr_frame_received"

    /**
     * Service-side presort rejected the frame on quality grounds. Non-terminal.
     * Payload: `{frameId: Long, reason: String, score: Float?}` where reason
     * is one of `blur`, `glare`, `motion`, `duplicate`, `low_text_density`,
     * `low_contrast`.
     */
    const val OCR_FRAME_REJECTED_QUALITY = "ocr_frame_rejected_quality"

    /**
     * Service intake queue saturated; frame discarded with backpressure hint.
     * Payload: `{frameId: Long, queueDepth: Int, retryAfterMs: Long}`.
     */
    const val OCR_FRAME_DROPPED_BUSY = "ocr_frame_dropped_busy"

    /**
     * Engine has picked up the frame and started inference.
     * Payload: `{frameId: Long}`.
     */
    const val OCR_FRAME_PROCESSING = "ocr_frame_processing"

    /**
     * Inference completed for a single frame (non-terminal — the session
     * continues to accept more frames). Payload:
     * `{frameId: Long, parseStatus: String, decodeMs: Long, missingFields: String[]?}`
     * where `parseStatus` is one of `ok`, `malformed_json`, `missing_required_fields`,
     * `empty`. Malformed-JSON and missing-required-fields are NOT terminal —
     * `ERROR` is reserved for session-fatal conditions only.
     */
    const val OCR_FRAME_PROCESSED = "ocr_frame_processed"

    /**
     * A single schema field's value or confidence changed in the running
     * fusion state. Payload:
     * `{frameId: Long, field: String, value: JsonElement,
     *   confidence: Float, evidence_cluster_ids: String[]}`.
     */
    const val OCR_FIELD_UPDATE = "ocr_field_update"

    /**
     * A field passed the K-consecutive-same lock criterion and is frozen
     * for the session (subject to the soft-reopen rule on high-confidence
     * contradiction). Payload:
     * `{field: String, value: JsonElement, confidence: Float,
     *   locked_at_frame_id: Long}`.
     */
    const val OCR_FIELD_LOCKED = "ocr_field_locked"

    /**
     * Current best-effort full result snapshot. Emitted periodically and on
     * caller demand. Payload: `{snapshot: JsonObject, completeness: Float}`
     * where `completeness ∈ [0,1]` is the fraction of required schema fields
     * currently locked.
     */
    const val OCR_RESULT_SNAPSHOT = "ocr_result_snapshot"

    /**
     * Session-level final result. Followed by a terminal [DONE] frame.
     * Payload: `{result: JsonObject, reason: String, frames_processed: Int,
     *   elapsed_ms: Long}` where `reason` is one of
     * `all_required_locked`, `client_finalize`, `no_improvement_timeout`,
     * `max_frames`, `max_duration`.
     */
    const val OCR_RESULT_FINALIZED = "ocr_result_finalized"

    /**
     * v0.9 multi-page realtime OCR — a new page accumulator opened.
     *
     * Emitted only when `OcrSessionConfig.optionsJson.pageBoundaries.enabled`
     * is true. The first page is opened at the same time as the first
     * accepted frame, so `pageIndex == 0` is always emitted at session
     * start; subsequent `OCR_PAGE_STARTED` events fire when the boundary
     * heuristic (Jaccard text overlap + spatial bbox shift + gyro spike,
     * gated by an N-frame stability window) decides the camera has moved
     * to different content.
     *
     * Payload: `{pageIndex: Int, triggerFrameId: Long}` where
     * `triggerFrameId` is the frame whose recognition output caused the
     * boundary detection to fire (or `0` for the implicit first-page
     * open).
     *
     * Non-terminal; the session continues to accept frames.
     */
    const val OCR_PAGE_STARTED = "ocr_page_started"

    /**
     * v0.9 multi-page realtime OCR — a page accumulator was closed off.
     *
     * Emitted only when `OcrSessionConfig.optionsJson.pageBoundaries.enabled`
     * is true. Fires when (a) a boundary is detected and the previous
     * accumulator is finalized before the next [OCR_PAGE_STARTED], or
     * (b) the session itself finalizes — the last open page is closed
     * before [OCR_RESULT_FINALIZED] / [DONE].
     *
     * Payload: `{pageIndex: Int, lines: JsonArray, fullJson: JsonObject?,
     * lineCount: Int, framesContributed: Int}`. `fullJson` is present
     * only when `optionsJson.pageBoundaries.llmExtractPerPage` is true
     * and the page-level LLM extraction produced output; otherwise it is
     * `null` and the consumer relies on `lines` for raw text.
     *
     * Non-terminal; further [OCR_PAGE_STARTED] events may follow, and
     * the session still concludes with one [OCR_RESULT_FINALIZED] event.
     */
    const val OCR_PAGE_FINALIZED = "ocr_page_finalized"

    /**
     * Advisory: client should slow down `pushOcrFrame` calls. Non-terminal.
     * Payload: `{minIntervalMs: Long, reason: String}` where `reason` is
     * one of `thermal`, `queue_pressure`, `decode_lag`, `memory_pressure`.
     */
    const val OCR_THROTTLE_HINT = "ocr_throttle_hint"
}

object StreamProtocol {
    /** v1 pipe protocol — single-token deltas only. */
    const val V1: String = "mindlayer.stream.v1"

    /**
     * v2 pipe protocol — supports [StreamEventType.TOKEN_DELTA_BATCH] and
     * optional `codeInt` on [StreamEventType.ERROR] payloads in
     * addition to v1's events. Negotiated per-stream: writer chooses based
     * on the session's `extraContextJson.token_batch` opt-in;
     * `ServiceCapabilities.FEATURE_TOKEN_BATCH` advertises that the writer
     * is *capable* of emitting v2 when the caller asks.
     */
    const val V2: String = "mindlayer.stream.v2"

    /**
     * v3 pipe protocol — strict superset of [V2] that ALSO carries
     * [StreamEventType.THOUGHT_DELTA] and [StreamEventType.THOUGHT_DELTA_BATCH]
     * for Gemma 4 thinking mode. Negotiated per-stream when the session is
     * created with `extraContextJson.thinking = { "enable": true }`;
     * `ServiceCapabilities.FEATURE_THINKING_MODE` advertises that the
     * service is *capable* of emitting v3 when the caller opts in.
     *
     * Token batching from v2 is preserved on v3 — both can coexist on a
     * single stream (some chunks carry thoughts, others carry answer
     * tokens, both can be batched).
     */
    const val V3: String = "mindlayer.stream.v3"

    /**
     * v0.8 OCR-session pipe protocol — sibling of [V1] / [V2] / [V3], NOT
     * an extension. Carries the `ocr_*` event types defined alongside
     * [StreamEventType.OCR_FRAME_RECEIVED] et al., plus the standard
     * terminal [StreamEventType.DONE] / [StreamEventType.ERROR] frames.
     *
     * **Disjoint from V1/V2/V3**: OCR streams must never carry [StreamEventType.TOKEN_DELTA],
     * [StreamEventType.TOKEN_DELTA_BATCH], or [StreamEventType.THOUGHT_DELTA];
     * chat readers must never see `ocr_*` events. The split keeps
     * `TokenStreamReader` free of OCR type discrimination and the OCR
     * reader free of token-batching state.
     *
     * Capability-gated via [ServiceCapabilities.FEATURE_OCR_SESSION].
     */
    const val OCR_V1: String = "mindlayer.stream.ocr.v1"

    /** All chat-stream protocols this build of the SDK reader can interpret. */
    val SUPPORTED: Set<String> = setOf(V1, V2, V3)

    /** All OCR-stream protocols this build of the SDK reader can interpret. */
    val OCR_SUPPORTED: Set<String> = setOf(OCR_V1)
}

@Serializable
data class StreamHeader(
    val protocol: String = StreamProtocol.V1,
    val requestId: String,
)

