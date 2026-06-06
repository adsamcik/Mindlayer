package com.adsamcik.mindlayer.ocrdriver

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.sdk.ConnectionState
import com.adsamcik.mindlayer.sdk.ConsentRequestResult
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.MindlayerConsent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Single owner of every Mindlayer SDK call the driver makes. Each tab
 * (Connection / Inference / Embeddings / OCR / Diagnostics / Validation)
 * gets a slice of [DriverUiState] and calls back through the
 * `on…` action methods exposed below.
 *
 * Threading: every SDK call runs inside [viewModelScope]; state updates
 * are atomic via [MutableStateFlow.update].
 *
 * Lifecycle: [disconnect] tears down the SDK client + clears every
 * per-tab transient state. Re-[connect] re-runs the post-connect
 * capabilities warm-up poll.
 */
class DriverViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _ui.asStateFlow()

    private var mindlayer: Mindlayer? = null
    private var capabilitiesPollJob: Job? = null
    private var inferenceJob: Job? = null

    /**
     * One-shot consent prompts to launch from the Activity. The ViewModel
     * cannot start an Activity itself, so [requestConsent] pushes the
     * server-issued [IntentSender] here and `MainActivity` launches it via
     * `StartIntentSenderForResult`.
     */
    private val _consentPrompts = Channel<IntentSender>(Channel.BUFFERED)
    val consentPrompts: Flow<IntentSender> = _consentPrompts.receiveAsFlow()

    // ── Connection lifecycle ────────────────────────────────────────────

    fun connect() {
        viewModelScope.launch {
            _ui.update { it.copy(connection = it.connection.copy(error = null), globalError = null) }
            val client = try {
                val c = Mindlayer.connect(getApplication())
                c.awaitConnected(Duration.INFINITE)
                c
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        connection = it.connection.copy(
                            state = ConnectionState.DISCONNECTED,
                            error = "Connect failed: ${t.javaClass.simpleName}: ${t.message?.take(200)}. " +
                                "If this app has not been approved yet, tap \u201CRequest consent\u201D.",
                        ),
                    )
                }
                return@launch
            }
            mindlayer = client
            _ui.update {
                it.copy(connection = it.connection.copy(state = ConnectionState.CONNECTED, error = null))
            }
            startCapabilitiesPoll(client)
        }
    }

    /**
     * Launch the Mindlayer consent flow. Any app must be approved by the user
     * once before the service will answer AIDL calls; this is the v0.10
     * consent-Intent handshake. On approval the Activity calls [onConsentResult]
     * which re-runs [connect].
     */
    fun requestConsent() {
        viewModelScope.launch {
            when (val r = MindlayerConsent.requestConsent(getApplication())) {
                is ConsentRequestResult.Available -> _consentPrompts.send(r.intentSender)
                ConsentRequestResult.AlreadyApproved -> connect()
                is ConsentRequestResult.Denied -> _ui.update {
                    val msg = if (r.untilEpochMs == null) {
                        "Permanently blocked — unblock this app from the Mindlayer dashboard."
                    } else {
                        "Temporarily denied — try again later (until=${r.untilEpochMs})."
                    }
                    it.copy(connection = it.connection.copy(error = msg))
                }
                ConsentRequestResult.ServiceUnavailable -> _ui.update {
                    it.copy(connection = it.connection.copy(error = "Mindlayer service is not installed."))
                }
                is ConsentRequestResult.Failed -> _ui.update {
                    it.copy(
                        connection = it.connection.copy(
                            error = "Consent request failed: code=${r.code} ${r.message?.take(120) ?: ""}",
                        ),
                    )
                }
            }
        }
    }

    /** Called by the Activity with the consent screen's result. */
    fun onConsentResult(approved: Boolean) {
        if (approved) {
            connect()
        } else {
            _ui.update {
                it.copy(connection = it.connection.copy(error = "Consent was not granted."))
            }
        }
    }

    fun disconnect() {
        capabilitiesPollJob?.cancel()
        capabilitiesPollJob = null
        inferenceJob?.cancel()
        inferenceJob = null
        try { mindlayer?.disconnect() } catch (_: Throwable) { /* fine */ }
        mindlayer = null
        _ui.value = DriverUiState()
    }

    /**
     * After connect, the on-device engines warm up asynchronously
     * (PaddleOCR ~200 ms, EmbeddingGemma ~500 ms, Gemma 4 E2B ~1-3 s).
     * The first `getCapabilities()` reply commonly omits engine-gated
     * features. Poll with `forceRefresh = true` at 1 Hz for 30 s so
     * the UI converges to truth. Stops once every engine-gated feature
     * has been observed at least once OR the timeout expires.
     */
    private fun startCapabilitiesPoll(client: Mindlayer) {
        capabilitiesPollJob?.cancel()
        capabilitiesPollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 30_000L
            var stableAfter = false
            while (!stableAfter && System.currentTimeMillis() < deadline) {
                val caps = try {
                    client.getCapabilities(forceRefresh = true)
                } catch (t: Throwable) {
                    _ui.update { it.copy(globalError = "getCapabilities failed: ${t.javaClass.simpleName}: ${t.message?.take(120)}") }
                    delay(2_000L)
                    continue
                }
                _ui.update {
                    it.copy(
                        connection = it.connection.copy(
                            apiVersion = caps.apiVersion,
                            allFeatures = caps.supportedFeatures.toSortedSet().toList(),
                            lastProbedAtMs = System.currentTimeMillis(),
                            maxRequestsPerMinute = caps.maxRequestsPerMinute,
                            maxConcurrentSessions = caps.maxConcurrentSessions,
                        ),
                        embeddings = it.embeddings.copy(
                            advertised = ServiceCapabilities.FEATURE_EMBEDDINGS in caps.supportedFeatures,
                            embeddingDims = caps.embeddingDims,
                            embeddingModelIds = caps.embeddingModelIds,
                        ),
                        ocr = it.ocr.copy(
                            asyncAdvertised = ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT in caps.supportedFeatures,
                            sessionAdvertised = ServiceCapabilities.FEATURE_OCR_SESSION in caps.supportedFeatures,
                        ),
                    )
                }
                val ocrUp = ServiceCapabilities.FEATURE_OCR_SESSION in caps.supportedFeatures &&
                    ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT in caps.supportedFeatures
                val embUp = ServiceCapabilities.FEATURE_EMBEDDINGS in caps.supportedFeatures
                // Stop early once both engine-gated families are up.
                // Gemma chat doesn't have its own capability flag — the
                // poll runs to the deadline anyway to catch late changes.
                if (ocrUp && embUp) {
                    stableAfter = true
                } else {
                    delay(1_000L)
                }
            }
        }
    }

    /** Manual refresh button — forces a single getCapabilities(forceRefresh) probe. */
    fun refreshCapabilities() {
        val client = mindlayer ?: return
        viewModelScope.launch {
            try {
                val caps = client.getCapabilities(forceRefresh = true)
                _ui.update {
                    it.copy(
                        connection = it.connection.copy(
                            apiVersion = caps.apiVersion,
                            allFeatures = caps.supportedFeatures.toSortedSet().toList(),
                            lastProbedAtMs = System.currentTimeMillis(),
                            maxRequestsPerMinute = caps.maxRequestsPerMinute,
                            maxConcurrentSessions = caps.maxConcurrentSessions,
                        ),
                    )
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(globalError = "Refresh failed: ${t.javaClass.simpleName}: ${t.message?.take(120)}") }
            }
        }
    }

    // ── Inference (Gemma) ───────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun runInference(prompt: String, maxTokens: Int, temperature: Float) {
        val client = mindlayer ?: return
        if (prompt.isBlank()) return
        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    inference = it.inference.copy(
                        inProgress = true,
                        response = "",
                        error = null,
                        durationMs = 0L,
                    ),
                )
            }
            val started = System.nanoTime()
            val sessionId = try {
                client.createSession {
                    systemPrompt("You are a concise tester probe. Answer in <= 60 tokens.")
                    maxTokens(maxTokens.coerceIn(128, 8192))
                    temperature(temperature.coerceIn(0f, 2f))
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        inference = it.inference.copy(
                            inProgress = false,
                            error = "createSession: ${t.javaClass.simpleName}: ${t.message?.take(180)}",
                        ),
                    )
                }
                return@launch
            }
            val response = try {
                withTimeoutOrNull(120_000L) {
                    client.inferAsync(sessionId, prompt)
                }
            } catch (t: Throwable) {
                runCatching { client.destroySession(sessionId) }
                _ui.update {
                    it.copy(
                        inference = it.inference.copy(
                            inProgress = false,
                            error = "inferAsync: ${t.javaClass.simpleName}: ${t.message?.take(180)}",
                            durationMs = (System.nanoTime() - started) / 1_000_000L,
                        ),
                    )
                }
                return@launch
            }
            runCatching { client.destroySession(sessionId) }
            val durationMs = (System.nanoTime() - started) / 1_000_000L
            _ui.update {
                it.copy(
                    inference = it.inference.copy(
                        inProgress = false,
                        response = response ?: "(null / timeout after 120 s)",
                        durationMs = durationMs,
                        error = if (response.isNullOrBlank()) "Empty response" else null,
                    ),
                )
            }
        }
    }

    fun cancelInference() {
        inferenceJob?.cancel()
        inferenceJob = null
        _ui.update {
            it.copy(
                inference = it.inference.copy(
                    inProgress = false,
                    error = "Cancelled by user",
                ),
            )
        }
    }

    // ── Embeddings ──────────────────────────────────────────────────────

    fun runEmbed(text: String) {
        val client = mindlayer ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            _ui.update {
                it.copy(embeddings = it.embeddings.copy(inProgress = true, error = null))
            }
            val started = System.nanoTime()
            try {
                val vec = client.vector(text)
                val l2 = kotlin.math.sqrt(vec.fold(0.0) { acc, v -> acc + v.toDouble() * v }).toFloat()
                val firstFew = vec.take(8).joinToString { "%.4f".format(it) }
                val durationMs = (System.nanoTime() - started) / 1_000_000L
                _ui.update {
                    it.copy(
                        embeddings = it.embeddings.copy(
                            inProgress = false,
                            lastDim = vec.size,
                            lastL2Norm = l2,
                            lastFirstFew = firstFew,
                            lastDurationMs = durationMs,
                            error = null,
                        ),
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        embeddings = it.embeddings.copy(
                            inProgress = false,
                            error = "vector: ${t.javaClass.simpleName}: ${t.message?.take(180)}",
                        ),
                    )
                }
            }
        }
    }

    // ── OCR (single image / async) ──────────────────────────────────────

    @Suppress("DEPRECATION")
    fun runOcrAsync(fixtureName: String, runLlm: Boolean, emitBoundingBoxes: Boolean) {
        val client = mindlayer ?: return
        viewModelScope.launch {
            _ui.update { it.copy(ocr = it.ocr.copy(inProgress = true, error = null)) }
            val started = System.nanoTime()
            try {
                val bytes = getApplication<Application>().assets
                    .open("fixtures/$fixtureName").use { it.readBytes() }
                val mimeType = OcrFixtures.mimeType(fixtureName)
                val result = client.ocrAsync(
                    bytes = bytes,
                    mimeType = mimeType,
                    options = com.adsamcik.mindlayer.OcrImageOptions(
                        runLlmExtraction = runLlm,
                        emitBoundingBoxes = emitBoundingBoxes,
                        extractionSchemaJson = if (runLlm) {
                            """{"type":"object","properties":{"total":{"type":"string"}}}"""
                        } else {
                            null
                        },
                    ),
                )
                val withBbox = result.lines.count { it.boundingBox != null }
                val previewLines = result.lines.take(8).joinToString("\n") {
                    "  • ${it.text}"
                }
                val durationMs = (System.nanoTime() - started) / 1_000_000L
                _ui.update {
                    it.copy(
                        ocr = it.ocr.copy(
                            inProgress = false,
                            lastFixture = fixtureName,
                            lastLineCount = result.lines.size,
                            lastWithBbox = withBbox,
                            lastOcrMs = result.ocrDurationMs,
                            lastLlmMs = result.llmDurationMs,
                            lastFields = result.extractionFields.size,
                            lastPreview = previewLines,
                            lastTotalMs = durationMs,
                            error = null,
                        ),
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        ocr = it.ocr.copy(
                            inProgress = false,
                            error = "ocrAsync: ${t.javaClass.simpleName}: ${t.message?.take(220)}",
                        ),
                    )
                }
            }
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────

    fun refreshDiagnostics() {
        val client = mindlayer ?: return
        viewModelScope.launch {
            _ui.update { it.copy(diagnostics = it.diagnostics.copy(inProgress = true, error = null)) }
            try {
                val status = client.getStatus()
                val ping = client.ping()
                val typed = runCatching { client.getDiagnosticsTyped() }.getOrNull()
                val sessions = runCatching { client.listSessions() }.getOrDefault(emptyList())
                _ui.update {
                    it.copy(
                        diagnostics = it.diagnostics.copy(
                            inProgress = false,
                            status = status,
                            ping = ping,
                            sessionCount = sessions.size,
                            sessionPreview = sessions.take(5).map { s ->
                                "${s.sessionId.take(8)}… backend=${s.backend} " +
                                    "tokens=${s.currentTokenCount} turns=${s.turnCount}"
                            },
                            typedDiagJson = typed?.let { td ->
                                "model=${td.engine.modelId} backend=${td.service.backend} " +
                                    "uptime=${td.service.uptimeMs}ms callerSessions=${td.callerSessionCount} " +
                                    "ocrFrames=${td.ocr.framesProcessed}/${td.ocr.framesRejected}rej " +
                                    "emb=${td.embedding.modelId ?: "—"}"
                            },
                            error = null,
                        ),
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        diagnostics = it.diagnostics.copy(
                            inProgress = false,
                            error = "diagnostics: ${t.javaClass.simpleName}: ${t.message?.take(180)}",
                        ),
                    )
                }
            }
        }
    }

    // ── Validation harness ──────────────────────────────────────────────

    fun runValidation() {
        val client = mindlayer ?: return
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    validation = it.validation.copy(
                        inProgress = true,
                        scenarios = emptyList(),
                        reportPath = null,
                        error = null,
                    ),
                )
            }
            try {
                val runner = ValidationRunner(getApplication(), client)
                val report = runner.runAll()
                val file = runner.writeReport(report)
                _ui.update {
                    it.copy(
                        validation = it.validation.copy(
                            inProgress = false,
                            scenarios = report.scenarios,
                            reportPath = file.absolutePath,
                            error = null,
                        ),
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        validation = it.validation.copy(
                            inProgress = false,
                            error = "validation: ${t.javaClass.simpleName}: ${t.message?.take(200)}",
                        ),
                    )
                }
            }
        }
    }

    // ── Headless auto_run (preserved for adb-driven CI) ─────────────────

    fun connectAndRunValidationAutomated() {
        viewModelScope.launch {
            try {
                android.util.Log.i("OcrDriverAuto", "Auto-run: connecting…")
                val client = Mindlayer.connect(getApplication())
                mindlayer = client
                client.awaitConnected(Duration.INFINITE)
                val caps = client.getCapabilities().supportedFeatures
                android.util.Log.i(
                    "OcrDriverAuto",
                    "Auto-run: connected. ocr_session=${ServiceCapabilities.FEATURE_OCR_SESSION in caps}, " +
                        "ocr_image_oneshot=${ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT in caps}",
                )
                val runner = ValidationRunner(getApplication(), client)
                val report = runner.runAll()
                val file = runner.writeReport(report)
                android.util.Log.i(
                    "OcrDriverAuto",
                    "Auto-run: complete. report=${file.absolutePath} passed=${report.passed} failed=${report.failed} total=${report.total}",
                )
                report.scenarios.forEach { s ->
                    android.util.Log.i(
                        "OcrDriverAuto",
                        "Auto-run scenario: ${s.name} ok=${s.ok} ${s.durationMs}ms note=${s.note ?: ""}",
                    )
                }
                _ui.update {
                    it.copy(
                        connection = it.connection.copy(state = ConnectionState.CONNECTED),
                        validation = it.validation.copy(
                            inProgress = false,
                            scenarios = report.scenarios,
                            reportPath = file.absolutePath,
                        ),
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.e(
                    "OcrDriverAuto",
                    "Auto-run aborted: ${t.javaClass.simpleName}: ${t.message}",
                )
                _ui.update {
                    it.copy(
                        globalError = "Auto-run aborted: ${t.javaClass.simpleName}: ${t.message?.take(220)}",
                    )
                }
            }
        }
    }
}
