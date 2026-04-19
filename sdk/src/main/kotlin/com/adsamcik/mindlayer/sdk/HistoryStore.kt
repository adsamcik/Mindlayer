package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.util.Log
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.db.ConversationEntity
import com.adsamcik.mindlayer.sdk.db.ConversationState
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.sdk.db.TurnEntity
import com.adsamcik.mindlayer.sdk.db.TurnRole
import com.adsamcik.mindlayer.sdk.db.TurnState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * High-level wrapper around [MindlayerDatabase] for conversation history
 * persistence. This is the SDK's local source of truth — the service is
 * stateless from the client's perspective after an OOM kill.
 */
class HistoryStore internal constructor(context: Context) {

    companion object {
        private const val TAG = "HistoryStore"

        /** Rough chars-per-token ratio for budget calculations. */
        private const val CHARS_PER_TOKEN = 4

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val db = MindlayerDatabase.getInstance(context)
    private val conversationDao = db.conversationDao()
    private val turnDao = db.turnDao()

    // -- Conversation lifecycle -----------------------------------------------

    /**
     * Persist a conversation's config to Room. Called when the SDK creates a
     * new session so recovery can recreate it later.
     */
    suspend fun persistConversation(sessionId: String, config: SessionConfig) {
        val samplerJson = buildJsonObject {
            put("topK", config.samplerTopK)
            put("topP", config.samplerTopP)
            put("temperature", config.samplerTemperature)
        }.toString()

        val now = System.currentTimeMillis()
        conversationDao.upsert(
            ConversationEntity(
                conversationId = sessionId,
                systemPrompt = config.systemPrompt,
                backend = config.backend,
                maxTokens = config.maxTokens,
                samplerConfigJson = samplerJson,
                toolsJson = config.toolsJson,
                extraContextJson = config.extraContextJson,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
    }

    /**
     * Persist a conversation as CREATING before the remote session is created.
     * This ensures we have a local record even if the process dies mid-creation.
     */
    suspend fun prepareConversation(sessionId: String, config: SessionConfig) {
        val samplerJson = buildJsonObject {
            put("topK", config.samplerTopK)
            put("topP", config.samplerTopP)
            put("temperature", config.samplerTemperature)
        }.toString()

        val now = System.currentTimeMillis()
        conversationDao.upsert(
            ConversationEntity(
                conversationId = sessionId,
                systemPrompt = config.systemPrompt,
                backend = config.backend,
                maxTokens = config.maxTokens,
                samplerConfigJson = samplerJson,
                toolsJson = config.toolsJson,
                extraContextJson = config.extraContextJson,
                createdAtMs = now,
                updatedAtMs = now,
                state = ConversationState.CREATING,
            ),
        )
    }

    /**
     * Mark a CREATING conversation as READY after successful remote session creation.
     */
    suspend fun confirmConversation(sessionId: String) {
        conversationDao.updateState(sessionId, ConversationState.READY)
    }

    /** Remove a specific conversation (used when remote creation fails). */
    suspend fun cleanupConversation(sessionId: String) {
        conversationDao.delete(sessionId)
    }

    /**
     * Clean up orphaned CREATING conversations from previous crashes.
     * Call on SDK initialization.
     */
    suspend fun cleanupOrphanedConversations(): Int {
        val deleted = conversationDao.deleteOrphaned()
        if (deleted > 0) {
            Log.i(TAG, "Cleaned up $deleted orphaned CREATING conversations")
        }
        return deleted
    }

    // -- Turn persistence -----------------------------------------------------

    /**
     * Persist a user turn BEFORE sending it over IPC. State starts as
     * [TurnState.PENDING] so that if the service dies before acknowledging,
     * we know to re-send it on recovery.
     *
     * @return the generated turn ID.
     */
    suspend fun persistUserTurn(
        sessionId: String,
        text: String,
    ): String {
        val turnId = UUID.randomUUID().toString()
        val tokens = estimateTokens(text)

        turnDao.insertWithAutoSeq(
            TurnEntity(
                turnId = turnId,
                conversationId = sessionId,
                seq = 0, // placeholder — overwritten by insertWithAutoSeq
                role = TurnRole.USER,
                state = TurnState.PENDING,
                textContent = text,
                tokenEstimate = tokens,
                startedAtMs = System.currentTimeMillis(),
            ),
        )
        return turnId
    }

    /**
     * Record that an assistant response has started streaming.
     *
     * @return the generated turn ID.
     */
    suspend fun beginAssistantTurn(sessionId: String): String {
        val turnId = UUID.randomUUID().toString()

        turnDao.insertWithAutoSeq(
            TurnEntity(
                turnId = turnId,
                conversationId = sessionId,
                seq = 0, // placeholder — overwritten by insertWithAutoSeq
                role = TurnRole.ASSISTANT,
                state = TurnState.STREAMING,
                textContent = null,
                startedAtMs = System.currentTimeMillis(),
            ),
        )
        return turnId
    }

    /**
     * Mark a user turn as completed (service acknowledged it).
     */
    suspend fun markUserTurnCompleted(turnId: String) {
        turnDao.updateState(turnId, TurnState.COMPLETED)
    }

    /**
     * Mark an assistant turn as completed with its full text.
     */
    suspend fun markTurnCompleted(turnId: String, text: String?) {
        val tokens = estimateTokens(text)
        turnDao.completeWithText(
            turnId = turnId,
            text = text,
            tokens = tokens,
        )
    }

    /**
     * Mark a turn as interrupted (service died mid-stream, user cancelled, etc.).
     */
    suspend fun markTurnInterrupted(turnId: String) {
        turnDao.updateState(turnId, TurnState.INTERRUPTED)
    }

    // -- Recovery queries -----------------------------------------------------

    /**
     * Clean up assistant turns that were still streaming or interrupted when
     * the service died. These are unsafe to replay.
     */
    suspend fun cleanupInterruptedTurns(sessionId: String): Int {
        val deleted = turnDao.deleteUnstableAssistantTurns(sessionId)
        if (deleted > 0) {
            Log.i(TAG, "Cleaned up $deleted unstable assistant turns for $sessionId")
        }
        return deleted
    }

    /**
     * Build replay data for session recovery. Returns the system prompt and
     * completed turns that fit within [maxTokens].
     */
    suspend fun getReplayHistory(
        sessionId: String,
        maxTokens: Int,
    ): ReplayData? {
        val conversation = conversationDao.get(sessionId) ?: return null

        // Walk backwards from newest turn, accumulating until budget exhausted
        val allCompleted = turnDao.completedDescending(sessionId)
        val selected = mutableListOf<TurnEntity>()
        var remaining = maxTokens

        // Reserve tokens for the system prompt
        val systemTokens = estimateTokens(conversation.systemPrompt)
        remaining -= systemTokens

        for (turn in allCompleted) {
            if (turn.tokenEstimate > remaining) break
            selected.add(turn)
            remaining -= turn.tokenEstimate
        }

        // Reverse to chronological order
        selected.reverse()

        // Find a pending user turn that was never acknowledged
        val pendingUserTurn = turnDao.firstPendingUserTurn(sessionId)

        return ReplayData(
            conversationId = sessionId,
            systemPrompt = conversation.systemPrompt,
            config = rebuildConfig(conversation),
            turns = selected,
            pendingUserTurn = pendingUserTurn,
        )
    }

    // -- History queries ------------------------------------------------------

    /**
     * List all past conversations with turn count and preview.
     */
    suspend fun listConversations(limit: Int = 50, offset: Int = 0): List<ConversationSummary> {
        val conversations = db.conversationDao().listPaged(limit, offset)
        return conversations.map { conv ->
            val turnCount = db.turnDao().countCompleted(conv.conversationId)
            val lastTurns = db.turnDao().lastNTurns(conv.conversationId, 3)
            ConversationSummary(
                conversationId = conv.conversationId,
                systemPrompt = conv.systemPrompt,
                turnCount = turnCount,
                tokenEstimate = conv.tokenEstimateTotal,
                createdAt = conv.createdAtMs,
                lastActiveAt = conv.updatedAtMs,
                isActive = false, // Will be enriched by SDK
                preview = lastTurns.reversed().map { turn ->
                    TurnPreview(
                        role = turn.role,
                        text = turn.textContent?.take(200),
                        timestamp = turn.startedAtMs,
                    )
                },
            )
        }
    }

    /**
     * Get full conversation history (all turns).
     */
    suspend fun getConversationHistory(conversationId: String): List<TurnPreview> {
        val turns = db.turnDao().completedForConversation(conversationId)
        return turns.map { turn ->
            TurnPreview(
                role = turn.role,
                text = turn.textContent,
                timestamp = turn.startedAtMs,
            )
        }
    }

    /**
     * Delete conversations older than the given duration.
     * Returns count of deleted conversations.
     */
    suspend fun pruneOlderThan(maxAgeMs: Long): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return db.conversationDao().deleteOlderThan(cutoff)
    }

    /**
     * Count total stored conversations.
     */
    suspend fun conversationCount(): Int {
        return db.conversationDao().count()
    }

    // -- Internals ------------------------------------------------------------

    private fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }

    private fun rebuildConfig(entity: ConversationEntity): SessionConfig {
        var topK = 40
        var topP = 0.95f
        var temperature = 0.7f
        if (entity.samplerConfigJson != null) {
            try {
                val obj = json.decodeFromString<JsonObject>(entity.samplerConfigJson)
                topK = obj["topK"]?.toString()?.toIntOrNull() ?: topK
                topP = obj["topP"]?.toString()?.toFloatOrNull() ?: topP
                temperature = obj["temperature"]?.toString()?.toFloatOrNull() ?: temperature
            } catch (_: Exception) { /* use defaults */ }
        }
        return SessionConfig(
            sessionId = entity.conversationId,
            systemPrompt = entity.systemPrompt,
            maxTokens = entity.maxTokens,
            backend = entity.backend,
            samplerTopK = topK,
            samplerTopP = topP,
            samplerTemperature = temperature,
            toolsJson = entity.toolsJson,
            extraContextJson = entity.extraContextJson,
        )
    }
}

/**
 * Data needed to replay a conversation into a fresh service session.
 */
data class ReplayData(
    val conversationId: String,
    val systemPrompt: String?,
    val config: SessionConfig,
    val turns: List<TurnEntity>,
    val pendingUserTurn: TurnEntity?,
)
