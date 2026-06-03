package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject

/**
 * Canonical, caller-facing inference event stream type (Spike-E §1).
 *
 * This is the single event type emitted from [InferenceHandle.events]. It is
 * the merge of the former wire-frame `MindlayerEvent` and the C1 placeholder
 * `InferenceEvent`: the rename completed in C3 keeps the wire-frame field
 * shape (so the impl, [Conversation], and the existing call sites read the
 * same members) and folds the two names into one.
 *
 * **Event ordering guarantee:**
 * 1. [Started] — exactly once, always first
 * 2. [TextDelta], [ToolCall], [Metrics] — zero or more, in sequence order
 * 3. [Done] or [Error] — exactly one, always last (terminal event)
 *
 * If the flow completes without a terminal event, the inference was cancelled
 * or the service connection was lost.
 *
 * ## C3 deviation from Spike-E §1
 *
 * Spike-E §1 sketched canonical subtypes whose fields differ from the wire
 * frame (`Started(sessionId)`, `Done(finishReason: FinishReason, metrics)`,
 * `Error(code: Int, ...)`). The completed rename keeps the **wire-frame**
 * shape instead — it is what the streaming impl, [Conversation], and the test
 * suite already consume — and adds one compatibility change: [seq] is nullable
 * (`null` when the event was not produced from a streamed pipe frame, e.g. a
 * one-shot bridge). This avoids an ~80-call-site shape rewrite while still
 * unifying the type name.
 */
sealed class InferenceEvent {
    /** Signals that the service has accepted the request and inference is beginning. */
    data class Started(val requestId: String) : InferenceEvent()

    /**
     * An incremental chunk of generated text. Collect and concatenate these
     * for the full response.
     *
     * @property seq Wire-stream sequence number; `null` when this event was
     *   not produced from a streamed pipe frame.
     */
    data class TextDelta(val text: String, val seq: Long? = null) : InferenceEvent()

    /**
     * v1.1: Gemma 4 thinking-mode delta — an incremental chunk of the
     * model's reasoning trace (text the model produced inside its
     * `<|channel>thought ... <channel|>` block, surfaced separately
     * from the user-visible answer).
     *
     * Only emitted on sessions created with the SDK's `enableThinking()`
     * builder hook (or `extraContextJson.thinking = { "enable": true }`
     * at the AIDL boundary) AND when the connected service advertises
     * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_THINKING_MODE].
     * Without that opt-in the model still streams normal [TextDelta]
     * events; thoughts are simply not separated.
     *
     * **Default filtering:** the per-subtype `await*()` terminals
     * (`awaitFullText`, `awaitFirstResult`, …) discard [ThoughtDelta]
     * by default, so callers that don't care about reasoning see only
     * the answer. Callers that DO want to render thoughts can collect
     * [InferenceHandle.events] directly or pipe through
     * [thoughtDeltas] / [answerOnly].
     *
     * @property seq Wire-stream sequence number; `null` when this event
     *   was not produced from a streamed pipe frame.
     */
    data class ThoughtDelta(val text: String, val seq: Long? = null) : InferenceEvent()

    /** The model is requesting a tool invocation. Respond with [Mindlayer.submitToolResult] using [callId]. */
    data class ToolCall(
        val toolName: String,
        val arguments: String,
        val callId: String,
        val seq: Long? = null,
    ) : InferenceEvent()

    /** Performance metrics snapshot emitted periodically during inference. */
    data class Metrics(
        val prefillToksPerSec: Float?,
        val decodeToksPerSec: Float?,
        val thermalBand: String?,
        val seq: Long? = null,
    ) : InferenceEvent()

    /** Terminal event indicating an error. No further events will follow. */
    data class Error(
        val message: String,
        val code: String?,
        val seq: Long? = null,
        val tsMs: Long? = null,
        val codeInt: Int? = null,
    ) : InferenceEvent()

    /** Terminal event indicating successful completion. [fullText] contains the accumulated response if available. */
    data class Done(
        val finishReason: String,
        val fullText: String?,
        val seq: Long? = null,
    ) : InferenceEvent()

    /** An event type not recognised by this SDK version. Safe to ignore. */
    data class Unknown(val type: String, val seq: Long? = null) : InferenceEvent()
}

/**
 * Generic union result for callers that want a single typed return rather than
 * collecting [InferenceHandle.events]. Most callers use the per-subtype
 * `awaitX()` terminals instead.
 */
sealed interface InferenceResult {
    val metrics: Metrics

    data class Text(val text: String, override val metrics: Metrics) : InferenceResult
    data class Structured(val json: JsonObject, override val metrics: Metrics) : InferenceResult
    data class Tools(val calls: List<ToolCall>, override val metrics: Metrics) : InferenceResult
}

/** Project the event stream down to just the text deltas. */
fun Flow<InferenceEvent>.textDeltas(): Flow<String> =
    filterIsInstance<InferenceEvent.TextDelta>().map { it.text }

/**
 * v1.1: project the event stream down to just the Gemma 4 thinking-mode
 * fragments (the model's internal reasoning surface, separated from the
 * user-visible answer). Empty on sessions that did not opt into thinking
 * mode or on services that don't advertise
 * [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_THINKING_MODE].
 */
fun Flow<InferenceEvent>.thoughtDeltas(): Flow<String> =
    filterIsInstance<InferenceEvent.ThoughtDelta>().map { it.text }

/**
 * v1.1: re-emit every event EXCEPT [InferenceEvent.ThoughtDelta]. Use
 * this when a downstream pipeline (display, persistence, tool dispatch)
 * should treat the stream as if thinking mode were not enabled — the
 * model's reasoning is dropped on the floor, only answer tokens,
 * metrics, tool calls, and terminal events pass through.
 */
fun Flow<InferenceEvent>.answerOnly(): Flow<InferenceEvent> =
    filter { it !is InferenceEvent.ThoughtDelta }

/**
 * Re-emit every event but throw a [MindlayerException] when an
 * [InferenceEvent.Error] is seen. Applied by default inside the `awaitX()`
 * terminals.
 */
fun Flow<InferenceEvent>.throwOnError(): Flow<InferenceEvent> =
    onEach { event ->
        if (event is InferenceEvent.Error) {
            throw MindlayerException.fromStreamError(
                message = event.message,
                codeName = event.code,
                codeInt = event.codeInt,
                seq = event.seq,
                tsMs = event.tsMs,
            )
        }
    }
