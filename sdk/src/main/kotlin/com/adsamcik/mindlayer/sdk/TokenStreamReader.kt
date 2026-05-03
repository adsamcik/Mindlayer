package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    /** The model is requesting a tool invocation. Respond with [Mindlayer.submitToolResult] using [callId]. */
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
    data class Error(
        val message: String,
        val code: String?,
        val seq: Long,
        val tsMs: Long? = null,
    ) : MindlayerEvent()

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

                require(len in 0..MAX_FRAME_BYTES) { "Invalid frame length: $len" }

                val payload = ByteArray(len)
                input.readFully(payload)
                val jsonStr = payload.decodeToString()

                // Most frames produce one event; v0.5 TOKEN_DELTA_BATCH
                // expands into many ordered TextDelta emissions. Local
                // List<MindlayerEvent> avoids widening parseFrame's signature.
                for (event in parseFrameMulti(jsonStr)) {
                    emit(event)
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
                        tsMs = null,
                    ),
                )
            }
        } finally {
            input.close()
        }
    }.flowOn(Dispatchers.IO)

    // -- Parsing --------------------------------------------------------------

    /**
     * Stable wire identifier for the v1 pipe protocol. Reader accepts v1
     * **or** v2 (see [com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED])
     * — v2 streams may carry [com.adsamcik.mindlayer.shared.StreamEventType.TOKEN_DELTA_BATCH]
     * which the reader expands into per-token [MindlayerEvent.TextDelta]
     * emissions so the SDK consumer surface is unchanged.
     */
    internal const val EXPECTED_PIPE_PROTOCOL = "mindlayer.stream.v1"

    /**
     * Parse one wire frame into zero-or-more [MindlayerEvent]s.
     * - Normal frames yield exactly one event.
     * - [com.adsamcik.mindlayer.shared.StreamEventType.TOKEN_DELTA_BATCH]
     *   frames yield one [MindlayerEvent.TextDelta] per `texts[]` element,
     *   in order, with synthesised contiguous `seq` values ending at the
     *   envelope's `seq`.
     * - Unparseable frames yield an empty list (legacy "skip silently"
     *   behavior preserved for forward-compat with future event payloads).
     */
    private fun parseFrameMulti(jsonStr: String): List<MindlayerEvent> {
        // Try the common case first.
        val streamEvent = try {
            json.decodeFromString<StreamEvent>(jsonStr)
        } catch (_: Exception) {
            // Header (first frame) fallback.
            val header = try {
                json.decodeFromString<StreamHeader>(jsonStr)
            } catch (_: Exception) {
                return emptyList()
            }
            return listOf(headerEvent(header))
        }
        return when (streamEvent.type) {
            StreamEventType.TOKEN_DELTA_BATCH -> expandBatch(streamEvent)
            else -> {
                val mapped = mapEvent(streamEvent)
                if (mapped is MindlayerEvent.Unknown && mapped.type == StreamEventType.TOKEN_DELTA_BATCH) {
                    // Defense-in-depth: should be unreachable since the when
                    // above handles it, but keeps the contract explicit.
                    expandBatch(streamEvent)
                } else {
                    listOf(mapped)
                }
            }
        }
    }

    /**
     * Validate the [StreamHeader.protocol] against
     * [com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED]. Mismatches
     * yield a synthetic [MindlayerEvent.Error] with code `PROTOCOL_MISMATCH`.
     */
    private fun headerEvent(header: StreamHeader): MindlayerEvent {
        return if (header.protocol !in com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED) {
            MindlayerEvent.Error(
                message = "Unsupported pipe protocol: '${header.protocol}' " +
                    "(SDK supports ${com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED})",
                code = "PROTOCOL_MISMATCH",
                seq = -1,
                tsMs = null,
            )
        } else {
            MindlayerEvent.Started(header.requestId)
        }
    }

    /**
     * Expand a [StreamEventType.TOKEN_DELTA_BATCH] frame into per-token
     * [MindlayerEvent.TextDelta] emissions. The envelope's `seq` is the
     * seq of the last token; earlier tokens' seqs count backwards. Empty
     * `texts` list yields no events.
     */
    private fun expandBatch(envelope: StreamEvent): List<MindlayerEvent> {
        val texts = envelope.payload["texts"]
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: return emptyList()
        if (texts.isEmpty()) return emptyList()
        val lastSeq = envelope.seq
        return texts.mapIndexed { index, element ->
            val text = (element as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
                ?: ""
            // Synthesise contiguous seq values: last entry gets the envelope
            // seq; earlier entries count backwards.
            val perTokenSeq = lastSeq - (texts.size - 1 - index)
            MindlayerEvent.TextDelta(text = text, seq = perTokenSeq)
        }
    }

    /**
     * Tries [StreamEvent] first (common case), then falls back to
     * [StreamHeader] (first frame only). Returns `null` for unparseable frames.
     *
     * The header is validated against [com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED];
     * a mismatch yields a synthetic [MindlayerEvent.Error] frame so old SDKs
     * talking to a future service that bumped the protocol fail loudly
     * instead of silently misinterpreting later frames.
     *
     * Kept for binary compat with prior internal callers; new code paths use
     * [parseFrameMulti] directly to handle batched events.
     */
    private fun parseFrame(jsonStr: String): MindlayerEvent? =
        parseFrameMulti(jsonStr).firstOrNull()

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
            tsMs = event.tsMs.takeIf { it > 0 },
        )

        StreamEventType.DONE -> MindlayerEvent.Done(
            finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            fullText = event.payload["full_text"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )

        else -> MindlayerEvent.Unknown(type = event.type, seq = event.seq)
    }
}
