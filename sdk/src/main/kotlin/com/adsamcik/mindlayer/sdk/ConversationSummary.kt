package com.adsamcik.mindlayer.sdk

/**
 * Summary of a past conversation, including metadata and a preview
 * of recent messages. Retrieved via [Mindlayer.listHistory].
 */
data class ConversationSummary(
    val conversationId: String,
    val systemPrompt: String?,
    val turnCount: Int,
    val tokenEstimate: Int,
    val createdAt: Long,
    val lastActiveAt: Long,
    val isActive: Boolean,
    val preview: List<TurnPreview>,
)

/**
 * Preview of a single turn in a conversation.
 */
data class TurnPreview(
    /**
     * One of [com.adsamcik.mindlayer.shared.Role] (`"user"`, `"model"`, `"tool"`,
     * `"system"`). Note: the wire vocabulary uses `"model"` for assistant
     * responses — the SDK's local Room enum [com.adsamcik.mindlayer.sdk.db.TurnRole]
     * spells the same value as `ASSISTANT`. Mapping happens in
     * [com.adsamcik.mindlayer.sdk.SessionRecovery].
     */
    val role: String,
    val text: String?,     // First 200 chars of content
    val timestamp: Long,
)
