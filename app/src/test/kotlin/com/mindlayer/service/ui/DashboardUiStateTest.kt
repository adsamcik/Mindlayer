package com.mindlayer.service.ui

import org.junit.Assert.*
import org.junit.Test

class DashboardUiStateTest {

    // ── DashboardUiState defaults ───────────────────────────────────────

    @Test
    fun `default DashboardUiState has expected values`() {
        val state = DashboardUiState()

        assertFalse(state.isEngineLoaded)
        assertEquals("NONE", state.backend)
        assertEquals(0f, state.initTimeSeconds, 0f)
        assertEquals(0L, state.uptimeMs)
        assertEquals("", state.modelPath)

        assertEquals("COOL", state.thermalBand)
        assertEquals("GPU", state.recommendedBackend)
        assertEquals(12, state.burstSeconds)
        assertEquals(0, state.restSeconds)
        assertEquals(128, state.chunkTokens)
        assertNull(state.headroom)

        assertEquals("NORMAL", state.memoryPressure)
        assertEquals(0L, state.availableRamMb)
        assertEquals(0L, state.totalRamMb)
        assertEquals(0, state.maxSessions)

        assertTrue(state.activeSessions.isEmpty())
        assertTrue(state.recentLogs.isEmpty())

        assertEquals("", state.testStatus)
        assertEquals("", state.testOutput)
        assertFalse(state.isTestRunning)
    }

    @Test
    fun `DashboardUiState headroom is nullable`() {
        val withNull = DashboardUiState(headroom = null)
        assertNull(withNull.headroom)

        val withValue = DashboardUiState(headroom = 0.75f)
        assertEquals(0.75f, withValue.headroom!!, 0.001f)
    }

    @Test
    fun `DashboardUiState testStatus and testOutput defaults`() {
        val state = DashboardUiState()
        assertEquals("", state.testStatus)
        assertEquals("", state.testOutput)
    }

    @Test
    fun `DashboardUiState isTestRunning default is false`() {
        assertFalse(DashboardUiState().isTestRunning)
    }

    @Test
    fun `DashboardUiState activeSessions and recentLogs default to empty`() {
        val state = DashboardUiState()
        assertEquals(emptyList<SessionUiItem>(), state.activeSessions)
        assertEquals(emptyList<LogUiItem>(), state.recentLogs)
    }

    // ── DashboardUiState copy ───────────────────────────────────────────

    @Test
    fun `DashboardUiState copy with modified fields`() {
        val original = DashboardUiState()
        val modified = original.copy(
            isEngineLoaded = true,
            backend = "GPU",
            thermalBand = "HOT",
            isTestRunning = true,
            testStatus = "Running",
            headroom = 0.5f,
        )

        assertTrue(modified.isEngineLoaded)
        assertEquals("GPU", modified.backend)
        assertEquals("HOT", modified.thermalBand)
        assertTrue(modified.isTestRunning)
        assertEquals("Running", modified.testStatus)
        assertEquals(0.5f, modified.headroom!!, 0.001f)

        // Unchanged fields remain at defaults
        assertEquals(0f, modified.initTimeSeconds, 0f)
        assertEquals("", modified.modelPath)
        assertEquals("NORMAL", modified.memoryPressure)
    }

    @Test
    fun `DashboardUiState copy preserves lists`() {
        val session = SessionUiItem("s1", "GPU", 100, 2048, false, "5s ago")
        val log = LogUiItem("12:00", "INFERENCE", "request_start", "detail")
        val state = DashboardUiState(
            activeSessions = listOf(session),
            recentLogs = listOf(log),
        )

        val copied = state.copy(backend = "CPU")

        assertEquals(1, copied.activeSessions.size)
        assertEquals(session, copied.activeSessions[0])
        assertEquals(1, copied.recentLogs.size)
        assertEquals(log, copied.recentLogs[0])
        assertEquals("CPU", copied.backend)
    }

    // ── DashboardUiState equality ───────────────────────────────────────

    @Test
    fun `DashboardUiState equals and hashCode`() {
        val a = DashboardUiState()
        val b = DashboardUiState()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `DashboardUiState not equal when field differs`() {
        val a = DashboardUiState(backend = "GPU")
        val b = DashboardUiState(backend = "CPU")
        assertNotEquals(a, b)
    }

    // ── SessionUiItem ───────────────────────────────────────────────────

    @Test
    fun `SessionUiItem construction with all fields`() {
        val item = SessionUiItem(
            sessionId = "session-42",
            backend = "GPU",
            tokenCount = 256,
            maxTokens = 4096,
            isStreaming = true,
            lastAccessedLabel = "2 min ago",
        )

        assertEquals("session-42", item.sessionId)
        assertEquals("GPU", item.backend)
        assertEquals(256, item.tokenCount)
        assertEquals(4096, item.maxTokens)
        assertTrue(item.isStreaming)
        assertEquals("2 min ago", item.lastAccessedLabel)
    }

    @Test
    fun `SessionUiItem equals and hashCode`() {
        val a = SessionUiItem("s1", "GPU", 10, 100, false, "now")
        val b = SessionUiItem("s1", "GPU", 10, 100, false, "now")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SessionUiItem not equal when field differs`() {
        val a = SessionUiItem("s1", "GPU", 10, 100, false, "now")
        val b = SessionUiItem("s1", "CPU", 10, 100, false, "now")
        assertNotEquals(a, b)
    }

    @Test
    fun `SessionUiItem copy works`() {
        val original = SessionUiItem("s1", "GPU", 10, 100, false, "now")
        val copied = original.copy(isStreaming = true, tokenCount = 50)
        assertTrue(copied.isStreaming)
        assertEquals(50, copied.tokenCount)
        assertEquals(original.sessionId, copied.sessionId)
    }

    @Test
    fun `SessionUiItem toString contains field values`() {
        val item = SessionUiItem("s1", "GPU", 10, 100, false, "now")
        val str = item.toString()
        assertTrue(str.contains("s1"))
        assertTrue(str.contains("GPU"))
    }

    // ── LogUiItem ───────────────────────────────────────────────────────

    @Test
    fun `LogUiItem construction with all fields`() {
        val item = LogUiItem(
            timestampLabel = "14:30:05",
            category = "INFERENCE",
            event = "request_complete",
            detail = "64 tokens in 250ms",
        )

        assertEquals("14:30:05", item.timestampLabel)
        assertEquals("INFERENCE", item.category)
        assertEquals("request_complete", item.event)
        assertEquals("64 tokens in 250ms", item.detail)
    }

    @Test
    fun `LogUiItem equals and hashCode`() {
        val a = LogUiItem("t", "c", "e", "d")
        val b = LogUiItem("t", "c", "e", "d")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `LogUiItem not equal when field differs`() {
        val a = LogUiItem("t", "c", "e", "d1")
        val b = LogUiItem("t", "c", "e", "d2")
        assertNotEquals(a, b)
    }

    @Test
    fun `LogUiItem copy works`() {
        val original = LogUiItem("t", "c", "e", "d")
        val copied = original.copy(detail = "new detail")
        assertEquals("new detail", copied.detail)
        assertEquals(original.timestampLabel, copied.timestampLabel)
    }

    @Test
    fun `LogUiItem toString contains field values`() {
        val item = LogUiItem("14:30", "THERMAL", "band_change", "HOT")
        val str = item.toString()
        assertTrue(str.contains("THERMAL"))
        assertTrue(str.contains("band_change"))
    }
}
