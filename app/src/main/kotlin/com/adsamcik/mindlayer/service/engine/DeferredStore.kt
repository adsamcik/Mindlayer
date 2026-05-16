package com.adsamcik.mindlayer.service.engine

import android.os.Bundle
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class DeferredStore(
    private val dao: DeferredDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxRunningPerUid: Int = DEFAULT_MAX_RUNNING_PER_UID,
    private val maxCompletedPendingPerUid: Int = DEFAULT_MAX_COMPLETED_PENDING_PER_UID,
    private val maxResultBytesPerUid: Long = DEFAULT_MAX_RESULT_BYTES_PER_UID,
    private val maxResultBytesPerResult: Int = DEFAULT_MAX_RESULT_BYTES_PER_RESULT,
) {
    companion object {
        const val DEFAULT_MAX_RUNNING_PER_UID: Int = 16
        const val DEFAULT_MAX_COMPLETED_PENDING_PER_UID: Int = 64
        const val DEFAULT_MAX_RESULT_BYTES_PER_UID: Long = 1L * 1024L * 1024L

        /**
         * Per-result hard cap. A single deferred result above this size is
         * truncated at commit and flagged via the `truncated` metric. Keeps
         * one fat result from self-evicting under [maxResultBytesPerUid].
         */
        const val DEFAULT_MAX_RESULT_BYTES_PER_RESULT: Int = 256 * 1024
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
            truncated = false,
        )
        return if (dao.createIfWithinQuota(entity, maxRunningPerUid, maxCompletedPendingPerUid)) {
            DeferredHandle(requestId = requestId, expiresAtMs = entity.expiresAtMs)
        } else {
            null
        }
    }

    suspend fun completeReady(requestId: String, uid: Int, text: String, metrics: Bundle?) {
        val (capped, truncated) = capResultText(text)
        complete(requestId, uid, DeferredResult.READY, capped, metrics, 0, null, truncated)
    }

    suspend fun completeFailed(requestId: String, uid: Int, errorCode: Int, errorName: String?) {
        complete(requestId, uid, DeferredResult.FAILED, null, null, errorCode, errorName, false)
    }

    suspend fun completeCancelled(requestId: String, uid: Int) {
        complete(requestId, uid, DeferredResult.CANCELLED, null, null, 0, null, false)
    }

    private suspend fun complete(
        requestId: String,
        uid: Int,
        status: Int,
        text: String?,
        metrics: Bundle?,
        errorCode: Int,
        errorName: String?,
        truncated: Boolean,
    ) {
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
            truncated = truncated,
        )
        enforceByteQuota(uid)
    }

    /**
     * Truncate [text] at [maxResultBytesPerResult] UTF-16 code units (the
     * String length unit Room persists). Returns the (possibly-truncated)
     * text and a flag indicating whether truncation occurred.
     */
    private fun capResultText(text: String): Pair<String, Boolean> =
        if (text.length <= maxResultBytesPerResult) text to false
        else text.substring(0, maxResultBytesPerResult) to true

    suspend fun fetch(uid: Int, requestId: String): DeferredResult {
        // M-D1: lookup BEFORE pruning so we can distinguish EXPIRED from
        // NOT_FOUND_OR_NOT_OWNED. Background prune is still driven by
        // create/cancel/acknowledge paths.
        val entity = dao.byRequestId(requestId)
            ?: return DeferredResult(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        if (entity.uid != uid) return DeferredResult(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        val now = clock()
        if (entity.expiresAtMs <= now) {
            dao.deleteOwned(requestId, uid)
            return DeferredResult(
                status = DeferredResult.EXPIRED,
                errorCodeInt = MindlayerErrorCode.DEFERRED_EXPIRED,
                errorCodeName = MindlayerErrorCode.nameOf(MindlayerErrorCode.DEFERRED_EXPIRED),
            )
        }
        return when (entity.statusCode) {
            DeferredResult.STILL_RUNNING -> DeferredResult(status = DeferredResult.STILL_RUNNING)
            DeferredResult.READY -> {
                dao.markFetched(requestId, uid, now)
                DeferredResult(
                    status = DeferredResult.READY,
                    text = entity.resultText.orEmpty(),
                    metrics = jsonToMetrics(entity.metricsJson, entity.truncated),
                )
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

    /**
     * Snapshot of completed entries for [uid] the client has not yet
     * fetched. Used by the binder to re-fire `onDeferredInferenceComplete`
     * callbacks after a reconnect — see M-D3.
     */
    suspend fun completedPendingForUid(uid: Int): List<DeferredEntity> =
        dao.completedPendingForUid(uid)

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
                is Boolean -> put(key, value)
            }
        }
    }.toString()

    private fun jsonToMetrics(raw: String?, truncated: Boolean): Bundle? {
        val obj: JsonObject? = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (obj == null && !truncated) return null
        val bundle = Bundle()
        obj?.forEach { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@forEach
            // M-D6: round-trip the same numeric/boolean types the writer
            // can emit. Prior implementation collapsed everything to Int,
            // silently dropping Long timestamps and floating-point metrics.
            primitive.booleanOrNull?.let { bundle.putBoolean(key, it); return@forEach }
            primitive.intOrNull?.let { bundle.putInt(key, it); return@forEach }
            primitive.longOrNull?.let { bundle.putLong(key, it); return@forEach }
            primitive.doubleOrNull?.let { bundle.putDouble(key, it) }
        }
        if (truncated) bundle.putBoolean(METRIC_TRUNCATED, true)
        return bundle
    }

    /**
     * Flag emitted in the [DeferredResult.metrics] bundle when the result
     * text was truncated to fit [maxResultBytesPerResult]. Surface to
     * callers so they can warn or refetch via a non-deferred path.
     */
    object Metrics {
        const val TRUNCATED = METRIC_TRUNCATED
    }
}

private const val METRIC_TRUNCATED = "truncated"
