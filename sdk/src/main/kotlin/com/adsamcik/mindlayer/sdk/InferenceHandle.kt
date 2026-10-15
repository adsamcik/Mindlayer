package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
 * Cancelling the coroutine collecting [events] automatically requests native
 * cancellation. [cancel] is also available for explicit user actions.
 */
sealed interface InferenceHandle {
    /** Unique request ID, available immediately (before collection starts). */
    val requestId: String

    /** Session this request runs in. Empty string when not yet assigned. */
    val sessionId: String

    /** Cold flow of inference events. Collect exactly once. */
    val events: Flow<InferenceEvent>

    /** Stop native inference. Idempotent and non-blocking on reconnection. */
    suspend fun cancel(): com.adsamcik.mindlayer.CancelResult

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

        /**
         * Submit once for a tool call already surfaced in [events], while that
         * request is active. Collect [events] while answering calls; do not wait
         * for [awaitToolCalls] to complete. Unavailable when `onToolCall` handles
         * results automatically. Unknown, answered, or expired IDs are rejected.
         */
        suspend fun submitToolResult(callId: String, resultJson: String)
    }
}

/**
 * Production [InferenceHandle]. Implements all three output subtypes
 * simultaneously; the canonical builder returns it typed to the subtype that
 * matches the request's output mode. Carries the package-private sync-cancel
 * machinery used by the streaming impl and [Conversation].
 */
internal class InferenceHandleImpl(
    override val requestId: String,
    events: Flow<InferenceEvent>,
    override val sessionId: String = "",
    private val automaticToolResults: Boolean = false,
    private val submitToolResultCallback: (suspend (String, String, String) -> Unit)? = null,
) : InferenceHandle, InferenceHandle.Text, InferenceHandle.Structured, InferenceHandle.Tools {

    private val cancelled = AtomicBoolean(false)
    private val sourceEvents = events
    private var syncCancelCallback: (() -> Unit)? = null
    private var cancelCallback: (suspend () -> com.adsamcik.mindlayer.CancelResult)? = null
    private val toolLock = Any()
    private val pendingTools = mutableMapOf<String, String>()
    private val answeredTools = mutableSetOf<String>()
    private var requestEnded = false

    private fun endRequest() = synchronized(toolLock) {
        requestEnded = true
        pendingTools.clear()
    }

    override val events: Flow<InferenceEvent> = flow {
        var terminal = false
        try {
            sourceEvents.collect { event ->
                when (event) {
                    is InferenceEvent.ToolCall -> synchronized(toolLock) {
                        if (!automaticToolResults && !requestEnded && event.callId !in answeredTools) {
                            pendingTools.putIfAbsent(event.callId, event.toolName)
                        }
                    }
                    is InferenceEvent.Done, is InferenceEvent.Error -> {
                        terminal = true
                        endRequest()
                    }
                    else -> Unit
                }
                emit(event)
            }
        } finally {
            endRequest()
            if (!terminal) withContext(NonCancellable) { cancel() }
        }
    }

    /** Read-only cancellation state for internal callers and the test suite. */
    internal val isCancelled: Boolean get() = cancelled.get()

    /**
     * Sets a non-suspend cancel callback used by [cancelSync]. Cleanup paths
     * (notably [Conversation.close]) must cancel without blocking on
     * `awaitConnected()`; the sync callback does best-effort work against the
     * cached binder and silently no-ops when no connection is available.
     */
    internal fun setSyncCancelCallback(cb: () -> Unit) {
        syncCancelCallback = cb
    }

    internal fun setCancelCallback(cb: suspend () -> com.adsamcik.mindlayer.CancelResult) {
        cancelCallback = cb
    }

    override suspend fun cancel(): com.adsamcik.mindlayer.CancelResult {
        endRequest()
        if (cancelled.getAndSet(true)) {
            return com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED,
            )
        }
        return cancelCallback?.invoke() ?: com.adsamcik.mindlayer.CancelResult(
            outcome = com.adsamcik.mindlayer.CancelResult.UNKNOWN,
        )
    }

    /** Non-suspend teardown for synchronous cleanup paths. Idempotent. */
    internal fun cancelSync() {
        endRequest()
        if (cancelled.getAndSet(true)) return
        syncCancelCallback?.invoke()
    }

    override suspend fun awaitText(): String = collectText()

    override suspend fun awaitJson(): JsonObject = parseLenientJson(collectText())

    override suspend fun awaitToolCalls(): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        events.throwOnError().collect { event ->
            if (event is InferenceEvent.ToolCall) {
                calls += ToolCall(
                    callId = event.callId,
                    name = event.toolName,
                    argsJson = event.arguments,
                )
            }
        }
        return calls
    }

    override suspend fun submitToolResult(callId: String, resultJson: String) {
        val callback = checkNotNull(submitToolResultCallback) { "Tool results are unavailable for this handle" }
        val toolName = synchronized(toolLock) {
            check(!automaticToolResults) { "Tool results are handled by onToolCall" }
            check(!requestEnded && !cancelled.get()) { "Inference request has ended" }
            require(callId !in answeredTools) { "Tool call has already been answered" }
            val name = requireNotNull(pendingTools.remove(callId)) { "Unknown or expired tool call ID" }
            answeredTools += callId
            name
        }
        callback(callId, toolName, resultJson)
    }

    /**
     * Collect the event stream and accumulate generated text. Prefers the
     * concatenated [InferenceEvent.TextDelta]s; falls back to
     * [InferenceEvent.Done.fullText] for sources that only report a terminal
     * payload. Surfaces an [InferenceEvent.Error] frame as a [MindlayerException].
     */
    private suspend fun collectText(): String {
        val sb = StringBuilder()
        var doneFullText: String? = null
        events.throwOnError().collect { event ->
            when (event) {
                is InferenceEvent.TextDelta -> sb.append(event.text)
                is InferenceEvent.Done -> doneFullText = event.fullText
                else -> Unit
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else (doneFullText ?: "")
    }

    private companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parse model output into a [JsonObject], tolerating the markdown
         * fences and surrounding prose that instruction-tuned models often add
         * around schema-constrained JSON.
         */
        fun parseLenientJson(raw: String): JsonObject {
            val stripped = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val candidate = outermostBraces(stripped) ?: stripped
            return lenientJson.parseToJsonElement(candidate) as JsonObject
        }

        private fun outermostBraces(text: String): String? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start in 0 until end) text.substring(start, end + 1) else null
        }
    }
}
