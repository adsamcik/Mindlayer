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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * Reads length-prefixed JSON frames from a [ParcelFileDescriptor] pipe and
 * emits typed [InferenceEvent]s.
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
    fun readStream(readEnd: ParcelFileDescriptor): Flow<InferenceEvent> = flow {
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
                var terminalError: InferenceEvent.Error? = null
                val parsedEvents: List<InferenceEvent>? = try {
                    require(len in 0..MAX_FRAME_BYTES) { "Invalid frame length: $len" }
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    parseFrameMulti(payload.decodeToString())
                } catch (e: EOFException) {
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error: truncated frame",
                        code = "PROTOCOL_ERROR_EOF",
                        seq = -1,
                    )
                    null
                } catch (e: IllegalArgumentException) {
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error: invalid frame length",
                        code = "PROTOCOL_ERROR_LENGTH",
                        seq = -1,
                    )
                    null
                } catch (e: SerializationException) {
                    // Defensive: parseFrameMulti already catches this, but guard against future changes.
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error: invalid frame encoding",
                        code = "PROTOCOL_ERROR_JSON",
                        seq = -1,
                    )
                    null
                } catch (e: Throwable) {
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error",
                        code = "PROTOCOL_ERROR",
                        seq = -1,
                    )
                    null
                }

                when {
                    terminalError != null -> { emit(terminalError); break }
                    parsedEvents == null -> {
                        // Unrecognised frame — not a StreamEvent or StreamHeader.
                        emit(
                            InferenceEvent.Error(
                                message = "Protocol error: unrecognised frame",
                                code = "PROTOCOL_ERROR_JSON",
                                seq = -1,
                            ),
                        )
                        break
                    }
                    else -> {
                        // Most frames produce one event; v0.5 TOKEN_DELTA_BATCH
                        // expands into many ordered TextDelta emissions.
                        for (event in parsedEvents) {
                            emit(event)
                        }
                    }
                }
            }

            // EOF reached — check whether the service closed cleanly
            try {
                readEnd.checkError()
            } catch (e: IOException) {
                emit(
                    InferenceEvent.Error(
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
     * which the reader expands into per-token [InferenceEvent.TextDelta]
     * emissions so the SDK consumer surface is unchanged.
     */
    internal const val EXPECTED_PIPE_PROTOCOL = "mindlayer.stream.v1"

    /**
     * Parse one wire frame into zero-or-more [InferenceEvent]s.
     * - Normal frames yield exactly one event.
     * - [com.adsamcik.mindlayer.shared.StreamEventType.TOKEN_DELTA_BATCH]
     *   frames yield one [InferenceEvent.TextDelta] per `texts[]` element,
     *   in order, with synthesised contiguous `seq` values ending at the
     *   envelope's `seq`.
     * - Unparseable frames yield an empty list (legacy "skip silently"
     *   behavior preserved for forward-compat with future event payloads).
     */
    private fun parseFrameMulti(jsonStr: String): List<InferenceEvent> {
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
                if (mapped is InferenceEvent.Unknown && mapped.type == StreamEventType.TOKEN_DELTA_BATCH) {
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
     * yield a synthetic [InferenceEvent.Error] with code `PROTOCOL_MISMATCH`.
     */
    private fun headerEvent(header: StreamHeader): InferenceEvent {
        return if (header.protocol !in com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED) {
            InferenceEvent.Error(
                message = "Unsupported pipe protocol: '${header.protocol}' " +
                    "(SDK supports ${com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED})",
                code = "PROTOCOL_MISMATCH",
                seq = -1,
                tsMs = null,
            )
        } else {
            InferenceEvent.Started(header.requestId)
        }
    }

    /**
     * Expand a [StreamEventType.TOKEN_DELTA_BATCH] frame into per-token
     * [InferenceEvent.TextDelta] emissions. The envelope's `seq` is the
     * seq of the last token; earlier tokens' seqs count backwards. Empty
     * `texts` list yields no events.
     */
    private fun expandBatch(envelope: StreamEvent): List<InferenceEvent> {
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
            InferenceEvent.TextDelta(text = text, seq = perTokenSeq)
        }
    }

    /**
     * Tries [StreamEvent] first (common case), then falls back to
     * [StreamHeader] (first frame only). Returns `null` for unparseable frames.
     *
     * The header is validated against [com.adsamcik.mindlayer.shared.StreamProtocol.SUPPORTED];
     * a mismatch yields a synthetic [InferenceEvent.Error] frame so old SDKs
     * talking to a future service that bumped the protocol fail loudly
     * instead of silently misinterpreting later frames.
     *
     * Kept for binary compat with prior internal callers; new code paths use
     * [parseFrameMulti] directly to handle batched events.
     */
    private fun parseFrame(jsonStr: String): InferenceEvent? =
        parseFrameMulti(jsonStr).firstOrNull()

    /**
     * Maps a wire [StreamEvent] to a typed [InferenceEvent], reading payload
     * keys that match [com.adsamcik.mindlayer.service.ipc.TokenStreamWriter].
     */
    private fun mapEvent(event: StreamEvent): InferenceEvent = when (event.type) {
        StreamEventType.TOKEN_DELTA -> InferenceEvent.TextDelta(
            text = event.payload["text"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )

        StreamEventType.TOOL_CALL -> InferenceEvent.ToolCall(
            toolName = event.payload["name"]?.jsonPrimitive?.contentOrNull ?: "",
            arguments = event.payload["args"]?.jsonPrimitive?.contentOrNull ?: "{}",
            callId = event.payload["callId"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )

        StreamEventType.METRICS -> InferenceEvent.Metrics(
            prefillToksPerSec = event.payload["prefillToksPerSec"]?.jsonPrimitive?.floatOrNull,
            decodeToksPerSec = event.payload["decodeToksPerSec"]?.jsonPrimitive?.floatOrNull,
            thermalBand = event.payload["thermalBand"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )

        StreamEventType.ERROR -> InferenceEvent.Error(
            message = event.payload["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
            code = event.payload["code"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
            tsMs = event.tsMs.takeIf { it > 0 },
            codeInt = event.payload["codeInt"]?.jsonPrimitive?.intOrNull,
        )

        StreamEventType.DONE -> InferenceEvent.Done(
            finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            fullText = event.payload["full_text"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )

        else -> InferenceEvent.Unknown(type = event.type, seq = event.seq)
    }
}
