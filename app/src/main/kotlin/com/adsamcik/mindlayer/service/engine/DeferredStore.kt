package com.adsamcik.mindlayer.service.engine

import android.os.Bundle
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DeferredStore(
    private val dao: DeferredDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxRunningPerUid: Int = DEFAULT_MAX_RUNNING_PER_UID,
    private val maxCompletedPendingPerUid: Int = DEFAULT_MAX_COMPLETED_PENDING_PER_UID,
    private val maxResultBytesPerUid: Long = DEFAULT_MAX_RESULT_BYTES_PER_UID,
) {
    companion object {
        const val DEFAULT_MAX_RUNNING_PER_UID: Int = 16
        const val DEFAULT_MAX_COMPLETED_PENDING_PER_UID: Int = 64
        const val DEFAULT_MAX_RESULT_BYTES_PER_UID: Long = 1L * 1024L * 1024L
        const val DEFAULT_TTL_MS: Long = 24L * 60L * 60L * 1000L
        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun create(uid: Int, requestId: String, meta: RequestMeta, mediaCount: Int): DeferredHandle? {
        pruneExpired()
        val now = clock()
        val entity = DeferredEntity(
            requestId = requestId,
            uid = uid,
            sessionId = meta.sessionId,
            promptChars = meta.textContent?.length ?: 0,
            mediaCount = mediaCount,
            metricsJson = null,
            resultText = null,
            errorCodeInt = 0,
            errorCodeName = null,
            statusCode = DeferredResult.STILL_RUNNING,
            createdAtMs = now,
            completedAtMs = null,
            expiresAtMs = now + ttlMs,
            fetchedAtMs = null,
        )
        return if (dao.createIfWithinQuota(entity, maxRunningPerUid, maxCompletedPendingPerUid)) {
            DeferredHandle(requestId = requestId, expiresAtMs = entity.expiresAtMs)
        } else {
            null
        }
    }

    suspend fun completeReady(requestId: String, uid: Int, text: String, metrics: Bundle?) {
        complete(requestId, uid, DeferredResult.READY, text, metrics, 0, null)
    }

    suspend fun completeFailed(requestId: String, uid: Int, errorCode: Int, errorName: String?) {
        complete(requestId, uid, DeferredResult.FAILED, null, null, errorCode, errorName)
    }

    suspend fun completeCancelled(requestId: String, uid: Int) {
        val now = clock()
        dao.complete(requestId, DeferredResult.CANCELLED, null, null, 0, null, now, now + ttlMs)
    }

    private suspend fun complete(requestId: String, uid: Int, status: Int, text: String?, metrics: Bundle?, errorCode: Int, errorName: String?) {
        val now = clock()
        dao.complete(
            requestId = requestId,
            status = status,
            text = text,
            metricsJson = metrics?.let { metricsToJson(it) },
            errorCodeInt = errorCode,
            errorCodeName = errorName,
            completedAtMs = now,
            expiresAtMs = now + ttlMs,
        )
        enforceByteQuota(uid)
    }

    suspend fun fetch(uid: Int, requestId: String): DeferredResult {
        pruneExpired()
        val entity = dao.byRequestId(requestId)
            ?: return DeferredResult(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        if (entity.uid != uid) return DeferredResult(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        val now = clock()
        if (entity.expiresAtMs <= now) {
            dao.deleteOwned(requestId, uid)
            return DeferredResult(status = DeferredResult.EXPIRED, errorCodeInt = MindlayerErrorCode.DEFERRED_EXPIRED, errorCodeName = MindlayerErrorCode.nameOf(MindlayerErrorCode.DEFERRED_EXPIRED))
        }
        return when (entity.statusCode) {
            DeferredResult.STILL_RUNNING -> DeferredResult(status = DeferredResult.STILL_RUNNING)
            DeferredResult.READY -> {
                dao.markFetched(requestId, uid, now)
                DeferredResult(status = DeferredResult.READY, text = entity.resultText.orEmpty(), metrics = jsonToMetrics(entity.metricsJson))
            }
            DeferredResult.CANCELLED -> DeferredResult(status = DeferredResult.CANCELLED)
            DeferredResult.FAILED -> DeferredResult(status = DeferredResult.FAILED, errorCodeInt = entity.errorCodeInt, errorCodeName = entity.errorCodeName)
            else -> DeferredResult(status = DeferredResult.FAILED, errorCodeInt = entity.errorCodeInt, errorCodeName = entity.errorCodeName)
        }
    }

    suspend fun cancel(uid: Int, requestId: String): Int {
        pruneExpired()
        val entity = dao.byRequestId(requestId) ?: return com.adsamcik.mindlayer.CancelResult.UNKNOWN
        if (entity.uid != uid) return com.adsamcik.mindlayer.CancelResult.UNKNOWN
        if (entity.statusCode != DeferredResult.STILL_RUNNING) return com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED
        val now = clock()
        dao.cancelRunning(requestId, uid, now, now + ttlMs)
        return com.adsamcik.mindlayer.CancelResult.CANCELLED
    }

    suspend fun acknowledge(uid: Int, requestId: String): Boolean {
        pruneExpired()
        return dao.deleteOwned(requestId, uid) > 0
    }

    suspend fun failRunningOnInit(): Int {
        val now = clock()
        return dao.failRunningOnInit(
            failed = DeferredResult.FAILED,
            nowMs = now,
            expiresAtMs = now + ttlMs,
            errorCode = MindlayerErrorCode.INTERNAL,
            errorName = MindlayerErrorCode.nameOf(MindlayerErrorCode.INTERNAL),
        )
    }

    suspend fun pruneExpired(): Int = dao.deleteExpired(clock())

    private suspend fun enforceByteQuota(uid: Int) {
        while (dao.resultBytes(uid) > maxResultBytesPerUid) {
            val oldest = dao.oldestCompletedWithText(uid) ?: return
            dao.deleteAny(oldest.requestId)
        }
    }

    private fun metricsToJson(metrics: Bundle): String = buildJsonObject {
        for (key in metrics.keySet()) {
            when (val value = metrics.get(key)) {
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Float -> put(key, value)
                is Double -> put(key, value)
            }
        }
    }.toString()

    private fun jsonToMetrics(raw: String?): Bundle? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val bundle = Bundle()
        for ((key, value) in obj) {
            val primitive = value as? JsonPrimitive ?: continue
            primitive.jsonPrimitive.intOrNull?.let { bundle.putInt(key, it) }
        }
        return bundle
    }
}
