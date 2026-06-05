package com.adsamcik.mindlayer.service.ipc

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sibling of [TokenStreamWriter] for the v0.8 multi-frame OCR
 * protocol ([StreamProtocol.OCR_V1] — ``mindlayer.stream.ocr.v1``).
 *
 * # Wire format
 *
 * Same length-prefixed-JSON framing as [TokenStreamWriter] — 4-byte
 * little-endian u32 length + UTF-8 [StreamEvent] payload. This lets
 * SDK readers reuse the existing frame loop with a different event-
 * type dispatcher.
 *
 * # What it carries
 *
 * Only the 10 ``OCR_*`` event types from
 * [StreamEventType] plus the terminal ``DONE`` / ``ERROR`` shared
 * with the chat-stream protocol. **Never** carries token deltas /
 * tool calls — those belong on the chat-stream protocol.
 *
 * # Threading
 *
 * Not thread-safe. The [com.adsamcik.mindlayer.service.engine.OcrSessionManager]
 * owns exactly one writer per session and serialises emit calls
 * through the session's internal state. There is no token-batching
 * (OCR events are discrete, not high-frequency tokens).
 */
class OcrTokenStreamWriter private constructor(
    private val output: OutputStream,
    private val pfd: ParcelFileDescriptor?,
) {

    constructor(writeEnd: ParcelFileDescriptor) : this(
        ParcelFileDescriptor.AutoCloseOutputStream(writeEnd) as OutputStream,
        writeEnd,
    ) {
        // Switch to non-blocking on the pipe so a peer holding the
        // read end open without draining surfaces as EAGAIN rather
        // than wedging the session-manager worker thread.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val flags = Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_GETFL, 0)
                Os.fcntlInt(
                    writeEnd.fileDescriptor,
                    OsConstants.F_SETFL,
                    flags or OsConstants.O_NONBLOCK,
                )
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "fcntl O_NONBLOCK failed: ${t.safeLabel()}")
            }
        }
    }

    private val json = Json { encodeDefaults = true }
    private val seqGen = AtomicLong(0L)

    @Volatile private var headerWritten: Boolean = false
    @Volatile private var closed: Boolean = false

    /**
     * R-12: distinct from [closed] (which only disables further writes).
     * All FD release funnels through the idempotent [closeResourcesQuietly]
     * so a `writeFrame` IOException can no longer set `closed = true` and
     * leave the pipe descriptor open for the later short-circuiting [close]
     * to skip — the same leak fixed in [TokenStreamWriter].
     */
    @Volatile private var resourcesClosed: Boolean = false

    /**
     * Write the protocol header. Must be the first frame on a freshly
     * opened pipe. Subsequent header writes are silent no-ops so a
     * session-restart race cannot duplicate the header.
     *
     * @param sessionId carried as the header's `requestId` field for
     *   compatibility with the chat-stream header shape. The OCR
     *   protocol treats it as a session identifier.
     */
    fun writeHeader(sessionId: String = "ocr-stream") {
        if (headerWritten || closed) return
        val header = StreamHeader(
            protocol = StreamProtocol.OCR_V1,
            requestId = sessionId,
        )
        writeFrame(json.encodeToString(StreamHeader.serializer(), header), droppedEventType = "header")
        headerWritten = true
    }

    fun writeFrameReceived(frameId: Long, queueDepth: Int) {
        writeEvent(StreamEventType.OCR_FRAME_RECEIVED, buildJsonObject {
            put("frameId", frameId)
            put("queueDepth", queueDepth)
        })
    }

    fun writeFrameRejectedQuality(frameId: Long, reason: String, score: Float? = null) {
        writeEvent(StreamEventType.OCR_FRAME_REJECTED_QUALITY, buildJsonObject {
            put("frameId", frameId)
            put("reason", reason)
            score?.let { put("score", it) }
        })
    }

    fun writeFrameDroppedBusy(frameId: Long, retryAfterMs: Long) {
        writeEvent(StreamEventType.OCR_FRAME_DROPPED_BUSY, buildJsonObject {
            put("frameId", frameId)
            put("retryAfterMs", retryAfterMs)
        })
    }

    fun writeFrameProcessing(frameId: Long) {
        writeEvent(StreamEventType.OCR_FRAME_PROCESSING, buildJsonObject {
            put("frameId", frameId)
        })
    }

    fun writeFrameProcessed(frameId: Long, lineCount: Int, durationMs: Long) {
        writeEvent(StreamEventType.OCR_FRAME_PROCESSED, buildJsonObject {
            put("frameId", frameId)
            put("lineCount", lineCount)
            put("durationMs", durationMs)
        })
    }

    /**
     * @param boundingBox optional quadrilateral in normalised 0..1 frame
     *   coordinates: `[x1, y1, x2, y2, x3, y3, x4, y4]` clockwise from
     *   top-left. Eight floats. Encoded as a JSON array under the
     *   ``bbox`` key only when non-null (so older SDK readers that
     *   predate the bbox key remain wire-compatible). Mirrors
     *   [com.adsamcik.mindlayer.service.engine.OcrTextLine.boundingBox].
     */
    fun writeFieldUpdate(
        fieldName: String,
        topValue: String,
        confidence: String,
        consecutiveAgreement: Int,
        boundingBox: FloatArray? = null,
    ) {
        writeEvent(StreamEventType.OCR_FIELD_UPDATE, buildJsonObject {
            put("fieldName", fieldName)
            put("topValue", topValue)
            put("confidence", confidence)
            put("consecutiveAgreement", consecutiveAgreement)
            if (boundingBox != null) {
                require(boundingBox.size == BBOX_SIZE) {
                    "boundingBox must have $BBOX_SIZE floats, got ${boundingBox.size}"
                }
                putJsonArray(BBOX_KEY) {
                    boundingBox.forEach { add(JsonPrimitive(it)) }
                }
            }
        })
    }

    fun writeFieldLocked(
        fieldName: String,
        topValue: String,
        boundingBox: FloatArray? = null,
    ) {
        writeEvent(StreamEventType.OCR_FIELD_LOCKED, buildJsonObject {
            put("fieldName", fieldName)
            put("topValue", topValue)
            if (boundingBox != null) {
                require(boundingBox.size == BBOX_SIZE) {
                    "boundingBox must have $BBOX_SIZE floats, got ${boundingBox.size}"
                }
                putJsonArray(BBOX_KEY) {
                    boundingBox.forEach { add(JsonPrimitive(it)) }
                }
            }
        })
    }

    fun writeResultSnapshot(partialJson: String) {
        writeEvent(StreamEventType.OCR_RESULT_SNAPSHOT, buildJsonObject {
            put("partialJson", partialJson)
        })
    }

    fun writeResultFinalized(fullJson: String) {
        writeEvent(StreamEventType.OCR_RESULT_FINALIZED, buildJsonObject {
            put("fullJson", fullJson)
        })
    }

    /**
     * v0.9 multi-page realtime OCR — a new page accumulator opened.
     *
     * Payload: `{pageIndex, triggerFrameId}`. `triggerFrameId == 0` for
     * the implicit first-page open at session start; subsequent
     * boundary fires carry the frame id whose recognition output
     * crossed the stability threshold. See [StreamEventType.OCR_PAGE_STARTED].
     */
    fun writePageStarted(pageIndex: Int, triggerFrameId: Long) {
        writeEvent(StreamEventType.OCR_PAGE_STARTED, buildJsonObject {
            put("pageIndex", pageIndex)
            put("triggerFrameId", triggerFrameId)
        })
    }

    /**
     * v0.9 multi-page realtime OCR — a page accumulator was closed off.
     *
     * Payload: `{pageIndex, lines, fullJson, lineCount, framesContributed}`.
     * [fullJson] is non-null only when per-page LLM extraction ran and
     * produced output; the writer encodes it as a JSON object (it is
     * already a `JsonElement` at call time, not a string, to avoid the
     * double-encoding pitfall on the SDK reader side).
     *
     * [lines] is an array of `{text, confidence, bbox?}` objects — the
     * `bestLines()` of the page accumulator. The text is user content;
     * the writer faithfully encodes it as supplied (the dispatcher is
     * the layer that gated on privacy / log-redaction).
     *
     * See [StreamEventType.OCR_PAGE_FINALIZED].
     */
    fun writePageFinalized(
        pageIndex: Int,
        lines: List<OcrPageLine>,
        fullJson: JsonElement?,
        framesContributed: Int,
    ) {
        writeEvent(StreamEventType.OCR_PAGE_FINALIZED, buildJsonObject {
            put("pageIndex", pageIndex)
            put("lineCount", lines.size)
            put("framesContributed", framesContributed)
            put("lines", buildJsonArray {
                for (line in lines) {
                    add(buildJsonObject {
                        put("text", line.text)
                        put("confidence", line.confidence)
                        if (line.boundingBox != null) {
                            require(line.boundingBox.size == BBOX_SIZE) {
                                "boundingBox must have $BBOX_SIZE floats, got ${line.boundingBox.size}"
                            }
                            putJsonArray(BBOX_KEY) {
                                line.boundingBox.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    })
                }
            })
            if (fullJson != null) {
                put("fullJson", fullJson)
            }
        })
    }

    /**
     * Wire shape for one line inside [writePageFinalized]. Mirrors
     * [com.adsamcik.mindlayer.service.engine.OcrTextLine] but with the
     * `confidence` enum already mapped to its string label so the
     * writer stays decoupled from the engine's value class.
     */
    data class OcrPageLine(
        val text: String,
        val confidence: String,
        val boundingBox: FloatArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OcrPageLine) return false
            if (text != other.text) return false
            if (confidence != other.confidence) return false
            return when {
                boundingBox == null && other.boundingBox == null -> true
                boundingBox == null || other.boundingBox == null -> false
                else -> boundingBox.contentEquals(other.boundingBox)
            }
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + (boundingBox?.contentHashCode() ?: 0)
            return result
        }
    }

    fun writeThrottleHint(recommendedIntervalMs: Long) {
        writeEvent(StreamEventType.OCR_THROTTLE_HINT, buildJsonObject {
            put("recommendedIntervalMs", recommendedIntervalMs)
        })
    }

    fun writeDone(finishReason: String) {
        writeEvent(StreamEventType.DONE, buildJsonObject { put("finish_reason", finishReason) })
    }

    fun writeError(code: Int, message: String) {
        val name = com.adsamcik.mindlayer.shared.MindlayerErrorCode.nameOf(code) ?: "INTERNAL"
        writeEvent(StreamEventType.ERROR, buildJsonObject {
            put("code", name)
            put("codeInt", code)
            put("message", message)
        })
    }

    fun close() {
        // R-12: always release the FD, even if `closed` was already set by a
        // failed writeFrame (pre-fix this returned early and leaked the pipe).
        if (closed) {
            closeResourcesQuietly()
            return
        }
        closed = true
        try {
            output.flush()
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "OCR pipe flush failed (client gone): ${e.safeLabel()}")
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "OCR pipe flush non-IOException: ${t.safeLabel()}")
        }
        closeResourcesQuietly()
    }

    /** Idempotently release the pipe descriptor. Safe from any path. */
    private fun closeResourcesQuietly() {
        if (resourcesClosed) return
        resourcesClosed = true
        try { pfd?.close() } catch (t: Throwable) {
            MindlayerLog.w(TAG, "OCR pfd.close() raised: ${t.safeLabel()}")
        }
        try {
            output.close()
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "OCR pipe close failed: ${e.safeLabel()}")
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "OCR pipe close non-IOException: ${t.safeLabel()}")
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun writeEvent(type: String, payload: JsonObject) {
        if (closed) return
        if (!headerWritten) {
            // Defense in depth — emit header automatically on first
            // event if caller forgot. Cheaper than throwing and
            // recovering from a half-broken session.
            writeHeader()
        }
        val event = StreamEvent(
            seq = seqGen.incrementAndGet(),
            type = type,
            tsMs = System.currentTimeMillis(),
            payload = payload,
        )
        writeFrame(json.encodeToString(StreamEvent.serializer(), event), droppedEventType = type)
    }

    private fun writeFrameDroppedWarning(droppedEventType: String) {
        writeEvent(StreamEventType.OCR_FRAME_DROPPED_BUSY, buildJsonObject {
            put("frameId", -1L)
            put("retryAfterMs", 0L)
            put("reason", "oversized_event")
            put("droppedEventType", droppedEventType)
        })
    }

    private fun writeFrame(payload: String, droppedEventType: String) {
        if (closed) return
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > MAX_FRAME_BYTES) {
            MindlayerLog.w(
                TAG,
                "Dropping oversized OCR stream event type=$droppedEventType bytes=${payloadBytes.size}",
                throwable = null,
            )
            if (droppedEventType != StreamEventType.OCR_FRAME_DROPPED_BUSY) {
                writeFrameDroppedWarning(droppedEventType)
            }
            return
        }
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payloadBytes.size).array()
        // R-12: bound the blocking write. On API < R the pipe is NOT
        // O_NONBLOCK, so a reader that stops draining can wedge this worker
        // thread forever; even on API >= R a force-close is the unblock
        // mechanism. The watchdog closes the pipe after WRITE_TIMEOUT_MS,
        // which makes a blocked write() throw, and is cancelled on success.
        val watchdog = WRITE_WATCHDOG.schedule({
            MindlayerLog.w(
                TAG,
                "OCR pipe write exceeded ${WRITE_TIMEOUT_MS}ms; force-closing",
                throwable = null,
            )
            closed = true
            closeResourcesQuietly()
        }, WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        try {
            output.write(header)
            output.write(payloadBytes)
            output.flush()
        } catch (e: IOException) {
            // Reader likely closed (or the watchdog force-closed us). Mark
            // closed so subsequent writes no-op AND release the FD here — do
            // NOT throw — OCR events are advisory and shouldn't poison the
            // session lifecycle.
            MindlayerLog.w(
                TAG,
                "OCR frame write failed (reader gone): ${e.safeLabel()}",
                throwable = null,
            )
            closed = true
            closeResourcesQuietly()
        } finally {
            watchdog.cancel(false)
        }
    }

    companion object {
        private const val TAG = "OcrTokenStreamWriter"

        /**
         * R-12: per-write wall-clock budget. A stalled/dead OCR reader must
         * not pin the session-manager worker thread; on timeout the pipe is
         * force-closed so the blocked write throws. Generous because OCR
         * events are small and the reader is first-party.
         */
        internal const val WRITE_TIMEOUT_MS: Long = 5_000L

        /** Daemon scheduler backing the OCR write watchdog. */
        private val WRITE_WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "mindlayer-ocr-pipe-watchdog").apply { isDaemon = true }
            }

        /**
         * Hard upper bound on a single framed JSON payload. Mirrors
         * [TokenStreamWriter]'s cap so OCR and chat-stream pipes use
         * the same Binder-aware limit.
         */
        const val MAX_FRAME_BYTES: Int = 1_048_576

        /** JSON key for the optional bounding-box quadrilateral. */
        internal const val BBOX_KEY: String = "bbox"

        /** Number of floats in a bounding-box quadrilateral. */
        internal const val BBOX_SIZE: Int = 8

        /** Test-only factory accepting a plain [OutputStream]. */
        internal fun forTesting(output: OutputStream): OcrTokenStreamWriter =
            OcrTokenStreamWriter(output = output, pfd = null)
    }
}
