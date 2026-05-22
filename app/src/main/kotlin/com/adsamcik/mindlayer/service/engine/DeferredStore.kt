package com.adsamcik.mindlayer.service.engine

import android.os.Bundle
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.EmbeddingItemMetadata
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

sealed class EmbeddingFetchOutcome {
    object StillRunning : EmbeddingFetchOutcome()
    data class Ready(val blobPath: String, val blobBytes: Long, val metrics: Bundle?, val metadata: List<EmbeddingItemMetadata>) : EmbeddingFetchOutcome()
    object Cancelled : EmbeddingFetchOutcome()
    data class Failed(val errorCodeInt: Int, val errorCodeName: String?) : EmbeddingFetchOutcome()
    object Expired : EmbeddingFetchOutcome()
    object NotFoundOrNotOwned : EmbeddingFetchOutcome()
}

class DeferredStore(
    private val dao: DeferredDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxRunningPerUid: Int = DEFAULT_MAX_RUNNING_PER_UID,
    private val maxCompletedPendingPerUid: Int = DEFAULT_MAX_COMPLETED_PENDING_PER_UID,
    private val maxResultBytesPerUid: Long = DEFAULT_MAX_RESULT_BYTES_PER_UID,
    private val maxResultBytesPerResult: Int = DEFAULT_MAX_RESULT_BYTES_PER_RESULT,
    /**
     * Per-UID byte budget specifically for embedding deferred results. The
     * default of 16 MiB is sized to accommodate a single max-size embedding
     * batch (4096 items × 768 dim × 4 bytes ≈ 12 MiB) plus headroom for a
     * partial second batch. Set independently from [maxResultBytesPerUid]
     * because embedding result blobs are an order of magnitude larger than
     * chat result text, and treating them under the same 1 MiB quota would
     * cause the byte-quota path to silently delete a just-written embedding
     * result before the client can fetch it.
     */
    private val maxEmbeddingResultBytesPerUid: Long = DEFAULT_MAX_EMBEDDING_RESULT_BYTES_PER_UID,
) {
    companion object {
        const val DEFAULT_MAX_RUNNING_PER_UID: Int = 16
        const val DEFAULT_MAX_COMPLETED_PENDING_PER_UID: Int = 64
        const val DEFAULT_MAX_RESULT_BYTES_PER_UID: Long = 1L * 1024L * 1024L
        const val DEFAULT_MAX_RESULT_BYTES_PER_RESULT: Int = 256 * 1024
        /**
         * 16 MiB — covers a single max-size embedding deferred batch
         * (~12 MiB at 4096 × 768 × float32 + 8-byte header) plus headroom.
         * If the embedding batch cap is ever raised, raise this in lockstep.
         */
        const val DEFAULT_MAX_EMBEDDING_RESULT_BYTES_PER_UID: Long = 16L * 1024L * 1024L
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
            kind = DeferredEntity.KIND_CHAT,
        )
        return if (dao.createIfWithinQuota(entity, maxRunningPerUid, maxCompletedPendingPerUid)) {
            DeferredHandle(requestId = requestId, expiresAtMs = entity.expiresAtMs)
        } else null
    }

    suspend fun createEmbeddingBatch(uid: Int, requestId: String, batchSize: Int): DeferredHandle? {
        pruneExpired()
        val now = clock()
        val entity = DeferredEntity(
            requestId = requestId,
            uid = uid,
            sessionId = "",
            promptChars = batchSize,
            mediaCount = 0,
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
            kind = DeferredEntity.KIND_EMBEDDING,
        )
        return if (dao.createIfWithinQuota(entity, maxRunningPerUid, maxCompletedPendingPerUid)) {
            DeferredHandle(requestId = requestId, expiresAtMs = entity.expiresAtMs)
        } else null
    }

    suspend fun completeReady(requestId: String, uid: Int, text: String, metrics: Bundle?): Boolean {
        val (capped, truncated) = capResultText(text)
        return complete(requestId, uid, DeferredEntity.KIND_CHAT, DeferredResult.READY, capped, metrics, 0, null, truncated, null, null)
    }

    suspend fun completeEmbeddingBatch(requestId: String, uid: Int, blobPath: String, blobBytes: Long, metrics: Bundle?, metadata: List<EmbeddingItemMetadata> = emptyList()): Boolean {
        return complete(requestId, uid, DeferredEntity.KIND_EMBEDDING, DeferredResult.READY, null, metrics, 0, null, false, blobPath, blobBytes, metadataToJson(metadata))
    }

    suspend fun completeFailed(requestId: String, uid: Int, errorCode: Int, errorName: String?): Boolean {
        return complete(requestId, uid, DeferredEntity.KIND_CHAT, DeferredResult.FAILED, null, null, errorCode, errorName, false, null, null)
    }

    suspend fun failEmbeddingBatch(requestId: String, uid: Int, errorCode: Int, errorName: String?): Boolean {
        return complete(requestId, uid, DeferredEntity.KIND_EMBEDDING, DeferredResult.FAILED, null, null, errorCode, errorName, false, null, null)
    }

    suspend fun completeCancelled(requestId: String, uid: Int): Boolean {
        return complete(requestId, uid, DeferredEntity.KIND_CHAT, DeferredResult.CANCELLED, null, null, 0, null, false, null, null)
    }

    suspend fun completeEmbeddingCancelled(requestId: String, uid: Int): Boolean {
        return complete(requestId, uid, DeferredEntity.KIND_EMBEDDING, DeferredResult.CANCELLED, null, null, 0, null, false, null, null)
    }

    private suspend fun complete(
        requestId: String,
        uid: Int,
        kind: String,
        status: Int,
        text: String?,
        metrics: Bundle?,
        errorCode: Int,
        errorName: String?,
        truncated: Boolean,
        blobPath: String?,
        blobBytes: Long?,
        perItemMetadataJson: String? = null,
    ): Boolean {
        val now = clock()
        val updated = dao.complete(
            requestId = requestId,
            uid = uid,
            kind = kind,
            status = status,
            text = text,
            metricsJson = metrics?.let { metricsToJson(it) },
            errorCodeInt = errorCode,
            errorCodeName = errorName,
            completedAtMs = now,
            expiresAtMs = now + ttlMs,
            truncated = truncated,
            blobPath = blobPath,
            blobBytes = blobBytes,
            perItemMetadataJson = perItemMetadataJson,
        )
        if (updated == 0) {
            return false
        }
        // The "kind" of the just-completed row determines which per-UID
        // byte budget applies. Embedding blobs are ~10× larger than chat
        // result text (12 MiB max vs 256 KiB) so they need a higher cap,
        // applied independently — without this, a single embedding batch
        // would trip the chat quota and self-delete before fetch.
        enforceByteQuota(uid, kind)
        return true
    }

    private fun capResultText(text: String): Pair<String, Boolean> =
        if (text.length <= maxResultBytesPerResult) text to false else text.substring(0, maxResultBytesPerResult) to true

    suspend fun fetch(uid: Int, requestId: String): DeferredResult {
        val entity = dao.byRequestId(requestId) ?: return DeferredResult(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        if (entity.uid != uid || entity.kind != DeferredEntity.KIND_CHAT) return DeferredResult(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        val now = clock()
        if (entity.fetchedAtMs != null && entity.statusCode != DeferredResult.STILL_RUNNING) {
            return DeferredResult(status = DeferredResult.NOT_FOUND_OR_NOT_OWNED)
        }
        if (entity.expiresAtMs <= now) {
            dao.deleteOwned(requestId, uid)
            deleteBlob(entity)
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
                DeferredResult(status = DeferredResult.READY, text = entity.resultText.orEmpty(), metrics = jsonToMetrics(entity.metricsJson, entity.truncated))
            }
            DeferredResult.CANCELLED -> {
                dao.markFetched(requestId, uid, now)
                DeferredResult(status = DeferredResult.CANCELLED)
            }
            DeferredResult.FAILED -> {
                dao.markFetched(requestId, uid, now)
                DeferredResult(status = DeferredResult.FAILED, errorCodeInt = entity.errorCodeInt, errorCodeName = entity.errorCodeName)
            }
            else -> {
                dao.markFetched(requestId, uid, now)
                DeferredResult(status = DeferredResult.FAILED, errorCodeInt = entity.errorCodeInt, errorCodeName = entity.errorCodeName)
            }
        }
    }

    suspend fun fetchEmbeddingBatch(uid: Int, requestId: String): EmbeddingFetchOutcome {
        val entity = dao.byRequestId(requestId) ?: return EmbeddingFetchOutcome.NotFoundOrNotOwned
        if (entity.uid != uid || entity.kind != DeferredEntity.KIND_EMBEDDING) return EmbeddingFetchOutcome.NotFoundOrNotOwned
        val now = clock()
        if (entity.fetchedAtMs != null && entity.statusCode != DeferredResult.STILL_RUNNING) {
            return EmbeddingFetchOutcome.NotFoundOrNotOwned
        }
        if (entity.expiresAtMs <= now) {
            dao.deleteOwned(requestId, uid)
            deleteBlob(entity)
            return EmbeddingFetchOutcome.Expired
        }
        return when (entity.statusCode) {
            DeferredResult.STILL_RUNNING -> EmbeddingFetchOutcome.StillRunning
            DeferredResult.READY -> {
                dao.markFetched(requestId, uid, now)
                val path = entity.blobPath ?: return EmbeddingFetchOutcome.Failed(MindlayerErrorCode.INTERNAL, MindlayerErrorCode.nameOf(MindlayerErrorCode.INTERNAL))
                EmbeddingFetchOutcome.Ready(path, entity.blobBytes ?: 0L, jsonToMetrics(entity.metricsJson, entity.truncated), metadataFromJson(entity.perItemMetadataJson))
            }
            DeferredResult.CANCELLED -> {
                dao.markFetched(requestId, uid, now)
                EmbeddingFetchOutcome.Cancelled
            }
            DeferredResult.FAILED -> {
                dao.markFetched(requestId, uid, now)
                EmbeddingFetchOutcome.Failed(entity.errorCodeInt, entity.errorCodeName)
            }
            else -> {
                dao.markFetched(requestId, uid, now)
                EmbeddingFetchOutcome.Failed(entity.errorCodeInt, entity.errorCodeName)
            }
        }
    }

    suspend fun cancel(uid: Int, requestId: String): Int = cancelInternal(uid, requestId, DeferredEntity.KIND_CHAT)

    suspend fun cancelEmbeddingBatch(requestId: String, uid: Int): Int = cancelInternal(uid, requestId, DeferredEntity.KIND_EMBEDDING)

    private suspend fun cancelInternal(uid: Int, requestId: String, kind: String): Int {
        pruneExpired()
        val entity = dao.byRequestId(requestId) ?: return com.adsamcik.mindlayer.CancelResult.UNKNOWN
        if (entity.uid != uid || entity.kind != kind) return com.adsamcik.mindlayer.CancelResult.UNKNOWN
        if (entity.statusCode != DeferredResult.STILL_RUNNING) {
            if (kind == DeferredEntity.KIND_EMBEDDING && dao.deleteOwned(requestId, uid) > 0) deleteBlob(entity)
            return com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED
        }
        val now = clock()
        dao.cancelRunning(requestId, uid, now, now + ttlMs)
        return com.adsamcik.mindlayer.CancelResult.CANCELLED
    }

    suspend fun acknowledge(uid: Int, requestId: String): Boolean = acknowledgeInternal(uid, requestId, DeferredEntity.KIND_CHAT)

    suspend fun acknowledgeEmbeddingBatch(uid: Int, requestId: String): Boolean = acknowledgeInternal(uid, requestId, DeferredEntity.KIND_EMBEDDING)

    private suspend fun acknowledgeInternal(uid: Int, requestId: String, kind: String): Boolean {
        pruneExpired()
        val entity = dao.byRequestId(requestId) ?: return false
        if (entity.uid != uid || entity.kind != kind) return false
        val deleted = dao.deleteOwned(requestId, uid) > 0
        if (deleted) deleteBlob(entity)
        return deleted
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

    suspend fun completedPendingForUid(uid: Int, kind: String = DeferredEntity.KIND_CHAT): List<DeferredEntity> =
        dao.completedPendingForUid(uid, kind)

    /**
     * Lightweight lookup used by the embedding-blob startup sweep. Returns
     * the row whose primary key matches [requestId], or null if no such row
     * exists (e.g. because it was cleaned up by ack/expire/prune already, or
     * because it was never created — orphaned blob case).
     */
    suspend fun entityByRequestIdOrNull(requestId: String): DeferredEntity? =
        dao.byRequestId(requestId)

    suspend fun pruneExpired(): Int {
        val now = clock()
        dao.expiredBefore(now).forEach { deleteBlob(it) }
        return dao.deleteExpired(now)
    }

    private suspend fun enforceByteQuota(uid: Int, kind: String) {
        val cap = if (kind == DeferredEntity.KIND_EMBEDDING) {
            maxEmbeddingResultBytesPerUid
        } else {
            maxResultBytesPerUid
        }
        while (dao.resultBytes(uid) > cap) {
            val oldest = dao.oldestCompletedWithText(uid) ?: return
            dao.deleteAny(oldest.requestId)
            deleteBlob(oldest)
        }
    }

    private fun deleteBlob(entity: DeferredEntity) {
        val path = entity.blobPath ?: return
        runCatching { File(path).delete() }
    }


    private fun metadataToJson(metadata: List<EmbeddingItemMetadata>): String? {
        if (metadata.isEmpty()) return null
        return JsonArray(metadata.map { item ->
            buildJsonObject {
                item.tag?.let { put("tag", it) }
                put("tokenCount", item.tokenCount)
                put("truncated", item.truncated)
            }
        }).toString()
    }

    private fun metadataFromJson(raw: String?): List<EmbeddingItemMetadata> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val tag = obj["tag"]?.jsonPrimitive?.contentOrNull
            val tokenCount = obj["tokenCount"]?.jsonPrimitive?.intOrNull ?: 0
            val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
            EmbeddingItemMetadata(tag = tag, tokenCount = tokenCount, truncated = truncated)
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
            primitive.booleanOrNull?.let { bundle.putBoolean(key, it); return@forEach }
            primitive.intOrNull?.let { bundle.putInt(key, it); return@forEach }
            primitive.longOrNull?.let { bundle.putLong(key, it); return@forEach }
            primitive.doubleOrNull?.let { bundle.putDouble(key, it) }
        }
        if (truncated) bundle.putBoolean(METRIC_TRUNCATED, true)
        return bundle
    }

    object Metrics {
        const val TRUNCATED = METRIC_TRUNCATED
    }
}

private const val METRIC_TRUNCATED = "truncated"
