package com.mindlayer.sdk.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TurnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(turn: TurnEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPart(part: TurnPartEntity)

    @Query("SELECT * FROM turns WHERE turnId = :id")
    suspend fun get(id: String): TurnEntity?

    /** All turns for a conversation, ordered by sequence number. */
    @Query("SELECT * FROM turns WHERE conversationId = :conversationId ORDER BY seq ASC")
    suspend fun allForConversation(conversationId: String): List<TurnEntity>

    /** Only completed turns, ordered by sequence number. */
    @Query(
        "SELECT * FROM turns WHERE conversationId = :conversationId AND state = 'COMPLETED' ORDER BY seq ASC",
    )
    suspend fun completedForConversation(conversationId: String): List<TurnEntity>

    /** Turns after the last stable sequence (for incremental replay). */
    @Query(
        "SELECT * FROM turns WHERE conversationId = :conversationId AND seq > :afterSeq AND state = 'COMPLETED' ORDER BY seq ASC",
    )
    suspend fun completedAfterSeq(conversationId: String, afterSeq: Int): List<TurnEntity>

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
    suspend fun completedDescending(conversationId: String): List<TurnEntity>

    /** Find the first pending user turn (for re-send on recovery). */
    @Query(
        "SELECT * FROM turns WHERE conversationId = :conversationId AND role = 'USER' AND state = 'PENDING' ORDER BY seq ASC LIMIT 1",
    )
    suspend fun firstPendingUserTurn(conversationId: String): TurnEntity?

    /** Next sequence number for a conversation. */
    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM turns WHERE conversationId = :conversationId")
    suspend fun nextSeq(conversationId: String): Int

    @Query("UPDATE turns SET state = :state, completedAtMs = :nowMs WHERE turnId = :turnId")
    suspend fun updateState(
        turnId: String,
        state: String,
        nowMs: Long? = System.currentTimeMillis(),
    )

    @Query(
        "UPDATE turns SET state = :state, textContent = :text, tokenEstimate = :tokens, completedAtMs = :nowMs WHERE turnId = :turnId",
    )
    suspend fun completeWithText(
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
    suspend fun deleteUnstableAssistantTurns(conversationId: String): Int

    /** Sum of token estimates for all completed turns in a conversation. */
    @Query(
        "SELECT COALESCE(SUM(tokenEstimate), 0) FROM turns WHERE conversationId = :conversationId AND state = 'COMPLETED'",
    )
    suspend fun totalTokenEstimate(conversationId: String): Int

    @Query("SELECT * FROM turn_parts WHERE turnId = :turnId ORDER BY ordinal ASC")
    suspend fun partsForTurn(turnId: String): List<TurnPartEntity>

    @Query("DELETE FROM turns WHERE turnId = :turnId")
    suspend fun delete(turnId: String)
}
