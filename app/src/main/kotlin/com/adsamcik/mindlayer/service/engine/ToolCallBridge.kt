package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.MindlayerLog
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
 * H3a — bookkeeping is keyed by the composite `(uid, requestId)` rather than
 * the raw client-supplied request id. Two unrelated client apps may pick the
 * same request id; without uid isolation, app B could submit a tool result
 * that fulfils app A's pending call. The composite key prevents that without
 * forcing a globally-unique id scheme on clients.
 *
 * Thread-safety: the [pending] map is a [ConcurrentHashMap]; individual
 * [PendingToolCall.resultDeferred] instances are coroutine-safe by design.
 */
class ToolCallBridge {

    companion object {
        private const val TAG = "ToolCallBridge"
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** Sentinel uid used by legacy callers that do not supply one. */
        const val LEGACY_UID = -1
    }

    data class PendingToolCall(
        val requestId: String,
        val callId: String,
        val toolName: String,
        val arguments: String,
        val resultDeferred: CompletableDeferred<String> = CompletableDeferred(),
    )

    /** "uid:requestId" → list of pending tool calls for that inference request. */
    private val pending = ConcurrentHashMap<String, MutableList<PendingToolCall>>()

    private fun key(uid: Int, requestId: String): String = "$uid:$requestId"

    /**
     * Register tool calls that the model wants executed.
     *
     * Generates a unique [PendingToolCall.callId] for each call and stores
     * them keyed by `(uid, requestId)`. Returns the list so the caller can
     * write corresponding pipe events.
     */
    fun registerPendingToolCalls(
        uid: Int,
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
        pending[key(uid, requestId)] = calls.toMutableList()
        MindlayerLog.d(
            TAG,
            "Registered ${calls.size} pending tool call(s) for uid=$uid request $requestId",
            requestId = requestId,
        )
        return calls
    }

    /** Backwards-compat overload (uid omitted → [LEGACY_UID]). */
    fun registerPendingToolCalls(
        requestId: String,
        toolCalls: List<Pair<String, String>>,
    ): List<PendingToolCall> = registerPendingToolCalls(LEGACY_UID, requestId, toolCalls)

    /**
     * Called from the AIDL binder thread when the client submits a tool result.
     *
     * When [callId] is provided, routes to the exact matching pending call (post-C1
     * clients).  Falls back to first-unfinished by [toolName] for legacy clients that
     * do not supply a callId.
     *
     * If no pending call matches, all remaining pending entries are failed immediately
     * so [awaitResults] unblocks fast rather than waiting for the full timeout (H3).
     *
     * H3a — [uid] selects the per-app slot. Submissions from other uids cannot
     * see or fulfil a pending call belonging to a different uid even when the
     * raw [requestId] strings collide.
     */
    fun submitResult(uid: Int, requestId: String, callId: String?, toolName: String, resultJson: String) {
        val calls = pending[key(uid, requestId)]
        if (calls == null) {
            MindlayerLog.w(
                TAG,
                "submitResult: no pending calls for uid=$uid request $requestId",
                requestId = requestId,
            )
            return
        }

        val match = synchronized(calls) {
            // 1. Prefer exact callId match (post-C1 clients).
            if (callId != null) {
                val byId = calls.firstOrNull { it.callId == callId && !it.resultDeferred.isCompleted }
                if (byId != null) return@synchronized byId
            }
            // 2. Fall back to first-unfinished by toolName (legacy clients).
            calls.firstOrNull { it.toolName == toolName && !it.resultDeferred.isCompleted }
        }

        if (match == null) {
            // H3 — surface mismatched submitResult IMMEDIATELY rather than letting awaitResults block.
            MindlayerLog.w(
                TAG,
                "submitResult: no pending call matches (callId=$callId, tool='$toolName') for uid=$uid request $requestId — failing pending entries",
                requestId = requestId,
            )
            // Fail every still-pending entry for this request so awaitResults unblocks fast.
            synchronized(calls) {
                calls.filter { !it.resultDeferred.isCompleted }.forEach {
                    it.resultDeferred.completeExceptionally(
                        IllegalStateException(
                            "Client submitted result for unmatched tool call (callId=$callId, tool='$toolName')",
                        ),
                    )
                }
            }
            return
        }

        match.resultDeferred.complete(resultJson)
        MindlayerLog.d(
            TAG,
            "Result submitted for tool '${match.toolName}' (callId=${match.callId}) in uid=$uid request $requestId",
            requestId = requestId,
        )
    }

    /** Backwards-compat overload — assumes [LEGACY_UID]. */
    fun submitResult(requestId: String, callId: String?, toolName: String, resultJson: String) =
        submitResult(LEGACY_UID, requestId, callId, toolName, resultJson)

    /** Backwards-compat overload — assumes [LEGACY_UID] and no callId. */
    fun submitResult(requestId: String, toolName: String, resultJson: String) =
        submitResult(LEGACY_UID, requestId, callId = null, toolName = toolName, resultJson = resultJson)

    /**
     * Suspend until every pending tool call for [uid]/[requestId] has a result, or
     * [timeoutMs] expires (throws [kotlinx.coroutines.TimeoutCancellationException]).
     *
     * Returns `(toolName, resultJson)` pairs in the same order as registration.
     * Cleans up the pending entry regardless of outcome.
     */
    suspend fun awaitResults(
        uid: Int,
        requestId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<Pair<String, String>> {
        val k = key(uid, requestId)
        val calls = pending[k]
            ?: throw IllegalStateException("No pending tool calls for uid=$uid request $requestId")

        return try {
            withTimeout(timeoutMs) {
                calls.map { call ->
                    val result = call.resultDeferred.await()
                    call.toolName to result
                }
            }
        } finally {
            pending.remove(k)
        }
    }

    /** Backwards-compat overload — assumes [LEGACY_UID]. */
    suspend fun awaitResults(
        requestId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<Pair<String, String>> = awaitResults(LEGACY_UID, requestId, timeoutMs)

    /** Clean up pending calls for a request (e.g. on cancellation). */
    fun cancel(uid: Int, requestId: String) {
        pending.remove(key(uid, requestId))?.forEach { call ->
            call.resultDeferred.cancel()
        }
    }

    /** Backwards-compat overload — assumes [LEGACY_UID]. */
    fun cancel(requestId: String) = cancel(LEGACY_UID, requestId)
}
