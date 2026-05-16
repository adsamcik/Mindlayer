package com.adsamcik.mindlayer.service.engine

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.mindlayer.DeferredResult

@Dao
interface DeferredDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DeferredEntity)

    @Query("SELECT * FROM deferred_inference WHERE requestId = :requestId LIMIT 1")
    suspend fun byRequestId(requestId: String): DeferredEntity?

    @Query("SELECT COUNT(*) FROM deferred_inference WHERE uid = :uid AND statusCode = :running")
    suspend fun runningCount(uid: Int, running: Int = DeferredResult.STILL_RUNNING): Int

    @Query("SELECT COUNT(*) FROM deferred_inference WHERE uid = :uid AND statusCode != :running AND fetchedAtMs IS NULL")
    suspend fun pendingCompletedCount(uid: Int, running: Int = DeferredResult.STILL_RUNNING): Int

    @Query("SELECT COALESCE(SUM(LENGTH(resultText)), 0) FROM deferred_inference WHERE uid = :uid AND resultText IS NOT NULL")
    suspend fun resultBytes(uid: Int): Long

    @Query("SELECT * FROM deferred_inference WHERE uid = :uid AND statusCode != :running AND resultText IS NOT NULL ORDER BY completedAtMs ASC LIMIT 1")
    suspend fun oldestCompletedWithText(uid: Int, running: Int = DeferredResult.STILL_RUNNING): DeferredEntity?

    @Query("UPDATE deferred_inference SET resultText = NULL WHERE requestId = :requestId")
    suspend fun clearResultText(requestId: String): Int

    @Query("DELETE FROM deferred_inference WHERE requestId = :requestId AND uid = :uid")
    suspend fun deleteOwned(requestId: String, uid: Int): Int

    @Query("DELETE FROM deferred_inference WHERE requestId = :requestId")
    suspend fun deleteAny(requestId: String): Int

    @Query("DELETE FROM deferred_inference WHERE expiresAtMs <= :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int

    @Query("UPDATE deferred_inference SET fetchedAtMs = :nowMs WHERE requestId = :requestId AND uid = :uid")
    suspend fun markFetched(requestId: String, uid: Int, nowMs: Long): Int

    @Query("UPDATE deferred_inference SET statusCode = :status, resultText = :text, metricsJson = :metricsJson, errorCodeInt = :errorCodeInt, errorCodeName = :errorCodeName, completedAtMs = :completedAtMs, expiresAtMs = :expiresAtMs, truncated = :truncated WHERE requestId = :requestId")
    suspend fun complete(requestId: String, status: Int, text: String?, metricsJson: String?, errorCodeInt: Int, errorCodeName: String?, completedAtMs: Long, expiresAtMs: Long, truncated: Boolean): Int

    /**
     * All completed entries (READY/FAILED/CANCELLED) for [uid] that the
     * client has not yet observed via [markFetched]. Used on
     * `subscribeEvictionNotices` to re-fire deferred-completion callbacks
     * for results that landed while the client's binder was dead.
     */
    @Query("SELECT * FROM deferred_inference WHERE uid = :uid AND statusCode != :running AND fetchedAtMs IS NULL ORDER BY completedAtMs ASC")
    suspend fun completedPendingForUid(uid: Int, running: Int = DeferredResult.STILL_RUNNING): List<DeferredEntity>

    @Query("UPDATE deferred_inference SET statusCode = :cancelled, completedAtMs = :nowMs, expiresAtMs = :expiresAtMs WHERE requestId = :requestId AND uid = :uid AND statusCode = :running")
    suspend fun cancelRunning(requestId: String, uid: Int, nowMs: Long, expiresAtMs: Long, cancelled: Int = DeferredResult.CANCELLED, running: Int = DeferredResult.STILL_RUNNING): Int

    @Query("UPDATE deferred_inference SET statusCode = :failed, completedAtMs = :nowMs, expiresAtMs = :expiresAtMs, errorCodeInt = :errorCode, errorCodeName = :errorName WHERE statusCode = :running")
    suspend fun failRunningOnInit(failed: Int, nowMs: Long, expiresAtMs: Long, errorCode: Int, errorName: String?, running: Int = DeferredResult.STILL_RUNNING): Int

    @Transaction
    suspend fun createIfWithinQuota(entity: DeferredEntity, maxRunning: Int, maxCompletedPending: Int): Boolean {
        if (runningCount(entity.uid) >= maxRunning) return false
        if (pendingCompletedCount(entity.uid) >= maxCompletedPending) return false
        insert(entity)
        return true
    }
}
