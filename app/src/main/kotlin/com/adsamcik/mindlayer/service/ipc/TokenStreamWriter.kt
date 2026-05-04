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

        /**
         * v0.5 token-batch policy: flush when the buffer holds this many
         * tokens. Smaller = lower perceived latency; larger = fewer
         * syscalls. 8 mirrors the multi-model review's recommendation.
         */
        internal const val BATCH_MAX_TOKENS = 8

        /**
         * v0.5: flush when the buffer's total UTF-8 byte size would exceed
         * this. Per the rubber-duck pass: structured-output emissions can
         * push large strings into `writeTokenDelta`, so a count cap alone
         * is not enough to stay under the 1 MiB pipe-frame limit.
         */
        internal const val BATCH_MAX_BYTES = 4 * 1024

        /**
         * v0.5: max wall-clock latency between the first buffered token
         * and the flush. Checked **inline** on the next `writeTokenDelta`
         * — there is no background timer (preserves the single-writer
         * guarantee documented above). If the model pauses with tokens
         * still buffered, they wait for the next event (token, tool call,
         * metrics, done, or close) — all of which trigger a flush.
         */
        internal const val BATCH_MAX_LATENCY_MS = 16L

        /** Test-only factory that accepts a plain [OutputStream]. */
        internal fun forTesting(
            output: OutputStream,
            writeTimeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
        ): TokenStreamWriter = TokenStreamWriter(output, writeTimeoutMs, pfd = null)
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
     * v0.5 batching state. Set via [enableBatching]; non-batching writers
     * never accumulate a buffer and emit a single TOKEN_DELTA per call.
     */
    private var batchingEnabled: Boolean = false
    private val tokenBatch = StringBuilder()
    private val tokenBatchTexts = mutableListOf<String>()
    private var tokenBatchFirstTimestamp: Long = 0L
    private var tokenBatchByteCount: Int = 0
    private var tokenBatchLastSeq: Long = -1L

    /**
     * Switch this writer to v2 protocol mode and enable the
     * [StreamEventType.TOKEN_DELTA_BATCH] coalescing buffer. Must be
     * called **before** [writeHeader] so the header advertises v2.
     */
    fun enableBatching() {
        require(!closed) { "writer is closed" }
        batchingEnabled = true
    }

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

    suspend fun writeHeader(requestId: String) {
        val protocol = if (batchingEnabled) {
            com.adsamcik.mindlayer.shared.StreamProtocol.V2
        } else {
            com.adsamcik.mindlayer.shared.StreamProtocol.V1
        }
        val header = StreamHeader(protocol = protocol, requestId = requestId)
        writeFrame(json.encodeToString(StreamHeader.serializer(), header))
    }

    suspend fun writeTokenDelta(seq: Long, text: String) {
        if (!batchingEnabled) {
            writeEvent(seq, StreamEventType.TOKEN_DELTA, buildJsonObject { put("text", text) })
            return
        }

        val now = System.currentTimeMillis()
        val incomingBytes = text.toByteArray(Charsets.UTF_8).size

        // Flush BEFORE appending if the new chunk would push us past either
        // size cap. Empty buffer always passes — single oversized chunks
        // emit as their own batch (still preferable to a v1 fall-back path
        // which would fragment unsupported types).
        if (tokenBatchTexts.isNotEmpty()) {
            val wouldExceedCount = tokenBatchTexts.size >= BATCH_MAX_TOKENS
            val wouldExceedBytes = tokenBatchByteCount + incomingBytes > BATCH_MAX_BYTES
            val wouldExceedLatency = now - tokenBatchFirstTimestamp >= BATCH_MAX_LATENCY_MS
            if (wouldExceedCount || wouldExceedBytes || wouldExceedLatency) {
                flushTokenBatch()
            }
        }

        // Fresh batch: capture start timestamp.
        if (tokenBatchTexts.isEmpty()) {
            tokenBatchFirstTimestamp = now
            tokenBatchByteCount = 0
        }
        tokenBatchTexts += text
        tokenBatchByteCount += incomingBytes
        tokenBatchLastSeq = seq
    }

    suspend fun writeToolCall(seq: Long, callId: String, name: String, argsJson: String) {
        flushTokenBatch()
        writeEvent(seq, StreamEventType.TOOL_CALL, buildJsonObject {
            put("callId", callId)
            put("name", name)
            put("args", JsonPrimitive(argsJson))
        })
    }

    suspend fun writeMetrics(seq: Long, metrics: JsonObject) {
        flushTokenBatch()
        writeEvent(seq, StreamEventType.METRICS, metrics)
    }

    suspend fun writeDone(seq: Long, finishReason: String) {
        flushTokenBatch()
        writeEvent(seq, StreamEventType.DONE, buildJsonObject { put("finish_reason", finishReason) })
    }

    suspend fun writeError(seq: Long, code: String, message: String) {
        flushTokenBatch()
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
        // v0.5: drain any pending batched tokens via a synchronous best-
        // effort flush. Suspend isn't available in close(), so we call the
        // sync helper which writes via the same writeFrame path used
        // elsewhere; the existing per-session mutex guarantees no
        // concurrent writer is touching `output`. If the pipe is already
        // dead the IOException catch in writeFrame swallows it.
        if (tokenBatchTexts.isNotEmpty()) {
            try {
                kotlinx.coroutines.runBlocking { flushTokenBatch() }
            } catch (t: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "Failed to drain pending token batch on close: ${t.safeLabel()}",
                )
                // Clear so a subsequent close() doesn't re-attempt.
                tokenBatchTexts.clear()
                tokenBatchByteCount = 0
            }
        }
        // F-020 + H4: independent timed-flush/close. Each runs through
        // [runBlockingIo] so an unresponsive reader on shutdown cannot pin
        // the orchestrator forever. Independent try/catch ensures a non-
        // IOException from flush() does NOT skip close() (FD leak guard).
        try {
            runBlockingIo { output.flush() }
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException flushing pipe (client may have disconnected)", throwable = e)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Non-IOException flushing pipe: ${t.safeLabel()}")
        }
        try {
            runBlockingIo { output.close() }
        } catch (e: IOException) {
            MindlayerLog.w(TAG, "IOException closing pipe (client may have disconnected)", throwable = e)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Non-IOException closing pipe: ${t.safeLabel()}")
        } finally {
            ioExec.shutdownNow()
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
            // Drain pending batched tokens before the terminal error frame
            // so the consumer gets every token the model produced before
            // the abort, in order, ahead of the error.
            flushTokenBatch()
        } catch (_: Throwable) {
            // Best-effort — fall through to writeError regardless.
        }
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

    // ---- v0.5 batching internals --------------------------------------------

    /**
     * Drain the pending token batch as a single
     * [StreamEventType.TOKEN_DELTA_BATCH] frame, then reset state. Idempotent
     * — empty buffer is a no-op. Called inline before every non-text event,
     * by the size/count/latency caps in [writeTokenDelta], and from
     * [close]/[closeWithError] so the batch never escapes the writer.
     *
     * Single-writer guarantee: this method itself doesn't introduce any
     * background work — flush happens on the caller's coroutine context,
     * just like every other write. The per-session mutex held by the
     * orchestrator continues to serialize all access to `output`.
     */
    private suspend fun flushTokenBatch() {
        if (!batchingEnabled || tokenBatchTexts.isEmpty()) return
        val texts = tokenBatchTexts.toList()
        val lastSeq = tokenBatchLastSeq
        // Reset before the write so a writeFrame failure doesn't leave the
        // buffer in a half-flushed state for the next call.
        tokenBatchTexts.clear()
        tokenBatchByteCount = 0
        tokenBatchLastSeq = -1L
        tokenBatchFirstTimestamp = 0L
        writeEvent(
            lastSeq,
            StreamEventType.TOKEN_DELTA_BATCH,
            buildJsonObject {
                put(
                    "texts",
                    kotlinx.serialization.json.JsonArray(
                        texts.map { JsonPrimitive(it) }
                    )
                )
            },
        )
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

    /**
     * H4 — submit [block] to the dedicated pipe-writer executor and bound it
     * with [writeTimeoutMs]. On timeout, cancel the future, mark the writer
     * closed, and raise [IOException] so callers convert it into a
     * cancellation that unwinds the inference coroutine and the per-session
     * mutex. Used by [close] for bounded flush+close on shutdown; the hot
     * write path uses [writeAllNonBlocking] + [withTimeout] instead so
     * EAGAIN backpressure can be retried in-flight.
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
