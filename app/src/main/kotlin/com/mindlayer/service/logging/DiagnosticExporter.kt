package com.mindlayer.service.logging

import com.mindlayer.service.engine.EngineManager
import com.mindlayer.service.engine.MemoryBudget
import com.mindlayer.service.engine.SessionManager
import com.mindlayer.service.engine.ThermalMonitor
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
 */
class DiagnosticExporter(
    private val engineManager: EngineManager,
    private val thermalMonitor: ThermalMonitor,
    private val memoryBudget: MemoryBudget,
    private val sessionManager: SessionManager,
    private val logDao: LogDao,
) {
    suspend fun export(): String {
        val now = System.currentTimeMillis()

        // Pre-fetch all suspend DAO data before building JSON
        val recentLogs = logDao.getRecent(50)
        val totalInferences = logDao.totalInferenceCount()
        val avgTps = logDao.averageTokensPerSec()
        val totalTokens = logDao.totalTokensGenerated()
        val avgDuration = logDao.averageInferenceDurationMs()
        val errorsLast24h = logDao.errorCountSince(now - 86_400_000)

        return buildJsonObject {
            put("timestamp", now)
            put("version", "0.1.0")

            // Engine
            putJsonObject("engine") {
                put("loaded", engineManager.isInitialized)
                put("backend", engineManager.currentBackend)
                put("initTimeSeconds", engineManager.initTimeSeconds)
                try { put("modelPath", engineManager.modelPath) } catch (_: Exception) {}
            }

            // Thermal
            putJsonObject("thermal") {
                val policy = thermalMonitor.currentPolicy.value
                put("band", policy.band.name)
                put("recommendedBackend", policy.recommendedBackend)
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

            // Memory
            putJsonObject("memory") {
                val snapshot = memoryBudget.currentSnapshot()
                put("pressure", snapshot.pressure.name)
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

            // Sessions
            putJsonObject("sessions") {
                val sessions = sessionManager.listSessions()
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

            // Recent logs
            putJsonArray("recentLogs") {
                for (entry in recentLogs) {
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
                        entry.errorMessage?.let { put("errorMessage", it) }
                    }
                }
            }

            // Aggregate stats
            putJsonObject("stats") {
                put("totalInferences", totalInferences)
                avgTps?.let { put("avgTokensPerSec", it) }
                totalTokens?.let { put("totalTokensGenerated", it) }
                avgDuration?.let { put("avgInferenceDurationMs", it) }
                put("errorsLast24h", errorsLast24h)
            }

            // Uptime
            put("uptimeMs", android.os.SystemClock.elapsedRealtime())
        }.toString()
    }
}
