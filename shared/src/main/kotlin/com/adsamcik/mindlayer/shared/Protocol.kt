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
     * `mindlayer.stream.v2`. v1 streams must NEVER carry this event type
     * (old readers see it as `MindlayerEvent.Unknown` and silently drop
     * the contained text — that's the only place silent text loss is
     * possible, and the v1/v2 split prevents it).
     */
    const val TOKEN_DELTA_BATCH = "token_delta_batch"

    const val TOOL_CALL = "tool_call"
    const val TOOL_RESULT = "tool_result"
    const val METRICS = "metrics"
    /**
     * Terminal error. Payload contains `message`, optional symbolic `code`,
     * and optional stable integer `codeInt` from [MindlayerErrorCode].
     */
    const val ERROR = "error"
    const val DONE = "done"
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

    /** All protocols this build of the SDK reader can interpret. */
    val SUPPORTED: Set<String> = setOf(V1, V2)
}

@Serializable
data class StreamHeader(
    val protocol: String = StreamProtocol.V1,
    val requestId: String,
)

