package com.mindlayer.service.logging

import org.junit.Assert.*
import org.junit.Test

class LogEntitiesTest {

    // ── LogEntry construction ───────────────────────────────────────────

    @Test
    fun `LogEntry with all fields`() {
        val entry = LogEntry(
            id = 42,
            timestampMs = 1_000_000L,
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_COMPLETE,
            sessionId = "sess-1",
            requestId = "req-1",
            backend = "GPU",
            durationMs = 250L,
            tokensGenerated = 64,
            tokensPerSec = 12.5f,
            prefillTokensPerSec = 80.0f,
            thermalBand = "HOT",
            memoryUsedMb = 512L,
            memoryAvailableMb = 1024L,
            errorMessage = "timeout",
            extraJson = """{"key":"value"}""",
        )

        assertEquals(42L, entry.id)
        assertEquals(1_000_000L, entry.timestampMs)
        assertEquals("INFERENCE", entry.category)
        assertEquals("request_complete", entry.event)
        assertEquals("sess-1", entry.sessionId)
        assertEquals("req-1", entry.requestId)
        assertEquals("GPU", entry.backend)
        assertEquals(250L, entry.durationMs)
        assertEquals(64, entry.tokensGenerated)
        assertEquals(12.5f, entry.tokensPerSec)
        assertEquals(80.0f, entry.prefillTokensPerSec)
        assertEquals("HOT", entry.thermalBand)
        assertEquals(512L, entry.memoryUsedMb)
        assertEquals(1024L, entry.memoryAvailableMb)
        assertEquals("timeout", entry.errorMessage)
        assertEquals("""{"key":"value"}""", entry.extraJson)
    }

    @Test
    fun `LogEntry with minimal fields uses defaults`() {
        val entry = LogEntry(
            timestampMs = 999L,
            category = LogCategory.ERROR,
            event = LogEvent.GENERAL_ERROR,
        )

        assertEquals(0L, entry.id)
        assertEquals(999L, entry.timestampMs)
        assertEquals("ERROR", entry.category)
        assertEquals("general_error", entry.event)
        assertNull(entry.sessionId)
        assertNull(entry.requestId)
        assertNull(entry.backend)
        assertNull(entry.durationMs)
        assertNull(entry.tokensGenerated)
        assertNull(entry.tokensPerSec)
        assertNull(entry.prefillTokensPerSec)
        assertNull(entry.thermalBand)
        assertNull(entry.memoryUsedMb)
        assertNull(entry.memoryAvailableMb)
        assertNull(entry.errorMessage)
        assertNull(entry.extraJson)
    }

    @Test
    fun `LogEntry default id is zero`() {
        val entry = LogEntry(timestampMs = 1L, category = "C", event = "E")
        assertEquals(0L, entry.id)
    }

    @Test
    fun `LogEntry nullable fields accept null explicitly`() {
        val entry = LogEntry(
            timestampMs = 1L,
            category = "C",
            event = "E",
            sessionId = null,
            requestId = null,
            backend = null,
            durationMs = null,
            tokensGenerated = null,
            tokensPerSec = null,
            prefillTokensPerSec = null,
            thermalBand = null,
            memoryUsedMb = null,
            memoryAvailableMb = null,
            errorMessage = null,
            extraJson = null,
        )

        assertNull(entry.sessionId)
        assertNull(entry.requestId)
        assertNull(entry.backend)
        assertNull(entry.durationMs)
        assertNull(entry.tokensGenerated)
        assertNull(entry.tokensPerSec)
        assertNull(entry.prefillTokensPerSec)
        assertNull(entry.thermalBand)
        assertNull(entry.memoryUsedMb)
        assertNull(entry.memoryAvailableMb)
        assertNull(entry.errorMessage)
        assertNull(entry.extraJson)
    }

    @Test
    fun `LogEntry copy works correctly`() {
        val original = LogEntry(
            timestampMs = 1L,
            category = LogCategory.THERMAL,
            event = LogEvent.BAND_CHANGE,
            thermalBand = "COOL",
        )

        val copied = original.copy(thermalBand = "HOT", durationMs = 100L)

        assertEquals(original.id, copied.id)
        assertEquals(original.timestampMs, copied.timestampMs)
        assertEquals(original.category, copied.category)
        assertEquals(original.event, copied.event)
        assertEquals("HOT", copied.thermalBand)
        assertEquals(100L, copied.durationMs)
        assertNull(original.durationMs)
    }

    // ── LogCategory constants ───────────────────────────────────────────

    @Test
    fun `LogCategory INFERENCE`() = assertEquals("INFERENCE", LogCategory.INFERENCE)

    @Test
    fun `LogCategory THERMAL`() = assertEquals("THERMAL", LogCategory.THERMAL)

    @Test
    fun `LogCategory SESSION`() = assertEquals("SESSION", LogCategory.SESSION)

    @Test
    fun `LogCategory MEMORY`() = assertEquals("MEMORY", LogCategory.MEMORY)

    @Test
    fun `LogCategory ENGINE`() = assertEquals("ENGINE", LogCategory.ENGINE)

    @Test
    fun `LogCategory ERROR`() = assertEquals("ERROR", LogCategory.ERROR)

    // ── LogEvent constants ──────────────────────────────────────────────

    @Test
    fun `LogEvent REQUEST_START`() = assertEquals("request_start", LogEvent.REQUEST_START)

    @Test
    fun `LogEvent REQUEST_COMPLETE`() = assertEquals("request_complete", LogEvent.REQUEST_COMPLETE)

    @Test
    fun `LogEvent REQUEST_CANCEL`() = assertEquals("request_cancel", LogEvent.REQUEST_CANCEL)

    @Test
    fun `LogEvent REQUEST_ERROR`() = assertEquals("request_error", LogEvent.REQUEST_ERROR)

    @Test
    fun `LogEvent TOOL_CALL`() = assertEquals("tool_call", LogEvent.TOOL_CALL)

    @Test
    fun `LogEvent TOOL_RESULT`() = assertEquals("tool_result", LogEvent.TOOL_RESULT)

    @Test
    fun `LogEvent BAND_CHANGE`() = assertEquals("band_change", LogEvent.BAND_CHANGE)

    @Test
    fun `LogEvent BACKEND_SWITCH`() = assertEquals("backend_switch", LogEvent.BACKEND_SWITCH)

    @Test
    fun `LogEvent SESSION_CREATED`() = assertEquals("session_created", LogEvent.SESSION_CREATED)

    @Test
    fun `LogEvent SESSION_DESTROYED`() = assertEquals("session_destroyed", LogEvent.SESSION_DESTROYED)

    @Test
    fun `LogEvent SESSION_EVICTED`() = assertEquals("session_evicted", LogEvent.SESSION_EVICTED)

    @Test
    fun `LogEvent PRESSURE_CHANGE`() = assertEquals("pressure_change", LogEvent.PRESSURE_CHANGE)

    @Test
    fun `LogEvent EVICTION_TRIGGERED`() = assertEquals("eviction_triggered", LogEvent.EVICTION_TRIGGERED)

    @Test
    fun `LogEvent ENGINE_INIT`() = assertEquals("engine_init", LogEvent.ENGINE_INIT)

    @Test
    fun `LogEvent ENGINE_SHUTDOWN`() = assertEquals("engine_shutdown", LogEvent.ENGINE_SHUTDOWN)

    @Test
    fun `LogEvent ENGINE_FALLBACK`() = assertEquals("engine_fallback", LogEvent.ENGINE_FALLBACK)

    @Test
    fun `LogEvent GENERAL_ERROR`() = assertEquals("general_error", LogEvent.GENERAL_ERROR)

    // ── Equality & hashCode (data class contract) ───────────────────────

    @Test
    fun `LogEntry equals and hashCode`() {
        val a = LogEntry(timestampMs = 5L, category = "C", event = "E", sessionId = "s")
        val b = LogEntry(timestampMs = 5L, category = "C", event = "E", sessionId = "s")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `LogEntry not equal when field differs`() {
        val a = LogEntry(timestampMs = 5L, category = "C", event = "E")
        val b = LogEntry(timestampMs = 6L, category = "C", event = "E")
        assertNotEquals(a, b)
    }

    @Test
    fun `LogEntry toString contains field values`() {
        val entry = LogEntry(timestampMs = 7L, category = "C", event = "E")
        val str = entry.toString()
        assertTrue(str.contains("timestampMs=7"))
        assertTrue(str.contains("category=C"))
        assertTrue(str.contains("event=E"))
    }
}
