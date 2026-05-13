package com.adsamcik.mindlayer.service.logging

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

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
    fun logInferenceStart(requestId: String, sessionId: String, backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_START,
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
            event = LogEvent.REQUEST_COMPLETE,
            requestId = requestId, sessionId = sessionId, backend = backend,
            durationMs = durationMs, tokensGenerated = tokensGenerated,
            tokensPerSec = tokensPerSec, prefillTokensPerSec = prefillTps,
        ))
    }

    fun logInferenceError(requestId: String, sessionId: String?, errorMessage: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ERROR,
            event = LogEvent.REQUEST_ERROR,
            requestId = requestId, sessionId = sessionId,
            errorMessage = sanitizeErrorClass(errorMessage),
        ))
    }

    fun logThermalBandChange(fromBand: String, toBand: String, backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.THERMAL,
            event = LogEvent.BAND_CHANGE,
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
            event = LogEvent.SESSION_CREATED,
            sessionId = sessionId, backend = backend,
            extraJson = logExtraJson { put("maxTokens", maxTokens) },
        ))
    }

    fun logSessionDestroyed(sessionId: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_DESTROYED,
            sessionId = sessionId,
        ))
    }

    fun logSessionEvicted(sessionId: String, reason: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_EVICTED,
            sessionId = sessionId,
            extraJson = logExtraJson { put("reason", reason) },
        ))
    }

    fun logMemoryPressure(pressure: String, availableMb: Long, totalMb: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.MEMORY,
            event = LogEvent.PRESSURE_CHANGE,
            memoryAvailableMb = availableMb,
            memoryUsedMb = totalMb - availableMb,
            extraJson = logExtraJson { put("pressure", pressure) },
        ))
    }

    fun logEngineInit(backend: String, durationMs: Long, modelPath: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT,
            backend = backend, durationMs = durationMs,
            extraJson = logExtraJson { put("modelPath", modelPath) },
        ))
    }

    fun logEngineShutdown(backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_SHUTDOWN,
            backend = backend,
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
            event = LogEvent.INIT_FAILURE_CATEGORIZED,
            backend = backend,
            errorMessage = label,
            extraJson = buildJsonObject {
                put("failureCategory", JsonPrimitive(categoryName))
            }.toString(),
        ))
    }

    /**
     * Log a security decision (approve / deny / revoke / pending). Persists
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
            event = LogEvent.SECURITY_DECISION,
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
            event = LogEvent.USER_MESSAGE,
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
            event = LogEvent.MODEL_RESPONSE,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson { put("tokenCount", tokenCount) },
        ))
    }


    fun logRequestCancel(requestId: String, sessionId: String?) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_CANCEL,
            requestId = requestId,
            sessionId = sessionId,
        ))
    }

    fun logRateLimitReject(method: String, uid: Int, cost: Double, requestId: String? = null, sessionId: String? = null) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.RATE_LIMIT_REJECT,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson {
                put("method", method)
                put("uid", uid)
                put("cost", cost)
            },
        ))
    }

    fun logAllowlistPendingRecorded(uid: Int, packageName: String, sigShaPrefix: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.ALLOWLIST_PENDING_RECORDED,
            extraJson = logExtraJson {
                put("uid", uid)
                put("pkg", packageName)
                put("sigPrefix", sigShaPrefix)
            },
        ))
    }

    fun logFgsPromoted(activeInferenceCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.FGS_PROMOTED,
            extraJson = logExtraJson { put("activeInferenceCount", activeInferenceCount) },
        ))
    }

    fun logFgsDemoted(activeInferenceCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.FGS_DEMOTED,
            extraJson = logExtraJson { put("activeInferenceCount", activeInferenceCount) },
        ))
    }

    fun logBackendSwitch(fromBackend: String, toBackend: String, outcome: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.BACKEND_SWITCH,
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
            event = LogEvent.BINDER_DEATH_CLIENT,
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
            event = LogEvent.BINDER_DEATH_SELF,
            extraJson = logExtraJson { put("uid", uid) },
        ))
    }

    fun logCrashLoopThrottle(uid: Int, cooldownEndsAtMs: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SECURITY,
            event = LogEvent.CRASH_LOOP_THROTTLE,
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
            event = LogEvent.STREAM_FRAME_TOO_LARGE,
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
            event = LogEvent.STREAM_BACKPRESSURE,
            durationMs = timeoutMs,
        ))
    }

    fun logSessionQuotaExceeded(sessionId: String?, ownerUid: Int?, ownedNow: Int, cap: Int, tierMaxSessions: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_QUOTA_EXCEEDED,
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
            event = LogEvent.TOOL_CALL_EXIT,
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
            event = LogEvent.TOOL_CALL_TIMEOUT,
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
