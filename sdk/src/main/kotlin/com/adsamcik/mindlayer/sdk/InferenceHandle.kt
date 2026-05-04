package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handle for an in-flight inference request. Provides:
 * - [requestId] for tracking and correlation
 * - [events] flow of inference events
 * - [cancel] to stop inference on both client and service side
 *
 * Returned by [Mindlayer.chat], [Mindlayer.chatWithImage], [Mindlayer.chatWithAudio].
 *
 * Usage:
 * ```
 * val handle = mindlayer.chat(sessionId, "Hello!")
 * handle.events.collect { event ->
 *     when (event) {
 *         is MindlayerEvent.TextDelta -> print(event.text)
 *         is MindlayerEvent.Done -> println()
 *         else -> {}
 *     }
 * }
 * ```
 *
 * To cancel:
 * ```
 * handle.cancel()
 * ```
 */
class InferenceHandle(
    /** Unique request ID, available immediately (before collection starts). */
    val requestId: String,

    /** Cold flow of inference events. Collect exactly once. */
    val events: Flow<MindlayerEvent>,
) {
    private val cancelled = AtomicBoolean(false)
    private var cancelCallback: (suspend () -> Unit)? = null
    private var syncCancelCallback: (() -> Unit)? = null

    /**
     * Whether [cancel] has been called on this handle.
     *
     * **Note:** This reflects the client-side cancellation state, not whether
     * the service has finished processing. Use this to avoid redundant [cancel]
     * calls. Monitor [events] for [MindlayerEvent.Done] or [MindlayerEvent.Error]
     * to detect actual completion.
     */
    val isCancelled: Boolean get() = cancelled.get()

    internal fun setCancelCallback(cb: suspend () -> Unit) {
        cancelCallback = cb
    }

    /**
     * Sets a non-suspend cancel callback used by [cancelSync].
     *
     * Cleanup paths (notably [Conversation.close], which is non-suspend by the
     * [AutoCloseable] contract) must be able to cancel without blocking on
     * `awaitConnected()`. The sync callback is expected to do best-effort work
     * against the currently-cached service binder and silently no-op when no
     * connection is available — the session-side request will be cleaned up
     * when its session is destroyed.
     */
    internal fun setSyncCancelCallback(cb: () -> Unit) {
        syncCancelCallback = cb
    }

    /**
     * Cancel the inference request. Idempotent — safe to call multiple times.
     *
     * This reaches through to the service's native [cancelProcess()] to stop
     * the LiteRT-LM inference loop, not just close the pipe.
     */
    suspend fun cancel() {
        if (cancelled.getAndSet(true)) return // already cancelled
        cancelCallback?.invoke()
    }

    /**
     * Non-suspend variant of [cancel] for synchronous cleanup paths such as
     * [Conversation.close]. Mutually idempotent with [cancel] — both share
     * the same `cancelled` flag, so calling either after the other is a no-op.
     *
     * Best-effort: when no service binder is currently connected the cancel
     * is silently dropped. The corresponding service-side request is cleaned
     * up automatically when its owning session is destroyed.
     */
    internal fun cancelSync() {
        if (cancelled.getAndSet(true)) return // already cancelled
        syncCancelCallback?.invoke()
    }
}
