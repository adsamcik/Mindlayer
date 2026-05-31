package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.flow.Flow
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
 * ## Cancellation (Spike-E §8.4)
 *
 * There is no `cancel()` on the handle. Cancellation flows through structured
 * concurrency: cancel the coroutine collecting [events] and the AIDL request is
 * torn down via the stream's `awaitClose`. Cleanup paths internal to the SDK
 * (notably [Conversation.close]) tear an in-flight request down through the
 * impl's package-private sync-cancel machinery.
 */
sealed interface InferenceHandle {
    /** Unique request ID, available immediately (before collection starts). */
    val requestId: String

    /** Session this request runs in. Empty string when not yet assigned. */
    val sessionId: String

    /** Cold flow of inference events. Collect exactly once. */
    val events: Flow<InferenceEvent>

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
 * Production [InferenceHandle]. Implements all three output subtypes
 * simultaneously; the canonical builder returns it typed to the subtype that
 * matches the request's output mode. Carries the package-private sync-cancel
 * machinery used by the streaming impl and [Conversation].
 */
internal class InferenceHandleImpl(
    override val requestId: String,
    override val events: Flow<InferenceEvent>,
    override val sessionId: String = "",
) : InferenceHandle, InferenceHandle.Text, InferenceHandle.Structured, InferenceHandle.Tools {

    private val cancelled = AtomicBoolean(false)
    private var syncCancelCallback: (() -> Unit)? = null

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

    /** Non-suspend teardown for synchronous cleanup paths. Idempotent. */
    internal fun cancelSync() {
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

    override suspend fun submitToolResult(callId: String, resultJson: String) =
        throw NotImplementedError(
            "Mindlayer v1 — InferenceHandle.Tools.submitToolResult() is not wired in 1.0.0-alpha01; " +
                "use the streaming onToolCall(...) handler on the request builder instead.",
        )

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
