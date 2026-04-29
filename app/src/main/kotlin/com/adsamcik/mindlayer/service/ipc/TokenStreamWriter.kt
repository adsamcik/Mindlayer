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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
 * Default per-write timeout (H4). A stalled pipe reader must not pin the
 * inference coroutine, the per-session mutex, or the foreground service.
 */
private const val DEFAULT_WRITE_TIMEOUT_MS = 5_000L

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
    private val writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
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

        /** Test-only factory with an explicit per-write timeout. */
        internal fun forTesting(output: OutputStream, writeTimeoutMs: Long): TokenStreamWriter =
            TokenStreamWriter(output, writeTimeoutMs)
    }

    private val json = Json { encodeDefaults = true }

    /**
     * L6 — `@Volatile` so the cancellation/close path racing the orchestrator
     * coroutine sees an up-to-date value without going through a synchronized
     * block. Combined with the H4 timeout this prevents writers from queueing
     * more frames against an already-doomed pipe.
     */
    @Volatile private var closed = false

    /**
     * H4 — single-thread executor used to bound each pipe write with a
     * Future timeout. A stalled reader (e.g. dead client) cannot pin the
     * orchestrator coroutine indefinitely; instead the IOException we throw
     * here surfaces as a CancellationException to the inference coroutine.
     */
    private val ioExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mindlayer-pipe-writer").apply { isDaemon = true }
    }

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
            // Best-effort flush+close, guarded by the same per-write timeout
            // so an unresponsive reader on shutdown still cannot block forever.
            runBlockingIo {
                try {
                    output.flush()
                } finally {
                    output.close()
                }
            }
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException closing pipe (client may have disconnected)", throwable = e)
        } finally {
            ioExec.shutdownNow()
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
            runBlockingIo {
                output.write(header)
                output.write(bytes)
                output.flush()
            }
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

    /**
     * H4 — submit [block] to the dedicated pipe-writer executor and bound it
     * with [writeTimeoutMs]. On timeout, cancel the future, mark the writer
     * closed, and raise [IOException] so [writeFrame] converts it into a
     * cancellation that unwinds the inference coroutine and the per-session
     * mutex.
     */
    private fun runBlockingIo(block: () -> Unit) {
        if (ioExec.isShutdown) {
            // Writer already closed — fall back to the existing behaviour
            // (write directly; the OutputStream is presumed closed and will
            // throw IOException, which the caller handles).
            block()
            return
        }
        val future = ioExec.submit(block)
        try {
            future.get(writeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            closed = true
            throw IOException("Pipe write timeout after ${writeTimeoutMs}ms", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is IOException) throw cause
            if (cause is RuntimeException) throw cause
            if (cause is Error) throw cause
            throw IOException("Pipe write failed", cause ?: e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("Pipe write interrupted", e)
        }
    }
}
