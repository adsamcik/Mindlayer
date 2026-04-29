package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * Typed events emitted during inference, collected from [InferenceHandle.events].
 *
 * **Event ordering guarantee:**
 * 1. [Started] — exactly once, always first
 * 2. [TextDelta], [ToolCall], [Metrics] — zero or more, in sequence order
 * 3. [Done] or [Error] — exactly one, always last (terminal event)
 *
 * If the flow completes without a terminal event, the inference was cancelled
 * or the service connection was lost.
 */
sealed class MindlayerEvent {
    /** Signals that the service has accepted the request and inference is beginning. */
    data class Started(val requestId: String) : MindlayerEvent()

    /** An incremental chunk of generated text. Collect and concatenate these for the full response. */
    data class TextDelta(val text: String, val seq: Long) : MindlayerEvent()

    /** The model is requesting a tool invocation. Respond with [Mindlayer.submitToolResult]. */
    data class ToolCall(
        val toolName: String,
        val arguments: String,
        val callId: String,
        val seq: Long,
    ) : MindlayerEvent()

    /** Performance metrics snapshot emitted periodically during inference. */
    data class Metrics(
        val prefillToksPerSec: Float?,
        val decodeToksPerSec: Float?,
        val thermalBand: String?,
        val seq: Long,
    ) : MindlayerEvent()

    /** Terminal event indicating an error. No further events will follow. */
    data class Error(val message: String, val code: String?, val seq: Long) : MindlayerEvent()

    /** Terminal event indicating successful completion. [fullText] contains the accumulated response if available. */
    data class Done(
        val finishReason: String,
        val fullText: String?,
        val seq: Long,
    ) : MindlayerEvent()

    /** An event type not recognised by this SDK version. Safe to ignore. */
    data class Unknown(val type: String, val seq: Long) : MindlayerEvent()
}

/**
 * Reads length-prefixed JSON frames from a [ParcelFileDescriptor] pipe and
 * emits typed [MindlayerEvent]s.
 *
 * Frame format: 4 bytes little-endian u32 length + UTF-8 JSON payload
 * (mirrors [com.adsamcik.mindlayer.service.ipc.TokenStreamWriter]).
 *
 * Usage:
 * ```
 * val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
 * service.infer(meta, image, audio, writeEnd)
 * TokenStreamReader.readStream(readEnd).collect { event -> ... }
 * ```
 */
object TokenStreamReader {

    private const val MAX_FRAME_BYTES = 1_048_576 // 1 MiB

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns a cold [Flow] that reads frames until EOF (or pipe error),
     * then calls [ParcelFileDescriptor.checkError] to distinguish a clean
     * close from a service crash.
     *
     * Runs entirely on [Dispatchers.IO]. Backpressure is natural — when the
     * collector is slow the pipe buffer fills and the service blocks.
     */
    fun readStream(readEnd: ParcelFileDescriptor): Flow<MindlayerEvent> = flow {
        val input = DataInputStream(
            BufferedInputStream(
                ParcelFileDescriptor.AutoCloseInputStream(readEnd),
            ),
        )

        try {
            while (true) {
                val len = try {
                    // DataInputStream reads big-endian; wire is little-endian
                    Integer.reverseBytes(input.readInt())
                } catch (_: EOFException) {
                    break
                }

                // Guard all per-frame operations: a malicious service must not crash the collector.
                // emit() is intentionally placed OUTSIDE the try block so CancellationException
                // from a cancelled collector propagates correctly and is never swallowed.
                var terminalError: MindlayerEvent.Error? = null
                val parsedEvent: MindlayerEvent? = try {
                    require(len in 0..MAX_FRAME_BYTES) { "Invalid frame length: $len" }
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    parseFrame(payload.decodeToString())
                } catch (e: EOFException) {
                    terminalError = MindlayerEvent.Error(
                        message = "Protocol error: truncated frame",
                        code = "PROTOCOL_ERROR_EOF",
                        seq = -1,
                    )
                    null
                } catch (e: IllegalArgumentException) {
                    terminalError = MindlayerEvent.Error(
                        message = "Protocol error: invalid frame length",
                        code = "PROTOCOL_ERROR_LENGTH",
                        seq = -1,
                    )
                    null
                } catch (e: SerializationException) {
                    // Defensive: parseFrame already catches this, but guard against future changes.
                    terminalError = MindlayerEvent.Error(
                        message = "Protocol error: invalid frame encoding",
                        code = "PROTOCOL_ERROR_JSON",
                        seq = -1,
                    )
                    null
                } catch (e: Throwable) {
                    terminalError = MindlayerEvent.Error(
                        message = "Protocol error",
                        code = "PROTOCOL_ERROR",
                        seq = -1,
                    )
                    null
                }

                when {
                    terminalError != null -> { emit(terminalError); break }
                    parsedEvent == null -> {
                        // Unrecognised frame — not a StreamEvent or StreamHeader.
                        emit(
                            MindlayerEvent.Error(
                                message = "Protocol error: unrecognised frame",
                                code = "PROTOCOL_ERROR_JSON",
                                seq = -1,
                            ),
                        )
                        break
                    }
                    else -> emit(parsedEvent)
                }
            }

            // EOF reached — check whether the service closed cleanly
            try {
                readEnd.checkError()
            } catch (e: IOException) {
                emit(
                    MindlayerEvent.Error(
                        message = "Service pipe error: ${e.message}",
                        code = "PIPE_ERROR",
                        seq = -1,
                    ),
                )
            }
        } finally {
            input.close()
        }
    }.flowOn(Dispatchers.IO)

    // -- Parsing --------------------------------------------------------------

    /**
     * Tries [StreamEvent] first (common case), then falls back to
     * [StreamHeader] (first frame only). Returns `null` for unparseable frames.
     */
    private fun parseFrame(jsonStr: String): MindlayerEvent? {
        return try {
            val streamEvent = json.decodeFromString<StreamEvent>(jsonStr)
            mapEvent(streamEvent)
        } catch (_: Exception) {
            try {
                val header = json.decodeFromString<StreamHeader>(jsonStr)
                MindlayerEvent.Started(header.requestId)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Maps a wire [StreamEvent] to a typed [MindlayerEvent], reading payload
     * keys that match [com.adsamcik.mindlayer.service.ipc.TokenStreamWriter].
     */
    private fun mapEvent(event: StreamEvent): MindlayerEvent = when (event.type) {
        StreamEventType.START -> MindlayerEvent.Started(
            requestId = event.payload["requestId"]?.jsonPrimitive?.contentOrNull ?: "",
        )

        StreamEventType.TOKEN_DELTA -> MindlayerEvent.TextDelta(
            text = event.payload["text"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )

        StreamEventType.TOOL_CALL -> MindlayerEvent.ToolCall(
            toolName = event.payload["name"]?.jsonPrimitive?.contentOrNull ?: "",
            arguments = event.payload["args"]?.jsonPrimitive?.contentOrNull ?: "{}",
            callId = event.payload["callId"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )

        StreamEventType.METRICS -> MindlayerEvent.Metrics(
            prefillToksPerSec = event.payload["prefillToksPerSec"]?.jsonPrimitive?.floatOrNull,
            decodeToksPerSec = event.payload["decodeToksPerSec"]?.jsonPrimitive?.floatOrNull,
            thermalBand = event.payload["thermalBand"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )

        StreamEventType.ERROR -> MindlayerEvent.Error(
            message = event.payload["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
            code = event.payload["code"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )

        StreamEventType.DONE -> MindlayerEvent.Done(
            finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            fullText = event.payload["full_text"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )

        else -> MindlayerEvent.Unknown(type = event.type, seq = event.seq)
    }
}
