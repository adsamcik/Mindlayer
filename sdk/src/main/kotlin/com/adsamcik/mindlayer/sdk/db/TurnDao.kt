package com.adsamcik.mindlayer.sdk.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class TurnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(turn: TurnEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPart(part: TurnPartEntity)

    @Query("SELECT * FROM turns WHERE turnId = :id")
    abstract suspend fun get(id: String): TurnEntity?

    /** All turns for a conversation, ordered by sequence number. */
    @Query("SELECT * FROM turns WHERE conversationId = :conversationId ORDER BY seq ASC")
    abstract suspend fun allForConversation(conversationId: String): List<TurnEntity>

    /** Only completed turns, ordered by sequence number. */
    @Query(
        "SELECT * FROM turns WHERE conversationId = :conversationId AND state = 'COMPLETED' ORDER BY seq ASC",
    )
    abstract suspend fun completedForConversation(conversationId: String): List<TurnEntity>

    /** Turns after the last stable sequence (for incremental replay). */
    @Query(
        "SELECT * FROM turns WHERE conversationId = :conversationId AND seq > :afterSeq AND state = 'COMPLETED' ORDER BY seq ASC",
    )
    abstract suspend fun completedAfterSeq(conversationId: String, afterSeq: Int): List<TurnEntity>

    /**
     * Recent completed turns within a token budget, newest first (caller
     * reverses for chronological order).
     */
    @Query(
        """
        SELECT * FROM turns
        WHERE conversationId = :conversationId AND state = 'COMPLETED'
        ORDER BY seq DESC
        """,
    )
    abstract suspend fun completedDescending(conversationId: String): List<TurnEntity>

    /** Find the first pending user turn (for re-send on recovery). */
    @Query(
        "SELECT * FROM turns WHERE conversationId = :conversationId AND role = 'USER' AND state = 'PENDING' ORDER BY seq ASC LIMIT 1",
    )
    abstract suspend fun firstPendingUserTurn(conversationId: String): TurnEntity?

    /** Next sequence number for a conversation. */
    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM turns WHERE conversationId = :conversationId")
    abstract suspend fun nextSeq(conversationId: String): Int

    /**
     * Atomically allocate the next sequence number and insert the turn.
     * Uses Room's @Transaction (SQLite BEGIN IMMEDIATE) to serialize
     * concurrent writes to the same conversation.
     */
    @Transaction
    open suspend fun insertWithAutoSeq(turn: TurnEntity): TurnEntity {
        val seq = nextSeq(turn.conversationId)
        val withSeq = turn.copy(seq = seq)
        upsert(withSeq)
        return withSeq
    }

    @Query("UPDATE turns SET state = :state, completedAtMs = :nowMs WHERE turnId = :turnId")
    abstract suspend fun updateState(
        turnId: String,
        state: String,
        nowMs: Long? = System.currentTimeMillis(),
    )

    @Query(
        "UPDATE turns SET state = :state, textContent = :text, tokenEstimate = :tokens, completedAtMs = :nowMs WHERE turnId = :turnId",
    )
    abstract suspend fun completeWithText(
        turnId: String,
        state: String = TurnState.COMPLETED,
        text: String?,
        tokens: Int,
        nowMs: Long = System.currentTimeMillis(),
    )

    /** Delete streaming/interrupted assistant turns (unsafe to replay). */
    @Query(
        "DELETE FROM turns WHERE conversationId = :conversationId AND role = 'ASSISTANT' AND state IN ('STREAMING', 'INTERRUPTED')",
    )
    abstract suspend fun deleteUnstableAssistantTurns(conversationId: String): Int

    /** Sum of token estimates for all completed turns in a conversation. */
    @Query(
        "SELECT COALESCE(SUM(tokenEstimate), 0) FROM turns WHERE conversationId = :conversationId AND state = 'COMPLETED'",
    )
    abstract suspend fun totalTokenEstimate(conversationId: String): Int

    @Query("SELECT * FROM turn_parts WHERE turnId = :turnId ORDER BY ordinal ASC")
    abstract suspend fun partsForTurn(turnId: String): List<TurnPartEntity>

    @Query("DELETE FROM turns WHERE turnId = :turnId")
    abstract suspend fun delete(turnId: String)

    /** Delete all turns. Used by [HistoryStore.clearAll]. */
    @Query("DELETE FROM turns")
    abstract suspend fun deleteAll(): Int

    /** Count completed turns for a conversation. */
    @Query("SELECT COUNT(*) FROM turns WHERE conversationId = :conversationId AND state = 'COMPLETED'")
    abstract suspend fun countCompleted(conversationId: String): Int

    /** Get the last N turns for a conversation (for preview). */
    @Query("SELECT * FROM turns WHERE conversationId = :conversationId AND state = 'COMPLETED' ORDER BY seq DESC LIMIT :limit")
    abstract suspend fun lastNTurns(conversationId: String, limit: Int): List<TurnEntity>

    /** Completed turns for a page of conversations, newest first within each conversation. */
    @Query(
        """
        SELECT * FROM turns
        WHERE conversationId IN (:conversationIds)
            AND state = 'COMPLETED'
        ORDER BY conversationId ASC, seq DESC
        """,
    )
    abstract suspend fun completedForConversationsDescending(
        conversationIds: List<String>,
    ): List<TurnEntity>
}
