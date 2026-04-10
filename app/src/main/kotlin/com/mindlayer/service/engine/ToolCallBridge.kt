package com.mindlayer.service.engine

import com.mindlayer.service.logging.MindlayerLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the tool-call loop between the inference streaming coroutine
 * and client-side [submitResult] calls arriving via AIDL.
 *
 * Flow:
 *  1. Streaming detects tool calls → [registerPendingToolCalls] stores them
 *  2. Tool call events are written to the pipe for the client
 *  3. Streaming suspends on [awaitResults] until the client submits every
 *     pending result via [submitResult]
 *  4. Results are returned to the orchestrator for injection into the
 *     conversation
 *
 * Thread-safety: the [pending] map is a [ConcurrentHashMap]; individual
 * [PendingToolCall.resultDeferred] instances are coroutine-safe by design.
 */
class ToolCallBridge {

    companion object {
        private const val TAG = "ToolCallBridge"
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }

    data class PendingToolCall(
        val requestId: String,
        val callId: String,
        val toolName: String,
        val arguments: String,
        val resultDeferred: CompletableDeferred<String> = CompletableDeferred(),
    )

    /** requestId → list of pending tool calls for that inference request. */
    private val pending = ConcurrentHashMap<String, MutableList<PendingToolCall>>()

    /**
     * Register tool calls that the model wants executed.
     *
     * Generates a unique [PendingToolCall.callId] for each call and stores
     * them keyed by [requestId]. Returns the list so the caller can write
     * corresponding pipe events.
     */
    fun registerPendingToolCalls(
        requestId: String,
        toolCalls: List<Pair<String, String>>,
    ): List<PendingToolCall> {
        val calls = toolCalls.map { (name, args) ->
            PendingToolCall(
                requestId = requestId,
                callId = UUID.randomUUID().toString(),
                toolName = name,
                arguments = args,
            )
        }
        pending[requestId] = calls.toMutableList()
        MindlayerLog.d(TAG, "Registered ${calls.size} pending tool call(s) for request $requestId", requestId = requestId)
        return calls
    }

    /**
     * Called from the AIDL binder thread when the client submits a tool result.
     *
     * Completes the [CompletableDeferred] of the first unfinished pending call
     * matching [toolName], unblocking the streaming coroutine in [awaitResults].
     */
    fun submitResult(requestId: String, toolName: String, resultJson: String) {
        val calls = pending[requestId]
        if (calls == null) {
            MindlayerLog.w(TAG, "submitResult: no pending calls for request $requestId", requestId = requestId)
            return
        }

        val match = synchronized(calls) {
            calls.firstOrNull { it.toolName == toolName && !it.resultDeferred.isCompleted }
        }

        if (match == null) {
            MindlayerLog.w(TAG, "submitResult: no pending call for tool '$toolName' in request $requestId", requestId = requestId)
            return
        }

        match.resultDeferred.complete(resultJson)
        MindlayerLog.d(TAG, "Result submitted for tool '$toolName' in request $requestId", requestId = requestId)
    }

    /**
     * Suspend until every pending tool call for [requestId] has a result, or
     * [timeoutMs] expires (throws [kotlinx.coroutines.TimeoutCancellationException]).
     *
     * Returns `(toolName, resultJson)` pairs in the same order as registration.
     * Cleans up the pending entry regardless of outcome.
     */
    suspend fun awaitResults(
        requestId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<Pair<String, String>> {
        val calls = pending[requestId]
            ?: throw IllegalStateException("No pending tool calls for request $requestId")

        return try {
            withTimeout(timeoutMs) {
                calls.map { call ->
                    val result = call.resultDeferred.await()
                    call.toolName to result
                }
            }
        } finally {
            pending.remove(requestId)
        }
    }

    /** Clean up pending calls for a request (e.g. on cancellation). */
    fun cancel(requestId: String) {
        pending.remove(requestId)?.forEach { call ->
            call.resultDeferred.cancel()
        }
    }
}
