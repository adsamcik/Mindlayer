package com.adsamcik.mindlayer.sdk.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE conversationId = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("UPDATE conversations SET updatedAtMs = :nowMs WHERE conversationId = :id")
    suspend fun touch(id: String, nowMs: Long = System.currentTimeMillis())

    @Query(
        "UPDATE conversations SET tokenEstimateTotal = :tokens, updatedAtMs = :nowMs WHERE conversationId = :id",
    )
    suspend fun updateTokenEstimate(
        id: String,
        tokens: Int,
        nowMs: Long = System.currentTimeMillis(),
    )

    @Query(
        "UPDATE conversations SET lastStableSeq = :seq, updatedAtMs = :nowMs WHERE conversationId = :id",
    )
    suspend fun updateLastStableSeq(
        id: String,
        seq: Int,
        nowMs: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE conversations SET state = :state, updatedAtMs = :nowMs WHERE conversationId = :id")
    suspend fun updateState(
        id: String,
        state: String,
        nowMs: Long = System.currentTimeMillis(),
    )

    /** Delete orphaned conversations that were never confirmed. */
    @Query("DELETE FROM conversations WHERE state = 'CREATING'")
    suspend fun deleteOrphaned(): Int

    @Query("DELETE FROM conversations WHERE conversationId = :id")
    suspend fun delete(id: String)

    /**
     * Bug #9: re-key a CREATING conversation when the service returns a
     * different session id than the one we tentatively inserted under.
     * Atomic: delete the tentative row + insert the replacement in a
     * single Room transaction so concurrent readers never see both rows
     * (or neither). The replacement entity carries the new
     * conversationId in its primary-key column.
     */
    @Transaction
    suspend fun renameConversation(tentativeId: String, replacement: ConversationEntity) {
        delete(tentativeId)
        upsert(replacement)
    }

    /** List all conversations, newest first. */
    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC")
    suspend fun listAll(): List<ConversationEntity>

    /** List conversations with pagination. */
    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC LIMIT :limit OFFSET :offset")
    suspend fun listPaged(limit: Int, offset: Int): List<ConversationEntity>

    /** List conversations with completed turn counts, newest first. */
    @Query(
        """
        SELECT c.*, COUNT(t.turnId) AS completedTurnCount
        FROM conversations c
        LEFT JOIN turns t
            ON t.conversationId = c.conversationId
            AND t.state = 'COMPLETED'
        GROUP BY c.conversationId
        ORDER BY c.updatedAtMs DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun listPagedWithCompletedTurnCounts(
        limit: Int,
        offset: Int,
    ): List<ConversationWithTurnCount>

    /** Count all conversations. */
    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    /** Delete conversations older than a given timestamp. */
    @Query("DELETE FROM conversations WHERE updatedAtMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int

    /** Delete all conversations. Used by [HistoryStore.clearAll]. */
    @Query("DELETE FROM conversations")
    suspend fun deleteAll(): Int
}

data class ConversationWithTurnCount(
    @Embedded val conversation: ConversationEntity,
    val completedTurnCount: Int,
)
