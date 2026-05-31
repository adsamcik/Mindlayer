package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject

/**
 * Canonical, caller-facing inference event stream type for the Spike-E surface.
 *
 * NOTE (C1 deviation): the live [InferenceHandle.events] flow still emits the
 * existing [MindlayerEvent] frames in C1 to keep the impl, [Conversation], and
 * tests compiling unchanged. `InferenceEvent` is published now as the forward
 * type that C2 will adapt the pipe stream onto. The free operators below are
 * defined against it so the public surface is complete.
 */
sealed interface InferenceEvent {
    /** The service accepted the request; [sessionId] is the resolved session. */
    data class Started(val sessionId: String) : InferenceEvent

    /** An incremental chunk of generated text. */
    data class TextDelta(val text: String) : InferenceEvent

    /** The model requested a tool call; respond via [InferenceHandle.Tools.submitToolResult]. */
    data class ToolCall(val callId: String, val name: String, val argsJson: String) : InferenceEvent

    /** Terminal success frame. */
    data class Done(val finishReason: FinishReason, val metrics: Metrics) : InferenceEvent

    /** Terminal error frame. [code] is a [MindlayerErrorCode] integer constant. */
    data class Error(val code: Int, val message: String) : InferenceEvent
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
 * Re-emit every event but throw a [MindlayerException] when an
 * [InferenceEvent.Error] is seen. Applied by default inside the `awaitX()`
 * terminals.
 */
fun Flow<InferenceEvent>.throwOnError(): Flow<InferenceEvent> =
    onEach { event ->
        if (event is InferenceEvent.Error) {
            throw MindlayerException(message = event.message, code = event.code)
        }
    }
