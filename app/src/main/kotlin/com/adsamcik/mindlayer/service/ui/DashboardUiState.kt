package com.adsamcik.mindlayer.service.ui

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

        !isEngineLoaded -> "Connected, but the model is not loaded yet."
        backend.equals("NONE", ignoreCase = true) -> {
            "Connected, but no GPU, CPU, or NPU backend is active yet."
        }

        else -> null
    }

    fun canRunTestInference(nowMs: Long = System.currentTimeMillis()): Boolean =
        testReadinessIssue(nowMs) == null

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
        !isEngineLoaded || backend.equals("NONE", ignoreCase = true) -> DashboardHealthLevel.DEGRADED
        thermalBand.equals("CRITICAL", ignoreCase = true) ||
            memoryPressure.equals("EMERGENCY", ignoreCase = true) -> DashboardHealthLevel.ERROR

        thermalBand.equals("HOT", ignoreCase = true) ||
            memoryPressure.equals("CRITICAL", ignoreCase = true) -> DashboardHealthLevel.DEGRADED

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

            !isEngineLoaded -> {
                RuntimeReadinessSummary(
                    headline = "Waiting for model load",
                    detail = "Connected, but the model is not loaded yet. " +
                        "Wait for runtime initialization to finish.",
                    pillLabel = "WAITING",
                    tone = DashboardMessageTone.WARNING,
                )
            }

            backend.equals("NONE", ignoreCase = true) -> {
                RuntimeReadinessSummary(
                    headline = "Waiting for backend",
                    detail = "Connected, but no GPU, CPU, or NPU backend is active yet.",
                    pillLabel = "WAITING",
                    tone = DashboardMessageTone.WARNING,
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
