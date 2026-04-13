package com.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.mindlayer.EngineInfo
import com.mindlayer.HistoryTurn
import com.mindlayer.ModelInfoParcel
import com.mindlayer.RequestMeta
import com.mindlayer.ServiceStatus
import com.mindlayer.SessionConfig
import com.mindlayer.SessionInfo
import com.mindlayer.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.UUID

/**
 * Main public entry-point for the Mindlayer SDK.
 *
 * Usage:
 * ```
 * val mindlayer = Mindlayer.connect(context)
 * val sessionId = mindlayer.createSession { systemPrompt("You are helpful") }
 * val handle = mindlayer.chat(sessionId, "Hello!")
 * handle.events.collect { event ->
 *     when (event) {
 *         is MindlayerEvent.TextDelta -> print(event.text)
 *         is MindlayerEvent.Done     -> println("\n${event.finishReason}")
 *         else -> { /* metrics, tool calls, … */ }
 *     }
 * }
 * // To cancel: handle.cancel()
 * mindlayer.disconnect()
 * ```
 */
class Mindlayer private constructor(
    internal val connection: ConnectionManager,
    private val historyStore: HistoryStore?,
) {

    companion object {
        private const val TAG = "Mindlayer"

        /**
         * Create a [Mindlayer] instance and start binding to the service.
         * Use [awaitConnected] or observe [connectionState] before issuing RPCs.
         */
        fun connect(context: Context): Mindlayer {
            val mgr = ConnectionManager()
            mgr.connect(context)
            val store = HistoryStore(context)
            return Mindlayer(mgr, store)
        }
    }

    /**
     * Session recovery helper. `null` when [historyStore] is disabled.
     */
    val recovery: SessionRecovery? =
        historyStore?.let { SessionRecovery(this, it) }

    /** Observable connection state. */
    val connectionState: StateFlow<ConnectionState>
        get() = connection.state

    /** Suspend until the service binder is available. */
    suspend fun awaitConnected() {
        connection.awaitConnected()
    }

    /** Unbind from the service and release resources. */
    fun disconnect() {
        connection.disconnect()
    }

    // -- Prewarm --------------------------------------------------------------

    /**
     * Pre-warms the LLM engine in the background. Call this early (e.g., when
     * scan screen opens) so the first [createSession] doesn't pay the 5-10s
     * init cost. Safe to call multiple times — subsequent calls are no-ops if
     * the engine is already loaded.
     *
     * @param backend the preferred backend to initialize ("GPU", "CPU", "NPU").
     */
    suspend fun prewarm(backend: String = "GPU") {
        val service = connection.awaitConnected()
        service.prewarm(backend)
    }

    // -- Session management ---------------------------------------------------

    /**
     * Create a new inference session.
     *
     * Uses a local-first saga: persist locally as CREATING, then create the
     * remote session, then confirm locally as READY. If the remote call fails,
     * the local CREATING record is cleaned up immediately.
     *
     * @param configure optional DSL block to customise [SessionConfig].
     * @return the server-assigned session ID.
     */
    suspend fun createSession(
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String {
        val config = SessionConfigBuilder().apply(configure).build()

        // 1. Persist locally as CREATING (survives process death)
        val tentativeId = config.sessionId ?: java.util.UUID.randomUUID().toString()
        val configWithId = if (config.sessionId == null) {
            config.copy(sessionId = tentativeId)
        } else {
            config
        }
        historyStore?.prepareConversation(tentativeId, configWithId)

        // 2. Create remote session
        val sessionId = try {
            connection.awaitConnected().createSession(configWithId)
        } catch (e: Exception) {
            // Remote creation failed — clean up local CREATING record
            historyStore?.cleanupConversation(tentativeId)
            throw e
        }

        // 3. Confirm local record
        historyStore?.confirmConversation(sessionId)

        return sessionId
    }

    /** Destroy a session and free server-side resources. */
    suspend fun destroySession(sessionId: String) {
        connection.awaitConnected().destroySession(sessionId)
    }

    /** Get info for a single session. */
    suspend fun getSessionInfo(sessionId: String): SessionInfo {
        return connection.awaitConnected().getSessionInfo(sessionId)
    }

    /** List all active sessions. */
    suspend fun listSessions(): List<SessionInfo> {
        return connection.awaitConnected().listSessions()
    }

    // -- Chat (text only) -----------------------------------------------------

    /**
     * Send a text message and stream back inference events.
     *
     * Creates a reliable pipe, hands the write end to the service via AIDL,
     * and returns an [InferenceHandle] that provides the [requestId], event
     * [Flow], and a [cancel][InferenceHandle.cancel] function that reaches
     * through to the service's native cancel.
     *
     * History persistence: the user turn is saved BEFORE IPC and the
     * assistant turn is marked COMPLETED only after the [MindlayerEvent.Done]
     * event.
     */
    fun chat(sessionId: String, text: String): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            image = null,
            audio = null,
        )
        return buildHandle(requestId, flow)
    }

    // -- Chat with image ------------------------------------------------------

    /**
     * Send a text + Bitmap message and stream back inference events.
     */
    fun chatWithImage(
        sessionId: String,
        text: String,
        bitmap: Bitmap,
    ): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val imageTransfer = MediaTransfer.fromBitmap(requestId, bitmap)
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            image = imageTransfer,
            audio = null,
        )
        return buildHandle(requestId, flow)
    }

    // -- Chat with audio ------------------------------------------------------

    /**
     * Send a text + audio file message and stream back inference events.
     */
    fun chatWithAudio(
        sessionId: String,
        text: String,
        audioFile: File,
    ): InferenceHandle {
        val requestId = UUID.randomUUID().toString()
        val audioTransfer = MediaTransfer.fromAudioFile(requestId, audioFile)
        val flow = startTrackedInference(
            sessionId = sessionId,
            userText = text,
            meta = RequestMeta(
                requestId = requestId,
                sessionId = sessionId,
                textContent = text,
            ),
            image = null,
            audio = audioTransfer,
        )
        return buildHandle(requestId, flow)
    }

    // -- Tool calling ---------------------------------------------------------

    /**
     * Submit a tool result back to the service for continued inference.
     */
    suspend fun submitToolResult(
        requestId: String,
        toolName: String,
        resultJson: String,
    ) {
        val result = ToolResult(
            requestId = requestId,
            toolName = toolName,
            resultJson = resultJson,
        )
        connection.awaitConnected().submitToolResult(requestId, result)
    }

    /** Cancel an in-flight inference request. */
    suspend fun cancelInference(requestId: String) {
        connection.awaitConnected().cancelInference(requestId)
    }

    // -- Service status -------------------------------------------------------

    /** Get the current service status (engine loaded, thermals, etc.). */
    suspend fun getStatus(): ServiceStatus {
        return connection.awaitConnected().status
    }

    /** Get engine info (model path, perf stats, etc.). */
    suspend fun getEngineInfo(): EngineInfo {
        return connection.awaitConnected().engineInfo
    }

    /** Get a diagnostic JSON dump for bug reports and troubleshooting. */
    suspend fun getDiagnostics(): String {
        return connection.awaitConnected().diagnostics
    }

    /** List all available models on the device. */
    suspend fun listModels(): List<ModelInfoParcel> {
        return connection.awaitConnected().listModels()
    }

    // -- Convenience ----------------------------------------------------------

    /**
     * Create a [MindlayerSession] scoped to a single session for a more
     * ergonomic chat API.
     */
    fun session(sessionId: String): MindlayerSession =
        MindlayerSession(this, sessionId)

    // -- One-shot convenience ------------------------------------------------

    /**
     * Send a text message and return the complete response text.
     *
     * Collects the streaming [chat] flow to completion and returns the
     * accumulated text. Throws [MindlayerException] on service errors.
     *
     * @throws MindlayerException if the service reports an error or sends
     *   an unexpected [MindlayerEvent.ToolCall] (tool calling is not
     *   supported in one-shot mode).
     * @throws IllegalStateException if the stream ends without a terminal
     *   [MindlayerEvent.Done] event.
     */
    suspend fun chatOnce(sessionId: String, text: String): String {
        var result: String? = null
        val accumulator = StringBuilder()

        chat(sessionId, text).events.collect { event ->
            when (event) {
                is MindlayerEvent.TextDelta -> accumulator.append(event.text)
                is MindlayerEvent.Done -> {
                    result = event.fullText ?: accumulator.toString()
                }
                is MindlayerEvent.Error -> throw MindlayerException(
                    message = event.message,
                    code = event.code,
                )
                is MindlayerEvent.ToolCall -> throw MindlayerException(
                    message = "Tool calls are not supported in one-shot mode. " +
                        "Use the streaming chat() API with a ToolCall handler instead.",
                    code = "UNSUPPORTED_TOOL_CALL",
                )
                else -> { /* Started, Metrics — ignored */ }
            }
        }

        return result ?: throw IllegalStateException(
            "Inference stream ended without a Done event"
        )
    }

    /**
     * Send a text + image message and return the complete response text.
     *
     * @see chatOnce for error semantics.
     */
    suspend fun chatWithImageOnce(
        sessionId: String,
        text: String,
        bitmap: Bitmap,
    ): String {
        var result: String? = null
        val accumulator = StringBuilder()

        chatWithImage(sessionId, text, bitmap).events.collect { event ->
            when (event) {
                is MindlayerEvent.TextDelta -> accumulator.append(event.text)
                is MindlayerEvent.Done -> {
                    result = event.fullText ?: accumulator.toString()
                }
                is MindlayerEvent.Error -> throw MindlayerException(
                    message = event.message,
                    code = event.code,
                )
                is MindlayerEvent.ToolCall -> throw MindlayerException(
                    message = "Tool calls are not supported in one-shot mode.",
                    code = "UNSUPPORTED_TOOL_CALL",
                )
                else -> {}
            }
        }

        return result ?: throw IllegalStateException(
            "Inference stream ended without a Done event"
        )
    }

    /**
     * Stateless one-shot generation: creates a temporary session, sends
     * the prompt, collects the full response, and destroys the session.
     *
     * Use this when each inference is independent (no conversation
     * context needed). Session cleanup is best-effort — a service crash
     * during cleanup will not mask the successful response.
     *
     * @param text the prompt to send.
     * @param configure optional DSL block to customise session parameters
     *   (temperature, topK, maxTokens, systemPrompt, etc.).
     * @return the complete generated text.
     * @throws MindlayerException on service errors.
     */
    suspend fun generate(
        text: String,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String {
        val sessionId = createSession(configure)
        return try {
            chatOnce(sessionId, text)
        } finally {
            // Best-effort cleanup — don't mask a successful result or
            // a meaningful exception if the service has disconnected.
            try {
                destroySession(sessionId)
            } catch (_: Exception) {
                // Session will be cleaned up server-side on timeout
            }
        }
    }

    /**
     * Stateless one-shot image + text generation.
     *
     * @see generate for lifecycle semantics.
     */
    suspend fun generateWithImage(
        text: String,
        bitmap: Bitmap,
        configure: SessionConfigBuilder.() -> Unit = {},
    ): String {
        val sessionId = createSession(configure)
        return try {
            chatWithImageOnce(sessionId, text, bitmap)
        } finally {
            try {
                destroySession(sessionId)
            } catch (_: Exception) {
                // Best-effort cleanup
            }
        }
    }

    // -- Internals ------------------------------------------------------------

    /**
     * Creates an [InferenceHandle] with a cancel callback that reaches
     * through to the service's [IMindlayerService.cancelInference].
     */
    private fun buildHandle(requestId: String, flow: Flow<MindlayerEvent>): InferenceHandle {
        return InferenceHandle(requestId, flow).also { handle ->
            handle.setCancelCallback {
                try {
                    connection.awaitConnected().cancelInference(requestId)
                } catch (_: Exception) {
                    // Best-effort cancel — service may be disconnected
                }
            }
        }
    }

    /**
     * Wraps [startInference] with history persistence.
     *
     * 1. Persist user turn as PENDING before IPC.
     * 2. On [MindlayerEvent.Started]: mark user turn COMPLETED, begin
     *    assistant turn as STREAMING.
     * 3. On [MindlayerEvent.Done]: mark assistant turn COMPLETED with full
     *    text.
     * 4. On error/cancellation: mark assistant turn INTERRUPTED.
     */
    private fun startTrackedInference(
        sessionId: String,
        userText: String,
        meta: RequestMeta,
        image: com.mindlayer.ImageTransfer?,
        audio: com.mindlayer.AudioTransfer?,
    ): Flow<MindlayerEvent> {
        if (historyStore == null) {
            return startInference(meta, image, audio)
        }

        return flow {
            val userTurnId = historyStore.persistUserTurn(sessionId, userText)
            var assistantTurnId: String? = null
            val textAccumulator = StringBuilder()
            var completed = false

            try {
                startInference(meta, image, audio)
                    .collect { event ->
                        when (event) {
                            is MindlayerEvent.Started -> {
                                historyStore.markUserTurnCompleted(userTurnId)
                                assistantTurnId = historyStore.beginAssistantTurn(sessionId)
                            }
                            is MindlayerEvent.TextDelta -> {
                                textAccumulator.append(event.text)
                            }
                            is MindlayerEvent.Done -> {
                                val finalText = event.fullText
                                    ?: textAccumulator.toString()
                                val aid = assistantTurnId
                                if (aid != null) {
                                    historyStore.markTurnCompleted(aid, finalText)
                                }
                                completed = true
                            }
                            is MindlayerEvent.Error -> {
                                val aid = assistantTurnId
                                if (aid != null) {
                                    historyStore.markTurnInterrupted(aid)
                                }
                            }
                            else -> { /* ToolCall, Metrics — pass through */ }
                        }
                        emit(event)
                    }
            } catch (e: Exception) {
                if (!completed) {
                    val aid = assistantTurnId
                    if (aid != null) {
                        historyStore.markTurnInterrupted(aid)
                    }
                }
                throw e
            }
        }
    }

    /**
     * Core inference pipe setup used by all chat variants.
     *
     * 1. Creates a reliable pipe pair.
     * 2. Passes the write end to the service via [IMindlayerService.infer].
     * 3. Returns a [Flow] reading typed events from the read end.
     *
     * The write-end PFD is closed immediately after handing it off (the
     * service duplicates the fd internally). The read-end PFD is closed
     * by [TokenStreamReader] when the flow completes or is cancelled.
     */
    private fun startInference(
        meta: RequestMeta,
        image: com.mindlayer.ImageTransfer?,
        audio: com.mindlayer.AudioTransfer?,
    ): Flow<MindlayerEvent> {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        try {
            val service = connection.requireService()
            service.infer(meta, image, audio, writeEnd)
        } catch (e: Exception) {
            readEnd.close()
            throw e
        } finally {
            // Always close our copy of the write end — the service has dup'd it
            writeEnd.close()
        }

        return TokenStreamReader.readStream(readEnd)
    }
}

/**
 * DSL builder for [SessionConfig].
 */
class SessionConfigBuilder {
    private var sessionId: String? = null
    private var systemPrompt: String? = null
    private var maxTokens: Int = 4096
    private var backend: String = "GPU"
    private var topK: Int = 40
    private var topP: Float = 0.95f
    private var temperature: Float = 0.7f
    private var toolsJson: String? = null
    private var extraContextJson: String? = null
    private var initialHistory: List<HistoryTurn>? = null
    private var modelId: String? = null

    fun sessionId(id: String) { sessionId = id }
    fun systemPrompt(prompt: String) { systemPrompt = prompt }
    fun maxTokens(n: Int) { maxTokens = n }
    fun backend(b: String) { backend = b }
    fun topK(k: Int) { topK = k }
    fun topP(p: Float) { topP = p }
    fun temperature(t: Float) { temperature = t }
    fun tools(json: String) { toolsJson = json }
    fun extraContext(json: String) { extraContextJson = json }
    fun initialHistory(history: List<HistoryTurn>) { initialHistory = history }
    fun model(id: String) { modelId = id }

    internal fun build(): SessionConfig = SessionConfig(
        sessionId = sessionId,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        backend = backend,
        samplerTopK = topK,
        samplerTopP = topP,
        samplerTemperature = temperature,
        toolsJson = toolsJson,
        extraContextJson = extraContextJson,
        initialHistory = initialHistory,
        modelId = modelId,
    )
}
