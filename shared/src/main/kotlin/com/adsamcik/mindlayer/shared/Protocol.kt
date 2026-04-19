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
    const val START = "start"
    const val TOKEN_DELTA = "token_delta"
    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val METRICS = "metrics"
    const val ERROR = "error"
    const val DONE = "done"
}

@Serializable
data class StreamHeader(
    val protocol: String = "mindlayer.stream.v1",
    val requestId: String,
)
