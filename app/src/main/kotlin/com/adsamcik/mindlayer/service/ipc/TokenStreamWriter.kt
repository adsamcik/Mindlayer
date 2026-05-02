package com.adsamcik.mindlayer.service.ipc

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private val writeTimeoutMs: Long,
    private val pfd: ParcelFileDescriptor?,
) {

    /** Production constructor — wraps the pipe's write-end. */
    constructor(writeEnd: ParcelFileDescriptor) : this(
        ParcelFileDescriptor.AutoCloseOutputStream(writeEnd) as OutputStream,
        DEFAULT_WRITE_TIMEOUT_MS,
        writeEnd,
    ) {
        // F-009: switch the pipe write end to non-blocking so a peer holding
        // the read end open without draining surfaces as EAGAIN rather than
        // wedging the worker thread. Best-effort — failure is logged and the
        // existing watchdog (writeTimeoutMs) still bounds latency.
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

    companion object {
        private const val TAG = "TokenStreamWriter"
        internal const val DEFAULT_WRITE_TIMEOUT_MS = 5_000L
        internal const val EAGAIN_BACKOFF_MS = 5L

        /** Test-only factory that accepts a plain [OutputStream]. */
        internal fun forTesting(
            output: OutputStream,
            writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
        ): TokenStreamWriter = TokenStreamWriter(output, writeTimeoutMs, pfd = null)
    }

    private val json = Json { encodeDefaults = true }
    @Volatile private var closed = false

    // ---- Public API --------------------------------------------------------

    suspend fun writeHeader(requestId: String) {
        val header = StreamHeader(requestId = requestId)
        writeFrame(json.encodeToString(StreamHeader.serializer(), header))
    }

    suspend fun writeTokenDelta(seq: Long, text: String) {
        writeEvent(seq, StreamEventType.TOKEN_DELTA, buildJsonObject { put("text", text) })
    }

    suspend fun writeToolCall(seq: Long, callId: String, name: String, argsJson: String) {
        writeEvent(seq, StreamEventType.TOOL_CALL, buildJsonObject {
            put("callId", callId)
            put("name", name)
            put("args", JsonPrimitive(argsJson))
        })
    }

    suspend fun writeMetrics(seq: Long, metrics: JsonObject) {
        writeEvent(seq, StreamEventType.METRICS, metrics)
    }

    suspend fun writeDone(seq: Long, finishReason: String) {
        writeEvent(seq, StreamEventType.DONE, buildJsonObject { put("finish_reason", finishReason) })
    }

    suspend fun writeError(seq: Long, code: String, message: String) {
        writeEvent(seq, StreamEventType.ERROR, buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    /**
     * Write an [StreamEventType.ERROR] frame using a typed wire code from
     * [MindlayerErrorCode]. Prefer this overload over the raw-string
     * [writeError] so the SDK side can react via
     * [com.adsamcik.mindlayer.sdk.MindlayerEvent.Error.code] symbolic names.
     */
    suspend fun writeError(seq: Long, code: Int, message: String) {
        val name = MindlayerErrorCode.nameOf(code) ?: "INTERNAL"
        writeError(seq, name, message)
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
            MindlayerLog.w(TAG, "Non-IOException flushing pipe: ${t.safeLabel()}")
        }
        try { output.close() } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException closing pipe (client may have disconnected)", throwable = e)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Non-IOException closing pipe: ${t.safeLabel()}")
        }
    }

    /**
     * Close the pipe after emitting a final [StreamEventType.ERROR] frame.
     *
     * The [code] should come from [MindlayerErrorCode] so the SDK side sees a
     * symbolic [com.adsamcik.mindlayer.sdk.MindlayerEvent.Error.code]. The
     * default [MindlayerErrorCode.INTERNAL] is the safe choice when the
     * call site is a generic catch — prefer a more specific code at the
     * call site so client-side retry logic has signal.
     */
    suspend fun closeWithError(
        seq: Long,
        message: String,
        code: Int = MindlayerErrorCode.INTERNAL,
    ) {
        try {
            writeError(seq, code, message)
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

    private suspend fun writeEvent(seq: Long, type: String, payload: JsonObject) {
        val event = StreamEvent(
            seq = seq,
            type = type,
            tsMs = System.currentTimeMillis(),
            payload = payload,
        )
        writeFrame(json.encodeToString(StreamEvent.serializer(), event))
    }

    private suspend fun writeFrame(payload: String) {
        if (closed) return
        val bytes = payload.encodeToByteArray()
        check(bytes.size <= MAX_FRAME_BYTES) {
            "Frame too large: ${bytes.size} bytes (max=$MAX_FRAME_BYTES)"
        }
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bytes.size)
            .array()
        try {
            withTimeout(writeTimeoutMs) {
                writeAllNonBlocking(header)
                writeAllNonBlocking(bytes)
                runInterruptible(Dispatchers.IO) { output.flush() }
            }
        } catch (e: TimeoutCancellationException) {
            // F-009: backpressure timeout. Force-close the underlying FD
            // (rubber-duck correction: pfd.close() in addition to OutputStream.close
            // so a stuck native syscall actually returns).
            MindlayerLog.w(TAG, "Pipe write timed out after ${writeTimeoutMs}ms; closing", throwable = null)
            closed = true
            try { pfd?.close() } catch (t: Throwable) {
                MindlayerLog.w(TAG, "pfd.close() after timeout raised: ${t.safeLabel()}")
            }
            try { output.close() } catch (t: Throwable) {
                MindlayerLog.w(TAG, "output.close() after timeout raised: ${t.safeLabel()}")
            }
            throw CancellationException("backpressure_timeout").apply { initCause(e) }
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException writing frame (client may have disconnected)", throwable = e)
            closed = true
            // Re-raise as CancellationException so the enclosing inference
            // coroutine unwinds promptly and native cancelProcess() fires.
            throw CancellationException("Pipe write failed; client likely disconnected").apply { initCause(e) }
        }
    }

    /**
     * EAGAIN-aware loop: with `O_NONBLOCK` set on the underlying pipe FD,
     * a wedged peer surfaces as `IOException` whose cause is
     * `ErrnoException(EAGAIN)`. We retry with a tiny [delay] so the
     * outer [withTimeout] can race the wait.
     */
    private suspend fun writeAllNonBlocking(buf: ByteArray) = withContext(Dispatchers.IO) {
        var written = 0
        while (written < buf.size) {
            try {
                runInterruptible {
                    output.write(buf, written, buf.size - written)
                }
                written = buf.size
            } catch (e: IOException) {
                if (isEagain(e)) {
                    delay(EAGAIN_BACKOFF_MS)
                } else {
                    throw e
                }
            }
        }
    }

    private fun isEagain(e: IOException): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ErrnoException && cause.errno == OsConstants.EAGAIN) return true
            cause = cause.cause
        }
        // Fallback: bionic message is "write failed: EAGAIN (Try again)"
        return e.message?.contains("EAGAIN") == true
    }
}
