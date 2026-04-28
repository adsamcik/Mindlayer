package com.adsamcik.mindlayer.service.engine

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.RequestTrace
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.gson.Gson
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.StagedMedia
import com.adsamcik.mindlayer.service.ipc.TokenStreamWriter
import com.adsamcik.mindlayer.service.logging.LogCategory
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.LogEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put
import com.adsamcik.mindlayer.service.logging.logExtraJson
import java.util.concurrent.ConcurrentHashMap

/**
 * Central inference request handler.
 *
 * Receives requests from [ServiceBinder], delegates session lifecycle to
 * [SessionManager], streams tokens over pipes, and coordinates foreground
 * state with the service.
 *
 * Each session has a per-session mutex so sends are serialised
 * (single-writer), but distinct sessions can run concurrently.
 */
class InferenceOrchestrator(
    private val service: MindlayerMlService,
    private val sessionManager: SessionManager,
    private val sharedMemoryPool: SharedMemoryPool,
    private val logRepository: com.adsamcik.mindlayer.service.logging.LogRepository? = null,
    private val writerFactory: (ParcelFileDescriptor) -> TokenStreamWriter = ::TokenStreamWriter,
) {

    companion object {
        private const val TAG = "InferenceOrchestrator"
        private const val MAX_TOOL_ROUNDS = 25
        private val gson = Gson()
    }

    /** Extract concatenated text from a [Message]'s contents. */
    private fun Message.text(): String? {
        val parts = contents.contents.filterIsInstance<Content.Text>()
        return if (parts.isEmpty()) null else parts.joinToString("") { it.text }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Active inference jobs keyed by requestId for cancellation
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Bridge between the streaming coroutine and AIDL submitToolResult(). */
    val toolCallBridge = ToolCallBridge()

    // ---- Session management (delegates to SessionManager) ------------------

    fun createSession(config: SessionConfig): String =
        sessionManager.createSession(config)

    fun createSession(config: SessionConfig, ownerToken: Any?): String =
        sessionManager.createSession(config, ownerToken)

    fun closeAllOwnedBy(ownerToken: Any): List<String> =
        sessionManager.closeAllOwnedBy(ownerToken)

    fun getSessionOwner(sessionId: String): Any? =
        sessionManager.getSessionOwner(sessionId)

    fun listSessionsOwnedBy(ownerToken: Any): List<SessionInfo> =
        sessionManager.listSessionsOwnedBy(ownerToken)

    fun destroySession(sessionId: String) =
        sessionManager.destroySession(sessionId)

    fun getSessionInfo(sessionId: String): SessionInfo? =
        sessionManager.getSessionInfo(sessionId)

    fun listSessions(): List<SessionInfo> =
        sessionManager.listSessions()

    // ---- Inference ---------------------------------------------------------

    /**
     * Launch an inference request. Streams events to [pipeWriteEnd] and closes
     * it when generation completes (or fails).
     *
     * If [image] or [audio] are provided, they are staged to cache files and
     * included as multimodal [Content] parts alongside the text prompt.
     *
     * ⚠️ Gemma 4 multimodal is blocked by issue #1874 (missing prompt-template
     * override in LiteRT-LM). The wiring is architecturally correct but may not
     * produce valid output until that issue is resolved.
     */
    fun infer(
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        pipeWriteEnd: ParcelFileDescriptor,
    ) {
        infer(meta, image, audio, pipeWriteEnd, onComplete = null)
    }

    /**
     * Launch an inference with an optional [onComplete] callback invoked on
     * the orchestrator's coroutine scope when the request finishes (success,
     * error, or cancellation). Used by [ServiceBinder] to release per-UID
     * rate-limit concurrency slots.
     */
    fun infer(
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        pipeWriteEnd: ParcelFileDescriptor,
        onComplete: (() -> Unit)?,
    ) {
        val job = scope.launch {
            runInference(meta, image, audio, pipeWriteEnd)
        }
        activeJobs[meta.requestId] = job
        job.invokeOnCompletion {
            activeJobs.remove(meta.requestId)
            onComplete?.invoke()
        }
    }

    fun cancelInference(requestId: String) {
        val handle = sessionManager.findSessionByActiveRequest(requestId)
        if (handle != null) {
            MindlayerLog.i(TAG, "Cancelling native inference for request $requestId", requestId = requestId, sessionId = handle.sessionId)
            try {
                handle.conversation.cancelProcess()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "cancelProcess() failed: ${t.safeLabel()}", requestId = requestId)
            }
        }
        toolCallBridge.cancel(requestId)
        activeJobs[requestId]?.cancel()
        logRepository?.log(com.adsamcik.mindlayer.service.logging.LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = com.adsamcik.mindlayer.service.logging.LogCategory.INFERENCE,
            event = com.adsamcik.mindlayer.service.logging.LogEvent.REQUEST_CANCEL,
            requestId = requestId,
            sessionId = handle?.sessionId,
        ))
        MindlayerLog.i(TAG, "Cancelled request $requestId", requestId = requestId, sessionId = handle?.sessionId)
    }

    fun shutdown() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        sharedMemoryPool.cleanupAll()
        sessionManager.shutdown()
    }

    // ---- Private -----------------------------------------------------------

    private suspend fun runInference(
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        pipeWriteEnd: ParcelFileDescriptor,
    ) {
        val handle = sessionManager.getSession(meta.sessionId) ?: run {
            val writer = writerFactory(pipeWriteEnd)
            writer.closeWithError(0, "Unknown session: ${meta.sessionId}")
            return
        }

        val trace = RequestTrace(meta.requestId, meta.sessionId)

        // Stage media outside the session lock so PFD I/O doesn't block other
        // sessions. Staging is idempotent per requestId.
        var stagedImage: StagedMedia? = null
        var stagedAudio: StagedMedia? = null
        try {
            if (image != null) {
                stagedImage = sharedMemoryPool.stageImage(image)
                MindlayerLog.d(TAG, "Staged image for request", requestId = meta.requestId, sessionId = meta.sessionId)
            }
            if (audio != null) {
                stagedAudio = sharedMemoryPool.stageAudio(audio)
                MindlayerLog.d(TAG, "Staged audio for request", requestId = meta.requestId, sessionId = meta.sessionId)
            }
        } catch (t: Throwable) {
            MindlayerLog.e(TAG, "Media staging failed for request ${meta.requestId}: ${t.safeLabel()}", requestId = meta.requestId, sessionId = meta.sessionId)
            sharedMemoryPool.cleanup(meta.requestId)
            val writer = writerFactory(pipeWriteEnd)
            writer.closeWithError(0, "media_staging_failed: ${t.safeLabel()}")
            return
        }

        // Single-writer: serialize sends for this session
        handle.mutex.withLock {
            handle.activeRequestId = meta.requestId
            handle.isStreaming = true
            val writer = writerFactory(pipeWriteEnd)
            service.enterForeground()
            val inferenceStartNs = System.nanoTime()
            logRepository?.logInferenceStart(
                meta.requestId, meta.sessionId, service.engineManager.currentBackend
            )
            logRepository?.logUserMessage(
                meta.requestId,
                meta.sessionId,
                tokenCount = ((meta.textContent?.length ?: 0) / 4).coerceAtLeast(if (meta.textContent.isNullOrEmpty()) 0 else 1),
            )
            try {
                writer.writeHeader(meta.requestId)

                // Build multimodal content parts.
                // ⚠️ Gemma 4 multimodal is blocked by issue #1874 (missing
                // prompt-template override). The Content parts are wired
                // correctly but may not produce valid output until resolved.
                val parts = mutableListOf<Content>()
                val textContent = meta.textContent
                if (textContent != null) {
                    parts.add(Content.Text(textContent))
                }
                if (stagedImage != null) {
                    parts.add(Content.ImageFile(stagedImage.filePath))
                }
                if (stagedAudio != null) {
                    parts.add(Content.AudioFile(stagedAudio.filePath))
                }

                val contents = if (parts.isNotEmpty()) {
                    Contents.of(*parts.toTypedArray())
                } else {
                    Contents.of("")
                }

                // Rough input-token estimate (≈ 1 token per 4 chars for text)
                val prompt = meta.textContent ?: ""
                handle.estimatedTokens += (prompt.length / 4).coerceAtLeast(1)

                var seq = 1L
                var toolCallRound = 0
                var requestTokenCount = 0
                var firstTokenSeen = false
                val accumulatedToolCalls = mutableListOf<Pair<String, String>>()

                val soConfig = handle.structuredOutputConfig
                val isToolRouting =
                    soConfig?.strategy == StructuredOutputStrategy.TOOL_ROUTING
                val isPromptValidate =
                    soConfig?.strategy == StructuredOutputStrategy.PROMPT_AND_VALIDATE
                // Buffer response for PROMPT_AND_VALIDATE (skip streaming
                // so we can validate + retry before sending to the client)
                val responseBuffer =
                    if (isPromptValidate) StringBuilder() else null

                // --- First round: stream user message -----------------------
                trace.markPrefillStart()
                handle.conversation.sendMessageAsync(contents).collect { chunk ->
                    val text = chunk.text()
                    if (!text.isNullOrEmpty()) {
                        if (isPromptValidate) {
                            responseBuffer!!.append(text)
                        } else {
                            writer.writeTokenDelta(seq, text)
                            seq++
                        }
                        handle.estimatedTokens++
                        requestTokenCount++
                        if (!firstTokenSeen) {
                            trace.markFirstToken()
                            firstTokenSeen = true
                        }
                    }
                    for (tc in chunk.toolCalls) {
                        accumulatedToolCalls.add(tc.name to gson.toJson(tc.arguments))
                    }
                }

                // --- Structured output: TOOL_ROUTING extraction -------------
                if (isToolRouting && accumulatedToolCalls.isNotEmpty()) {
                    val structuredResult = StructuredOutputHelper.extractStructuredResult(
                        accumulatedToolCalls,
                    )
                    if (structuredResult != null) {
                        accumulatedToolCalls.removeAll {
                            it.first == StructuredOutputHelper.TOOL_NAME
                        }
                        writer.writeTokenDelta(seq, structuredResult)
                        seq++
                        handle.estimatedTokens++
                        requestTokenCount++
                    }
                }

                // --- Structured output: PROMPT_AND_VALIDATE retry -----------
                if (isPromptValidate && responseBuffer != null) {
                    var output = responseBuffer.toString()
                    for (attempt in 0..soConfig.maxRetries) {
                        when (val result = StructuredOutputHelper.validateJsonOutput(
                            output, soConfig.schema,
                        )) {
                            is ValidationResult.Valid -> {
                                output = result.json
                                break
                            }
                            is ValidationResult.Invalid -> {
                                if (attempt >= soConfig.maxRetries) {
                                    MindlayerLog.w(TAG, "Structured output: validation failed " +
                                        "after $attempt retries, using last output", requestId = meta.requestId, sessionId = meta.sessionId)
                                    break
                                }
                                MindlayerLog.w(TAG, "Structured output: validation failed " +
                                    "(retry ${attempt + 1}/${soConfig.maxRetries}): " +
                                    "${result.errors}", requestId = meta.requestId, sessionId = meta.sessionId)
                                val retryPrompt = StructuredOutputHelper.buildRetryPrompt(
                                    result.errors, soConfig.schema,
                                )
                                val retryBuf = StringBuilder()
                                handle.conversation
                                    .sendMessageAsync(Contents.of(retryPrompt))
                                    .collect { chunk ->
                                        chunk.text()?.let { retryBuf.append(it) }
                                    }
                                output = retryBuf.toString()
                            }
                        }
                    }
                    writer.writeTokenDelta(seq, output)
                    seq++
                    handle.estimatedTokens++
                    requestTokenCount++
                }

                // --- Tool call loop -----------------------------------------
                while (accumulatedToolCalls.isNotEmpty() && toolCallRound < MAX_TOOL_ROUNDS) {
                    // TOOL_ROUTING: intercept synthetic tool before forwarding
                    if (isToolRouting) {
                        val structuredResult = StructuredOutputHelper
                            .extractStructuredResult(accumulatedToolCalls)
                        if (structuredResult != null) {
                            accumulatedToolCalls.removeAll {
                                it.first == StructuredOutputHelper.TOOL_NAME
                            }
                            writer.writeTokenDelta(seq, structuredResult)
                            seq++
                            handle.estimatedTokens++
                            requestTokenCount++
                            if (accumulatedToolCalls.isEmpty()) break
                        }
                    }

                    toolCallRound++
                    MindlayerLog.i(TAG, "Tool call round $toolCallRound for request ${meta.requestId} " +
                        "(${accumulatedToolCalls.size} call(s))", requestId = meta.requestId, sessionId = meta.sessionId)

                    val pending = toolCallBridge.registerPendingToolCalls(
                        meta.requestId, accumulatedToolCalls.toList()
                    )
                    for (call in pending) {
                        writer.writeToolCall(seq, call.callId, call.toolName, call.arguments)
                        seq++
                        logRepository?.log(LogEntry(
                            timestampMs = System.currentTimeMillis(),
                            category = LogCategory.INFERENCE,
                            event = LogEvent.TOOL_CALL,
                            requestId = meta.requestId,
                            sessionId = meta.sessionId,
                            extraJson = logExtraJson {
                                put("tool", call.toolName)
                                put("round", toolCallRound)
                            },
                        ))
                    }
                    accumulatedToolCalls.clear()

                    // Suspend until client submits all tool results (or timeout)
                    val results = toolCallBridge.awaitResults(meta.requestId)

                    // Inject tool responses into the conversation
                    val toolResponses = results.map { (name, result) ->
                        Content.ToolResponse(name, result)
                    }
                    val response = handle.conversation.sendMessage(
                        Message.tool(Contents.of(toolResponses))
                    )

                    // Write the model's continuation text
                    val text = response.text()
                    if (!text.isNullOrEmpty()) {
                        writer.writeTokenDelta(seq, text)
                        seq++
                        handle.estimatedTokens++
                        requestTokenCount++
                    }

                    // Check whether the model wants more tool calls
                    for (tc in response.toolCalls) {
                        accumulatedToolCalls.add(tc.name to gson.toJson(tc.arguments))
                    }
                }

                val finishReason = when {
                    toolCallRound >= MAX_TOOL_ROUNDS -> "tool_call_limit"
                    else -> "stop"
                }
                trace.markDecodeEnd(requestTokenCount)
                writer.writeDone(seq, finishReason)
                trace.markPipeWriteComplete()
                handle.turnCount++
                handle.recordAccess()

                // Log inference completion with metrics
                val durationMs = (System.nanoTime() - inferenceStartNs) / 1_000_000
                val tokensGenerated = handle.estimatedTokens
                val tokPerSec = if (durationMs > 0) tokensGenerated * 1000f / durationMs else 0f
                logRepository?.logModelResponse(
                    meta.requestId, meta.sessionId, tokenCount = requestTokenCount,
                )
                logRepository?.logInferenceComplete(
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                    backend = service.engineManager.currentBackend,
                    durationMs = durationMs,
                    tokensGenerated = tokensGenerated,
                    tokensPerSec = tokPerSec,
                    prefillTps = null,
                )
                MindlayerLog.i(TAG, trace.summary(), requestId = meta.requestId, sessionId = meta.sessionId)

            } catch (e: CancellationException) {
                toolCallBridge.cancel(meta.requestId)
                // Flow cancellation does NOT cancel native generation — the LiteRT-LM
                // engine keeps decoding in the background unless we ask it to stop.
                // This matters on broken-pipe (client died mid-stream) because the
                // writer raises CancellationException to unwind the coroutine.
                try {
                    handle.conversation.cancelProcess()
                } catch (t: Throwable) {
                    MindlayerLog.w(TAG, "cancelProcess() after CancellationException raised ${t.safeLabel()}", requestId = meta.requestId, sessionId = meta.sessionId)
                }
                trace.markError("cancelled")
                MindlayerLog.i(TAG, "Inference cancelled for request ${meta.requestId}", requestId = meta.requestId, sessionId = meta.sessionId)
                writer.writeDone(0, "cancelled")
                throw e
            } catch (t: Throwable) {
                toolCallBridge.cancel(meta.requestId)
                val safe = t.safeLabel()
                trace.markError(safe)
                MindlayerLog.e(TAG, "Inference failed for request ${meta.requestId}: $safe", requestId = meta.requestId, sessionId = meta.sessionId)
                logRepository?.logInferenceError(
                    meta.requestId, meta.sessionId, safe
                )
                writer.closeWithError(0, safe)
                return
            } finally {
                writer.close()
                handle.activeRequestId = null
                handle.isStreaming = false
                service.exitForeground()
                // Clean up staged media files regardless of outcome
                sharedMemoryPool.cleanup(meta.requestId)
            }
        }
    }
}
