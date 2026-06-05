package com.adsamcik.mindlayer.service.logging

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal object LogExtras {
    fun toolNameMetadata(name: String): JsonObject = buildJsonObject {
        put("len", name.length)
        put("reason", "unknown_tool")
        put("hash8", sha256Hex(name).take(8))
    }

    /**
     * Metadata for the oversize-tool-args drop path. Mirrors
     * [toolNameMetadata]'s `{len, reason, hash8}` shape so neither logcat
     * nor [LogEntry.extraJson] ever carries the raw (model-emitted) tool
     * name — even when that name passed the allowlist check, it can still
     * encode prompt fragments and is treated as untrusted in the log path.
     */
    fun oversizeArgsMetadata(name: String, argsSize: Int): JsonObject = buildJsonObject {
        put("len", name.length)
        put("reason", "oversize_args")
        put("hash8", sha256Hex(name).take(8))
        put("size", argsSize)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

class LogRepository(
    private val dao: LogDao,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    bufferCapacity: Int = BUFFER_CAPACITY,
) {

    companion object {
        private const val TAG = "LogRepo"
        private const val BUFFER_CAPACITY = 1024
        private const val BATCH_MAX = 128
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Bounded queue — trySend never blocks; fails (and drops) when the buffer is full.
    private val queue = Channel<LogEntry>(bufferCapacity)
    private val droppedCount = AtomicLong(0)

    init {
        scope.launch { drainLoop() }
    }

    // Fire-and-forget log (non-blocking)
    fun log(entry: LogEntry) {
        val ok = queue.trySend(entry).isSuccess
        if (!ok) droppedCount.incrementAndGet()
    }

    private suspend fun drainLoop() {
        val batch = ArrayList<LogEntry>(BATCH_MAX)
        while (true) {
            // Block for at least one entry, then opportunistically drain more.
            val first = try {
                queue.receive()
            } catch (_: ClosedReceiveChannelException) {
                return
            }
            batch.add(first)
            while (batch.size < BATCH_MAX) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            try {
                dao.insertAll(batch)
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Failed to flush ${batch.size} log entries", throwable = t)
            }
            batch.clear()
        }
    }

    /** Number of log entries dropped due to backpressure since process start. */
    fun droppedLogCount(): Long = droppedCount.get()

    // Convenience builders
    fun logDeferredSubmit(requestId: String, sessionId: String, mediaCount: Int, kind: String = "chat") {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.DEFERRED_SUBMIT.key,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson {
                put("kind", kind)
                put("mediaCount", mediaCount)
            },
        ))
    }

    fun logDeferredComplete(requestId: String, sessionId: String?, statusCode: Int, kind: String = "chat") {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.DEFERRED_COMPLETE.key,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson {
                put("kind", kind)
                put("status", statusCode)
            },
        ))
    }

    fun logDeferredFetch(requestId: String, statusCode: Int, kind: String = "chat") {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.DEFERRED_FETCH.key,
            requestId = requestId,
            extraJson = logExtraJson {
                put("kind", kind)
                put("status", statusCode)
            },
        ))
    }

    fun logDeferredCancel(requestId: String, outcome: Int, kind: String = "chat") {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.DEFERRED_CANCEL.key,
            requestId = requestId,
            extraJson = logExtraJson {
                put("kind", kind)
                put("outcome", outcome)
            },
        ))
    }

    /**
     * M-D8: emitted on fetch when the deferred row has crossed `expiresAtMs`
     * and is being evicted. Distinct from [DEFERRED_COMPLETE] so dashboard
     * filters can show "completed but never fetched" vs "lapsed without
     * fetch" separately. Paired with M-D1's `fetch()` returning
     * `DeferredResult.EXPIRED` (instead of `NOT_FOUND_OR_NOT_OWNED`) for
     * the same row.
     */
    fun logDeferredExpired(requestId: String, kind: String = "chat") {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.DEFERRED_EXPIRED.key,
            requestId = requestId,
            extraJson = logExtraJson { put("kind", kind) },
        ))
    }
    fun logInferenceStart(requestId: String, sessionId: String, backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_START.key,
            requestId = requestId,
            sessionId = sessionId,
            backend = backend,
        ))
    }

    fun logInferenceComplete(
        requestId: String, sessionId: String, backend: String,
        durationMs: Long, tokensGenerated: Int, tokensPerSec: Float, prefillTps: Float?,
    ) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_COMPLETE.key,
            requestId = requestId, sessionId = sessionId, backend = backend,
            durationMs = durationMs, tokensGenerated = tokensGenerated,
            tokensPerSec = tokensPerSec, prefillTokensPerSec = prefillTps,
        ))
    }

    fun logInferenceError(requestId: String, sessionId: String?, errorMessage: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ERROR,
            event = LogEvent.REQUEST_ERROR.key,
            requestId = requestId, sessionId = sessionId,
            errorMessage = sanitizeErrorClass(errorMessage),
        ))
    }

    fun logThermalBandChange(fromBand: String, toBand: String, backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.THERMAL,
            event = LogEvent.BAND_CHANGE.key,
            thermalBand = toBand, backend = backend,
            extraJson = logExtraJson {
                put("from", fromBand)
                put("to", toBand)
            },
        ))
    }

    fun logSessionCreated(sessionId: String, backend: String, maxTokens: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_CREATED.key,
            sessionId = sessionId, backend = backend,
            extraJson = logExtraJson { put("maxTokens", maxTokens) },
        ))
    }

    fun logSessionDestroyed(sessionId: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_DESTROYED.key,
            sessionId = sessionId,
        ))
    }

    fun logSessionEvicted(sessionId: String, reason: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_EVICTED.key,
            sessionId = sessionId,
            extraJson = logExtraJson { put("reason", reason) },
        ))
    }

    /**
     * M-E3: persist that an eviction sweep fired (memory pressure,
     * pre-emptive trim). Distinct from per-session [logSessionEvicted]:
     * this event captures the triggering pressure and how many sessions
     * were evicted in the batch, so the dashboard can correlate spikes
     * even when individual session ids are not interesting.
     */
    fun logEvictionTriggered(
        trigger: String,
        evictedCount: Int,
        remainingCount: Int,
    ) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.MEMORY,
            event = LogEvent.EVICTION_TRIGGERED.key,
            extraJson = buildJsonObject {
                put("trigger", JsonPrimitive(trigger))
                put("evicted", JsonPrimitive(evictedCount))
                put("remaining", JsonPrimitive(remainingCount))
            }.toString(),
        ))
    }

    fun logMemoryPressure(pressure: String, availableMb: Long, totalMb: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.MEMORY,
            event = LogEvent.PRESSURE_CHANGE.key,
            memoryAvailableMb = availableMb,
            memoryUsedMb = totalMb - availableMb,
            extraJson = logExtraJson { put("pressure", pressure) },
        ))
    }

    fun logEngineInit(backend: String, durationMs: Long, modelPath: String) {
        val modelFile = modelPath.substringAfterLast("/").substringAfterLast(Char(92))
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT.key,
            backend = backend, durationMs = durationMs,
            extraJson = logExtraJson { put("modelFile", modelFile) },
        ))
    }

    fun logOcrBackendReady(backend: String, bundleId: String, durationMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.OCR_BACKEND_READY.key,
            backend = backend,
            durationMs = durationMs,
            extraJson = logExtraJson { put("bundleId", bundleId) },
        ))
    }

    fun logOcrBackendShutdown(backend: String, bundleId: String, durationMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.OCR_BACKEND_SHUTDOWN.key,
            backend = backend,
            durationMs = durationMs,
            extraJson = logExtraJson { put("bundleId", bundleId) },
        ))
    }

    fun logEmbeddingBackendReady(backend: String, modelId: String, durationMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.EMBEDDING,
            event = LogEvent.EMBEDDING_BACKEND_READY.key,
            backend = backend,
            durationMs = durationMs,
            extraJson = logExtraJson { put("modelId", modelId) },
        ))
    }

    fun logEmbeddingBackendShutdown(backend: String, modelId: String?, durationMs: Long? = null) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.EMBEDDING,
            event = LogEvent.EMBEDDING_BACKEND_SHUTDOWN.key,
            backend = backend,
            durationMs = durationMs,
            extraJson = logExtraJson {
                modelId?.let { put("modelId", it) }
            },
        ))
    }

    fun logEmbeddingBatchComplete(backend: String, modelId: String, batchSize: Int, vectorCount: Int, durationMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.EMBEDDING,
            event = LogEvent.EMBEDDING_BATCH_COMPLETE.key,
            backend = backend,
            durationMs = durationMs,
            extraJson = logExtraJson {
                put("modelId", modelId)
                put("batchSize", batchSize)
                put("vectorCount", vectorCount)
            },
        ))
    }


    fun logBackendDecision(
        featureName: String,
        backend: String,
        reason: String,
        attempted: List<Pair<String, String>>,
    ) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.BACKEND_DECISION.key,
            backend = backend,
            extraJson = buildJsonObject {
                put("feature", JsonPrimitive(featureName))
                put("reason", JsonPrimitive(reason))
                put("attempted", kotlinx.serialization.json.buildJsonArray {
                    attempted.forEach { (candidate, candidateReason) ->
                        addJsonObject {
                            put("backend", JsonPrimitive(candidate))
                            put("reason", JsonPrimitive(candidateReason))
                        }
                    }
                })
            }.toString(),
        ))
    }

    fun logEngineShutdown(backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_SHUTDOWN.key,
            backend = backend,
        ))
    }

    /**
     * Log a process-restart event triggered by
     * [com.adsamcik.mindlayer.service.engine.EngineManager.shutdownAndRestart].
     *
     * Distinct from [logEngineShutdown] because the latter persists "engine
     * `close()`'d in-process", while this event documents "process killed
     * and re-spawned to obtain a fresh native engine (LiteRT-LM #2028
     * workaround)". Both write to [LogCategory.ENGINE] so the dashboard's
     * engine timeline renders them inline.
     *
     * `reason` and `targetBackend` are short opaque labels the caller
     * provides verbatim. The caller is responsible for redaction; this
     * method does not inspect either string.
     *
     * **F-006 invariant:** only enum-shaped labels are persisted (the
     * reason names are a closed set produced by `MindlayerMlService` /
     * `EngineManager`), and there is no exception message in the row.
     */
    fun logEngineRestart(
        reason: String,
        targetBackend: String?,
        currentBackend: String,
        attemptCount: Int,
    ) {
        val extraJson = buildString {
            append('{')
            append("\"reason\":\"")
            append(reason)
            append("\",")
            append("\"attemptCount\":")
            append(attemptCount)
            if (targetBackend != null) {
                append(",\"targetBackend\":\"")
                append(targetBackend)
                append('"')
            }
            append('}')
        }
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_RESTART.key,
            backend = currentBackend,
            extraJson = extraJson,
        ))
    }

    /**
     * F-077: persist a categorised init-failure row so the dashboard can
     * render variant-specific remediation copy.
     *
     * The variant *name* (`LowMemory`, `BackendUnavailable`,
     * `ModelMissing`, `IntegrityMismatch`, `NativeError`) goes into
     * `extraJson.failureCategory`. For variants that carry detail:
     *  - [com.adsamcik.mindlayer.service.engine.InitFailure.BackendUnavailable]
     *    populates `backend` *and* `errorMessage` (the safeLabel).
     *  - [com.adsamcik.mindlayer.service.engine.InitFailure.NativeError]
     *    populates `errorMessage` only (no backend context).
     *
     * **F-006 invariant:** the only string fields written here are the
     * variant-name enum value (a fixed Kotlin literal) and the
     * pre-sanitised safeLabel from the engine-side caller. No raw
     * exception messages are persisted — the upstream
     * [com.adsamcik.mindlayer.service.engine.EngineManager.recordInitFailure]
     * pipeline only forwards safeLabel results from
     * [com.adsamcik.mindlayer.service.logging.safeLabel], so prompt
     * fragments embedded in native LiteRT-LM exceptions cannot reach
     * this row.
     */
    fun logInitFailureCategorized(failure: com.adsamcik.mindlayer.service.engine.InitFailure) {
        val (categoryName, backend, label) = when (failure) {
            com.adsamcik.mindlayer.service.engine.InitFailure.LowMemory ->
                Triple("LowMemory", null, null)
            is com.adsamcik.mindlayer.service.engine.InitFailure.BackendUnavailable ->
                Triple("BackendUnavailable", failure.backend, failure.safeLabel)
            com.adsamcik.mindlayer.service.engine.InitFailure.ModelMissing ->
                Triple("ModelMissing", null, null)
            com.adsamcik.mindlayer.service.engine.InitFailure.IntegrityMismatch ->
                Triple("IntegrityMismatch", null, null)
            is com.adsamcik.mindlayer.service.engine.InitFailure.NativeError ->
                Triple("NativeError", null, failure.safeLabel)
        }
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            backend = backend,
            errorMessage = label,
            extraJson = buildJsonObject {
                put("failureCategory", JsonPrimitive(categoryName))
            }.toString(),
        ))
    }

    /**
     * Log a security decision (approve / deny / revoke). Persists
     * package + signing-cert SHA prefix so an audit trail exists for the
     * dashboard, addressing SECURITY_REVIEW F-056.
     */
    fun logSecurityDecision(
        action: String,
        packageName: String,
        sigShaPrefix: String,
        extra: String? = null,
    ) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.SECURITY_DECISION.key,
            extraJson = buildJsonObject {
                put("action", JsonPrimitive(action))
                put("pkg", JsonPrimitive(packageName))
                put("sigPrefix", JsonPrimitive(sigShaPrefix))
                extra?.let { put("note", JsonPrimitive(it)) }
            }.toString(),
        ))
    }

    /**
     * Log a user-message event. Persists metadata ONLY — never the user's
     * prompt text. This class of content is private to the caller app and
     * must not end up in the service's Room database.
     */
    fun logUserMessage(requestId: String, sessionId: String, tokenCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.USER_MESSAGE.key,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson { put("tokenCount", tokenCount) },
        ))
    }

    /**
     * Log a model-response event. Persists metadata ONLY — never the model
     * output text. Model output can echo or paraphrase the prompt and is
     * treated as equally sensitive.
     */
    fun logModelResponse(requestId: String, sessionId: String, tokenCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.MODEL_RESPONSE.key,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson { put("tokenCount", tokenCount) },
        ))
    }


    fun logRequestCancel(requestId: String, sessionId: String?) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_CANCEL.key,
            requestId = requestId,
            sessionId = sessionId,
        ))
    }

    fun logRateLimitReject(method: String, uid: Int, cost: Double, requestId: String? = null, sessionId: String? = null) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.RATE_LIMIT_REJECT.key,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson {
                put("method", method)
                put("uid", uid)
                put("cost", cost)
            },
        ))
    }

    fun logFgsPromoted(activeInferenceCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.FGS_PROMOTED.key,
            extraJson = logExtraJson { put("activeInferenceCount", activeInferenceCount) },
        ))
    }

    fun logFgsDemoted(activeInferenceCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.FGS_DEMOTED.key,
            extraJson = logExtraJson { put("activeInferenceCount", activeInferenceCount) },
        ))
    }

    fun logBackendSwitch(fromBackend: String, toBackend: String, outcome: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.BACKEND_SWITCH.key,
            backend = toBackend,
            extraJson = logExtraJson {
                put("from", fromBackend)
                put("to", toBackend)
                put("outcome", outcome)
            },
        ))
    }

    fun logBinderDeathClient(uid: Int, registrationId: String? = null) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.BINDER_DEATH_CLIENT.key,
            extraJson = logExtraJson {
                put("uid", uid)
                registrationId?.let { put("registrationId", it) }
            },
        ))
    }

    fun logBinderDeathSelf(uid: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.BINDER_DEATH_SELF.key,
            extraJson = logExtraJson { put("uid", uid) },
        ))
    }

    fun logCrashLoopThrottle(uid: Int, cooldownEndsAtMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.CRASH_LOOP_THROTTLE.key,
            extraJson = logExtraJson {
                put("uid", uid)
                put("cooldownEndsAtMs", cooldownEndsAtMs)
            },
        ))
    }

    fun logStreamFrameTooLarge(frameBytes: Int, maxFrameBytes: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.STREAM_FRAME_TOO_LARGE.key,
            extraJson = logExtraJson {
                put("frameBytes", frameBytes)
                put("maxFrameBytes", maxFrameBytes)
            },
        ))
    }

    fun logStreamBackpressure(timeoutMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.STREAM_BACKPRESSURE.key,
            durationMs = timeoutMs,
        ))
    }

    fun logSessionQuotaExceeded(sessionId: String?, ownerUid: Int?, ownedNow: Int, cap: Int, tierMaxSessions: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_QUOTA_EXCEEDED.key,
            sessionId = sessionId,
            extraJson = logExtraJson {
                ownerUid?.let { put("uid", it) }
                put("ownedNow", ownedNow)
                put("cap", cap)
                put("tierMaxSessions", tierMaxSessions)
            },
        ))
    }

    fun logToolCallExit(requestId: String, sessionId: String?, result: String, pendingCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.TOOL_CALL_EXIT.key,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson {
                put("result", result)
                put("pendingCount", pendingCount)
            },
        ))
    }

    fun logToolCallTimeout(requestId: String, sessionId: String?, timeoutMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.TOOL_CALL_TIMEOUT.key,
            requestId = requestId,
            sessionId = sessionId,
            durationMs = timeoutMs,
        ))
    }

    suspend fun recentErrorCount(windowMs: Long = 60_000L): Int =
        dao.recentErrorCountSince(System.currentTimeMillis() - windowMs.coerceAtLeast(0L))

    suspend fun latestThroughput(): Pair<Float, Float>? =
        dao.latestThroughput()?.let { entry ->
            (entry.prefillTokensPerSec ?: 0f) to (entry.tokensPerSec ?: 0f)
        }

    // Cleanup logs older than N days
    suspend fun cleanup(retentionDays: Int = 7) {
        val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }

    fun shutdown() {
        queue.close()
        scope.cancel()
    }
}
