package com.adsamcik.mindlayer.service.ipc

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hard upper bound on a single framed JSON payload sent through the pipe.
 *
 * Mirrors `TokenStreamReader.MAX_FRAME_BYTES` on the SDK side — duplicated
 * intentionally because the shared module is off-limits this pass. A frame
 * exceeding this bound indicates a programmer error (runaway payload) and
 * must fail fast rather than be truncated or streamed.
 */
private const val MAX_FRAME_BYTES = 1_048_576

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
        // F-020: independent try/catch around flush and close. The
        // previous code wrapped both in a single `catch(IOException)`,
        // so any non-IOException from `flush()` skipped `close()` and
        // leaked the FD permanently (closed=true was already set above).
        try { output.flush() } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException flushing pipe (client may have disconnected)", throwable = e)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Non-IOException flushing pipe", throwable = t)
        }
        try { output.close() } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException closing pipe (client may have disconnected)", throwable = e)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Non-IOException closing pipe", throwable = t)
        }
    }

    fun closeWithError(seq: Long, message: String) {
        try {
            writeError(seq, "internal_error", message)
        } catch (_: IOException) {
            // Best-effort
        } catch (_: CancellationException) {
            // writeFrame re-throws pipe IOExceptions as CancellationException
            // so that live inference is cancelled. For closeWithError the
            // call is already terminal — swallow and continue to close().
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
        val bytes = payload.encodeToByteArray()
        check(bytes.size <= MAX_FRAME_BYTES) {
            "Frame too large: ${bytes.size} bytes (max=$MAX_FRAME_BYTES)"
        }
        try {
            val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(bytes.size)
                .array()
            output.write(header)
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException writing frame (client may have disconnected)", throwable = e)
            closed = true
            // Re-raise as CancellationException so the enclosing inference
            // coroutine unwinds promptly and native cancelProcess() fires.
            // Callers outside a coroutine context (e.g. closeWithError) catch
            // CancellationException explicitly.
            throw CancellationException("Pipe write failed; client likely disconnected").apply { initCause(e) }
        }
    }
}
