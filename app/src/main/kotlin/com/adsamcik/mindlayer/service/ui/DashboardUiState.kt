package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.engine.OcrAcceleratorFailureCache

private const val STATUS_STALE_AFTER_MS = 6_000L
private const val LOGS_STALE_AFTER_MS = 12_000L

enum class DashboardConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

enum class DashboardFreshness {
    UNKNOWN,
    FRESH,
    STALE,
}

enum class DashboardHealthLevel {
    CONNECTING,
    IDLE, // Service is reachable and healthy; the engine has not started yet.
    HEALTHY,
    DEGRADED,
    ERROR,
}

enum class DashboardMessageTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

/**
 * Per-engine verification state shown on the dashboard's engine
 * verification card. Captures the most recent run for one engine
 * (chat / embeddings / OCR) so the three modalities are testable
 * independently and the UI can show a status pill + last-run output
 * for each.
 */
data class EngineTestState(
    val isRunning: Boolean = false,
    val status: String = "",
    val tone: DashboardMessageTone = DashboardMessageTone.NEUTRAL,
    val output: String = "",
    val lastCompletedAtMs: Long? = null,
)

data class AcceleratorDecisionUi(
    val featureName: String,
    val backend: String,
    val reason: String,
    val attemptedSummary: String,
)

data class RuntimeReadinessSummary(
    val headline: String,
    val detail: String,
    val pillLabel: String,
    val tone: DashboardMessageTone,
)

data class DashboardUiState(
    // Diagnostics
    val connectionState: DashboardConnectionState = DashboardConnectionState.CONNECTING,
    val isStatusLoading: Boolean = true,
    val isLogsLoading: Boolean = true,
    val lastStatusUpdateMs: Long? = null,
    val lastLogsUpdateMs: Long? = null,
    val statusErrorMessage: String? = null,
    val logsErrorMessage: String? = null,
    // Engine
    val isEngineLoaded: Boolean = false,
    val backend: String = "NONE",
    val gpuFailureReason: String? = null,
    val acceleratorDecision: AcceleratorDecisionUi? = null,
    val acceleratorDecisions: List<AcceleratorDecisionUi> = emptyList(),
    /**
     * F-077: typed structured signal for the most recent
     * [com.adsamcik.mindlayer.service.engine.EngineManager.initialize]
     * failure, sourced from the persisted `init_failure_categorized`
     * log row by [DashboardViewModel]. Replaces the opaque
     * [gpuFailureReason] string for variant-specific UI rendering;
     * [gpuFailureReason] is retained for backward compatibility with
     * the existing `engine_fallback` log query.
     *
     * Each variant maps to a specific message + remediation in
     * [DashboardScreen]'s status section. `null` means no init failure
     * has been observed since the last engine shutdown (or the engine
     * has never been initialised).
     */
    val lastInitFailure: com.adsamcik.mindlayer.service.engine.InitFailure? = null,
    val initTimeSeconds: Float = 0f,
    val uptimeMs: Long = 0,
    val modelId: String = "",
    // Thermal
    val thermalBand: String = "COOL",
    val headroom: Float? = null,
    /**
     * F-073: `false` on Android 8 / 8.1 (API 26-28) where no thermal API
     * exists and the service has switched to the conservative duty-cycle
     * variant of [ThermalPolicy]. Derived from the wire sentinel
     * [com.adsamcik.mindlayer.service.ServiceBinder.THERMAL_TELEMETRY_UNAVAILABLE]
     * by [DashboardViewModel] so the dashboard's thermal card can
     * surface the indicator. `true` whenever the wire reports a real
     * 4-band value (`COOL`/`WARM`/`HOT`/`CRITICAL`).
     */
    val thermalTelemetryAvailable: Boolean = true,
    /**
     * F-074: `true` when the `:ml` crash-loop watchdog is currently
     * refusing external binds. Derived from the
     * `MlHealthRecorder.shouldThrottleBinds()` peek the dashboard
     * polls on the same `filesDir/ml_health/abnormal_deaths.json`
     * the service writes to.
     */
    val serviceThrottled: Boolean = false,
    /**
     * F-074: seconds remaining before the throttle window expires.
     * Computed from `cooldownEndsAt - now`, clamped at zero. Always
     * `0` when [serviceThrottled] is `false`.
     */
    val throttleCooldownSecondsRemaining: Int = 0,
    /**
     * F-074: count of recent abnormal deaths recorded by the watchdog.
     * Surfaced in the throttle banner so the user can tell "first
     * crash" from "8 crashes in a row" at a glance.
     */
    val recentDeathCount: Int = 0,
    // Memory
    val memoryPressure: String = "NORMAL",
    val availableRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val maxSessions: Int = 0,
    // Sessions
    val activeSessions: List<SessionUiItem> = emptyList(),
    // Logs
    val recentLogs: List<LogUiItem> = emptyList(),
    // Test
    val testStatus: String = "",
    val testOutput: String = "",
    val isTestRunning: Boolean = false,
    val testStatusTone: DashboardMessageTone = DashboardMessageTone.NEUTRAL,
    val lastTestCompletedAtMs: Long? = null,
    /**
     * Embeddings verification state — independent of [isTestRunning]
     * (which models chat). Embeddings exercise the EmbeddingGemma engine
     * via [com.adsamcik.mindlayer.IMindlayerService.embed], which is
     * separate from the chat engine and can run concurrently.
     */
    val embeddingTest: EngineTestState = EngineTestState(),
    /**
     * OCR verification state — independent of chat + embeddings.
     * Exercises the PaddleOCR engine via the
     * [com.adsamcik.mindlayer.IMindlayerService.createOcrSession]
     * + ``pushOcrFrame`` + ``finalizeOcrSession`` session lifecycle.
     */
    val ocrTest: EngineTestState = EngineTestState(),
    /**
     * Snapshot of the on-disk OCR accelerator failure cache. Populated by
     * the dashboard ViewModel via a cross-process read of
     * `<filesDir>/ocr_accelerator/accelerator_failure.json` — written by
     * `:ml` whenever the GPU/NPU init falls back to CPU.
     *
     * `null` means the cache is empty (no recent failure). A non-null value
     * with `isInCooldown == true` (computed against `System.currentTimeMillis()`
     * in the Composable) drives the "GPU acceleration disabled until …" status
     * row + "Retry now" button under the OCR engine card.
     *
     * The data class itself is intentionally small + serialisable-friendly;
     * we do NOT bake locale-aware date formatting into the cache layer.
     */
    val ocrFailureSnapshot: OcrAcceleratorFailureCache.FailureRecord? = null,
    /**
     * Cooldown window in ms that the dashboard should apply when deciding
     * whether [ocrFailureSnapshot] is currently active. Matches the value
     * the service uses; surfacing it here keeps cache + UI in lockstep when
     * the constant is bumped.
     */
    val ocrFailureCooldownMs: Long = OcrAcceleratorFailureCache.DEFAULT_COOLDOWN_MS,
    /**
     * Image + Text (Gemma multimodal) verification state — independent of
     * [isTestRunning], [embeddingTest], and [ocrTest]. Exercises the LLM
     * engine via [com.adsamcik.mindlayer.IMindlayerService.infer] with an
     * [com.adsamcik.mindlayer.ImageTransfer] attachment so the dashboard can
     * confirm the multimodal AIDL path responds end-to-end.
     */
    val imageInferenceTest: EngineTestState = EngineTestState(),
    /**
     * SDK infer-async verification state — exercises the SDK facade's
     * [com.adsamcik.mindlayer.sdk.Mindlayer.inferAsync] single-shot path.
     */
    val sdkInferAsyncTest: EngineTestState = EngineTestState(),
    /**
     * SDK infer-realtime verification state — exercises the SDK facade's
     * [com.adsamcik.mindlayer.sdk.Mindlayer.inferRealtime] streaming path
     * and verifies that [com.adsamcik.mindlayer.sdk.InferenceEvent] events
     * are delivered correctly.
     */
    val sdkInferRealtimeTest: EngineTestState = EngineTestState(),
    /**
     * SDK generate-with-image verification state — exercises the SDK facade's
     * stateless [com.adsamcik.mindlayer.sdk.Mindlayer.generateWithImage] path
     * with the same fixture bitmap used by [imageInferenceTest].
     */
    val sdkGenerateWithImageTest: EngineTestState = EngineTestState(),
    /**
     * OCR + LLM extraction verification state — exercises the SDK facade's
     * [com.adsamcik.mindlayer.sdk.Mindlayer.ocrAsync] one-shot path with
     * [com.adsamcik.mindlayer.OcrImageOptions.runLlmExtraction] enabled.
     */
    val ocrLlmExtractionTest: EngineTestState = EngineTestState(),
) {
    fun statusFreshness(nowMs: Long = System.currentTimeMillis()): DashboardFreshness =
        freshnessOf(lastStatusUpdateMs, nowMs, STATUS_STALE_AFTER_MS)

    fun logsFreshness(nowMs: Long = System.currentTimeMillis()): DashboardFreshness =
        freshnessOf(lastLogsUpdateMs, nowMs, LOGS_STALE_AFTER_MS)

    fun testReadinessIssue(nowMs: Long = System.currentTimeMillis()): String? = when {
        isTestRunning -> "A test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for a live runtime status before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        isStatusLoading -> {
            "Runtime status is still loading. Wait for the first live sample before testing."
        }

        statusErrorMessage != null -> {
            "Status polling failed. Refresh status before running a test."
        }

        statusFreshness(nowMs) == DashboardFreshness.UNKNOWN -> {
            "No live runtime status yet. Refresh status and wait for readiness."
        }

        statusFreshness(nowMs) == DashboardFreshness.STALE -> {
            "Status is stale. Refresh before running a test."
        }

        else -> null
    }

    fun canRunTestInference(nowMs: Long = System.currentTimeMillis()): Boolean =
        testReadinessIssue(nowMs) == null

    /**
     * Returns the reason embedding verification can't run right now,
     * or null when the embedding ``Test`` button should be enabled.
     * Looser than [testReadinessIssue]: embeddings have their own engine
     * and don't require the chat engine to be warm or status to be fresh.
     */
    fun embeddingTestReadinessIssue(): String? = when {
        embeddingTest.isRunning -> "An embedding test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for the binder to attach before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        else -> null
    }

    fun canRunEmbeddingTest(): Boolean = embeddingTestReadinessIssue() == null

    /**
     * Returns the reason OCR verification can't run right now, or null
     * when the OCR ``Test`` button should be enabled. Same loose policy
     * as [embeddingTestReadinessIssue]: OCR has its own engine
     * (``PaddleOcrEngine``) which is independent of chat warmup.
     */
    fun ocrTestReadinessIssue(): String? = when {
        ocrTest.isRunning -> "An OCR test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for the binder to attach before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        else -> null
    }

    fun canRunOcrTest(): Boolean = ocrTestReadinessIssue() == null

    /**
     * Returns the reason image inference verification can't run right now,
     * or null when the image inference ``Test`` button should be enabled.
     * Uses the same loose policy as [ocrTestReadinessIssue]: the test
     * issues its own prewarm and does not require a prior status sample.
     */
    fun imageInferenceTestReadinessIssue(): String? = when {
        imageInferenceTest.isRunning -> "An image inference test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for the binder to attach before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        else -> null
    }

    fun canRunImageInferenceTest(): Boolean = imageInferenceTestReadinessIssue() == null

    fun sdkInferAsyncTestReadinessIssue(): String? = when {
        sdkInferAsyncTest.isRunning -> "An SDK infer-async test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for the binder to attach before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        else -> null
    }

    fun canRunSdkInferAsyncTest(): Boolean = sdkInferAsyncTestReadinessIssue() == null

    fun sdkInferRealtimeTestReadinessIssue(): String? = when {
        sdkInferRealtimeTest.isRunning -> "An SDK infer-realtime test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for the binder to attach before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        else -> null
    }

    fun canRunSdkInferRealtimeTest(): Boolean = sdkInferRealtimeTestReadinessIssue() == null

    fun sdkGenerateWithImageTestReadinessIssue(): String? = when {
        sdkGenerateWithImageTest.isRunning -> "An SDK generate-with-image test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for the binder to attach before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        else -> null
    }

    fun canRunSdkGenerateWithImageTest(): Boolean = sdkGenerateWithImageTestReadinessIssue() == null

    fun ocrLlmExtractionTestReadinessIssue(): String? = when {
        ocrLlmExtractionTest.isRunning -> "An OCR + LLM extraction test is already running."
        connectionState == DashboardConnectionState.CONNECTING -> {
            "Service is connecting. Wait for the binder to attach before testing."
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> {
            "Reconnect the service before running a test."
        }

        else -> null
    }

    fun canRunOcrLlmExtractionTest(): Boolean = ocrLlmExtractionTestReadinessIssue() == null

    /**
     * Whether the "Verify all engines" button on the welcome card
     * should be enabled. Disabled while ANY of the four engines is
     * already running a test (so the orchestrator's sequencing isn't
     * fighting a manual single-engine run).
     */
    fun canRunAllVerifications(): Boolean =
        !isTestRunning && !embeddingTest.isRunning && !ocrTest.isRunning &&
            !imageInferenceTest.isRunning && !sdkInferAsyncTest.isRunning &&
            !sdkInferRealtimeTest.isRunning && !sdkGenerateWithImageTest.isRunning &&
            !ocrLlmExtractionTest.isRunning &&
            connectionState == DashboardConnectionState.CONNECTED

    val isAnyTestRunning: Boolean
        get() = isTestRunning || embeddingTest.isRunning || ocrTest.isRunning ||
            imageInferenceTest.isRunning || sdkInferAsyncTest.isRunning ||
            sdkInferRealtimeTest.isRunning || sdkGenerateWithImageTest.isRunning ||
            ocrLlmExtractionTest.isRunning

    /**
     * Summarises the verification state across all four engines for
     * the dashboard's welcome card pill / colour. Returns a tone +
     * a string-resource id the UI can resolve to localised copy.
     */
    fun verifyAllSummaryTone(): DashboardMessageTone {
        if (isAnyTestRunning) return DashboardMessageTone.INFO
        val toneOf = { test: EngineTestState -> test.tone.takeIf { test.lastCompletedAtMs != null } }
        val chatTone = if (lastTestCompletedAtMs != null) testStatusTone else null
        val tones = listOfNotNull(chatTone, toneOf(embeddingTest), toneOf(ocrTest), toneOf(imageInferenceTest),
            toneOf(sdkInferAsyncTest), toneOf(sdkInferRealtimeTest),
            toneOf(sdkGenerateWithImageTest), toneOf(ocrLlmExtractionTest))
        if (tones.isEmpty()) return DashboardMessageTone.NEUTRAL
        return when {
            tones.any { it == DashboardMessageTone.ERROR } -> DashboardMessageTone.ERROR
            tones.any { it == DashboardMessageTone.WARNING } -> DashboardMessageTone.WARNING
            tones.all { it == DashboardMessageTone.SUCCESS } -> DashboardMessageTone.SUCCESS
            else -> DashboardMessageTone.NEUTRAL
        }
    }

    fun serviceHealth(nowMs: Long = System.currentTimeMillis()): DashboardHealthLevel = when {
        connectionState == DashboardConnectionState.CONNECTING || isStatusLoading -> {
            DashboardHealthLevel.CONNECTING
        }

        connectionState == DashboardConnectionState.DISCONNECTED -> DashboardHealthLevel.ERROR
        // F-074: a live throttle is a louder signal than memory/thermal
        // pressure — surface it as ERROR so the headline reflects it.
        serviceThrottled -> DashboardHealthLevel.ERROR
        statusErrorMessage != null -> DashboardHealthLevel.ERROR
        statusFreshness(nowMs) == DashboardFreshness.STALE -> DashboardHealthLevel.DEGRADED
        lastInitFailure != null -> DashboardHealthLevel.DEGRADED
        thermalBand.equals("CRITICAL", ignoreCase = true) ||
            memoryPressure.equals("EMERGENCY", ignoreCase = true) -> DashboardHealthLevel.ERROR

        thermalBand.equals("HOT", ignoreCase = true) ||
            memoryPressure.equals("CRITICAL", ignoreCase = true) -> DashboardHealthLevel.DEGRADED

        !isEngineLoaded || backend.equals("NONE", ignoreCase = true) -> DashboardHealthLevel.IDLE
        else -> DashboardHealthLevel.HEALTHY
    }

    fun runtimeReadiness(nowMs: Long = System.currentTimeMillis()): RuntimeReadinessSummary {
        val freshness = statusFreshness(nowMs)
        return when {
            connectionState == DashboardConnectionState.DISCONNECTED -> {
                RuntimeReadinessSummary(
                    headline = "Reconnect required",
                    detail = lastStatusUpdateMs?.let {
                        "Refresh status to restore the service connection. " +
                            "Last good status sample ${formatRelativeTimestamp(it, nowMs)}."
                    } ?: "Refresh status to connect to the service before testing.",
                    pillLabel = "RECONNECT",
                    tone = DashboardMessageTone.ERROR,
                )
            }

            connectionState == DashboardConnectionState.CONNECTING -> {
                RuntimeReadinessSummary(
                    headline = "Connecting to service",
                    detail = "Wait for a live runtime status before testing.",
                    pillLabel = "CONNECTING",
                    tone = DashboardMessageTone.INFO,
                )
            }

            statusErrorMessage != null -> {
                RuntimeReadinessSummary(
                    headline = "Status polling failed",
                    detail = "Refresh status. If polling keeps failing, open System Logs.",
                    pillLabel = "CHECK LOGS",
                    tone = DashboardMessageTone.ERROR,
                )
            }

            isStatusLoading -> {
                RuntimeReadinessSummary(
                    headline = "Waiting for runtime status",
                    detail = "Connected, but the first live runtime status sample is still loading.",
                    pillLabel = "WAITING",
                    tone = DashboardMessageTone.INFO,
                )
            }

            freshness == DashboardFreshness.UNKNOWN -> {
                RuntimeReadinessSummary(
                    headline = "Waiting for runtime status",
                    detail = "Refresh status and wait for a live runtime sample before testing.",
                    pillLabel = "WAITING",
                    tone = DashboardMessageTone.INFO,
                )
            }

            freshness == DashboardFreshness.STALE -> {
                RuntimeReadinessSummary(
                    headline = "Status stale — refresh required",
                    detail = lastStatusUpdateMs?.let {
                        "Refresh status before trusting runtime values or running a test. " +
                            "Last successful sample ${formatRelativeTimestamp(it, nowMs)}."
                    } ?: "Refresh status before trusting runtime values or running a test.",
                    pillLabel = "STALE",
                    tone = DashboardMessageTone.WARNING,
                )
            }

            !isEngineLoaded || backend.equals("NONE", ignoreCase = true) -> {
                RuntimeReadinessSummary(
                    headline = "Engine idle",
                    detail = "The engine loads on first use. Tap Run test inference to load it now.",
                    pillLabel = "IDLE",
                    tone = DashboardMessageTone.INFO,
                )
            }

            thermalBand.equals("CRITICAL", ignoreCase = true) ||
                memoryPressure.equals("EMERGENCY", ignoreCase = true) -> {
                RuntimeReadinessSummary(
                    headline = "Runtime guard active",
                    detail = "Runtime is available, but thermal or memory pressure is critical.",
                    pillLabel = "ATTENTION",
                    tone = DashboardMessageTone.ERROR,
                )
            }

            thermalBand.equals("HOT", ignoreCase = true) ||
                memoryPressure.equals("CRITICAL", ignoreCase = true) -> {
                RuntimeReadinessSummary(
                    headline = "Runtime degraded",
                    detail = "Runtime is available, but thermal or memory pressure may affect test results.",
                    pillLabel = "DEGRADED",
                    tone = DashboardMessageTone.WARNING,
                )
            }

            else -> {
                RuntimeReadinessSummary(
                    headline = "Ready to test",
                    detail = "Runtime is connected, model loaded, and ${backend.uppercase()} backend is active.",
                    pillLabel = "READY",
                    tone = DashboardMessageTone.SUCCESS,
                )
            }
        }
    }

    fun shouldHighlightTestResult(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isTestRunning || testStatusTone != DashboardMessageTone.SUCCESS) return false

        return connectionState != DashboardConnectionState.CONNECTED ||
            !isEngineLoaded ||
            backend.equals("NONE", ignoreCase = true) ||
            statusFreshness(nowMs) == DashboardFreshness.STALE
    }
}

internal fun formatRelativeTimestamp(
    timestampMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val diff = (nowMs - timestampMs).coerceAtLeast(0L)
    return when {
        diff < 1_000L -> "just now"
        diff < 60_000L -> "${diff / 1_000L}s ago"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> "${diff / 86_400_000L}d ago"
    }
}

private fun freshnessOf(
    timestampMs: Long?,
    nowMs: Long,
    staleAfterMs: Long,
): DashboardFreshness = when {
    timestampMs == null -> DashboardFreshness.UNKNOWN
    nowMs - timestampMs > staleAfterMs -> DashboardFreshness.STALE
    else -> DashboardFreshness.FRESH
}

data class SessionUiItem(
    val sessionId: String,
    val backend: String,
    val tokenCount: Int,
    val maxTokens: Int,
    val isStreaming: Boolean,
    val lastAccessedLabel: String,
)

data class LogUiItem(
    val timestampLabel: String,
    val category: String,
    val event: String,
    val detail: String,
)

// ── Models page (Status / Models / Tests UI split) ──────────────────────────

/**
 * Per-role view-model summary backing the Models page card list. Pure data,
 * with no Compose or Context dependencies — derived by
 * [DashboardUiState.modelSummaries] from existing state fields plus the
 * per-engine test result so the Models tab can render LOADED / IDLE /
 * UNKNOWN / FAILED without re-querying the service.
 */
data class RoleModelSummary(
    val role: ModelRole,
    val modelDisplayName: String,
    val packDescription: String,
    val state: ModelLoadState,
    val backend: String?,
    val initTimeSeconds: Float?,
    val failureDetail: String?,
)

enum class ModelRole { CHAT_AND_VISION, EMBEDDINGS, OCR }

enum class ModelLoadState { UNKNOWN, IDLE, LOADED, FAILED }

private const val MODEL_PACK_CHAT = "Install-time AI Pack: gemma_model"
private const val MODEL_PACK_EMBEDDINGS = "Install-time AI Pack: gemma_embed_model"
private const val MODEL_PACK_OCR = "Install-time AI Pack: paddleocr_model"
private const val MODEL_DISPLAY_CHAT_DEFAULT = "Gemma 4 E2B"
private const val MODEL_DISPLAY_EMBEDDINGS = "EmbeddingGemma"
private const val MODEL_DISPLAY_OCR = "PaddleOCR PP-OCRv5 mobile"

/**
 * Pure derivation of the three role cards rendered by `ModelsScreen`.
 * Does NOT touch Compose or Context — safe to unit-test.
 *
 *  - Chat & vision: derived from [isEngineLoaded] + [backend] + [modelId]
 *    + [lastInitFailure] + [initTimeSeconds]. State LOADED when the engine
 *    is loaded; FAILED when an init failure is recorded; IDLE otherwise.
 *  - Embeddings: state is UNKNOWN until the embedding test has been
 *    exercised at least once (no failure pathway is observable through
 *    [DashboardUiState] today; SUCCESS-toned completed test → LOADED;
 *    ERROR-toned → FAILED; otherwise IDLE).
 *  - OCR: same shape as embeddings, plus an explicit FAILED if the test
 *    tone is ERROR.
 */
fun DashboardUiState.modelSummaries(): List<RoleModelSummary> {
    val chatState = when {
        lastInitFailure != null -> ModelLoadState.FAILED
        isEngineLoaded -> ModelLoadState.LOADED
        else -> ModelLoadState.IDLE
    }
    val chatBackend = backend.takeIf { it.isNotBlank() && !it.equals("NONE", ignoreCase = true) }
    val chatFailureDetail = lastInitFailure?.let { failure ->
        // Reuse the same human-readable mapping used by the Status page,
        // but only emit the text — the tone is implied by ModelLoadState.
        // Mirrors describeInitFailure(...) without taking a Compose dep
        // here; we accept slight string duplication to keep this file
        // free of UI dependencies for tests.
        when (failure) {
            com.adsamcik.mindlayer.service.engine.InitFailure.LowMemory ->
                "Engine init refused: insufficient memory. Free up memory and retry."
            com.adsamcik.mindlayer.service.engine.InitFailure.ModelMissing ->
                "Model file missing — install the AI Pack."
            com.adsamcik.mindlayer.service.engine.InitFailure.IntegrityMismatch ->
                "Model file corrupted — reinstall."
            is com.adsamcik.mindlayer.service.engine.InitFailure.BackendUnavailable -> {
                val recovered = backend.isNotBlank() &&
                    !backend.equals("NONE", ignoreCase = true) &&
                    !backend.equals(failure.backend, ignoreCase = true)
                if (recovered) {
                    "${failure.backend} backend failed (${failure.safeLabel}) — running on $backend."
                } else {
                    "${failure.backend} backend failed (${failure.safeLabel})."
                }
            }
            is com.adsamcik.mindlayer.service.engine.InitFailure.NativeError ->
                "Native runtime error (${failure.safeLabel})."
        }
    }
    val chatDisplay = modelId.takeIf { it.isNotBlank() }
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?: MODEL_DISPLAY_CHAT_DEFAULT
    val chat = RoleModelSummary(
        role = ModelRole.CHAT_AND_VISION,
        modelDisplayName = chatDisplay,
        packDescription = MODEL_PACK_CHAT,
        state = chatState,
        backend = chatBackend,
        initTimeSeconds = initTimeSeconds.takeIf { it > 0f },
        failureDetail = chatFailureDetail,
    )

    val embeddings = RoleModelSummary(
        role = ModelRole.EMBEDDINGS,
        modelDisplayName = MODEL_DISPLAY_EMBEDDINGS,
        packDescription = MODEL_PACK_EMBEDDINGS,
        state = derivedTestState(embeddingTest),
        backend = null,
        initTimeSeconds = null,
        failureDetail = embeddingTest.status.takeIf {
            it.isNotBlank() && embeddingTest.tone == DashboardMessageTone.ERROR
        },
    )

    val ocr = RoleModelSummary(
        role = ModelRole.OCR,
        modelDisplayName = MODEL_DISPLAY_OCR,
        packDescription = MODEL_PACK_OCR,
        state = derivedTestState(ocrTest),
        backend = null,
        initTimeSeconds = null,
        failureDetail = ocrTest.status.takeIf {
            it.isNotBlank() && ocrTest.tone == DashboardMessageTone.ERROR
        },
    )

    return listOf(chat, embeddings, ocr)
}

private fun derivedTestState(test: EngineTestState): ModelLoadState = when {
    test.isRunning -> ModelLoadState.UNKNOWN
    test.lastCompletedAtMs == null -> ModelLoadState.UNKNOWN
    test.tone == DashboardMessageTone.SUCCESS -> ModelLoadState.LOADED
    test.tone == DashboardMessageTone.ERROR -> ModelLoadState.FAILED
    else -> ModelLoadState.IDLE
}
