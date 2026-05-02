package com.adsamcik.mindlayer.service.logging

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LogRepository(private val dao: LogDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Fire-and-forget log (non-blocking)
    fun log(entry: LogEntry) {
        scope.launch { dao.insert(entry) }
    }

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
            errorMessage = errorMessage,
        ))
    }

    fun logThermalBandChange(fromBand: String, toBand: String, backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.THERMAL,
            event = LogEvent.BAND_CHANGE,
            thermalBand = toBand, backend = backend,
            extraJson = buildJsonObject {
                put("from", JsonPrimitive(fromBand))
                put("to", JsonPrimitive(toBand))
            }.toString(),
        ))
    }

    fun logSessionCreated(sessionId: String, backend: String, maxTokens: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_CREATED,
            sessionId = sessionId, backend = backend,
            extraJson = buildJsonObject { put("maxTokens", maxTokens) }.toString(),
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
            extraJson = buildJsonObject { put("reason", JsonPrimitive(reason)) }.toString(),
        ))
    }

    fun logMemoryPressure(pressure: String, availableMb: Long, totalMb: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.MEMORY,
            event = LogEvent.PRESSURE_CHANGE,
            memoryAvailableMb = availableMb,
            memoryUsedMb = totalMb - availableMb,
            extraJson = buildJsonObject { put("pressure", JsonPrimitive(pressure)) }.toString(),
        ))
    }

    fun logEngineInit(backend: String, durationMs: Long, modelPath: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT,
            backend = backend, durationMs = durationMs,
            extraJson = buildJsonObject { put("modelPath", JsonPrimitive(modelPath)) }.toString(),
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
            extraJson = buildJsonObject { put("tokenCount", tokenCount) }.toString(),
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
            extraJson = buildJsonObject { put("tokenCount", tokenCount) }.toString(),
        ))
    }

    // Cleanup logs older than N days
    suspend fun cleanup(retentionDays: Int = 7) {
        val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }

    fun shutdown() { scope.cancel() }
}
