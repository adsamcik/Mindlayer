package com.mindlayer.service.ipc

import android.os.ParcelFileDescriptor
import com.mindlayer.service.logging.MindlayerLog
import com.mindlayer.shared.StreamEvent
import com.mindlayer.shared.StreamEventType
import com.mindlayer.shared.StreamHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes length-prefixed JSON events to a [ParcelFileDescriptor] pipe.
 *
 * Frame format: 4 bytes little-endian u32 length + UTF-8 JSON payload.
 *
 * Thread-safety: **not** thread-safe — callers must serialise externally
 * (the orchestrator holds a per-session mutex).
 */
class TokenStreamWriter private constructor(
    private val output: OutputStream,
) {

    /** Production constructor — wraps the pipe's write-end. */
    constructor(writeEnd: ParcelFileDescriptor) : this(
        ParcelFileDescriptor.AutoCloseOutputStream(writeEnd) as OutputStream,
    )

    companion object {
        private const val TAG = "TokenStreamWriter"

        /** Test-only factory that accepts a plain [OutputStream]. */
        internal fun forTesting(output: OutputStream): TokenStreamWriter =
            TokenStreamWriter(output)
    }

    private val json = Json { encodeDefaults = true }
    private var closed = false

    // ---- Public API --------------------------------------------------------

    fun writeHeader(requestId: String) {
        val header = StreamHeader(requestId = requestId)
        writeFrame(json.encodeToString(StreamHeader.serializer(), header))
    }

    fun writeTokenDelta(seq: Long, text: String) {
        writeEvent(seq, StreamEventType.TOKEN_DELTA, buildJsonObject { put("text", text) })
    }

    fun writeToolCall(seq: Long, callId: String, name: String, argsJson: String) {
        writeEvent(seq, StreamEventType.TOOL_CALL, buildJsonObject {
            put("callId", callId)
            put("name", name)
            put("args", JsonPrimitive(argsJson))
        })
    }

    fun writeMetrics(seq: Long, metrics: JsonObject) {
        writeEvent(seq, StreamEventType.METRICS, metrics)
    }

    fun writeDone(seq: Long, finishReason: String) {
        writeEvent(seq, StreamEventType.DONE, buildJsonObject { put("finish_reason", finishReason) })
    }

    fun writeError(seq: Long, code: String, message: String) {
        writeEvent(seq, StreamEventType.ERROR, buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            output.flush()
            output.close()
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException closing pipe (client may have disconnected)", throwable = e)
        }
    }

    fun closeWithError(seq: Long, message: String) {
        try {
            writeError(seq, "internal_error", message)
        } catch (_: IOException) {
            // Best-effort
        }
        close()
    }

    // ---- Internals ---------------------------------------------------------

    private fun writeEvent(seq: Long, type: String, payload: JsonObject) {
        val event = StreamEvent(
            seq = seq,
            type = type,
            tsMs = System.currentTimeMillis(),
            payload = payload,
        )
        writeFrame(json.encodeToString(StreamEvent.serializer(), event))
    }

    private fun writeFrame(payload: String) {
        if (closed) return
        try {
            val bytes = payload.encodeToByteArray()
            val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(bytes.size)
                .array()
            output.write(header)
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException writing frame (client may have disconnected)", throwable = e)
            closed = true
        }
    }
}
