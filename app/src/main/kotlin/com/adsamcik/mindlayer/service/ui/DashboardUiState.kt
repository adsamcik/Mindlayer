package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.engine.OcrAcceleratorFailureCache
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryCatalog
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryRefreshState
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState

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
    /** Independent Play delivery state; this is intentionally separate from
     * runtime load state because downloaded bytes are not necessarily loaded. */
    val modelDelivery: Map<ModelRole, ModelDeliveryState> = emptyMap(),
    val modelDeliveryRefresh: ModelDeliveryRefreshState = ModelDeliveryRefreshState(),
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
 * [DashboardUiState.modelSummaries] from delivery state plus either live
 * service evidence (chat) or the latest dashboard verification (embeddings/OCR).
 * [lastRuntimeStatusAtMs] timestamps retained service evidence only; dashboard
 * verification roles use [lastVerificationAtMs] instead.
 */
data class RoleModelSummary(
    val role: ModelRole,
    val modelDisplayName: String,
    val deliveryPackNames: List<String>,
    val state: ModelLoadState,
    val evidence: ModelRuntimeEvidence,
    val readiness: ModelReadiness,
    val backend: String?,
    val initTimeSeconds: Float?,
    val runtimeIssue: ModelRuntimeIssue?,
    val lastRuntimeStatusAtMs: Long?,
    val lastVerificationAtMs: Long?,
    val lastVerificationPassed: Boolean?,
    val deliveryState: ModelDeliveryState,
)

enum class ModelRole { CHAT_AND_VISION, EMBEDDINGS, OCR }

enum class ModelLoadState { NOT_VERIFIED, IDLE, STARTING, READY, FAILED }

enum class ModelRuntimeEvidence {
    LIVE_SERVICE,
    LAST_KNOWN_SERVICE,
    SERVICE_STATUS_UNAVAILABLE,
    DASHBOARD_VERIFICATION,
}

sealed interface ModelRuntimeIssue {
    data class InitializationFailed(
        val failure: com.adsamcik.mindlayer.service.engine.InitFailure,
    ) : ModelRuntimeIssue

    data object VerificationFailed : ModelRuntimeIssue
}

enum class ModelReadiness {
    CHECKING,
    DOWNLOAD_REQUIRED,
    WAITING,
    DOWNLOADING,
    PREPARING,
    DOWNLOADED_IDLE,
    STARTING,
    READY,
    NEEDS_ATTENTION,
    REMOVING,
    UNAVAILABLE,
}

enum class ModelDeliveryAction {
    NONE,
    DOWNLOAD,
    RETRY_DOWNLOAD,
    RETRY_ACTIVATION,
    CONFIRM,
    REMOVE,
    RETRY_REMOVE,
}

fun modelDeliveryAction(
    state: ModelDeliveryState,
): ModelDeliveryAction = when (state) {
    ModelDeliveryState.NotInstalled -> ModelDeliveryAction.DOWNLOAD
    is ModelDeliveryState.Failed -> when (state.issue) {
        ModelDeliveryIssue.ConfirmationUnavailable -> ModelDeliveryAction.CONFIRM
        else -> ModelDeliveryAction.RETRY_DOWNLOAD
    }
    is ModelDeliveryState.RemovalFailed -> ModelDeliveryAction.RETRY_REMOVE
    ModelDeliveryState.RequiresConfirmation -> ModelDeliveryAction.CONFIRM
    ModelDeliveryState.Installed -> ModelDeliveryAction.REMOVE
    ModelDeliveryState.InstalledWithActivationError -> ModelDeliveryAction.RETRY_ACTIVATION
    else -> ModelDeliveryAction.NONE
}

fun modelReadiness(
    role: ModelRole,
    deliveryState: ModelDeliveryState,
    runtimeState: ModelLoadState,
    evidence: ModelRuntimeEvidence,
): ModelReadiness = when (deliveryState) {
    ModelDeliveryState.Checking -> ModelReadiness.CHECKING
    ModelDeliveryState.NotInstalled -> ModelReadiness.DOWNLOAD_REQUIRED
    ModelDeliveryState.Pending,
    ModelDeliveryState.WaitingForWifi,
    ModelDeliveryState.RequiresConfirmation,
    -> ModelReadiness.WAITING
    is ModelDeliveryState.Downloading -> ModelReadiness.DOWNLOADING
    ModelDeliveryState.Transferring,
    ModelDeliveryState.Provisioning,
    ModelDeliveryState.Activating,
    -> ModelReadiness.PREPARING
    ModelDeliveryState.Removing,
    ModelDeliveryState.Quiescing,
    -> ModelReadiness.REMOVING
    is ModelDeliveryState.Failed,
    is ModelDeliveryState.RemovalFailed,
    ModelDeliveryState.InstalledWithActivationError,
    -> ModelReadiness.NEEDS_ATTENTION
    ModelDeliveryState.Unsupported -> ModelReadiness.UNAVAILABLE
    ModelDeliveryState.Installed -> when {
        runtimeState == ModelLoadState.FAILED &&
            evidence != ModelRuntimeEvidence.LAST_KNOWN_SERVICE ->
            ModelReadiness.NEEDS_ATTENTION
        runtimeState == ModelLoadState.STARTING &&
            evidence != ModelRuntimeEvidence.LAST_KNOWN_SERVICE ->
            ModelReadiness.STARTING
        role == ModelRole.CHAT_AND_VISION &&
            runtimeState == ModelLoadState.READY &&
            evidence == ModelRuntimeEvidence.LIVE_SERVICE -> ModelReadiness.READY
        else -> ModelReadiness.DOWNLOADED_IDLE
    }
}

internal fun ModelRole.toModelFamily(): com.adsamcik.mindlayer.service.modeldelivery.ModelFamily = when (this) {
    ModelRole.CHAT_AND_VISION -> com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.CHAT
    ModelRole.EMBEDDINGS -> com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.EMBEDDINGS
    ModelRole.OCR -> com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.OCR
}

private const val MODEL_DISPLAY_CHAT_DEFAULT = "Gemma 4 E2B"
private const val MODEL_DISPLAY_EMBEDDINGS = "EmbeddingGemma"
private const val MODEL_DISPLAY_OCR = "PaddleOCR PP-OCRv5 mobile"

/**
 * Pure derivation of the three role cards rendered by `ModelsScreen`.
 * Does NOT touch Compose or Context — safe to unit-test.
 *
 * Chat only uses live service runtime state when the latest successful status
 * sample is current. Otherwise it retains the last sampled state and metadata
 * without claiming current READY. Embeddings and OCR only have historical
 * dashboard verification evidence, so a successful test never claims READY.
 */
fun DashboardUiState.modelSummaries(
    nowMs: Long = System.currentTimeMillis(),
): List<RoleModelSummary> {
    val hasLiveChatEvidence =
        connectionState == DashboardConnectionState.CONNECTED &&
            !isStatusLoading &&
            statusErrorMessage == null &&
            lastStatusUpdateMs != null &&
            statusFreshness(nowMs) == DashboardFreshness.FRESH
    val chatEvidence = when {
        hasLiveChatEvidence -> ModelRuntimeEvidence.LIVE_SERVICE
        lastStatusUpdateMs != null -> ModelRuntimeEvidence.LAST_KNOWN_SERVICE
        else -> ModelRuntimeEvidence.SERVICE_STATUS_UNAVAILABLE
    }
    val chatBackend = backend.takeIf { it.isNotBlank() && !it.equals("NONE", ignoreCase = true) }
    val chatState = when {
        lastStatusUpdateMs == null -> ModelLoadState.NOT_VERIFIED
        isEngineLoaded && chatBackend != null -> ModelLoadState.READY
        lastInitFailure != null -> ModelLoadState.FAILED
        else -> ModelLoadState.IDLE
    }
    val chatIssue = lastInitFailure
        ?.takeIf { chatState == ModelLoadState.FAILED }
        ?.let(ModelRuntimeIssue::InitializationFailed)
    val chatDisplay = modelId.takeIf { it.isNotBlank() }
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?: MODEL_DISPLAY_CHAT_DEFAULT
    val chatDelivery = modelDelivery[ModelRole.CHAT_AND_VISION] ?: ModelDeliveryState.Checking
    val chat = RoleModelSummary(
        role = ModelRole.CHAT_AND_VISION,
        modelDisplayName = chatDisplay,
        deliveryPackNames = ModelDeliveryCatalog.family(ModelRole.CHAT_AND_VISION.toModelFamily()).packNames,
        state = chatState,
        evidence = chatEvidence,
        readiness = modelReadiness(
            ModelRole.CHAT_AND_VISION,
            chatDelivery,
            chatState,
            chatEvidence,
        ),
        backend = chatBackend,
        initTimeSeconds = initTimeSeconds.takeIf { it > 0f },
        runtimeIssue = chatIssue,
        lastRuntimeStatusAtMs = lastStatusUpdateMs,
        lastVerificationAtMs = null,
        lastVerificationPassed = null,
        deliveryState = chatDelivery,
    )

    val embeddingsRuntime = derivedTestRuntime(embeddingTest)
    val embeddingsDelivery = modelDelivery[ModelRole.EMBEDDINGS] ?: ModelDeliveryState.Checking
    val embeddings = RoleModelSummary(
        role = ModelRole.EMBEDDINGS,
        modelDisplayName = MODEL_DISPLAY_EMBEDDINGS,
        deliveryPackNames = ModelDeliveryCatalog.family(ModelRole.EMBEDDINGS.toModelFamily()).packNames,
        state = embeddingsRuntime.state,
        evidence = ModelRuntimeEvidence.DASHBOARD_VERIFICATION,
        readiness = modelReadiness(
            ModelRole.EMBEDDINGS,
            embeddingsDelivery,
            embeddingsRuntime.state,
            ModelRuntimeEvidence.DASHBOARD_VERIFICATION,
        ),
        backend = null,
        initTimeSeconds = null,
        runtimeIssue = embeddingsRuntime.issue,
        lastRuntimeStatusAtMs = null,
        lastVerificationAtMs = embeddingTest.lastCompletedAtMs,
        lastVerificationPassed = embeddingsRuntime.passed,
        deliveryState = embeddingsDelivery,
    )

    val ocrRuntime = derivedTestRuntime(ocrTest)
    val ocrDelivery = modelDelivery[ModelRole.OCR] ?: ModelDeliveryState.Checking
    val ocr = RoleModelSummary(
        role = ModelRole.OCR,
        modelDisplayName = MODEL_DISPLAY_OCR,
        deliveryPackNames = ModelDeliveryCatalog.family(ModelRole.OCR.toModelFamily()).packNames,
        state = ocrRuntime.state,
        evidence = ModelRuntimeEvidence.DASHBOARD_VERIFICATION,
        readiness = modelReadiness(
            ModelRole.OCR,
            ocrDelivery,
            ocrRuntime.state,
            ModelRuntimeEvidence.DASHBOARD_VERIFICATION,
        ),
        backend = null,
        initTimeSeconds = null,
        runtimeIssue = ocrRuntime.issue,
        lastRuntimeStatusAtMs = null,
        lastVerificationAtMs = ocrTest.lastCompletedAtMs,
        lastVerificationPassed = ocrRuntime.passed,
        deliveryState = ocrDelivery,
    )

    return listOf(chat, embeddings, ocr)
}

private data class DerivedTestRuntime(
    val state: ModelLoadState,
    val issue: ModelRuntimeIssue?,
    val passed: Boolean?,
)

private fun derivedTestRuntime(test: EngineTestState): DerivedTestRuntime = when {
    test.isRunning -> DerivedTestRuntime(ModelLoadState.STARTING, issue = null, passed = null)
    test.lastCompletedAtMs == null ->
        DerivedTestRuntime(ModelLoadState.NOT_VERIFIED, issue = null, passed = null)
    test.tone == DashboardMessageTone.SUCCESS ->
        DerivedTestRuntime(ModelLoadState.IDLE, issue = null, passed = true)
    test.tone == DashboardMessageTone.ERROR ->
        DerivedTestRuntime(
            ModelLoadState.FAILED,
            issue = ModelRuntimeIssue.VerificationFailed,
            passed = false,
        )
    else -> DerivedTestRuntime(ModelLoadState.IDLE, issue = null, passed = null)
}
