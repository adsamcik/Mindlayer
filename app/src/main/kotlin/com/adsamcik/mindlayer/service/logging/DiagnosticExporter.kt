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

        val ownedSessionIds: Set<String>? = if (scopeUid != null) {
            sessionManager.listSessionsOwnedBy(scopeUid).map { it.sessionId }.toSet()
        } else null

        return buildJsonObject {
            put("timestamp", now)
            put("version", "0.1.0")
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
