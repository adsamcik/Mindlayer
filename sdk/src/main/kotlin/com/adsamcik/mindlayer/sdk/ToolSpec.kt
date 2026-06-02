package com.adsamcik.mindlayer.sdk

/**
 * Declaration of a tool the model may call during a tool-enabled inference.
 *
 * Passed to [InferenceRequest.Builder.outputTools]. [parametersSchema] is the
 * JSON Schema describing the tool's arguments; the service uses it both to
 * prompt the model and (optionally) to validate the emitted arguments.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonSchema,
)

/**
 * A single tool invocation requested by the model.
 *
 * Surfaced to [InferenceRequest.Builder.onToolCall] handlers and returned by
 * [InferenceHandle.Tools.awaitToolCalls]. [argsJson] is the raw arguments
 * object as a JSON string — callers parse it against the matching
 * [ToolSpec.parametersSchema].
 *
 * This is the caller-facing tool-call type; [InferenceEvent.ToolCall] is the
 * matching streaming-event frame.
 */
data class ToolCall(
    val callId: String,
    val name: String,
    val argsJson: String,
)
