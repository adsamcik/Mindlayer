package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.EngineInfo
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.UUID

/**
 * Client for the Mindlayer on-device LLM service.
 *
 * Mindlayer runs large language models (Gemma, etc.) locally on the device
 * as a separate Android service. This class manages the connection lifecycle,
 * session creation, and inference requests.
 *
 * **Quick start:**
 * ```kotlin
 * val mindlayer = Mindlayer.connect(context)
 * mindlayer.awaitConnected()
 *
 * val sessionId = mindlayer.createSession {
 *     systemPrompt("You are a helpful assistant.")
 * }
 *
 * mindlayer.chat(sessionId, "Hello!").events.collect { event ->
 *     when (event) {
 *         is MindlayerEvent.TextDelta -> print(event.text)
 *         is MindlayerEvent.Done -> println()
 *         else -> {}
 *     }
 * }
 *
 * mindlayer.disconnect()
 * ```
 *
 * **Lifecycle:** Call [connect] to bind to the service, [awaitConnected] to
 * wait for the connection, and [disconnect] to release resources. The service
 * continues running independently — reconnecting will find existing sessions.
 *
 * **Models:** Mindlayer discovers available models automatically.
 * The best available model is always used.
 *
 * **Thread safety:** All methods are safe to call from any thread.
 * Suspend methods use the caller's coroutine context.
 */
/** Inference backend for engine pre-warming. */
enum class InferenceBackend(internal val value: String) {
    /** GPU acceleration (default, recommended). */
    GPU("GPU"),
    /** CPU fallback. */
    CPU("CPU"),
    /** Neural Processing Unit (device-specific). */
    NPU("NPU"),
}

class Mindlayer private constructor(
    internal val connection: ConnectionManager,
    private val historyStore: HistoryStore?,
) {

    companion object {
        private const val TAG = "Mindlayer"

        internal const val TOOL_CALL_IN_ONESHOT_MSG =
            "Tool calls are not supported in one-shot mode. " +
            "Use the streaming chat() API with a ToolCall handler instead."

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

    /**
     * Unbind from the Mindlayer service and release resources.
     *
     * Active inference flows will complete with [MindlayerEvent.Error] (code: "DISCONNECTED").
     * Blocking one-shot methods ([chatOnce], [generate]) will throw [MindlayerException].
     * Sessions are preserved on the service side and can be resumed after [connect].
     *
     * Safe to call from any thread. Idempotent — multiple calls are harmless.
     */
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
     * @param backend the preferred backend to initialize.
     */
    suspend fun prewarm(backend: InferenceBackend = InferenceBackend.GPU) {
        val service = connection.awaitConnected()
        service.prewarm(backend.value)
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

    /** Get engine info (selected model, perf stats, etc.). */
    suspend fun getEngineInfo(): EngineInfo {
        return connection.awaitConnected().engineInfo
    }

    /** Get a diagnostic JSON dump for bug reports and troubleshooting. */
    suspend fun getDiagnostics(): String {
        return connection.awaitConnected().diagnostics
    }

    // ── Simple API ──────────────────────────────────────────────────────

    /**
     * Start a multi-turn conversation. The session is created lazily on first message.
     *
     * ```kotlin
     * val conv = mindlayer.conversation {
     *     systemPrompt("You are a helpful barista.")
     * }
     * val response = conv.chat("What's a cortado?")
     * conv.close()
     * ```
     */
    fun conversation(configure: ConversationBuilder.() -> Unit = {}): Conversation {
        val config = ConversationBuilder().apply(configure).build()
        return Conversation(this, config)
    }

    /**
     * Send a single message and get the response. No session management needed.
     * Creates a temporary session, sends the message, destroys the session.
     *
     * For multi-turn conversations, use [conversation] instead.
     *
     * ```kotlin
     * val answer = mindlayer.chat("What is specialty coffee?")
     * ```
     */
    suspend fun chat(text: String): String {
        awaitConnected()
        return generate(text)
    }

    /**
     * Send a message with an image and get the response. No session management needed.
     */
    suspend fun chat(text: String, image: Bitmap): String {
        awaitConnected()
        return generateWithImage(text, image)
    }

    // ── History ─────────────────────────────────────────────

    /**
     * List past conversations with turn previews.
     * Returns conversations from the local history database, including
     * both active and destroyed sessions.
     *
     * ```kotlin
     * val history = mindlayer.listHistory(limit = 20)
     * history.forEach { conv ->
     *     println("${conv.conversationId}: ${conv.turnCount} turns")
     *     conv.preview.forEach { turn ->
     *         println("  ${turn.role}: ${turn.text?.take(50)}")
     *     }
     * }
     * ```
     */
    suspend fun listHistory(limit: Int = 50, offset: Int = 0): List<ConversationSummary> {
        val summaries = historyStore?.listConversations(limit, offset) ?: emptyList()
        val activeSessions = try {
            if (connectionState.value == ConnectionState.CONNECTED) {
                listSessions().map { it.sessionId }.toSet()
            } else emptySet()
        } catch (_: Exception) { emptySet() }

        return summaries.map { it.copy(isActive = it.conversationId in activeSessions) }
    }

    /**
     * Get full conversation history for a specific session.
     */
    suspend fun getHistory(conversationId: String): List<TurnPreview> {
        return historyStore?.getConversationHistory(conversationId) ?: emptyList()
    }

    /**
     * Delete conversations older than [maxAgeDays] days.
     * Returns count of deleted conversations.
     */
    suspend fun pruneHistory(maxAgeDays: Int = 30): Int {
        val maxAgeMs = maxAgeDays.toLong() * 24 * 60 * 60 * 1000
        return historyStore?.pruneOlderThan(maxAgeMs) ?: 0
    }

    /**
     * Count total conversations in history.
     */
    suspend fun historyCount(): Int {
        return historyStore?.conversationCount() ?: 0
    }

    // -- Convenience ----------------------------------------------------------

    // ── Advanced API ──────────────────────────────────────────────────────

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
                    message = TOOL_CALL_IN_ONESHOT_MSG,
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
                    message = TOOL_CALL_IN_ONESHOT_MSG,
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
        image: com.adsamcik.mindlayer.ImageTransfer?,
        audio: com.adsamcik.mindlayer.AudioTransfer?,
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
        image: com.adsamcik.mindlayer.ImageTransfer?,
        audio: com.adsamcik.mindlayer.AudioTransfer?,
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
 * DSL builder for configuring a Mindlayer inference session.
 *
 * Example:
 * ```
 * val sessionId = mindlayer.createSession {
 *     systemPrompt("You are a coffee expert.")
 *     maxTokens(2048)
 *     temperature(0.7f)
 * }
 * ```
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
    private var expirationMs: Long = 14L * 24 * 60 * 60 * 1000

    /**
     * Set a custom session ID. If not set, a UUID is generated automatically.
     * Use this for deterministic session tracking or reconnection after OOM.
     */
    fun sessionId(id: String) { sessionId = id }

    /**
     * System instruction that defines the model's behavior and persona.
     * This is injected before the first user message and persists for the session.
     */
    fun systemPrompt(prompt: String) { systemPrompt = prompt }

    /**
     * Maximum number of tokens (input + output) for this session's KV cache.
     * Higher values allow longer conversations but consume more memory.
     * Valid range: 128–8192. Default: 4096.
     */
    fun maxTokens(n: Int) {
        require(n in 128..8192) { "maxTokens must be between 128 and 8192, got $n" }
        maxTokens = n
    }

    /**
     * Set the compute backend for inference.
     * Supported values: "GPU" (default), "CPU", "NPU".
     */
    internal fun backend(b: String) { backend = b }

    /**
     * Top-K sampling: only consider the K most likely tokens at each step.
     * Lower values = more focused, higher values = more diverse.
     * Default: 40. Must be >= 1.
     */
    fun topK(k: Int) {
        require(k >= 1) { "topK must be >= 1, got $k" }
        topK = k
    }

    /**
     * Top-P (nucleus) sampling: only consider tokens whose cumulative
     * probability reaches P. Complements top-K.
     * Default: 0.95. Must be in (0.0, 1.0].
     */
    fun topP(p: Float) {
        require(p > 0.0f && p <= 1.0f) { "topP must be in (0.0, 1.0], got $p" }
        topP = p
    }

    /**
     * Sampling temperature controlling response randomness.
     * - 0.0 = deterministic (always pick most likely token)
     * - 0.7 = balanced creativity (default, recommended for most uses)
     * - 1.0+ = highly random
     * Must be >= 0.0.
     */
    fun temperature(t: Float) {
        require(t >= 0.0f) { "temperature must be >= 0.0, got $t" }
        temperature = t
    }

    /**
     * Register tools (function calling) for this session.
     * Pass a JSON array of OpenAPI-style tool definitions.
     * See [MindlayerEvent.ToolCall] for handling tool invocations.
     */
    fun tools(json: String) { toolsJson = json }

    /**
     * Additional context passed to the model as grounding data.
     * This is injected alongside the system prompt.
     */
    fun extraContext(json: String) { extraContextJson = json }

    /**
     * Request structured JSON output that conforms to a schema.
     *
     * The service validates the model's response against the supplied schema
     * and (for [JsonOutputStrategy.PromptAndValidate]) retries on mismatch.
     * If the connected Mindlayer service predates this feature, the config
     * is silently ignored and generation proceeds normally — making this a
     * zero-risk opt-in.
     *
     * Merges with any `structured_output` key already present on
     * [extraContext]; other keys on [extraContext] are preserved.
     *
     * ```kotlin
     * jsonOutput {
     *     schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
     *     strategy(JsonOutputStrategy.PromptAndValidate)
     * }
     * ```
     */
    fun jsonOutput(block: JsonOutputBuilder.() -> Unit) {
        val envelope = JsonOutputBuilder().apply(block).build()
        extraContextJson = mergeExtraContext(extraContextJson, envelope)
    }

    /**
     * Pre-populate conversation history for session recovery.
     * Turns are injected into the model's context at creation time.
     */
    fun initialHistory(history: List<HistoryTurn>) { initialHistory = history }

    /** Set session expiration in milliseconds. Internal — consumers use [ConversationBuilder.expiration]. */
    internal fun expirationMs(ms: Long) { expirationMs = ms }

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
        expirationMs = expirationMs,
    )
}
