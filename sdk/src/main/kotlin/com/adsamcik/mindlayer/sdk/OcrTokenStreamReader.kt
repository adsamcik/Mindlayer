package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException

/**
 * SDK-side reader for the v0.8 ``mindlayer.stream.ocr.v1`` protocol
 * (sibling of [TokenStreamReader] for the chat-stream protocol).
 *
 * Same wire framing — 4-byte LE u32 length + UTF-8 JSON
 * [StreamEvent] payload — but dispatches the 10 OCR_* event types
 * from [StreamEventType] into the [OcrEvent] sealed hierarchy.
 *
 * Use as the cold Flow plumbing behind [OcrSession.events]:
 *
 * ```kotlin
 * val pipe = ParcelFileDescriptor.createPipe()
 * service.streamOcrEvents(sessionId, pipe[1])
 * pipe[1].close()
 * val flow: Flow<OcrEvent> = OcrTokenStreamReader.readStream(pipe[0])
 * ```
 *
 * # Privacy
 *
 * Event payloads (``FieldUpdate.topValue`` / ``FieldLocked.topValue``
 * / ``ResultSnapshot.partialJson`` / ``ResultFinalized.fullJson``)
 * carry recognized text. Callers must treat them as PII — the SDK
 * never logs them. The [OcrEvent.toString] implementations on those
 * data classes already redact for accidental logging.
 */
object OcrTokenStreamReader {

    private const val MAX_FRAME_BYTES = 1_048_576 // 1 MiB

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read frames until EOF and map them to [OcrEvent] emissions.
     * Header / unknown / malformed frames produce no emission;
     * terminal ``DONE`` / ``ERROR`` close the stream.
     */
    fun readStream(readEnd: ParcelFileDescriptor): Flow<OcrEvent> = flow {
        val input = DataInputStream(
            BufferedInputStream(
                ParcelFileDescriptor.AutoCloseInputStream(readEnd),
            ),
        )
        try {
            while (true) {
                val len = try {
                    Integer.reverseBytes(input.readInt())
                } catch (_: EOFException) {
                    break
                }
                if (len !in 0..MAX_FRAME_BYTES) break
                val payload = ByteArray(len)
                try {
                    input.readFully(payload)
                } catch (_: EOFException) {
                    break
                }
                val event = parseFrame(payload.decodeToString()) ?: continue
                emit(event)
                if (event is OcrEvent.ResultFinalized) break
            }
        } finally {
            try {
                input.close()
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parse a single frame payload. Returns:
     *  - `null` for the protocol header / unknown / malformed events
     *    (caller continues reading).
     *  - an [OcrEvent] for known OCR_* / DONE / ERROR events.
     *
     * The terminal handling lives in [readStream]:
     *  - DONE breaks the loop with no emission (clean end).
     *  - ERROR is not currently surfaced — `ResultFinalized` is the
     *    canonical success-end signal. A future PR can extend the
     *    OcrEvent hierarchy with an `Error` subtype if needed.
     */
    internal fun parseFrame(text: String): OcrEvent? {
        // Try header first (one-shot)
        try {
            val header = json.decodeFromString(StreamHeader.serializer(), text)
            // Accept the OCR_V1 protocol; reject anything else outright
            // — the service should never write a chat-stream header
            // onto an OCR pipe.
            if (header.protocol == StreamProtocol.OCR_V1) return null
            // Wrong protocol — treat as malformed and skip.
            if (header.protocol.isNotBlank()) return null
        } catch (_: SerializationException) {
            // Not a header; fall through to StreamEvent parsing.
        }
        val event = try {
            json.decodeFromString(StreamEvent.serializer(), text)
        } catch (_: SerializationException) {
            return null
        }
        return mapEvent(event)
    }

    private fun mapEvent(e: StreamEvent): OcrEvent? = when (e.type) {
        StreamEventType.OCR_FRAME_RECEIVED -> OcrEvent.FrameReceived(
            frameId = e.payload["frameId"]?.jsonPrimitive?.long ?: return null,
        )
        StreamEventType.OCR_FRAME_REJECTED_QUALITY -> OcrEvent.FrameRejectedQuality(
            frameId = e.payload["frameId"]?.jsonPrimitive?.long ?: return null,
            reason = e.payload["reason"]?.jsonPrimitive?.contentOrNull,
        )
        StreamEventType.OCR_FRAME_DROPPED_BUSY -> OcrEvent.FrameDroppedBusy(
            frameId = e.payload["frameId"]?.jsonPrimitive?.long ?: return null,
            retryAfterMs = e.payload["retryAfterMs"]?.jsonPrimitive?.long ?: 0L,
        )
        StreamEventType.OCR_FRAME_PROCESSING -> OcrEvent.FrameProcessing(
            frameId = e.payload["frameId"]?.jsonPrimitive?.long ?: return null,
        )
        StreamEventType.OCR_FRAME_PROCESSED -> OcrEvent.FrameProcessed(
            frameId = e.payload["frameId"]?.jsonPrimitive?.long ?: return null,
            lineCount = e.payload["lineCount"]?.jsonPrimitive?.int ?: 0,
        )
        StreamEventType.OCR_FIELD_UPDATE -> OcrEvent.FieldUpdate(
            fieldName = e.payload["fieldName"]?.jsonPrimitive?.contentOrNull ?: return null,
            topValue = e.payload["topValue"]?.jsonPrimitive?.contentOrNull ?: return null,
            confidence = e.payload["confidence"]?.jsonPrimitive?.contentOrNull ?: "low",
            consecutiveAgreement = e.payload["consecutiveAgreement"]?.jsonPrimitive?.intOrNull ?: 1,
        )
        StreamEventType.OCR_FIELD_LOCKED -> OcrEvent.FieldLocked(
            fieldName = e.payload["fieldName"]?.jsonPrimitive?.contentOrNull ?: return null,
            topValue = e.payload["topValue"]?.jsonPrimitive?.contentOrNull ?: return null,
        )
        StreamEventType.OCR_RESULT_SNAPSHOT -> OcrEvent.ResultSnapshot(
            partialJson = e.payload["partialJson"]?.jsonPrimitive?.contentOrNull ?: return null,
        )
        StreamEventType.OCR_RESULT_FINALIZED -> OcrEvent.ResultFinalized(
            fullJson = e.payload["fullJson"]?.jsonPrimitive?.contentOrNull ?: return null,
        )
        StreamEventType.OCR_THROTTLE_HINT -> OcrEvent.ThrottleHint(
            recommendedIntervalMs = e.payload["recommendedIntervalMs"]?.jsonPrimitive?.long ?: 0L,
        )
        StreamEventType.DONE -> null // terminal, handled in readStream loop break
        StreamEventType.ERROR -> null // not surfaced in OcrEvent (yet)
        else -> null
    }
}
