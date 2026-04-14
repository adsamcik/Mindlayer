package com.mindlayer.service

import android.os.ParcelFileDescriptor
import com.mindlayer.service.logging.MindlayerLog
import com.mindlayer.AudioTransfer
import com.mindlayer.EngineInfo
import com.mindlayer.IMindlayerService
import com.mindlayer.ImageTransfer
import com.mindlayer.RequestMeta
import com.mindlayer.ServiceStatus
import com.mindlayer.SessionConfig
import com.mindlayer.SessionInfo
import com.mindlayer.ToolResult
import com.mindlayer.service.engine.EngineManager
import com.mindlayer.service.engine.InferenceOrchestrator
import com.mindlayer.service.engine.MemoryBudget
import com.mindlayer.service.engine.ThermalMonitor
import com.mindlayer.service.logging.DiagnosticExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * AIDL binder implementation. Delegates inference to [InferenceOrchestrator]
 * and engine/status queries to [EngineManager] and [MindlayerMlService].
 */
class ServiceBinder(
    private val service: MindlayerMlService,
    val engineManager: EngineManager,
    val orchestrator: InferenceOrchestrator,
    private val diagnosticExporter: DiagnosticExporter,
    private val thermalMonitor: ThermalMonitor,
    private val memoryBudget: MemoryBudget,
) : IMindlayerService.Stub() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "ServiceBinder"
    }

    // ---- Session management ------------------------------------------------

    override fun createSession(config: SessionConfig): String {
        MindlayerLog.d(TAG, "createSession")
        return orchestrator.createSession(config)
    }

    override fun destroySession(sessionId: String) {
        MindlayerLog.d(TAG, "destroySession: $sessionId", sessionId = sessionId)
        orchestrator.destroySession(sessionId)
    }

    override fun getSessionInfo(sessionId: String): SessionInfo? {
        return orchestrator.getSessionInfo(sessionId)
    }

    override fun listSessions(): List<SessionInfo> {
        return orchestrator.listSessions()
    }

    // ---- Inference ---------------------------------------------------------

    override fun infer(
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        eventWriteEnd: ParcelFileDescriptor,
    ) {
        MindlayerLog.d(TAG, "infer: requestId=${meta.requestId}, session=${meta.sessionId}", requestId = meta.requestId, sessionId = meta.sessionId)
        orchestrator.infer(meta, image, audio, eventWriteEnd)
    }

    override fun cancelInference(requestId: String) {
        MindlayerLog.d(TAG, "cancelInference: $requestId", requestId = requestId)
        orchestrator.cancelInference(requestId)
    }

    // ---- Tool results -------------------------------------------------------

    override fun submitToolResult(requestId: String, result: ToolResult) {
        MindlayerLog.d(TAG, "submitToolResult: $requestId, tool=${result.toolName}", requestId = requestId)
        orchestrator.toolCallBridge.submitResult(
            requestId = requestId,
            toolName = result.toolName,
            resultJson = result.resultJson,
        )
    }

    // ---- Prewarm -----------------------------------------------------------

    override fun prewarm(backend: String?) {
        MindlayerLog.d(TAG, "prewarm: backend=${backend ?: "GPU"}")
        scope.launch {
            try {
                engineManager.initialize(
                    preferredBackend = backend ?: "GPU",
                    maxTokens = 4096,
                )
            } catch (e: Exception) {
                MindlayerLog.w(TAG, "Prewarm failed: ${e.message}")
            }
        }
    }

    // ---- Status ------------------------------------------------------------

    override fun getStatus(): ServiceStatus {
        val thermalPolicy = thermalMonitor.currentPolicy.value
        val thermalSample = thermalMonitor.latestSample.value
        val memSnapshot = memoryBudget.currentSnapshot()

        return ServiceStatus(
            isEngineLoaded = engineManager.isInitialized,
            activeSessionCount = orchestrator.listSessions().size,
            activeInferenceCount = service.activeInferenceCount,
            backend = engineManager.currentBackend,
            thermalBand = thermalPolicy.band.name,
            isForeground = service.activeInferenceCount > 0,
            uptimeMs = android.os.SystemClock.elapsedRealtime() - service.createdAtMs,
            memoryPressure = memSnapshot.pressure.name,
            availableRamMb = memSnapshot.availableMb,
            totalRamMb = memSnapshot.totalMb,
            maxSessions = memoryBudget.deviceTier.maxSessions,
            headroom = thermalSample?.headroom10s,
        )
    }

    override fun getDiagnostics(): String {
        return runBlocking { diagnosticExporter.export() }
    }

    override fun getEngineInfo(): EngineInfo {
        val currentModel = engineManager.currentModel
        val modelPath = try {
            engineManager.modelPath
        } catch (_: Throwable) {
            ""
        }
        val modelId = currentModel?.id ?: modelPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".litertlm")
        val modelSize = currentModel?.sizeBytes ?: if (modelPath.isNotEmpty()) {
            try {
                File(modelPath).length()
            } catch (_: Throwable) {
                0L
            }
        } else 0L

        return EngineInfo(
            modelId = modelId,
            modelSizeBytes = modelSize,
            backend = engineManager.currentBackend,
            maxTokens = 4096,
            initTimeSeconds = engineManager.initTimeSeconds,
            lastPrefillToksPerSec = 0f,
            lastDecodeToksPerSec = 0f,
        )
    }
}
