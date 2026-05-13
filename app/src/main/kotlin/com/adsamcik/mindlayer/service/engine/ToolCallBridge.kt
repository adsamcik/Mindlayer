package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.LogExtras
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.loggable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the tool-call loop between the inference streaming coroutine
 * and client-side [submitResult] calls arriving via AIDL.
 *
 * Internal map keys are **scoped keys** (`uid:publicRequestId`) so that two
 * authorized callers with colliding public `requestId` values cannot poison
 * each other's pending tool-call list (F-007). The orchestrator and the
 * binder are the only callers; both already handle scoping.
 *
 * Flow:
 *  1. Streaming detects tool calls → [registerPendingToolCalls] stores them
 *  2. Tool call events are written to the pipe for the client
 *  3. Streaming suspends on [awaitResults] until the client submits every
 *     pending result via [submitResult], correlated by callId and tool name
 *  4. Results are returned to the orchestrator for injection into the
 *     conversation
 *
 * H3a — bookkeeping is keyed by the composite `(uid, requestId)` rather than
 * the raw client-supplied request id. Two unrelated client apps may pick the
 * same request id; without uid isolation, app B could submit a tool result
 * that fulfils app A's pending call. The composite key prevents that without
 * forcing a globally-unique id scheme on clients.
 *
 * Thread-safety: the [pending] map is a [ConcurrentHashMap]; individual
 * [PendingToolCall.resultDeferred] instances are coroutine-safe by design.
 */
class ToolCallBridge(
    private val logRepository: LogRepository? = null,
) {

    companion object {
        private const val TAG = "ToolCallBridge"
        /**
         * F-061: a single tool round-trip times out after 30 s. Reduced from
         * the previous 60 s because the orchestrator now enforces a
         * 5-minute total wall-clock cap on the whole inference; long tool
         * round-trips were the dominant way to get close to the old cap.
         */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    data class PendingToolCall(
        val scopedKey: String,
        val callId: String,
        val toolName: String,
        val arguments: String,
        val resultDeferred: CompletableDeferred<String> = CompletableDeferred(),
    )

    /** scopedKey → list of pending tool calls for that inference request. */
    private val pending = ConcurrentHashMap<String, MutableList<PendingToolCall>>()

    private fun requestLabel(scopedKey: String): String =
        scopedKey.substringAfter(':', scopedKey).loggable()

    /**
     * Register tool calls that the model wants executed.
     *
     * Generates a unique [PendingToolCall.callId] for each call and stores
     * them keyed by the orchestrator-supplied [scopedKey]. Returns the list
     * so the caller can write corresponding pipe events.
     */
    fun registerPendingToolCalls(
        scopedKey: String,
        toolCalls: List<Pair<String, String>>,
    ): List<PendingToolCall> {
        val calls = toolCalls.map { (name, args) ->
            PendingToolCall(
                scopedKey = scopedKey,
                callId = UUID.randomUUID().toString(),
                toolName = name,
                arguments = args,
            )
        }
        pending[scopedKey] = calls.toMutableList()
        MindlayerLog.d(
            TAG,
            "Registered ${calls.size} pending tool call(s) for request ${requestLabel(scopedKey)}",
        )
        return calls
    }

    /**
     * Called from the AIDL binder thread when the client submits a tool result.
     *
     * [scopedKey] is the binder-side namespaced key derived from the caller's
     * UID and the public requestId. Completes the [CompletableDeferred] of
     * the unfinished pending call matching both [callId] and [toolName],
     * unblocking the streaming coroutine in [awaitResults].
     *
     * If no pending call matches, all remaining pending entries for this
     * [scopedKey] are failed immediately so [awaitResults] unblocks fast
     * rather than waiting for the full timeout (H3).
     *
     * @return `true` when a matching pending call was found and the result
     *   was delivered; `false` when the request has no pending calls or no
     *   call with the given (callId, toolName) is awaiting a result. The
     *   return value powers the v0.4 [com.adsamcik.mindlayer.ToolSubmitResult]
     *   tri-state.
     */
    fun submitResult(scopedKey: String, callId: String, toolName: String, resultJson: String): Boolean {
        val calls = pending[scopedKey]
        if (calls == null) {
            MindlayerLog.w(
                TAG,
                "submitResult: no pending calls for request ${requestLabel(scopedKey)}",
            )
            return false
        }

        val match = synchronized(calls) {
            calls.firstOrNull {
                it.callId == callId && it.toolName == toolName && !it.resultDeferred.isCompleted
            }
        }

        if (match == null) {
            // H3 — surface mismatched submitResult IMMEDIATELY rather than letting awaitResults block.
            val toolMetadata = LogExtras.toolNameMetadata(toolName)
            MindlayerLog.w(
                TAG,
                "submitResult: no pending call for call ${callId.loggable()} " +
                    "toolMetadata=$toolMetadata request ${requestLabel(scopedKey)} — failing pending entries",
            )
            // Fail every still-pending entry for this request so awaitResults unblocks fast.
            synchronized(calls) {
                calls.filter { !it.resultDeferred.isCompleted }.forEach {
                    it.resultDeferred.completeExceptionally(
                        IllegalStateException(
                            "Client submitted result for unmatched tool call (callId=${callId.loggable()})",
                        ),
                    )
                }
            }
            logRepository?.logToolCallExit(
                requestId = scopedKey.substringAfter(':', scopedKey),
                sessionId = null,
                result = "unmatched_submit",
                pendingCount = calls.size,
            )
            return false
        }

        match.resultDeferred.complete(resultJson)
        logRepository?.logToolCallExit(
            requestId = scopedKey.substringAfter(':', scopedKey),
            sessionId = null,
            result = "submitted",
            pendingCount = calls.count { !it.resultDeferred.isCompleted },
        )
        MindlayerLog.d(
            TAG,
            "Result submitted for call ${callId.loggable()} " +
                "toolMetadata=${LogExtras.toolNameMetadata(toolName)} " +
                "request ${requestLabel(scopedKey)}",
        )
        return true
    }

    /**
     * Suspend until every pending tool call for [scopedKey] has a result, or
     * [timeoutMs] expires (throws [kotlinx.coroutines.TimeoutCancellationException]).
     *
     * Returns `(toolName, resultJson)` pairs in the same order as registration.
     * Cleans up the pending entry regardless of outcome.
     */
    suspend fun awaitResults(
        scopedKey: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<Pair<String, String>> {
        val calls = pending[scopedKey]
            ?: throw IllegalStateException(
                "No pending tool calls for request ${requestLabel(scopedKey)}"
            )

        return try {
            withTimeout(timeoutMs) {
                calls.map { call ->
                    val result = call.resultDeferred.await()
                    call.toolName to result
                }
            }
        } finally {
            pending.remove(scopedKey)
        }
    }

    /** Clean up pending calls for a request (e.g. on cancellation). */
    fun cancel(scopedKey: String) {
        pending.remove(scopedKey)?.forEach { call ->
            call.resultDeferred.cancel()
        }
    }
}
