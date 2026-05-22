package com.adsamcik.mindlayer.service.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.EngineInfo
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.service.health.MlHealthRecorder
import com.adsamcik.mindlayer.service.logging.LogDao
import com.adsamcik.mindlayer.service.logging.LogDatabase
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.util.UUID

/**
 * Connects to [com.adsamcik.mindlayer.service.MindlayerMlService] via AIDL (cross-process :ml)
 * and reads the Room log database directly (file-based, accessible from main process).
 */
class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var logDao: LogDao? = null
    private var mlHealthRecorder: MlHealthRecorder? = null
    private var service: com.adsamcik.mindlayer.IMindlayerService? = null
    private var bound = false
    private var statusPollingJob: Job? = null
    private var logPollingJob: Job? = null

    /**
     * Stable liveness token passed to `registerClient` so the service's death
     * recipient can tear down dashboard-owned sessions if this process dies.
     * Must be held for the whole lifetime of the connection.
     */
    private val livenessToken = android.os.Binder()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = com.adsamcik.mindlayer.IMindlayerService.Stub.asInterface(binder)
            service = svc
            // Dashboard shares this UID with the service — authorizeCall()
            // self-UID-bypasses, so this call always succeeds.
            viewModelScope.launch(Dispatchers.IO) {
                try { svc.registerClient(livenessToken) } catch (_: Throwable) { }
            }
            _uiState.update {
                it.copy(
                    connectionState = DashboardConnectionState.CONNECTED,
                    isStatusLoading = true,
                    statusErrorMessage = null,
                )
            }
            startPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _uiState.update { current ->
                current.copy(
                    connectionState = DashboardConnectionState.DISCONNECTED,
                    isStatusLoading = false,
                    statusErrorMessage = current.lastStatusUpdateMs?.let {
                        "Binder connection lost. Last good status sample ${formatRelativeTimestamp(it)}."
                    } ?: "Binder connection lost before a status sample was received.",
                )
            }
        }
    }

    fun bindService(context: Context) {
        if (bound) return
        logDao = LogDatabase.getInstance(context).logDao()
        // F-074: the dashboard reads the watchdog state directly from the
        // shared file in `filesDir/ml_health/` — no AIDL hop required.
        // The service writes from `:ml`, atomic-rename means we never see
        // a torn JSON, and self-UID AIDL would be throttled by design.
        mlHealthRecorder = MlHealthRecorder(context)

        _uiState.update {
            it.copy(
                connectionState = DashboardConnectionState.CONNECTING,
                isStatusLoading = true,
                isLogsLoading = true,
                statusErrorMessage = null,
                logsErrorMessage = null,
            )
        }

        val intent = Intent().apply {
            component = ComponentName(
                context.packageName,
                "com.adsamcik.mindlayer.service.MindlayerMlService"
            )
        }

        val didBind = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = didBind
        if (!didBind) {
            _uiState.update {
                it.copy(
                    connectionState = DashboardConnectionState.DISCONNECTED,
                    isStatusLoading = false,
                    isLogsLoading = false,
                    statusErrorMessage = "Dashboard service bind failed.",
                    logsErrorMessage = "Log polling was not started because the service bind failed.",
                )
            }
            return
        }

        startLogPolling()
    }

    fun unbindService(context: Context) {
        if (!bound) return
        bound = false
        statusPollingJob?.cancel()
        logPollingJob?.cancel()
        context.unbindService(connection)
        service = null
        _uiState.update {
            it.copy(
                connectionState = DashboardConnectionState.DISCONNECTED,
                isStatusLoading = false,
                isLogsLoading = false,
            )
        }
    }

    /**
     * F-055: ask the `:ml` service (over self-UID AIDL) to revoke a caller
     * package. The service:
     *   1. Removes the entry from `entries.json` under the file lock.
     *   2. Tears down any sessions currently owned by the revoked UID
     *      (in-flight inferences are killed cleanly).
     *   3. Logs a SECURITY_DECISION audit row.
     *
     * The dashboard's own `AllowlistStore` picks up the change on its next
     * `refresh()` cycle (it polls every 2 s) since the file is shared between
     * the main process and `:ml`.
     */
    fun revokeApp(packageName: String) {
        val svc = service ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                svc.revokeApp(packageName)
            } catch (t: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        statusErrorMessage = "Revoke failed: ${t.toDashboardMessage()}",
                    )
                }
            }
        }
    }

    private fun startPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = viewModelScope.launch {
            while (isActive && bound) {
                val svc = service
                if (svc == null) {
                    delay(2_000)
                    continue
                }

                try {
                    val (status, engineInfo, sessions, now) = withContext(Dispatchers.IO) {
                        DashboardStatusSample(
                            status = svc.status,
                            engineInfo = svc.engineInfo,
                            sessions = svc.listSessions().orEmpty(),
                            sampledAtMs = System.currentTimeMillis(),
                        )
                    }

                    // F-074: peek the watchdog file (cross-process) so the
                    // banner stays in sync with whatever `:ml` last wrote.
                    // Cheap unlocked read; atomic rename guarantees no
                    // torn JSON.
                    val healthSnapshot = mlHealthRecorder?.peek()
                    val cooldownEndsAt = mlHealthRecorder?.cooldownEndsAt() ?: 0L
                    val throttled = mlHealthRecorder?.shouldThrottleBinds() == true
                    val cooldownRemaining = if (throttled && cooldownEndsAt > now) {
                        ((cooldownEndsAt - now + 999L) / 1000L).toInt().coerceAtLeast(0)
                    } else {
                        0
                    }

                    _uiState.update { current ->
                        current.copy(
                            connectionState = DashboardConnectionState.CONNECTED,
                            isStatusLoading = false,
                            lastStatusUpdateMs = now,
                            statusErrorMessage = null,
                            isEngineLoaded = status.isEngineLoaded,
                            backend = status.backend,
                            uptimeMs = status.uptimeMs,
                            thermalBand = status.thermalBand,
                            // F-073: the service writes the sentinel
                            // `UNAVAILABLE` into thermalBand when the
                            // current ThermalPolicy is INFERRED (Android
                            // 8 / 8.1, no thermal telemetry). Surface
                            // that to the UI via a typed boolean.
                            thermalTelemetryAvailable = !status.thermalBand
                                .equals("UNAVAILABLE", ignoreCase = true),
                            serviceThrottled = throttled,
                            throttleCooldownSecondsRemaining = cooldownRemaining,
                            recentDeathCount = healthSnapshot?.deathCount ?: 0,
                            headroom = status.headroom,
                            memoryPressure = status.memoryPressure,
                            availableRamMb = status.availableRamMb,
                            totalRamMb = status.totalRamMb,
                            maxSessions = status.maxSessions,
                            activeSessions = sessions.map { session ->
                                SessionUiItem(
                                    sessionId = session.sessionId.take(8) + "…",
                                    backend = session.backend,
                                    tokenCount = session.currentTokenCount,
                                    maxTokens = session.maxTokens,
                                    isStreaming = session.isStreaming,
                                    lastAccessedLabel = formatRelativeTimestamp(
                                        session.lastAccessedAtMs,
                                        now,
                                    ),
                                )
                            },
                            initTimeSeconds = engineInfo?.initTimeSeconds ?: 0f,
                            modelId = engineInfo?.modelId ?: "",
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update { current ->
                        current.copy(
                            connectionState = if (service == null) {
                                DashboardConnectionState.DISCONNECTED
                            } else {
                                current.connectionState
                            },
                            isStatusLoading = false,
                            statusErrorMessage = "Status polling failed: ${e.toDashboardMessage()}",
                        )
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun startLogPolling() {
        logPollingJob?.cancel()
        logPollingJob = viewModelScope.launch {
            while (isActive && bound) {
                val dao = logDao
                if (dao == null) {
                    _uiState.update {
                        it.copy(
                            isLogsLoading = false,
                            logsErrorMessage = "Log database is unavailable.",
                        )
                    }
                    delay(3_000)
                    continue
                }

                try {
                    val sample = withContext(Dispatchers.IO) {
                        DashboardLogSample(
                            recent = dao.getRecent(20),
                            gpuFailure = dao.latestGpuFallbackMessage(),
                            sampledAtMs = System.currentTimeMillis(),
                            initFailure = dao.latestInitFailure()?.let(::parseInitFailureLogRow),
                            acceleratorDecisions = BACKEND_DECISION_FEATURES.mapNotNull { feature ->
                                dao.latestBackendDecisionByFeature(feature)?.let(::parseBackendDecisionLogRow)
                            },
                        )
                    }
                    val recent = sample.recent
                    val gpuFailure = sample.gpuFailure
                    val now = sample.sampledAtMs
                    val initFailure = sample.initFailure
                    val acceleratorDecisions = sample.acceleratorDecisions
                    _uiState.update { current ->
                        current.copy(
                            isLogsLoading = false,
                            lastLogsUpdateMs = now,
                            logsErrorMessage = null,
                            gpuFailureReason = gpuFailure,
                            lastInitFailure = initFailure,
                            acceleratorDecision = acceleratorDecisions.firstOrNull(),
                            acceleratorDecisions = acceleratorDecisions,
                            recentLogs = recent.map { entry ->
                                LogUiItem(
                                    timestampLabel = formatRelativeTimestamp(entry.timestampMs, now),
                                    category = entry.category,
                                    event = entry.event,
                                    detail = buildLogDetail(entry),
                                )
                            }
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isLogsLoading = false,
                            logsErrorMessage = "Log polling failed: ${e.toDashboardMessage()}",
                        )
                    }
                }
                delay(3_000)
            }
        }
    }

    private fun buildLogDetail(entry: LogEntry): String = buildList {
        entry.sessionId?.let { add("session=${it.take(8)}…") }
        entry.backend?.let { add("backend=$it") }
        entry.durationMs?.let { add("duration=${it}ms") }
        entry.tokensGenerated?.let { add("tokens=$it") }
        entry.tokensPerSec?.let { add("speed=${"%.1f".format(it)} tok/s") }
        entry.thermalBand?.let { add("band=$it") }
        entry.errorMessage?.let { add("error=$it") }
        entry.extraJson?.takeIf { entry.event == com.adsamcik.mindlayer.service.logging.LogEvent.BACKEND_DECISION.key }?.let { extra ->
            parseBackendDecisionLogRow(entry)?.let { decision ->
                add("reason=${decision.reason}")
            }
        }
    }.joinToString(" • ")

    // ---- Test inference --------------------------------------------------------

    private companion object {
        const val TEST_INFERENCE_PREWARM_BACKEND = "GPU"
        const val TEST_INFERENCE_PREWARM_TIMEOUT_MS = 30_000L
    }

    private val testJson = Json { ignoreUnknownKeys = true }

    /**
     * Runs a full end-to-end inference test:
     * 1. Creates a session via AIDL
     * 2. Sends a text prompt via AIDL infer() with a pipe
     * 3. Reads streaming events from the pipe
     * 4. Reports results (or errors) in the UI
     */
    fun runTestInference(prompt: String = "Hello! What are you?") {
        _uiState.value.testReadinessIssue()?.let { issue ->
            _uiState.update {
                it.copy(
                    isTestRunning = false,
                    testStatus = issue,
                    testStatusTone = DashboardMessageTone.WARNING,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isTestRunning = true,
                testStatus = "Preparing test prompt",
                testOutput = "",
                testStatusTone = DashboardMessageTone.INFO,
            )
        }

        viewModelScope.launch {
            var testService: com.adsamcik.mindlayer.IMindlayerService? = null
            var sessionId: String? = null
            try {
                val svc = service ?: run {
                    _uiState.update {
                        it.copy(
                            isTestRunning = false,
                            testStatus = "Service is not connected",
                            testStatusTone = DashboardMessageTone.ERROR,
                        )
                    }
                    return@launch
                }
                testService = svc

                _uiState.update {
                    it.copy(testStatus = "Warming engine for test inference")
                }
                val activeBackend = withContext(Dispatchers.IO) {
                    svc.prewarmAndAwait(
                        TEST_INFERENCE_PREWARM_BACKEND,
                        TEST_INFERENCE_PREWARM_TIMEOUT_MS,
                    )
                }
                if (activeBackend.equals("NONE", ignoreCase = true)) {
                    throw IllegalStateException(
                        "Engine did not become ready within " +
                            "${TEST_INFERENCE_PREWARM_TIMEOUT_MS / 1_000}s",
                    )
                }

                _uiState.update {
                    it.copy(testStatus = "Engine ready on $activeBackend • creating test session")
                }
                sessionId = withContext(Dispatchers.IO) {
                    svc.createSession(SessionConfig(
                        systemPrompt = "You are a helpful assistant. Be concise.",
                        maxTokens = 2048,
                    ))
                }
                val activeSessionId = requireNotNull(sessionId) {
                    "Service returned a null session id"
                }
                _uiState.update {
                    it.copy(testStatus = "Session ${activeSessionId.take(8)}… created • sending prompt")
                }

                val pipe = ParcelFileDescriptor.createReliablePipe()
                val readEnd = pipe[0]
                val writeEnd = pipe[1]

                val requestId = UUID.randomUUID().toString()
                val meta = RequestMeta(
                    requestId = requestId,
                    sessionId = activeSessionId,
                    textContent = prompt,
                )

                try {
                    withContext(Dispatchers.IO) {
                        svc.infer(meta, null, null, writeEnd)
                    }
                } catch (e: Exception) {
                    readEnd.close()
                    throw e
                } finally {
                    writeEnd.close()
                }

                _uiState.update { it.copy(testStatus = "Streaming response") }

                val output = StringBuilder()
                var eventCount = 0
                var finishReason = "unknown"
                var errorEventCount = 0
                var unparsedEventCount = 0
                var streamReadError: String? = null

                withContext(Dispatchers.IO) {
                    val input = DataInputStream(BufferedInputStream(
                        ParcelFileDescriptor.AutoCloseInputStream(readEnd)
                    ))

                    try {
                        while (true) {
                            val len = try {
                                Integer.reverseBytes(input.readInt())
                            } catch (_: EOFException) {
                                break
                            }
                            if (len < 0 || len > 1_048_576) break

                            val bytes = ByteArray(len)
                            input.readFully(bytes)
                            val jsonStr = bytes.decodeToString()
                            eventCount++

                            try {
                                val event = testJson.decodeFromString<StreamEvent>(jsonStr)
                                when (event.type) {
                                    "token_delta" -> {
                                        val text = event.payload["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                        output.append(text)
                                        _uiState.update { it.copy(testOutput = output.toString()) }
                                    }

                                    "error" -> {
                                        errorEventCount++
                                        val message = event.payload["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                                        val code = event.payload["code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                                        if (output.isNotEmpty()) output.append('\n')
                                        output.append("[service error][$code] $message")
                                        _uiState.update { it.copy(testOutput = output.toString()) }
                                    }

                                    "done" -> {
                                        finishReason = event.payload["finish_reason"]
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                            ?: "unknown"
                                    }
                                }
                            } catch (_: Exception) {
                                try {
                                    testJson.decodeFromString<StreamHeader>(jsonStr)
                                } catch (_: Exception) {
                                    unparsedEventCount++
                                    if (unparsedEventCount <= 3) {
                                        if (output.isNotEmpty()) output.append('\n')
                                        output.append("[unparsed event] ").append(jsonStr.take(200))
                                        _uiState.update { it.copy(testOutput = output.toString()) }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        streamReadError = e.toDashboardMessage()
                    }
                }

                if (streamReadError != null) {
                    if (output.isNotEmpty()) output.append("\n\n")
                    output.append("[stream error] ").append(streamReadError)
                }

                val completedAt = System.currentTimeMillis()
                val (status, tone) = when {
                    streamReadError != null -> {
                        "Stream read failed after $eventCount event(s)" to DashboardMessageTone.ERROR
                    }

                    errorEventCount > 0 -> {
                        "Service returned $errorEventCount error event(s)" to DashboardMessageTone.ERROR
                    }

                    output.isNotEmpty() && unparsedEventCount > 0 -> {
                        "Completed with $unparsedEventCount parser warning(s)" to DashboardMessageTone.WARNING
                    }

                    output.isNotEmpty() -> {
                        "Completed • $eventCount event(s) • finish=$finishReason" to DashboardMessageTone.SUCCESS
                    }

                    unparsedEventCount > 0 -> {
                        "Received $unparsedEventCount unparsed event(s)" to DashboardMessageTone.WARNING
                    }

                    else -> {
                        "No output received ($eventCount event(s))" to DashboardMessageTone.WARNING
                    }
                }

                _uiState.update {
                    it.copy(
                        isTestRunning = false,
                        testStatus = status,
                        testOutput = output.toString(),
                        testStatusTone = tone,
                        lastTestCompletedAtMs = completedAt,
                    )
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTestRunning = false,
                        testStatus = "Test inference failed: ${e.safeLabel()}",
                        testOutput = e.safeLabel(),
                        testStatusTone = DashboardMessageTone.ERROR,
                        lastTestCompletedAtMs = System.currentTimeMillis(),
                    )
                }
            } finally {
                sessionId?.let { activeSessionId ->
                    try {
                        withContext(Dispatchers.IO) {
                            testService?.destroySession(activeSessionId)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    override fun onCleared() {
        statusPollingJob?.cancel()
        logPollingJob?.cancel()
        super.onCleared()
    }

    private fun Throwable.toDashboardMessage(): String {
        val summary = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return summary.ifBlank { javaClass.simpleName }
    }

    private data class DashboardStatusSample(
        val status: ServiceStatus,
        val engineInfo: EngineInfo?,
        val sessions: List<SessionInfo>,
        val sampledAtMs: Long,
    )

    private data class DashboardLogSample(
        val recent: List<LogEntry>,
        val gpuFailure: String?,
        val sampledAtMs: Long,
        val initFailure: com.adsamcik.mindlayer.service.engine.InitFailure? = null,
        val acceleratorDecisions: List<AcceleratorDecisionUi> = emptyList(),
    )
}

private val BACKEND_DECISION_FEATURES = listOf("chat", "embeddings", "ocr")

/**
 * F-077: parse a `LogEvent.INIT_FAILURE_CATEGORIZED.key` row back into the
 * typed [com.adsamcik.mindlayer.service.engine.InitFailure] sealed
 * class. Returns `null` for rows that have no `failureCategory` field
 * in `extraJson`, an unknown category name, or malformed JSON — the
 * dashboard simply hides the variant card in that case rather than
 * surfacing a parse error.
 *
 * Visible (`internal`) for testing — the round-trip
 * `LogRepository.logInitFailureCategorized` ⇌ `parseInitFailureLogRow`
 * is the contract that pins F-077's wire format.
 */
internal fun parseInitFailureLogRow(
    entry: LogEntry,
): com.adsamcik.mindlayer.service.engine.InitFailure? {
    val extra = entry.extraJson ?: return null
    val category = try {
        Json.parseToJsonElement(extra)
            .jsonObject["failureCategory"]
            ?.jsonPrimitive
            ?.contentOrNull
    } catch (_: Exception) {
        null
    } ?: return null
    return when (category) {
        "LowMemory" -> com.adsamcik.mindlayer.service.engine.InitFailure.LowMemory
        "ModelMissing" -> com.adsamcik.mindlayer.service.engine.InitFailure.ModelMissing
        "IntegrityMismatch" -> com.adsamcik.mindlayer.service.engine.InitFailure.IntegrityMismatch
        "BackendUnavailable" -> com.adsamcik.mindlayer.service.engine.InitFailure.BackendUnavailable(
            backend = entry.backend.orEmpty(),
            safeLabel = entry.errorMessage.orEmpty(),
        )
        "NativeError" -> com.adsamcik.mindlayer.service.engine.InitFailure.NativeError(
            safeLabel = entry.errorMessage.orEmpty(),
        )
        else -> null
    }
}


internal fun parseBackendDecisionLogRow(entry: LogEntry): AcceleratorDecisionUi? {
    val extra = entry.extraJson ?: return null
    return try {
        val root = Json.parseToJsonElement(extra).jsonObject
        val feature = root["feature"]?.jsonPrimitive?.contentOrNull ?: return null
        val reason = root["reason"]?.jsonPrimitive?.contentOrNull ?: return null
        val attempted = root["attempted"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val backend = obj["backend"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val candidateReason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            "$backend:$candidateReason"
        }.joinToString(" -> ")
        AcceleratorDecisionUi(
            featureName = feature,
            backend = entry.backend.orEmpty(),
            reason = reason,
            attemptedSummary = attempted,
        )
    } catch (_: Exception) {
        null
    }
}
