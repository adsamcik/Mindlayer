package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cold handle for an inference call.
 *
 * The subtype is determined by the request's output mode: [Text] for plain
 * generation, [Structured] for schema-constrained JSON, and [Tools] for
 * tool-calling. The canonical [Mindlayer.infer] returns the matching subtype
 * so common call sites avoid a cast.
 *
 * ## C1 deviations from Spike E §1
 * - [events] is a `Flow<MindlayerEvent>` (the existing pipe-frame type), not
 *   `Flow<InferenceEvent>`. Keeping the existing frame type lets the impl,
 *   [Conversation], and the test suite compile unchanged in C1; C2 adapts the
 *   stream onto [InferenceEvent].
 * - [isCancelled] and [cancel] are retained on the handle. Spike E removes
 *   `cancel()` in favour of structured concurrency, but [Conversation] and the
 *   existing tests rely on explicit cancellation, so it stays for now.
 *
 * The terminal `awaitX()` methods throw [NotImplementedError] in C1; behaviour
 * lands in C2.
 */
sealed interface InferenceHandle {
    /** Unique request ID, available immediately (before collection starts). */
    val requestId: String

    /** Session this request runs in. Empty string when not yet assigned. */
    val sessionId: String

    /** Cold flow of inference events. Collect exactly once. */
    val events: Flow<MindlayerEvent>

    /** Client-side cancellation state. See [cancel]. */
    val isCancelled: Boolean

    /** Cancel the inference request. Idempotent. */
    suspend fun cancel()

    /** Plain text generation. */
    interface Text : InferenceHandle {
        suspend fun awaitText(): String
    }

    /** Schema-constrained JSON generation. */
    interface Structured : InferenceHandle {
        suspend fun awaitJson(): JsonObject
    }

    /** Tool-calling generation. */
    interface Tools : InferenceHandle {
        suspend fun awaitToolCalls(): List<ToolCall>

        /** Submit a result for a tool call ID surfaced in [events]. */
        suspend fun submitToolResult(callId: String, resultJson: String)
    }
}

/**
 * Production [InferenceHandle]. In C1 it implements all three output subtypes
 * simultaneously; C2 returns the specific subtype matching the request's
 * output mode. Carries the client-side cancel machinery used by the streaming
 * impl and [Conversation].
 */
internal class InferenceHandleImpl(
    override val requestId: String,
    override val events: Flow<MindlayerEvent>,
    override val sessionId: String = "",
) : InferenceHandle, InferenceHandle.Text, InferenceHandle.Structured, InferenceHandle.Tools {

    private val cancelled = AtomicBoolean(false)
    private var cancelCallback: (suspend () -> Unit)? = null
    private var syncCancelCallback: (() -> Unit)? = null

    override val isCancelled: Boolean get() = cancelled.get()

    internal fun setCancelCallback(cb: suspend () -> Unit) {
        cancelCallback = cb
    }

    /**
     * Sets a non-suspend cancel callback used by [cancelSync]. Cleanup paths
     * (notably [Conversation.close]) must cancel without blocking on
     * `awaitConnected()`; the sync callback does best-effort work against the
     * cached binder and silently no-ops when no connection is available.
     */
    internal fun setSyncCancelCallback(cb: () -> Unit) {
        syncCancelCallback = cb
    }

    override suspend fun cancel() {
        if (cancelled.getAndSet(true)) return
        cancelCallback?.invoke()
    }

    /** Non-suspend [cancel] variant for synchronous cleanup paths. */
    internal fun cancelSync() {
        if (cancelled.getAndSet(true)) return
        syncCancelCallback?.invoke()
    }

    override suspend fun awaitText(): String =
        throw NotImplementedError("Mindlayer v1 — InferenceHandle.awaitText() lands in C2")

    override suspend fun awaitJson(): JsonObject =
        throw NotImplementedError("Mindlayer v1 — InferenceHandle.awaitJson() lands in C2")

    override suspend fun awaitToolCalls(): List<ToolCall> =
        throw NotImplementedError("Mindlayer v1 — InferenceHandle.awaitToolCalls() lands in C2")

    override suspend fun submitToolResult(callId: String, resultJson: String) =
        throw NotImplementedError("Mindlayer v1 — InferenceHandle.submitToolResult() lands in C2")
}
