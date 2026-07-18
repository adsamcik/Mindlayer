package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardUiStateTest {

    @Test
    fun `default DashboardUiState exposes loading diagnostics state`() {
        val state = DashboardUiState()

        assertEquals(DashboardConnectionState.CONNECTING, state.connectionState)
        assertTrue(state.isStatusLoading)
        assertTrue(state.isLogsLoading)
        assertNull(state.lastStatusUpdateMs)
        assertNull(state.lastLogsUpdateMs)
        assertNull(state.statusErrorMessage)
        assertNull(state.logsErrorMessage)
        assertEquals("NONE", state.backend)
        assertNull(state.gpuFailureReason)
        assertEquals(DashboardMessageTone.NEUTRAL, state.testStatusTone)
        assertNull(state.lastTestCompletedAtMs)
        assertTrue(state.activeSessions.isEmpty())
        assertTrue(state.recentLogs.isEmpty())
    }

    @Test
    fun `status freshness becomes stale after threshold`() {
        val nowMs = 20_000L
        val freshState = DashboardUiState(lastStatusUpdateMs = nowMs - 2_000)
        val staleState = DashboardUiState(lastStatusUpdateMs = nowMs - 7_000)

        assertEquals(DashboardFreshness.FRESH, freshState.statusFreshness(nowMs))
        assertEquals(DashboardFreshness.STALE, staleState.statusFreshness(nowMs))
    }

    @Test
    fun `stale status keeps chat metadata as last known without claiming ready`() {
        val nowMs = 20_000L
        val chat = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 7_000L,
            isEngineLoaded = true,
            backend = "GPU",
            modelId = "models/gemma",
            modelDelivery = mapOf(
                ModelRole.CHAT_AND_VISION to ModelDeliveryState.Installed,
            ),
        ).modelSummaries(nowMs).single { it.role == ModelRole.CHAT_AND_VISION }

        assertEquals(ModelRuntimeEvidence.LAST_KNOWN_SERVICE, chat.evidence)
        assertEquals(ModelLoadState.IDLE, chat.state)
        assertEquals(ModelReadiness.DOWNLOADED_IDLE, chat.readiness)
        assertEquals("GPU", chat.backend)
        assertEquals("gemma", chat.modelDisplayName)
    }

    @Test
    fun `logs freshness becomes stale after threshold`() {
        val nowMs = 20_000L
        val freshState = DashboardUiState(lastLogsUpdateMs = nowMs - 5_000)
        val staleState = DashboardUiState(lastLogsUpdateMs = nowMs - 13_000)

        assertEquals(DashboardFreshness.FRESH, freshState.logsFreshness(nowMs))
        assertEquals(DashboardFreshness.STALE, staleState.logsFreshness(nowMs))
    }

    @Test
    fun `service health reflects connection and engine readiness`() {
        val nowMs = 20_000L

        assertEquals(
            DashboardHealthLevel.CONNECTING,
            DashboardUiState().serviceHealth(nowMs),
        )

        assertEquals(
            DashboardHealthLevel.ERROR,
            DashboardUiState(
                connectionState = DashboardConnectionState.DISCONNECTED,
                isStatusLoading = false,
            ).serviceHealth(nowMs),
        )

        assertEquals(
            DashboardHealthLevel.IDLE,
            DashboardUiState(
                connectionState = DashboardConnectionState.CONNECTED,
                isStatusLoading = false,
                lastStatusUpdateMs = nowMs - 1_000,
                backend = "NONE",
            ).serviceHealth(nowMs),
        )

        assertEquals(
            DashboardHealthLevel.HEALTHY,
            DashboardUiState(
                connectionState = DashboardConnectionState.CONNECTED,
                isStatusLoading = false,
                lastStatusUpdateMs = nowMs - 1_000,
                isEngineLoaded = true,
                backend = "GPU",
            ).serviceHealth(nowMs),
        )
    }

    @Test
    fun `runtime readiness summary gives one primary healthy answer`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
        )

        val readiness = state.runtimeReadiness(nowMs)

        assertEquals("Ready to test", readiness.headline)
        assertEquals("Runtime is connected, model loaded, and GPU backend is active.", readiness.detail)
        assertEquals("READY", readiness.pillLabel)
        assertEquals(DashboardMessageTone.SUCCESS, readiness.tone)
    }

    @Test
    fun serviceHealth_engineNotLoadedButOtherwiseHealthy_returnsIdle() {
        val nowMs = 20_000L

        val health = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = false,
            backend = "GPU",
        ).serviceHealth(nowMs)

        assertEquals(DashboardHealthLevel.IDLE, health)
    }

    @Test
    fun serviceHealth_engineNotLoadedAndStaleStatus_returnsDegraded() {
        val nowMs = 20_000L

        val health = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 7_000,
            isEngineLoaded = false,
            backend = "GPU",
        ).serviceHealth(nowMs)

        assertEquals(DashboardHealthLevel.DEGRADED, health)
    }

    @Test
    fun serviceHealth_engineNotLoadedAndInitFailure_returnsDegraded() {
        val nowMs = 20_000L

        val health = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = false,
            backend = "GPU",
            lastInitFailure = InitFailure.ModelMissing,
        ).serviceHealth(nowMs)

        assertEquals(DashboardHealthLevel.DEGRADED, health)
    }

    @Test
    fun runtimeReadiness_engineNotLoaded_returnsIdleSummary() {
        val nowMs = 20_000L

        val readiness = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = false,
            backend = "GPU",
        ).runtimeReadiness(nowMs)

        assertEquals("Engine idle", readiness.headline)
        assertEquals("IDLE", readiness.pillLabel)
        assertEquals(DashboardMessageTone.INFO, readiness.tone)
    }

    @Test
    fun `runtime readiness summary explains blocked states in plain language`() {
        val nowMs = 20_000L

        val disconnected = DashboardUiState(
            connectionState = DashboardConnectionState.DISCONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
        ).runtimeReadiness(nowMs)
        assertEquals("Reconnect required", disconnected.headline)
        assertEquals(
            "Refresh status to restore the service connection. Last good status sample 1s ago.",
            disconnected.detail,
        )
        assertEquals("RECONNECT", disconnected.pillLabel)
        assertEquals(DashboardMessageTone.ERROR, disconnected.tone)

        val stale = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 7_000,
            isEngineLoaded = true,
            backend = "GPU",
        ).runtimeReadiness(nowMs)
        assertEquals("Status stale — refresh required", stale.headline)
        assertEquals(
            "Refresh status before trusting runtime values or running a test. Last successful sample 7s ago.",
            stale.detail,
        )
        assertEquals("STALE", stale.pillLabel)
        assertEquals(DashboardMessageTone.WARNING, stale.tone)

        val pollingFailed = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            statusErrorMessage = "Status polling failed: timeout",
        ).runtimeReadiness(nowMs)
        assertEquals("Status polling failed", pollingFailed.headline)
        assertEquals(
            "Refresh status. If polling keeps failing, open System Logs.",
            pollingFailed.detail,
        )
        assertEquals("CHECK LOGS", pollingFailed.pillLabel)
        assertEquals(DashboardMessageTone.ERROR, pollingFailed.tone)

        val modelLoading = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = false,
            backend = "GPU",
        ).runtimeReadiness(nowMs)
        assertEquals("Engine idle", modelLoading.headline)
        assertEquals(
            "The engine loads on first use. Tap Run test inference to load it now.",
            modelLoading.detail,
        )
        assertEquals("IDLE", modelLoading.pillLabel)
        assertEquals(DashboardMessageTone.INFO, modelLoading.tone)
    }

    @Test
    fun `runtime readiness summary separates degraded runtime from ready state`() {
        val nowMs = 20_000L
        val degraded = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
            thermalBand = "HOT",
        ).runtimeReadiness(nowMs)
        val guarded = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
            memoryPressure = "EMERGENCY",
        ).runtimeReadiness(nowMs)

        assertEquals("Runtime degraded", degraded.headline)
        assertEquals("DEGRADED", degraded.pillLabel)
        assertEquals(DashboardMessageTone.WARNING, degraded.tone)
        assertEquals("Runtime guard active", guarded.headline)
        assertEquals("ATTENTION", guarded.pillLabel)
        assertEquals(DashboardMessageTone.ERROR, guarded.tone)
    }

    @Test
    fun `successful test result is flagged when runtime is no longer ready`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            backend = "NONE",
            testStatus = "Completed",
            testStatusTone = DashboardMessageTone.SUCCESS,
        )

        assertTrue(state.shouldHighlightTestResult(nowMs))
    }

    @Test
    fun `successful test result is not flagged for healthy live runtime`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
            testStatus = "Completed",
            testStatusTone = DashboardMessageTone.SUCCESS,
        )

        assertFalse(state.shouldHighlightTestResult(nowMs))
    }

    @Test
    fun `test inference readiness allows healthy fresh runtime`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
        )

        assertNull(state.testReadinessIssue(nowMs))
        assertTrue(state.canRunTestInference(nowMs))
    }

    @Test
    fun `test inference readiness blocks disconnected and loading states`() {
        val nowMs = 20_000L
        val disconnected = DashboardUiState(
            connectionState = DashboardConnectionState.DISCONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
        )
        val loading = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = true,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
        )

        assertEquals(
            "Reconnect the service before running a test.",
            disconnected.testReadinessIssue(nowMs),
        )
        assertFalse(disconnected.canRunTestInference(nowMs))
        assertEquals(
            "Runtime status is still loading. Wait for the first live sample before testing.",
            loading.testReadinessIssue(nowMs),
        )
        assertFalse(loading.canRunTestInference(nowMs))
    }

    @Test
    fun `test inference readiness blocks unknown and stale status samples`() {
        val nowMs = 20_000L
        val pollingFailed = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            statusErrorMessage = "Status polling failed: timeout",
            isEngineLoaded = true,
            backend = "GPU",
        )
        val unknown = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = null,
            isEngineLoaded = true,
            backend = "GPU",
        )
        val stale = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 7_000,
            isEngineLoaded = true,
            backend = "GPU",
        )

        assertEquals(
            "Status polling failed. Refresh status before running a test.",
            pollingFailed.testReadinessIssue(nowMs),
        )
        assertFalse(pollingFailed.canRunTestInference(nowMs))
        assertEquals(
            "No live runtime status yet. Refresh status and wait for readiness.",
            unknown.testReadinessIssue(nowMs),
        )
        assertFalse(unknown.canRunTestInference(nowMs))
        assertEquals(
            "Status is stale. Refresh before running a test.",
            stale.testReadinessIssue(nowMs),
        )
        assertFalse(stale.canRunTestInference(nowMs))
    }

    @Test
    fun `test inference readiness does NOT block when engine is not loaded (chicken-and-egg fix)`() {
        // The button is the only affordance that triggers engine initialization via
        // prewarmAndAwait. Blocking it on isEngineLoaded==false made it impossible to
        // ever load the engine from the dashboard on a fresh install.
        val nowMs = 20_000L
        val unloaded = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = false,
            backend = "GPU",
        )
        val noBackend = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "NONE",
        )

        assertNull(unloaded.testReadinessIssue(nowMs))
        assertTrue(unloaded.canRunTestInference(nowMs))
        assertNull(noBackend.testReadinessIssue(nowMs))
        assertTrue(noBackend.canRunTestInference(nowMs))
    }

    @Test
    fun `test inference readiness blocks while a test is already running`() {
        val nowMs = 20_000L
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = nowMs - 1_000,
            isEngineLoaded = true,
            backend = "GPU",
            isTestRunning = true,
        )

        assertEquals("A test is already running.", state.testReadinessIssue(nowMs))
        assertFalse(state.canRunTestInference(nowMs))
    }

    @Test
    fun `formatRelativeTimestamp returns concise labels`() {
        val nowMs = 10_000L

        assertEquals("just now", formatRelativeTimestamp(nowMs - 500, nowMs))
        assertEquals("45s ago", formatRelativeTimestamp(nowMs - 45_000, nowMs))
        assertEquals("3m ago", formatRelativeTimestamp(nowMs - 180_000, nowMs))
        assertEquals("2h ago", formatRelativeTimestamp(nowMs - 7_200_000, nowMs))
    }

    @Test
    fun `copy preserves structured diagnostics and list content`() {
        val session = SessionUiItem("s1", "GPU", 100, 2048, true, "just now")
        val log = LogUiItem("1s ago", "ERROR", "poll_failed", "timeout")
        val state = DashboardUiState(
            connectionState = DashboardConnectionState.CONNECTED,
            isStatusLoading = false,
            lastStatusUpdateMs = 10_000L,
            statusErrorMessage = "Status polling failed",
            gpuFailureReason = "RuntimeException: GPU driver crash",
            activeSessions = listOf(session),
            recentLogs = listOf(log),
        )

        val copied = state.copy(
            logsErrorMessage = "Log polling failed",
            testStatusTone = DashboardMessageTone.ERROR,
        )

        assertEquals(DashboardConnectionState.CONNECTED, copied.connectionState)
        assertEquals(10_000L, copied.lastStatusUpdateMs)
        assertEquals("Status polling failed", copied.statusErrorMessage)
        assertEquals("Log polling failed", copied.logsErrorMessage)
        assertEquals("RuntimeException: GPU driver crash", copied.gpuFailureReason)
        assertEquals(DashboardMessageTone.ERROR, copied.testStatusTone)
        assertEquals(listOf(session), copied.activeSessions)
        assertEquals(listOf(log), copied.recentLogs)
    }

    @Test
    fun `gpuFailureReason is set when backend is CPU`() {
        val state = DashboardUiState(
            backend = "CPU",
            gpuFailureReason = "IllegalStateException: GPU not available",
        )

        assertEquals("CPU", state.backend)
        assertEquals("IllegalStateException: GPU not available", state.gpuFailureReason)
    }
}
