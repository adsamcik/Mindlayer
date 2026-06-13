package com.adsamcik.mindlayer.sdk

/**
 * Why an inference run stopped, surfaced on [InferenceEvent.Done].
 *
 * The service emits a wire string (see [InferenceEvent.Done.finishReason]);
 * [from] maps it onto this stable enum so callers branch on a closed set
 * rather than string-matching. Unknown wire values map to [UNKNOWN].
 */
enum class FinishReason {
    /** The model emitted a natural stop / end-of-turn token. */
    STOP,

    /** Generation hit the configured `maxTokens` budget. */
    MAX_TOKENS,

    /** The model requested one or more tool calls and is awaiting results. */
    TOOL_CALLS,

    /** The per-request tool-round cap was reached. */
    TOOL_CALL_LIMIT,

    /** Generation terminated due to an error frame. */
    ERROR,

    /** The call was cancelled by the caller or a lost connection. */
    CANCELLED,

    /** Wire value not recognised by this SDK version. */
    UNKNOWN,
    ;

    companion object {
        /** Map a service wire string onto a [FinishReason]; unrecognised → [UNKNOWN]. */
        fun from(wire: String?): FinishReason = when (wire?.lowercase()) {
            "stop", "end_of_turn", "eos" -> STOP
            "max_tokens", "length" -> MAX_TOKENS
            "tool_calls", "tool_call" -> TOOL_CALLS
            "tool_call_limit" -> TOOL_CALL_LIMIT
            "error" -> ERROR
            "cancelled", "canceled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/**
 * Performance snapshot for a completed inference, OCR, or embedding call.
 *
 * Distinct from the streaming [InferenceEvent.Metrics] frame: this is the
 * terminal, caller-facing aggregate carried by [InferenceEvent.Done],
 * [InferenceResult], and [OcrResult]. All fields are nullable because the
 * service may omit them (e.g. on cached or short calls).
 */
data class Metrics(
    val prefillToksPerSec: Float? = null,
    val decodeToksPerSec: Float? = null,
    val promptTokens: Int? = null,
    val generatedTokens: Int? = null,
    val totalDurationMs: Long? = null,
    val thermalBand: String? = null,
    val ocrDurationMs: Long? = null,
    val llmDurationMs: Long? = null,
    val backend: String? = null,
) {
    companion object {
        /** A metrics object with no fields populated. */
        val EMPTY: Metrics = Metrics()
    }
}
