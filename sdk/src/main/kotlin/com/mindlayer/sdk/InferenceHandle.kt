package com.mindlayer.sdk

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

    /** Whether cancel() has been called. */
    val isCancelled: Boolean get() = cancelled.get()

    internal fun setCancelCallback(cb: suspend () -> Unit) {
        cancelCallback = cb
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
}
