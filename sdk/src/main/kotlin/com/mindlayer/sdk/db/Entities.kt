package com.mindlayer.sdk.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val conversationId: String,
    val systemPrompt: String?,
    val backend: String,
    val maxTokens: Int,
    val samplerConfigJson: String?,
    val toolsJson: String?,
    val extraContextJson: String?,
    val tokenEstimateTotal: Int = 0,
    val lastStableSeq: Int = 0,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "turns",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "seq"], unique = true),
    ],
)
data class TurnEntity(
    @PrimaryKey val turnId: String,
    val conversationId: String,
    val seq: Int,
    val role: String,
    val state: String,
    val textContent: String?,
    val tokenEstimate: Int = 0,
    val startedAtMs: Long,
    val completedAtMs: Long? = null,
)

@Entity(
    tableName = "turn_parts",
    foreignKeys = [
        ForeignKey(
            entity = TurnEntity::class,
            parentColumns = ["turnId"],
            childColumns = ["turnId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("turnId")],
)
data class TurnPartEntity(
    @PrimaryKey val partId: String,
    val turnId: String,
    val ordinal: Int,
    val kind: String,
    val text: String?,
    val uriString: String?,
    val mimeType: String?,
    val metadataJson: String?,
)

/** Turn lifecycle states. */
object TurnState {
    const val PENDING = "PENDING"
    const val STREAMING = "STREAMING"
    const val COMPLETED = "COMPLETED"
    const val INTERRUPTED = "INTERRUPTED"
}

/** Participant roles. */
object TurnRole {
    const val USER = "USER"
    const val ASSISTANT = "ASSISTANT"
    const val TOOL = "TOOL"
    const val SYSTEM = "SYSTEM"
}

/** Part content kinds. */
object PartKind {
    const val TEXT = "TEXT"
    const val IMAGE_REF = "IMAGE_REF"
    const val AUDIO_REF = "AUDIO_REF"
    const val TOOL_CALL = "TOOL_CALL"
    const val TOOL_RESULT = "TOOL_RESULT"
}
