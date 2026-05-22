package com.adsamcik.mindlayer.service.logging

import androidx.room.*

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry): Long

    @Insert
    suspend fun insertAll(entries: List<LogEntry>)

    // Recent logs
    @Query("SELECT * FROM usage_logs ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<LogEntry>

    // By category
    @Query("SELECT * FROM usage_logs WHERE category = :category ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getByCategory(category: String, limit: Int = 100): List<LogEntry>

    // By session
    @Query("SELECT * FROM usage_logs WHERE sessionId = :sessionId ORDER BY timestampMs DESC")
    suspend fun getBySession(sessionId: String): List<LogEntry>

    // By request
    @Query("SELECT * FROM usage_logs WHERE requestId = :requestId ORDER BY timestampMs ASC")
    suspend fun getByRequest(requestId: String): List<LogEntry>

    // Aggregates
    @Query("SELECT COUNT(*) FROM usage_logs WHERE category = 'INFERENCE' AND event = 'request_complete'")
    suspend fun totalInferenceCount(): Int

    @Query("SELECT AVG(tokensPerSec) FROM usage_logs WHERE tokensPerSec IS NOT NULL AND event = 'request_complete'")
    suspend fun averageTokensPerSec(): Float?

    @Query("SELECT SUM(tokensGenerated) FROM usage_logs WHERE tokensGenerated IS NOT NULL")
    suspend fun totalTokensGenerated(): Long?

    @Query("SELECT AVG(durationMs) FROM usage_logs WHERE durationMs IS NOT NULL AND event = 'request_complete'")
    suspend fun averageInferenceDurationMs(): Float?

    // Error count in last N hours
    @Query("SELECT COUNT(*) FROM usage_logs WHERE category = 'ERROR' AND timestampMs > :sinceMs")
    suspend fun errorCountSince(sinceMs: Long): Int

    @Query("SELECT COUNT(*) FROM usage_logs WHERE category = 'ERROR' AND timestampMs > :sinceMs")
    suspend fun recentErrorCountSince(sinceMs: Long): Int

    @Query("SELECT * FROM usage_logs WHERE event = 'request_complete' AND (tokensPerSec IS NOT NULL OR prefillTokensPerSec IS NOT NULL) ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latestThroughput(): LogEntry?

    // Thermal band distribution
    @Query("SELECT thermalBand, COUNT(*) as count FROM usage_logs WHERE category = 'THERMAL' AND event = 'band_change' GROUP BY thermalBand")
    suspend fun thermalBandDistribution(): List<ThermalBandCount>

    // Cleanup old logs
    @Query("DELETE FROM usage_logs WHERE timestampMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int

    // Total count
    @Query("SELECT COUNT(*) FROM usage_logs")
    suspend fun totalCount(): Int

    // Session history
    @Query("""
        SELECT 
            sessionId,
            MIN(timestampMs) AS firstEventMs,
            MAX(timestampMs) AS lastEventMs,
            MAX(CASE WHEN backend IS NOT NULL THEN backend ELSE NULL END) AS backend,
            SUM(CASE WHEN event = 'request_complete' THEN 1 ELSE 0 END) AS inferenceCount,
            COALESCE(SUM(CASE WHEN event = 'request_complete' THEN tokensGenerated ELSE 0 END), 0) AS totalTokens
        FROM usage_logs
        WHERE sessionId IS NOT NULL
        GROUP BY sessionId
        ORDER BY MAX(timestampMs) DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun listSessionSummaries(limit: Int = 50, offset: Int = 0): List<SessionSummaryRow>

    @Query("SELECT COUNT(DISTINCT sessionId) FROM usage_logs WHERE sessionId IS NOT NULL")
    suspend fun countDistinctSessions(): Int

    @Query("SELECT errorMessage FROM usage_logs WHERE event = 'engine_fallback' AND backend = 'GPU' ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latestGpuFallbackMessage(): String?

    /**
     * F-077: latest categorised init-failure row, used by
     * [com.adsamcik.mindlayer.service.ui.DashboardViewModel] to render
     * variant-specific remediation copy.
     *
     * Returns the full [LogEntry] so the dashboard can extract
     * [LogEntry.backend], [LogEntry.errorMessage] (safeLabel), and the
     * variant name from [LogEntry.extraJson]'s `failureCategory` field.
     * Returns `null` when no init failure has been logged yet.
     */
    @Query("""
        SELECT * FROM usage_logs
        WHERE event = 'init_failure_categorized'
          AND timestampMs > (
              SELECT COALESCE(MAX(timestampMs), 0)
              FROM usage_logs
              WHERE event IN ('engine_init_success', 'engine_init', 'ocr_backend_ready', 'embedding_backend_ready')
          )
        ORDER BY timestampMs DESC
        LIMIT 1
    """)
    suspend fun latestInitFailure(): LogEntry?

    @Query("SELECT * FROM usage_logs WHERE event = 'backend_decision' ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latestBackendDecision(): LogEntry?

    @Query("""
        SELECT * FROM usage_logs
        WHERE event = 'backend_decision'
          AND extraJson LIKE '%' || '"feature":"' || :feature || '"' || '%'
        ORDER BY timestampMs DESC
        LIMIT 1
    """)
    suspend fun latestBackendDecisionByFeature(feature: String): LogEntry?

    @Query("SELECT * FROM usage_logs WHERE event IN (:events) ORDER BY timestampMs DESC")
    suspend fun getByEvents(events: List<String>): List<LogEntry>
}

data class ThermalBandCount(
    val thermalBand: String?,
    val count: Int,
)

data class SessionSummaryRow(
    val sessionId: String,
    val firstEventMs: Long,
    val lastEventMs: Long,
    val backend: String?,
    val inferenceCount: Int,
    val totalTokens: Int,
)
