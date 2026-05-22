package com.adsamcik.mindlayer.service.logging

import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import kotlinx.serialization.json.*

/**
 * Collects a diagnostic snapshot of the entire service state for bug reports.
 *
 * Output is a JSON string containing:
 * - timestamp
 * - engine state (loaded, backend, init time, model path)
 * - thermal state (band, headroom, policy)
 * - memory state (pressure, available/total, device tier)
 * - active sessions (count, IDs, streaming status)
 * - recent logs (last 50)
 * - aggregate stats (total inferences, avg tps, total tokens, error count last 24h)
 *
 * **Cross-tenant scoping (F-005)**: when [export] is called with a
 * non-null `scopeUid`, the dump is restricted to that UID's own state and
 * leakage-prone fields (`modelPath`, `lastGpuFailureReason`, raw error
 * messages, other UIDs' session IDs / request IDs) are redacted. The
 * dashboard (self-UID) gets the full unfiltered dump.
 */
class DiagnosticExporter(
    private val engineManager: EngineManager,
    private val thermalMonitor: ThermalMonitor,
    private val memoryBudget: MemoryBudget,
    private val sessionManager: SessionManager,
    private val logDao: LogDao,
) {
    /**
     * Export diagnostics. When [scopeUid] is null (self-UID dashboard)
     * the full snapshot is returned. When non-null, sessions are filtered
     * by ownership, recent logs are filtered by request/session ownership,
     * and host-internal fields (`modelPath`, `lastGpuFailureReason`,
     * `errorMessage`, `deviceRamMb`) are redacted.
     */
    suspend fun export(scopeUid: Int? = null): String {
        val now = System.currentTimeMillis()

        // Pre-fetch all suspend DAO data before building JSON
        val recentLogs = logDao.getRecent(50)
        val totalInferences = logDao.totalInferenceCount()
        val avgTps = logDao.averageTokensPerSec()
        val totalTokens = logDao.totalTokensGenerated()
        val avgDuration = logDao.averageInferenceDurationMs()
        val errorsLast24h = logDao.errorCountSince(now - 86_400_000)
        val observabilityRows = if (scopeUid == null) {
            logDao.getByEvents(OBSERVABILITY_EVENTS)
        } else {
            emptyList()
        }
        val ocrDiagnostics = buildOcrDiagnostics(observabilityRows)
        val embeddingDiagnostics = buildEmbeddingDiagnostics(observabilityRows)
        val acceleratorDecisions = buildAcceleratorDecisions(observabilityRows)
        val deferredCounters = buildDeferredCounters(observabilityRows)

        val ownedSessionIds: Set<String>? = if (scopeUid != null) {
            sessionManager.listSessionsOwnedBy(scopeUid).map { it.sessionId }.toSet()
        } else null

        return buildJsonObject {
            put("timestamp", now)
            put("version", "0.1.0")
            put("schemaVersion", 2)
            put("scoped", scopeUid != null)

            // Engine
            putJsonObject("engine") {
                put("loaded", engineManager.isInitialized)
                put("backend", engineManager.currentBackend)
                put("initTimeSeconds", engineManager.initTimeSeconds)
                if (scopeUid == null) {
                    // Host-only: GPU failure reason can echo backend
                    // exception text and reveal install paths. modelPath
                    // (even redacted to filename) is host-internal — F-005
                    // requires it to stay scoped to the dashboard.
                    put("lastGpuFailureReason", engineManager.lastGpuFailureReason)
                    val loadedModelPath = engineManager.currentModel?.path
                    if (loadedModelPath != null) {
                        put("modelPath", loadedModelPath.redactedFileName())
                    }
                }
            }

            // Thermal (host-only details)
            putJsonObject("thermal") {
                val policy = thermalMonitor.currentPolicy.value
                put("band", policy.band.name)
                put("recommendedBackend", policy.recommendedBackend)
                if (scopeUid == null) {
                    put("burstSeconds", policy.burstSeconds)
                    put("restSeconds", policy.restSeconds)
                    put("chunkTokens", policy.chunkTokens)
                    thermalMonitor.latestSample.value?.let { sample ->
                        put("status", sample.status)
                        sample.headroom10s?.let { put("headroom10s", it) }
                        sample.headroomNow?.let { put("headroomNow", it) }
                    }
                    put("canReenableGpu", thermalMonitor.canReenableGpu())
                }
            }

            // Memory (host-only)
            putJsonObject("memory") {
                val snapshot = memoryBudget.currentSnapshot()
                put("pressure", snapshot.pressure.name)
                if (scopeUid == null) {
                    put("availableMb", snapshot.availableMb)
                    put("totalMb", snapshot.totalMb)
                    put("lowMemory", snapshot.lowMemory)
                    put("recommendedMaxTokens", snapshot.recommendedMaxTokens)
                    val tier = memoryBudget.deviceTier
                    putJsonObject("deviceTier") {
                        put("maxSessions", tier.maxSessions)
                        put("defaultMaxTokens", tier.defaultMaxTokens)
                        put("deviceRamMb", tier.deviceRamMb)
                    }
                }
            }

            // Sessions — owner-scoped for external callers.
            putJsonObject("sessions") {
                val sessions = if (scopeUid != null) {
                    sessionManager.listSessionsOwnedBy(scopeUid)
                } else {
                    sessionManager.listSessions()
                }
                put("activeCount", sessions.size)
                putJsonArray("list") {
                    for (s in sessions) {
                        addJsonObject {
                            put("sessionId", s.sessionId)
                            put("backend", s.backend)
                            put("maxTokens", s.maxTokens)
                            put("currentTokenCount", s.currentTokenCount)
                            put("turnCount", s.turnCount)
                            put("isStreaming", s.isStreaming)
                            put("lastAccessedAtMs", s.lastAccessedAtMs)
                        }
                    }
                }
            }

            // Recent logs — filtered by ownership for external callers.
            putJsonArray("recentLogs") {
                for (entry in recentLogs) {
                    if (scopeUid != null) {
                        // Only include the entry if it is plainly tied to a
                        // session we own; entries with no session/request ids
                        // (e.g. global engine events) are not exposed.
                        val sid = entry.sessionId ?: continue
                        if (sid !in (ownedSessionIds ?: emptySet())) continue
                    }
                    addJsonObject {
                        put("timestamp", entry.timestampMs)
                        put("category", entry.category)
                        put("event", entry.event)
                        entry.sessionId?.let { put("sessionId", it) }
                        entry.requestId?.let { put("requestId", it) }
                        entry.backend?.let { put("backend", it) }
                        entry.durationMs?.let { put("durationMs", it) }
                        entry.tokensGenerated?.let { put("tokensGenerated", it) }
                        entry.tokensPerSec?.let { put("tokensPerSec", it) }
                        entry.thermalBand?.let { put("thermalBand", it) }
                        entry.errorMessage?.let { msg ->
                            // Privacy invariant (HARD-RULE): raw errorMessage
                            // can echo prompt fragments from native LiteRT-LM
                            // exceptions and MUST NEVER appear in a diagnostic
                            // export — not even on the self-UID dashboard,
                            // because exports are user-shareable bug reports.
                            // Always sanitize via errorClass and cap at 256.
                            sanitizeErrorClass(msg)?.take(256)?.let { safe ->
                                put("errorClass", safe)
                            }
                        }
                    }
                }
            }

            // Aggregate stats (host-only — global counters across all UIDs)
            if (scopeUid == null) {
                putOcrDiagnostics(ocrDiagnostics)
                putEmbeddingDiagnostics(embeddingDiagnostics)
                putAcceleratorDecisions(acceleratorDecisions)
                putDeferredCounters(deferredCounters)
                putJsonObject("stats") {
                    put("totalInferences", totalInferences)
                    avgTps?.let { put("avgTokensPerSec", it) }
                    totalTokens?.let { put("totalTokensGenerated", it) }
                    avgDuration?.let { put("avgInferenceDurationMs", it) }
                    put("errorsLast24h", errorsLast24h)
                }
            }

            // Uptime (always safe)
            put("uptimeMs", android.os.SystemClock.elapsedRealtime())
        }.toString()
    }
}

private val OBSERVABILITY_EVENTS = listOf(
    LogEvent.OCR_BACKEND_READY.key,
    LogEvent.OCR_BACKEND_SHUTDOWN.key,
    LogEvent.OCR_FRAME_PROCESSED.key,
    LogEvent.OCR_FRAME_REJECTED.key,
    LogEvent.OCR_SESSION_FINALIZED.key,
    LogEvent.EMBEDDING_BACKEND_READY.key,
    LogEvent.EMBEDDING_BACKEND_SHUTDOWN.key,
    LogEvent.EMBEDDING_BATCH_COMPLETE.key,
    LogEvent.BACKEND_DECISION.key,
    LogEvent.DEFERRED_SUBMIT.key,
    LogEvent.DEFERRED_COMPLETE.key,
    LogEvent.DEFERRED_FETCH.key,
    LogEvent.DEFERRED_CANCEL.key,
    LogEvent.DEFERRED_EXPIRED.key,
)

private data class OcrDiagnosticsJson(
    val currentBackend: String?,
    val framesProcessed: Int,
    val framesRejected: Int,
    val framesDropped: Int,
    val sessionsFinalized: Int,
    val lastFinalizedAt: Long?,
    val lastBackendReadyAt: Long?,
)

private data class EmbeddingDiagnosticsJson(
    val modelLoaded: Boolean,
    val currentBackend: String?,
    val modelId: String?,
    val batchesProcessed: Int,
    val vectorsGenerated: Int,
    val lastBackendReadyAt: Long?,
)

private data class AcceleratorDecisionJson(
    val feature: String,
    val backend: String,
    val reason: String,
    val attempted: List<Pair<String, String>>,
    val timestampMs: Long,
)

private data class DeferredCountersJson(
    val submitted: Int = 0,
    val completed: Int = 0,
    val fetched: Int = 0,
    val cancelled: Int = 0,
    val expired: Int = 0,
) {
    fun increment(event: String): DeferredCountersJson = when (event) {
        LogEvent.DEFERRED_SUBMIT.key -> copy(submitted = submitted + 1)
        LogEvent.DEFERRED_COMPLETE.key -> copy(completed = completed + 1)
        LogEvent.DEFERRED_FETCH.key -> copy(fetched = fetched + 1)
        LogEvent.DEFERRED_CANCEL.key -> copy(cancelled = cancelled + 1)
        LogEvent.DEFERRED_EXPIRED.key -> copy(expired = expired + 1)
        else -> this
    }
}

private fun JsonObjectBuilder.putOcrDiagnostics(ocr: OcrDiagnosticsJson) {
    putJsonObject("ocr") {
        ocr.currentBackend?.let { put("currentBackend", it) }
        put("framesProcessed", ocr.framesProcessed)
        put("framesRejected", ocr.framesRejected)
        put("framesDropped", ocr.framesDropped)
        put("sessionsFinalized", ocr.sessionsFinalized)
        ocr.lastFinalizedAt?.let { put("lastFinalizedAt", it) }
        ocr.lastBackendReadyAt?.let { put("lastBackendReadyAt", it) }
    }
}

private fun JsonObjectBuilder.putEmbeddingDiagnostics(embedding: EmbeddingDiagnosticsJson) {
    putJsonObject("embedding") {
        put("modelLoaded", embedding.modelLoaded)
        embedding.currentBackend?.let { put("currentBackend", it) }
        embedding.modelId?.let { put("modelId", it) }
        put("batchesProcessed", embedding.batchesProcessed)
        put("vectorsGenerated", embedding.vectorsGenerated)
        embedding.lastBackendReadyAt?.let { put("lastBackendReadyAt", it) }
    }
}

private fun JsonObjectBuilder.putAcceleratorDecisions(decisions: Map<String, AcceleratorDecisionJson>) {
    putJsonObject("acceleratorDecisions") {
        decisions.toSortedMap().forEach { (feature, decision) ->
            putJsonObject(feature) {
                put("feature", decision.feature)
                put("backend", decision.backend)
                put("reason", decision.reason)
                put("timestampMs", decision.timestampMs)
                putJsonArray("attempted") {
                    decision.attempted.forEach { (backend, reason) ->
                        addJsonObject {
                            put("backend", backend)
                            put("reason", reason)
                        }
                    }
                }
            }
        }
    }
}

private fun JsonObjectBuilder.putDeferredCounters(counters: Map<String, DeferredCountersJson>) {
    putJsonObject("deferredCounters") {
        listOf("chat", "embedding").forEach { kind ->
            val counter = counters[kind] ?: DeferredCountersJson()
            putJsonObject(kind) {
                put("submitted", counter.submitted)
                put("completed", counter.completed)
                put("fetched", counter.fetched)
                put("cancelled", counter.cancelled)
                put("expired", counter.expired)
            }
        }
    }
}

private fun buildOcrDiagnostics(rows: List<LogEntry>): OcrDiagnosticsJson {
    val latestReady = rows.latest(LogEvent.OCR_BACKEND_READY)
    val latestShutdown = rows.latest(LogEvent.OCR_BACKEND_SHUTDOWN)
    val latestFinalized = rows.latest(LogEvent.OCR_SESSION_FINALIZED)
    return OcrDiagnosticsJson(
        currentBackend = latestReady
            ?.takeIf { ready -> latestShutdown == null || ready.timestampMs > latestShutdown.timestampMs }
            ?.backend,
        framesProcessed = rows.count { it.event == LogEvent.OCR_FRAME_PROCESSED.key } +
            rows.sumExtraInt(LogEvent.OCR_SESSION_FINALIZED, "framesAccepted"),
        framesRejected = rows.count { it.event == LogEvent.OCR_FRAME_REJECTED.key } +
            rows.sumExtraInt(LogEvent.OCR_SESSION_FINALIZED, "framesRejected"),
        framesDropped = rows.sumExtraInt(LogEvent.OCR_SESSION_FINALIZED, "framesDropped"),
        sessionsFinalized = rows.count { it.event == LogEvent.OCR_SESSION_FINALIZED.key },
        lastFinalizedAt = latestFinalized?.timestampMs,
        lastBackendReadyAt = latestReady?.timestampMs,
    )
}

private fun buildEmbeddingDiagnostics(rows: List<LogEntry>): EmbeddingDiagnosticsJson {
    val latestReady = rows.latest(LogEvent.EMBEDDING_BACKEND_READY)
    val latestShutdown = rows.latest(LogEvent.EMBEDDING_BACKEND_SHUTDOWN)
    val batchRows = rows.filter { it.event == LogEvent.EMBEDDING_BATCH_COMPLETE.key }
    return EmbeddingDiagnosticsJson(
        modelLoaded = latestReady != null &&
            (latestShutdown == null || latestReady.timestampMs > latestShutdown.timestampMs),
        currentBackend = latestReady?.backend,
        modelId = latestReady?.extraObject()?.get("modelId")?.jsonPrimitive?.contentOrNull,
        batchesProcessed = batchRows.size,
        vectorsGenerated = batchRows.sumOf { row ->
            row.extraObject()?.get("vectorCount")?.jsonPrimitive?.intOrNull ?: 0
        },
        lastBackendReadyAt = latestReady?.timestampMs,
    )
}

private fun buildAcceleratorDecisions(rows: List<LogEntry>): Map<String, AcceleratorDecisionJson> =
    rows.asSequence()
        .filter { it.event == LogEvent.BACKEND_DECISION.key }
        .mapNotNull { row ->
            val root = row.extraObject() ?: return@mapNotNull null
            val feature = root["feature"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val reason = root["reason"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val attempted = root["attempted"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element.jsonObject
                val backend = obj["backend"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val candidateReason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                backend to candidateReason
            }
            AcceleratorDecisionJson(
                feature = feature,
                backend = row.backend.orEmpty(),
                reason = reason,
                attempted = attempted,
                timestampMs = row.timestampMs,
            )
        }
        .groupBy { it.feature }
        .mapValues { (_, decisions) -> decisions.maxBy { it.timestampMs } }

private fun buildDeferredCounters(rows: List<LogEntry>): Map<String, DeferredCountersJson> {
    val counters = mutableMapOf(
        "chat" to DeferredCountersJson(),
        "embedding" to DeferredCountersJson(),
    )
    rows.filter { it.event in DEFERRED_EVENTS }.forEach { row ->
        val kind = row.extraObject()
            ?.get("kind")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it == "chat" || it == "embedding" }
            ?: if (row.requestId?.startsWith("emb-") == true) "embedding" else "chat"
        counters[kind] = (counters[kind] ?: DeferredCountersJson()).increment(row.event)
    }
    return counters
}

private val DEFERRED_EVENTS = setOf(
    LogEvent.DEFERRED_SUBMIT.key,
    LogEvent.DEFERRED_COMPLETE.key,
    LogEvent.DEFERRED_FETCH.key,
    LogEvent.DEFERRED_CANCEL.key,
    LogEvent.DEFERRED_EXPIRED.key,
)

private fun List<LogEntry>.latest(event: LogEvent): LogEntry? =
    filter { it.event == event.key }.maxByOrNull { it.timestampMs }

private fun List<LogEntry>.sumExtraInt(event: LogEvent, key: String): Int =
    filter { it.event == event.key }.sumOf { row ->
        row.extraObject()?.get(key)?.jsonPrimitive?.intOrNull ?: 0
    }

private fun LogEntry.extraObject(): JsonObject? =
    extraJson?.takeIf { it.isNotBlank() }?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
    }
