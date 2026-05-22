package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.DeferredResult

/**
 * Hand-rolled in-memory implementation of [DeferredDao] for unit tests.
 *
 * Mirrors the SQL semantics encoded in the DAO annotations:
 *  - `byRequestId(requestId)` → row keyed by primary key
 *  - `runningCount(uid)` → rows where uid matches AND statusCode = STILL_RUNNING
 *  - `pendingCompletedCount(uid)` → uid matches AND statusCode != STILL_RUNNING
 *    AND fetchedAtMs IS NULL
 *  - `resultBytes(uid)` → SUM(LENGTH(resultText)) where uid matches
 *  - `oldestCompletedWithText(uid)` → ORDER BY completedAtMs ASC LIMIT 1
 *
 * Keeping this fake colocated with the test makes the semantics auditable.
 * The real Room DAO is exercised by integration tests (planned, see F-D2).
 */
internal class FakeDeferredDao : DeferredDao {

    private val rows = mutableMapOf<String, DeferredEntity>()

    fun snapshot(requestId: String): DeferredEntity? = rows[requestId]

    override suspend fun insert(entity: DeferredEntity) {
        require(rows.put(entity.requestId, entity) == null) {
            "Conflict on primary key requestId=${entity.requestId}"
        }
    }

    override suspend fun byRequestId(requestId: String): DeferredEntity? = rows[requestId]

    override suspend fun runningCount(uid: Int, running: Int): Int =
        rows.values.count { it.uid == uid && it.statusCode == running }

    override suspend fun pendingCompletedCount(uid: Int, running: Int): Int =
        rows.values.count { it.uid == uid && it.statusCode != running && it.fetchedAtMs == null }

    override suspend fun resultBytes(uid: Int): Long =
        rows.values.filter { it.uid == uid }.sumOf { (it.resultText?.length?.toLong() ?: 0L) + (it.blobBytes ?: 0L) }

    override suspend fun oldestCompletedWithText(uid: Int, running: Int): DeferredEntity? =
        rows.values
            .filter { it.uid == uid && it.statusCode != running && (it.resultText != null || it.blobPath != null) }
            .minByOrNull { it.completedAtMs ?: Long.MAX_VALUE }

    override suspend fun clearResultText(requestId: String): Int {
        val r = rows[requestId] ?: return 0
        rows[requestId] = r.copy(resultText = null)
        return 1
    }

    override suspend fun deleteOwned(requestId: String, uid: Int): Int {
        val r = rows[requestId] ?: return 0
        if (r.uid != uid) return 0
        rows.remove(requestId)
        return 1
    }

    override suspend fun deleteAny(requestId: String): Int =
        if (rows.remove(requestId) != null) 1 else 0

    override suspend fun expiredBefore(nowMs: Long): List<DeferredEntity> = rows.values.filter { it.expiresAtMs <= nowMs }

    override suspend fun deleteExpired(nowMs: Long): Int {
        val victims = rows.values.filter { it.expiresAtMs <= nowMs }.map { it.requestId }
        victims.forEach { rows.remove(it) }
        return victims.size
    }

    override suspend fun markFetched(requestId: String, uid: Int, nowMs: Long): Int {
        val r = rows[requestId] ?: return 0
        if (r.uid != uid) return 0
        rows[requestId] = r.copy(fetchedAtMs = nowMs)
        return 1
    }

    override suspend fun complete(
        requestId: String,
        uid: Int,
        kind: String,
        status: Int,
        text: String?,
        metricsJson: String?,
        errorCodeInt: Int,
        errorCodeName: String?,
        completedAtMs: Long,
        expiresAtMs: Long,
        truncated: Boolean,
        blobPath: String?,
        blobBytes: Long?,
        perItemMetadataJson: String?,
        running: Int,
    ): Int {
        val r = rows[requestId] ?: return 0
        if (r.uid != uid || r.kind != kind || r.statusCode != running) return 0
        rows[requestId] = r.copy(
            statusCode = status,
            resultText = text,
            metricsJson = metricsJson,
            errorCodeInt = errorCodeInt,
            errorCodeName = errorCodeName,
            completedAtMs = completedAtMs,
            expiresAtMs = expiresAtMs,
            truncated = truncated,
            blobPath = blobPath,
            blobBytes = blobBytes,
            perItemMetadataJson = perItemMetadataJson,
        )
        return 1
    }

    override suspend fun completedPendingForUid(uid: Int, kind: String, running: Int): List<DeferredEntity> =
        rows.values
            .filter { it.uid == uid && it.kind == kind && it.statusCode != running && it.fetchedAtMs == null }
            .sortedBy { it.completedAtMs ?: Long.MAX_VALUE }

    override suspend fun cancelRunning(
        requestId: String,
        uid: Int,
        nowMs: Long,
        expiresAtMs: Long,
        cancelled: Int,
        running: Int,
    ): Int {
        val r = rows[requestId] ?: return 0
        if (r.uid != uid || r.statusCode != running) return 0
        rows[requestId] = r.copy(
            statusCode = cancelled,
            completedAtMs = nowMs,
            expiresAtMs = expiresAtMs,
        )
        return 1
    }

    override suspend fun failRunningOnInit(
        failed: Int,
        nowMs: Long,
        expiresAtMs: Long,
        errorCode: Int,
        errorName: String?,
        running: Int,
    ): Int {
        var flipped = 0
        val keys = rows.keys.toList()
        for (k in keys) {
            val r = rows[k] ?: continue
            if (r.statusCode == running) {
                rows[k] = r.copy(
                    statusCode = failed,
                    completedAtMs = nowMs,
                    expiresAtMs = expiresAtMs,
                    errorCodeInt = errorCode,
                    errorCodeName = errorName,
                )
                flipped++
            }
        }
        return flipped
    }
}



