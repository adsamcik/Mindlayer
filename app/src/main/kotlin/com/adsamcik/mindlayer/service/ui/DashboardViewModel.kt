package com.adsamcik.mindlayer.service.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EngineInfo
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.service.health.MlHealthRecorder
import com.adsamcik.mindlayer.service.logging.LogDao
import com.adsamcik.mindlayer.service.logging.LogDatabase
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamEventType
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
import java.io.File
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
        // Emulator first-load of a 2.4 GB .litertlm with the software GPU
        // backend routinely takes 60-90s; real devices finish in seconds.
        // Budget 3 minutes so the verification UX doesn't bail out before
        // the engine has had a chance to come up on slow hardware.
        const val TEST_INFERENCE_PREWARM_TIMEOUT_MS = 180_000L

        const val OCR_FIXTURE_TEXT = "Hello world 1234"
        const val OCR_FIXTURE_WIDTH = 480
        const val OCR_FIXTURE_HEIGHT = 140
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
                    it.copy(testStatus = "Warming engine — first load can take 1–2 min on emulators")
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
                val rendered = e.toInferenceErrorMessage()
                _uiState.update {
                    it.copy(
                        isTestRunning = false,
                        testStatus = "Test inference failed: $rendered",
                        testOutput = rendered,
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

    // ---- Embedding verification ------------------------------------------------

    /**
     * Runs a deterministic two-sample embedding smoke test:
     * 1. Embed sentence A via AIDL.
     * 2. Embed sentence B via AIDL.
     * 3. Verify both vectors are non-trivial, L2-normalised, and have
     *    cosine similarity strictly < 0.99 (distinguishable).
     *
     * Independent of [runTestInference] — the embedding engine has its
     * own backend and can run while a chat test is in-flight. Uses the
     * same fixtures as [com.adsamcik.mindlayer.service.engine.EmbeddingEndToEndInstrumentedTest]
     * so the dashboard surface and the instrumented contract test exercise
     * the same code path.
     */
    fun runEmbeddingTest() {
        _uiState.value.embeddingTestReadinessIssue()?.let { issue ->
            _uiState.update {
                it.copy(embeddingTest = it.embeddingTest.copy(
                    isRunning = false,
                    status = issue,
                    tone = DashboardMessageTone.WARNING,
                ))
            }
            return
        }

        _uiState.update {
            it.copy(embeddingTest = EngineTestState(
                isRunning = true,
                status = "Loading EmbeddingGemma — first call warms the engine",
                tone = DashboardMessageTone.INFO,
            ))
        }

        viewModelScope.launch {
            try {
                val svc = service ?: run {
                    _uiState.update {
                        it.copy(embeddingTest = it.embeddingTest.copy(
                            isRunning = false,
                            status = "Service is not connected",
                            tone = DashboardMessageTone.ERROR,
                            lastCompletedAtMs = System.currentTimeMillis(),
                        ))
                    }
                    return@launch
                }

                val textA = "The cat sits on the mat."
                val textB = "Quantum mechanics describes particles."

                val resultA = withContext(Dispatchers.IO) {
                    svc.embed(EmbeddingRequest(text = textA, tag = "dashboard-a"))
                }
                _uiState.update {
                    it.copy(embeddingTest = it.embeddingTest.copy(
                        status = "First embedding ready • ${resultA.dim}-D in " +
                            "${resultA.durationMs}ms • sending second",
                    ))
                }

                val resultB = withContext(Dispatchers.IO) {
                    svc.embed(EmbeddingRequest(text = textB, tag = "dashboard-b"))
                }

                val vecA = resultA.vector
                val vecB = resultB.vector
                check(vecA.size == vecB.size) {
                    "Embedding vector dimensions differ: A=${vecA.size}, B=${vecB.size}"
                }
                var cosine = 0.0
                for (i in vecA.indices) cosine += vecA[i].toDouble() * vecB[i].toDouble()
                val normA = kotlin.math.sqrt(vecA.fold(0.0) { acc, v -> acc + v * v })
                val anyNonZero = vecA.any { kotlin.math.abs(it) > 1e-6f }
                val anyNaN = vecA.any { it.isNaN() } || vecB.any { it.isNaN() }
                val distinguishable = cosine < 0.99

                val passed = anyNonZero && !anyNaN && distinguishable
                val backend = resultA.backend
                val output = buildString {
                    appendLine("Dim:       ${resultA.dim}")
                    appendLine("Backend:   $backend")
                    appendLine("Latency:   ${resultA.durationMs}ms (A) / ${resultB.durationMs}ms (B)")
                    appendLine("Tokens:    ${resultA.tokenCount} (A) / ${resultB.tokenCount} (B)")
                    appendLine("\u2016A\u2016\u2082:     " + "%.4f".format(normA) + "  (expect \u2248 1.0)")
                    append("cos(A,B):  " + "%.4f".format(cosine) + "  (expect < 0.99)")
                }
                val status = when {
                    !anyNonZero -> "Vector A is all-zero — model is not returning weights"
                    anyNaN -> "Embedding vectors contain NaN values"
                    !distinguishable ->
                        "Vectors look near-identical (cos=${"%.4f".format(cosine)}); model may be miscalibrated"
                    else -> "Completed \u2022 two distinguishable ${resultA.dim}-D vectors on $backend"
                }

                _uiState.update {
                    it.copy(embeddingTest = EngineTestState(
                        isRunning = false,
                        status = status,
                        tone = if (passed) DashboardMessageTone.SUCCESS else DashboardMessageTone.WARNING,
                        output = output,
                        lastCompletedAtMs = System.currentTimeMillis(),
                    ))
                }
            } catch (e: Exception) {
                val rendered = e.toInferenceErrorMessage()
                _uiState.update {
                    it.copy(embeddingTest = it.embeddingTest.copy(
                        isRunning = false,
                        status = "Embedding test failed: $rendered",
                        tone = DashboardMessageTone.ERROR,
                        output = rendered,
                        lastCompletedAtMs = System.currentTimeMillis(),
                    ))
                }
            }
        }
    }

    // ---- OCR verification -----------------------------------------------------

    /**
     * Runs the OCR session lifecycle end-to-end:
     * 1. Render a high-contrast ``Hello world 1234`` PNG fixture into
     *    [Context.getCacheDir] so the engine sees a real, decodable
     *    image (the in-tree instrumented test uses a synthetic
     *    checkerboard + a mock backend; the dashboard test runs against
     *    the real PaddleOCR engine).
     * 2. ``svc.createOcrSession(MODE_GENERAL_DOCUMENT)``.
     * 3. ``svc.streamOcrEvents(sessionId, writePfd)`` — pipe is opened
     *    locally and the read end is consumed in [Dispatchers.IO].
     * 4. ``svc.pushOcrFrame(sessionId, MediaPart(IMAGE), OcrFrameMeta)``.
     * 5. ``svc.finalizeOcrSession`` then drain until ``ocr_result_finalized``
     *    or ``done`` arrives.
     * 6. ``svc.closeOcrSession`` in finally.
     *
     * Surfaces recognized text + frame stats to the dashboard output box.
     */
    fun runOcrTest(context: Context) {
        _uiState.value.ocrTestReadinessIssue()?.let { issue ->
            _uiState.update {
                it.copy(ocrTest = it.ocrTest.copy(
                    isRunning = false,
                    status = issue,
                    tone = DashboardMessageTone.WARNING,
                ))
            }
            return
        }

        _uiState.update {
            it.copy(ocrTest = EngineTestState(
                isRunning = true,
                status = "Rendering OCR fixture image",
                tone = DashboardMessageTone.INFO,
            ))
        }

        val cacheDir = context.cacheDir
        viewModelScope.launch {
            var ocrService: com.adsamcik.mindlayer.IMindlayerService? = null
            var sessionId: String? = null
            try {
                val svc = service ?: run {
                    _uiState.update {
                        it.copy(ocrTest = it.ocrTest.copy(
                            isRunning = false,
                            status = "Service is not connected",
                            tone = DashboardMessageTone.ERROR,
                            lastCompletedAtMs = System.currentTimeMillis(),
                        ))
                    }
                    return@launch
                }
                ocrService = svc

                val fixtureFile = withContext(Dispatchers.IO) {
                    renderOcrFixturePng(cacheDir)
                }

                _uiState.update {
                    it.copy(ocrTest = it.ocrTest.copy(
                        status = "Opening OCR session",
                    ))
                }
                sessionId = withContext(Dispatchers.IO) {
                    svc.createOcrSession(
                        OcrSessionConfig(
                            mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
                            outputSchemaJson = "{\"type\":\"object\"}",
                            maxFrames = 1,
                        ),
                    )
                }
                val activeSessionId = requireNotNull(sessionId) { "Service returned a null OCR session id" }

                val pipe = ParcelFileDescriptor.createReliablePipe()
                val readEnd = pipe[0]
                val writeEnd = pipe[1]

                try {
                    withContext(Dispatchers.IO) { svc.streamOcrEvents(activeSessionId, writeEnd) }
                } finally {
                    runCatching { writeEnd.close() }
                }

                val frameSource = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(fixtureFile, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                val frame = MediaPart(
                    requestId = UUID.randomUUID().toString(),
                    kind = MediaPart.KIND_IMAGE,
                    mimeType = "image/png",
                    source = frameSource,
                    isSharedMemory = false,
                    payloadBytes = fixtureFile.length(),
                    width = OCR_FIXTURE_WIDTH,
                    height = OCR_FIXTURE_HEIGHT,
                )
                _uiState.update {
                    it.copy(ocrTest = it.ocrTest.copy(
                        status = "Pushing fixture frame (${fixtureFile.length()} bytes)",
                    ))
                }
                val ack = withContext(Dispatchers.IO) {
                    svc.pushOcrFrame(
                        activeSessionId,
                        frame,
                        OcrFrameMeta(
                            frameId = 1L,
                            captureTimeMs = System.currentTimeMillis(),
                            rotationDegrees = 0,
                            qualityHint = OcrFrameMeta.QUALITY_GOOD,
                        ),
                    )
                }
                if (ack.status != OcrFrameAck.STATUS_ACCEPTED) {
                    throw IllegalStateException(
                        "pushOcrFrame returned status=${ack.status} (expected ACCEPTED)",
                    )
                }

                _uiState.update {
                    it.copy(ocrTest = it.ocrTest.copy(
                        status = "Frame accepted — finalizing session",
                    ))
                }
                withContext(Dispatchers.IO) { svc.finalizeOcrSession(activeSessionId) }

                _uiState.update {
                    it.copy(ocrTest = it.ocrTest.copy(
                        status = "Waiting for recognition result",
                    ))
                }
                val drained = withContext(Dispatchers.IO) { drainOcrEvents(readEnd) }

                val recognized = drained.recognizedText
                val producedText = drained.lineCount > 0 || recognized.isNotBlank()
                val pass = drained.finalized &&
                    drained.frameProcessedCount > 0 &&
                    drained.errorCount == 0 &&
                    producedText
                val tone = when {
                    drained.errorCount > 0 -> DashboardMessageTone.ERROR
                    !drained.finalized -> DashboardMessageTone.WARNING
                    drained.frameProcessedCount == 0 -> DashboardMessageTone.WARNING
                    !producedText -> DashboardMessageTone.WARNING
                    else -> DashboardMessageTone.SUCCESS
                }
                val status = when {
                    drained.errorCount > 0 ->
                        "OCR pipeline returned ${drained.errorCount} error event(s) \u2014 see output"
                    !drained.finalized ->
                        "Session never finalized \u2014 got ${drained.totalEvents} event(s)"
                    drained.frameProcessedCount == 0 ->
                        "Finalized without processing any frame"
                    !producedText ->
                        "Pipeline OK but PaddleOCR returned 0 lines from the fixture \u2014 " +
                            "the recognition model may not have loaded on this device " +
                            "(check Recent Logs for native errors)"
                    else -> "Completed \u2022 recognized ${drained.lineCount} line(s) on PaddleOCR / CPU"
                }
                val output = buildString {
                    appendLine("Events:    ${drained.totalEvents} total \u2022 ${drained.frameProcessedCount} processed \u2022 ${drained.errorCount} error(s)")
                    appendLine("Lines:     ${drained.lineCount}")
                    appendLine("Finalized: ${drained.finalized}")
                    if (recognized.isNotBlank()) {
                        appendLine("\nRecognized text:")
                        appendLine(recognized)
                    } else if (drained.finalized && drained.frameProcessedCount > 0) {
                        appendLine("\n(No text surfaced from the engine. The fixture is a 480x140 PNG " +
                            "of \u201c$OCR_FIXTURE_TEXT\u201d; if PaddleOCR is healthy this should " +
                            "produce at least one line. A LiteRtException during recognise typically " +
                            "means the recognition .tflite model has a custom op that's not registered " +
                            "in the runtime.)")
                    }
                }

                _uiState.update {
                    it.copy(ocrTest = EngineTestState(
                        isRunning = false,
                        status = status,
                        tone = tone,
                        output = output.trimEnd(),
                        lastCompletedAtMs = System.currentTimeMillis(),
                    ))
                }
            } catch (e: Exception) {
                val rendered = e.toInferenceErrorMessage()
                _uiState.update {
                    it.copy(ocrTest = it.ocrTest.copy(
                        isRunning = false,
                        status = "OCR test failed: $rendered",
                        tone = DashboardMessageTone.ERROR,
                        output = rendered,
                        lastCompletedAtMs = System.currentTimeMillis(),
                    ))
                }
            } finally {
                sessionId?.let { activeSessionId ->
                    try {
                        withContext(Dispatchers.IO) {
                            ocrService?.closeOcrSession(activeSessionId)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * Aggregated result of draining the OCR event pipe. The dashboard
     * surface only needs counts + a flattened "recognized text" view;
     * the full structured ``ocr_field_update`` / ``ocr_result_finalized``
     * machinery lives in [com.adsamcik.mindlayer.sdk.OcrTokenStreamReader]
     * which the app module deliberately doesn't depend on (sdk is a
     * test-only dependency here).
     */
    private data class OcrDrainResult(
        val totalEvents: Int,
        val frameProcessedCount: Int,
        val errorCount: Int,
        val finalized: Boolean,
        val recognizedText: String,
        val lineCount: Int,
    )

    private suspend fun drainOcrEvents(readEnd: ParcelFileDescriptor): OcrDrainResult =
        withContext(Dispatchers.IO) {
            val input = DataInputStream(BufferedInputStream(
                ParcelFileDescriptor.AutoCloseInputStream(readEnd)
            ))
            var totalEvents = 0
            var frameProcessedCount = 0
            var errorCount = 0
            var finalized = false
            var lineCount = 0
            val recognizedLines = mutableListOf<String>()
            var finalizedJson: String? = null

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
                    totalEvents++
                    try {
                        val event = testJson.decodeFromString<StreamEvent>(jsonStr)
                        when (event.type) {
                            StreamEventType.OCR_FRAME_PROCESSED -> {
                                frameProcessedCount++
                                event.payload["line_count"]?.jsonPrimitive?.contentOrNull
                                    ?.toIntOrNull()?.let { lineCount += it }
                            }

                            StreamEventType.OCR_FIELD_UPDATE,
                            StreamEventType.OCR_FIELD_LOCKED -> {
                                event.payload["top_value"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { recognizedLines += it }
                            }

                            StreamEventType.OCR_RESULT_FINALIZED -> {
                                finalized = true
                                finalizedJson = event.payload["full_json"]
                                    ?.jsonPrimitive?.contentOrNull
                            }

                            StreamEventType.DONE -> {
                                // terminal — stop draining
                                break
                            }

                            StreamEventType.ERROR -> {
                                errorCount++
                            }
                        }
                    } catch (_: Exception) {
                        try {
                            testJson.decodeFromString<StreamHeader>(jsonStr)
                            // Header frame, ignore
                        } catch (_: Exception) {
                            // Unparseable — skip
                        }
                    }
                }
            } catch (_: Exception) {
                // Stream ended mid-read; report what we have.
            }

            // Prefer the finalized JSON snapshot if it carried recognizable text.
            val recognized = if (!finalizedJson.isNullOrBlank()) {
                finalizedJson!!.takeIf { it.length < 2_000 } ?: finalizedJson!!.take(2_000) + "…"
            } else {
                recognizedLines.joinToString(separator = "\n")
            }

            OcrDrainResult(
                totalEvents = totalEvents,
                frameProcessedCount = frameProcessedCount,
                errorCount = errorCount,
                finalized = finalized,
                recognizedText = recognized,
                lineCount = lineCount,
            )
        }

    private fun renderOcrFixturePng(cacheDir: File): File {
        val bitmap = Bitmap.createBitmap(
            OCR_FIXTURE_WIDTH, OCR_FIXTURE_HEIGHT, Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 56f
                isAntiAlias = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            // Centred-ish baseline so the entire glyph fits comfortably.
            canvas.drawText(OCR_FIXTURE_TEXT, 24f, OCR_FIXTURE_HEIGHT * 0.65f, paint)
            val file = File(cacheDir, "ocr-dashboard-fixture.png")
            file.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Failed to encode OCR dashboard fixture PNG"
                }
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    private fun Throwable.toDashboardMessage(): String {
        val raw = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val typed = decodeTypedWireMessage(raw)
        return typed ?: raw.ifBlank { javaClass.simpleName }
    }

    /**
     * F-079: defense-in-depth renderer for the test-inference catch
     * block. Typed binder exceptions (`MLERR:<code>:<message>`) are
     * decoded to a human-readable `<NAME>: <message>` so users see e.g.
     * `LOW_MEMORY: Insufficient memory: availMb=2348 requiredMb=2980`
     * instead of a bare `SecurityException` (every typed code rides as
     * `SecurityException` on the wire — `ServiceBinder.typedBinderException`).
     *
     * For *untyped* exceptions we deliberately fall back to
     * `safeLabel()` (class name only). The inference path may bubble
     * up `RuntimeException`s whose `message` embeds LiteRT-LM internal
     * class names or native-engine state; exposing those verbatim was
     * the regression covered by
     * `DashboardViewModelTest.test inference failure renders safe label
     * without LiteRT stack frames`.
     */
    private fun Throwable.toInferenceErrorMessage(): String {
        val raw = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return decodeTypedWireMessage(raw) ?: safeLabel()
    }

    /**
     * Returns `"<NAME>: <message>"` for a `MLERR:<code>:<message>` wire
     * string, or `null` if [raw] is not a typed wire message. The wire
     * message text is intentionally operator-safe — it embeds error
     * codes and diagnostic numbers only, never prompt or output text
     * (see `MindlayerErrorCode.wireMessage` callers in
     * `ServiceBinder.kt`).
     */
    private fun decodeTypedWireMessage(raw: String): String? {
        val code = MindlayerErrorCode.codeFromWireMessage(raw) ?: return null
        val name = MindlayerErrorCode.nameOf(code) ?: "ERROR_$code"
        val msg = MindlayerErrorCode.messageFromWireMessage(raw).orEmpty()
        return if (msg.isBlank()) name else "$name: $msg"
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
