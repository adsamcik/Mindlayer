package com.adsamcik.mindlayer.service.ui

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
            DashboardHealthLevel.DEGRADED,
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
