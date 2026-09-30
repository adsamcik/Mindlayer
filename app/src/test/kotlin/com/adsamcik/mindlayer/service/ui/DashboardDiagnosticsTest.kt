package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.logging.RecordingDiagnosticsLogger
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIssue
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryState
import org.junit.Assert.*
import org.junit.Test

class DashboardDiagnosticsTest {
    @Test
    fun `report contains check outcomes without test output or status messages`() {
        val logger = RecordingDiagnosticsLogger()
        DashboardDiagnostics.record(DashboardUiState(
            connectionState = DashboardConnectionState.DISCONNECTED,
            statusErrorMessage = "private-status", logsErrorMessage = "private-log",
            backend = "private-backend", testOutput = "private-output", testStatus = "private-test",
            availableRamMb = 512, embeddingTest = EngineTestState(
                tone = DashboardMessageTone.ERROR, output = "private-embedding", lastCompletedAtMs = 123L,
            ),
        ), logger)
        val recorded = logger.text()
        assertTrue(recorded.contains("DISCONNECTED"))
        assertTrue(recorded.contains("ERROR"))
        assertTrue(recorded.contains("512"))
        assertTrue(recorded.contains("123"))
        assertFalse(recorded.contains("private-"))
    }

    @Test
    fun `error filter includes engine failures and excludes normal activity`() {
        assertTrue(LogUiItem("", "ENGINE", "Init failure categorized", "").isError())
        assertTrue(LogUiItem("", "ERROR", "Request rejected", "").isError())
        assertFalse(LogUiItem("", "INFERENCE", "Request complete", "").isError())
        assertTrue(LogUiItem("", "INFERENCE", "Tool call timeout", "").isError())
        assertTrue(LogUiItem("", "SECURITY", "Crash loop throttle", "").isError())
    }

    @Test
    fun `missing models need setup while installed and running engines do not`() {
        val absent = DashboardUiState(modelDelivery = mapOf(ModelRole.CHAT_AND_VISION to ModelDeliveryState.NotInstalled))
        assertTrue(absent.needsModelSetup())
        assertFalse(absent.copy(isEngineLoaded = true).needsModelSetup())
        assertFalse(absent.copy(modelDelivery = mapOf(ModelRole.OCR to ModelDeliveryState.Installed)).needsModelSetup())
    }

    @Test
    fun `report preserves typed model failure and storage evidence`() {
        val logger = RecordingDiagnosticsLogger()
        DashboardDiagnostics.record(DashboardUiState(modelDelivery = mapOf(
            ModelRole.OCR to ModelDeliveryState.Failed(ModelDeliveryIssue.InsufficientStorage(2048L, 1024L)),
        )), logger)
        assertTrue(logger.text().contains("InsufficientStorage"))
        assertTrue(logger.text().contains("2048"))
        assertTrue(logger.text().contains("1024"))
    }
}
