package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.SystemClock
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages [Conversation] lifecycle, enforces device-aware limits, and evicts
 * sessions under memory pressure using priority-based ordering.
 *
 * Each session wraps a single LiteRT-LM [Conversation] with its own KV cache.
 * Memory scales with active sessions, so the manager caps the count based on
 * device RAM and provides [evictUnderPressure] for the service's
 * `onTrimMemory` path.
 *
 * Priority algorithm (higher = safer from eviction):
 *   streaming +1000, pinned +400, accessed <30 s +300, <120 s +150,
 *   client hint 0–100.
 */
class SessionManager(
    private val context: Context,
    private val engineManager: EngineManager,
    private val memoryBudget: MemoryBudget,
    private val logRepository: com.adsamcik.mindlayer.service.logging.LogRepository? = null,
) {

    companion object {
        private const val TAG = "SessionManager"
    }

    private val sessions = ConcurrentHashMap<String, SessionHandle>()

    val sessionCount: Int get() = sessions.size

    /** Delegate to the shared [MemoryBudget] component. */
    fun getDeviceTier(): DeviceTier = memoryBudget.deviceTier

    // ---- Session lifecycle -------------------------------------------------

    /**
     * Create a new [Conversation] backed session.
     *
     * If the device is already at its session limit the lowest-priority
     * non-streaming session is evicted first. The requested `maxTokens` in
     * [config] is clamped to the device budget *and* the current runtime
     * memory-pressure recommendation.
     */
    fun createSession(config: SessionConfig): String = createSession(config, ownerToken = null)

    /**
     * Create a session, optionally attributing ownership to [ownerToken] so
     * that [closeAllOwnedBy] can tear down all of a caller's sessions when
     * its binder dies.
     */
    fun createSession(config: SessionConfig, ownerToken: Any?): String {
        validateSessionConfig(config)
        cleanupExpiredSessions()
        val sessionId = config.sessionId ?: UUID.randomUUID().toString()
        if (sessions.containsKey(sessionId)) {
            throw IllegalArgumentException("Session ID already in use")
        }
        val tier = memoryBudget.deviceTier
        val snap = memoryBudget.currentSnapshot()

        if (sessions.size >= tier.maxSessions) {
            MindlayerLog.w(TAG, "At session limit (${tier.maxSessions}), evicting lowest priority")
            val evicted = if (ownerToken == null) {
                evictLowestPriority()
            } else {
                evictLowestPriorityOwnedBy(ownerToken)
            }
            if (!evicted) {
                throw IllegalStateException("Session limit reached for caller")
            }
        }

        // Under CRITICAL/EMERGENCY pressure, refuse new sessions if any exist
        if (snap.pressure >= MemoryPressure.CRITICAL && sessions.isNotEmpty()) {
            MindlayerLog.w(TAG, "Refusing new session under ${snap.pressure} pressure")
            throw IllegalStateException(
                "Memory pressure ${snap.pressure}: cannot create session " +
                    "(avail=${snap.availableMb} MB)"
            )
        }

        if (!engineManager.isInitialized) {
            throw IllegalStateException(
                "Engine is not initialized; call prewarm before creating sessions"
            )
        }

        val engine = engineManager.requireEngine()

        // Clamp to the lesser of the static tier ceiling and runtime recommendation
        val runtimeCeiling = snap.recommendedMaxTokens.coerceAtMost(tier.maxMaxTokens)
        val effectiveMaxTokens = config.maxTokens.coerceIn(1, runtimeCeiling)

        val samplerConfig = SamplerConfig(
            topK = config.samplerTopK,
            topP = config.samplerTopP.toDouble(),
            temperature = config.samplerTemperature.toDouble(),
        )

        // Parse client-supplied tool definitions (OpenAPI JSON array)
        val tools = parseToolDefinitions(config.toolsJson)

        // Parse structured output config and adjust tools/prompt accordingly
        val structuredOutputConfig = StructuredOutputHelper.parseConfig(config.extraContextJson)

        val effectiveTools = if (structuredOutputConfig != null &&
            structuredOutputConfig.strategy == StructuredOutputStrategy.TOOL_ROUTING
        ) {
            (tools ?: emptyList()) +
                StructuredOutputHelper.buildSchemaToolDefinition(structuredOutputConfig)
        } else {
            tools
        }

        val effectiveSystemPrompt = if (structuredOutputConfig != null &&
            structuredOutputConfig.strategy == StructuredOutputStrategy.PROMPT_AND_VALIDATE
        ) {
            (config.systemPrompt ?: "") +
                StructuredOutputHelper.buildSchemaPromptSuffix(structuredOutputConfig)
        } else {
            config.systemPrompt
        }

        val hasTools = !effectiveTools.isNullOrEmpty()

        // Map client-supplied history turns to LiteRT-LM Message objects
        val initialMessages = config.initialHistory?.map { turn ->
            val contents = Contents.of(turn.text)
            when (turn.role) {
                "model" -> Message.model(contents)
                "tool" -> Message.tool(contents)
                else -> Message.user(contents)
            }
        } ?: emptyList()

        val conversationConfig = ConversationConfig(
            systemInstruction = effectiveSystemPrompt?.let { Contents.of(it) },
            samplerConfig = samplerConfig,
            tools = effectiveTools ?: emptyList(),
            automaticToolCalling = !hasTools,
            initialMessages = initialMessages,
        )

        val now = System.currentTimeMillis()
        val conversation = engine.createConversation(conversationConfig)
        val handle = SessionHandle(
            sessionId = sessionId,
            conversation = conversation,
            config = config,
            createdAtMs = now,
            effectiveMaxTokens = effectiveMaxTokens,
            structuredOutputConfig = structuredOutputConfig,
            ownerToken = ownerToken,
        )
        if (sessions.putIfAbsent(sessionId, handle) != null) {
            try {
                conversation.close()
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Error closing duplicate session conversation", throwable = t)
            }
            throw IllegalArgumentException("Session ID already in use")
        }

        MindlayerLog.i(
            TAG, "Session created: $sessionId " +
                "(maxTokens=$effectiveMaxTokens, sessions=${sessions.size}/${tier.maxSessions}, " +
                "pressure=${snap.pressure})",
            sessionId = sessionId,
        )
        logRepository?.logSessionCreated(sessionId, engineManager.currentBackend, effectiveMaxTokens)
        if (structuredOutputConfig != null) {
            MindlayerLog.i(
                TAG, "Structured output: strategy=${structuredOutputConfig.strategy}, " +
                    "maxRetries=${structuredOutputConfig.maxRetries}",
                sessionId = sessionId,
            )
        }
        return sessionId
    }

    /** Return the live handle for [id], or `null` if the session is unknown or expired. */
    fun getSession(id: String): SessionHandle? {
        val handle = sessions[id] ?: return null
        if (System.currentTimeMillis() - handle.createdAtMs > handle.expirationMs) {
            MindlayerLog.i(TAG, "Session expired: $id", sessionId = id)
            destroySession(id)
            return null
        }
        handle.recordAccess()
        return handle
    }

    fun destroySession(id: String) {
        val handle = sessions.remove(id) ?: run {
            MindlayerLog.w(TAG, "destroySession: unknown session $id", sessionId = id)
            return
        }
        try {
            handle.conversation.close()
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Error closing conversation for session $id", sessionId = id, throwable = t)
        }
        logRepository?.logSessionDestroyed(id)
        MindlayerLog.i(TAG, "Session destroyed: $id (remaining=${sessions.size})", sessionId = id)
    }

    fun getSessionInfo(id: String): SessionInfo? {
        val handle = sessions[id] ?: return null
        return handle.toSessionInfo(engineManager.currentBackend)
    }

    /** Returns the owner token attached at creation, or `null` if unowned / unknown. */
    fun getSessionOwner(id: String): Any? = sessions[id]?.ownerToken

    fun listSessions(): List<SessionInfo> {
        val backend = engineManager.currentBackend
        return sessions.values.map { it.toSessionInfo(backend) }
    }

    /** Subset of [listSessions] attributed to [ownerToken]. */
    fun listSessionsOwnedBy(ownerToken: Any): List<SessionInfo> {
        val backend = engineManager.currentBackend
        return sessions.values
            .filter { it.ownerToken != null && it.ownerToken == ownerToken }
            .map { it.toSessionInfo(backend) }
    }

    /**
     * Find the session that is currently servicing [requestId], used by the
     * orchestrator for mid-stream cancellation.
     */
    fun findSessionByActiveRequest(requestId: String): SessionHandle? =
        sessions.values.find { it.activeRequestId == requestId }

    // ---- Access tracking ---------------------------------------------------

    /** Refresh the last-accessed timestamp for [id]. */
    fun updateSessionAccess(id: String) {
        sessions[id]?.recordAccess()
    }

    /** Mark a session as actively streaming (protects it from eviction). */
    fun markStreaming(id: String, streaming: Boolean) {
        sessions[id]?.let { it.isStreaming = streaming }
    }

    // ---- Priority & eviction -----------------------------------------------

    /**
     * Compute a numeric priority for [handle]. Higher values mean the session
     * is more important and should be evicted last.
     */
    fun calculatePriority(handle: SessionHandle): Int {
        var p = 0
        if (handle.isStreaming) p += 1000
        if (handle.isPinned) p += 400
        val recencyMs = SystemClock.elapsedRealtime() - handle.lastAccessedElapsedMs
        if (recencyMs < 30_000) p += 300
        else if (recencyMs < 120_000) p += 150
        p += handle.clientPriorityHint.coerceIn(0, 100)
        return p
    }

    /**
     * Evict the single lowest-priority non-streaming session.
     * @return `true` if a session was evicted.
     */
    private fun evictLowestPriority(): Boolean {
        val victim = sessions.values
            .filter { !it.isStreaming }
            .minByOrNull { calculatePriority(it) }
            ?: return false

        MindlayerLog.w(
            TAG, "Evicting session ${victim.sessionId} " +
                "(priority=${calculatePriority(victim)})",
            sessionId = victim.sessionId,
        )
        logRepository?.logSessionEvicted(victim.sessionId, "lowest_priority")
        destroySession(victim.sessionId)
        return true
    }

    private fun evictLowestPriorityOwnedBy(ownerToken: Any): Boolean {
        val victim = sessions.values
            .filter { !it.isStreaming && it.ownerToken == ownerToken }
            .minByOrNull { calculatePriority(it) }
            ?: return false

        MindlayerLog.w(
            TAG, "Evicting owned session ${victim.sessionId} " +
                "(priority=${calculatePriority(victim)})",
            sessionId = victim.sessionId,
        )
        logRepository?.logSessionEvicted(victim.sessionId, "owner_session_limit")
        destroySession(victim.sessionId)
        return true
    }

    /**
     * Evict sessions under memory pressure.
     *
     * Keeps at most **one** non-streaming session (the highest-priority one).
     * Streaming sessions are never evicted — the system must wait for them to
     * finish or the client must cancel.
     */
    fun evictUnderPressure() {
        val nonStreaming = sessions.values
            .filter { !it.isStreaming }
            .sortedBy { calculatePriority(it) }

        // Keep the single highest-priority non-streaming session
        val toEvict = if (nonStreaming.size > 1) nonStreaming.dropLast(1) else emptyList()

        for (handle in toEvict) {
            MindlayerLog.w(TAG, "Pressure-evicting session ${handle.sessionId}", sessionId = handle.sessionId)
            logRepository?.logSessionEvicted(handle.sessionId, "memory_pressure")
            destroySession(handle.sessionId)
        }

        if (toEvict.isNotEmpty()) {
            MindlayerLog.w(
                TAG, "Evicted ${toEvict.size} session(s) under pressure " +
                    "(remaining=${sessions.size})"
            )
        }
    }

    /**
     * React to a [MemoryPressure] change from the [MemoryBudget] monitor.
     *
     * | Pressure  | Action                                       |
     * |-----------|----------------------------------------------|
     * | NORMAL    | No-op                                        |
     * | WARNING   | Evict lowest-priority non-streaming session   |
     * | CRITICAL  | Evict all but the highest-priority session    |
     * | EMERGENCY | Evict all non-streaming sessions, cap tokens  |
     */
    fun applyMemoryPressure(pressure: MemoryPressure) {
        when (pressure) {
            MemoryPressure.NORMAL -> { /* nothing to do */ }

            MemoryPressure.WARNING -> {
                if (sessions.size > 1) {
                    MindlayerLog.w(TAG, "WARNING pressure — evicting lowest-priority session")
                    evictLowestPriority()
                }
            }

            MemoryPressure.CRITICAL -> {
                MindlayerLog.w(TAG, "CRITICAL pressure — evicting to single session")
                evictUnderPressure()
            }

            MemoryPressure.EMERGENCY -> {
                MindlayerLog.w(TAG, "EMERGENCY pressure — evicting all non-streaming sessions")
                val nonStreaming = sessions.values.filter { !it.isStreaming }
                for (handle in nonStreaming) {
                    logRepository?.logSessionEvicted(handle.sessionId, "emergency_pressure")
                    destroySession(handle.sessionId)
                }
                if (nonStreaming.isNotEmpty()) {
                    MindlayerLog.w(
                        TAG, "Emergency-evicted ${nonStreaming.size} session(s) " +
                            "(remaining=${sessions.size})"
                    )
                }
            }
        }
    }

    /** Remove all sessions that have exceeded their expiration. */
    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expired = sessions.filter { (_, handle) ->
            now - handle.createdAtMs > handle.expirationMs
        }
        expired.forEach { (id, _) -> destroySession(id) }
        if (expired.isNotEmpty()) {
            MindlayerLog.i(TAG, "Cleaned up ${expired.size} expired session(s)")
        }
    }

    /**
     * Destroy every session attributed to [ownerToken]. Used by the service
     * binder's death-recipient to reclaim state when a client process dies.
     * Returns the list of sessionIds that were torn down.
     */
    fun closeAllOwnedBy(ownerToken: Any): List<String> {
        val ids = sessions.values
            .filter { it.ownerToken != null && it.ownerToken == ownerToken }
            .map { it.sessionId }
        for (id in ids) destroySession(id)
        return ids
    }

    /**
     * Validate client-supplied sampler + token parameters. Throws
     * [IllegalArgumentException] for out-of-range values — callers at the
     * AIDL boundary translate this into a [SecurityException].
     */
    private fun validateSessionConfig(config: SessionConfig) {
        require(config.samplerTopK > 0) { "samplerTopK must be > 0" }
        require(config.samplerTopP in 0.0f..1.0f) { "samplerTopP must be in [0.0, 1.0]" }
        require(config.samplerTemperature in 0.0f..5.0f) { "samplerTemperature out of range" }
        require(config.maxTokens in 1..32_768) { "maxTokens out of range" }
        val sessionId = config.sessionId
        require(sessionId == null || sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    /** Destroy all sessions. Called during service teardown. */
    fun shutdown() {
        val ids = sessions.keys.toList()
        for (id in ids) {
            destroySession(id)
        }
    }

    // ---- Tool definition parsing -------------------------------------------

    /**
     * Parse a JSON array of OpenAPI-style tool descriptions into [ToolProvider]
     * instances.
     *
     * Each element is treated as an independent tool description JSON string.
     * In manual mode (`automaticToolCalling = false`) the `execute()` method is
     * never invoked by the framework — the model emits tool calls that the
     * client handles externally.
     */
    private fun parseToolDefinitions(toolsJson: String?): List<ToolProvider>? {
        if (toolsJson.isNullOrBlank()) return null

        return try {
            val array = Json.parseToJsonElement(toolsJson) as JsonArray
            if (array.isEmpty()) return null

            array.map { element ->
                val description = element.jsonObject.toString()
                tool(JsonDefinedTool(description))
            }.also {
                MindlayerLog.d(TAG, "Parsed ${it.size} tool definition(s)")
            }
        } catch (t: Throwable) {
            MindlayerLog.e(TAG, "Failed to parse toolsJson", throwable = t)
            null
        }
    }

    /**
     * Lightweight [OpenApiTool] backed by a pre-serialised JSON description.
     *
     * [execute] is never called in manual mode — the model's tool-call output
     * is forwarded to the client, which returns results via AIDL.
     */
    private class JsonDefinedTool(
        private val descriptionJson: String,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptionJson
        override fun execute(paramsJsonString: String): String =
            error("Manual tool mode — execute() should not be called by the framework")
    }

    // ---- SessionHandle -----------------------------------------------------

    /**
     * Mutable bookkeeping wrapper around a LiteRT-LM [Conversation].
     *
     * The [mutex] serialises sends so that only one inference is in-flight per
     * session, while distinct sessions can run concurrently.
     */
    class SessionHandle(
        val sessionId: String,
        val conversation: Conversation,
        val config: SessionConfig,
        val createdAtMs: Long,
        val effectiveMaxTokens: Int,
        val expirationMs: Long = config.expirationMs,
        val structuredOutputConfig: StructuredOutputConfig? = null,
        val ownerToken: Any? = null,
    ) {
        val mutex = Mutex()

        @Volatile var turnCount: Int = 0
        @Volatile var lastAccessedAtMs: Long = createdAtMs
        @Volatile var lastAccessedElapsedMs: Long = SystemClock.elapsedRealtime()
        @Volatile var estimatedTokens: Int = 0
        @Volatile var isStreaming: Boolean = false
        @Volatile var isPinned: Boolean = false
        @Volatile var clientPriorityHint: Int = 0
        @Volatile var activeRequestId: String? = null

        /** Refresh both wall-clock and elapsed-realtime timestamps. */
        fun recordAccess() {
            lastAccessedAtMs = System.currentTimeMillis()
            lastAccessedElapsedMs = SystemClock.elapsedRealtime()
        }

        fun toSessionInfo(backend: String) = SessionInfo(
            sessionId = sessionId,
            backend = backend,
            maxTokens = effectiveMaxTokens,
            currentTokenCount = estimatedTokens,
            turnCount = turnCount,
            createdAtMs = createdAtMs,
            lastAccessedAtMs = lastAccessedAtMs,
            isStreaming = isStreaming,
            expirationMs = expirationMs,
        )
    }
}
