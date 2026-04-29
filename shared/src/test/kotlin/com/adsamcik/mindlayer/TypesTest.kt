package com.adsamcik.mindlayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Parcelable data classes in [Types.kt].
 *
 * We test construction and default values only — Parcel marshalling requires
 * an Android runtime and is out of scope for pure-JVM unit tests.
 */
class TypesTest {

    // ── SessionConfig ────────────────────────────────────────────────────

    @Test
    fun `SessionConfig default values`() {
        val cfg = SessionConfig()
        assertNull(cfg.sessionId)
        assertNull(cfg.systemPrompt)
        assertEquals(4096, cfg.maxTokens)
        assertEquals("GPU", cfg.backend)
        assertEquals(40, cfg.samplerTopK)
        assertEquals(0.95f, cfg.samplerTopP, 0.001f)
        assertEquals(0.7f, cfg.samplerTemperature, 0.001f)
        assertNull(cfg.toolsJson)
        assertNull(cfg.extraContextJson)
        assertNull(cfg.initialHistory)
    }

    @Test
    fun `SessionConfig with custom values`() {
        val history = listOf(
            HistoryTurn("user", "Hello"),
            HistoryTurn("model", "Hi"),
        )
        val cfg = SessionConfig(
            sessionId = "s1",
            systemPrompt = "You are helpful",
            maxTokens = 2048,
            backend = "CPU",
            samplerTopK = 10,
            samplerTopP = 0.8f,
            samplerTemperature = 0.5f,
            toolsJson = """{"tools":[]}""",
            extraContextJson = """{"ctx":"val"}""",
            initialHistory = history,
        )
        assertEquals("s1", cfg.sessionId)
        assertEquals("You are helpful", cfg.systemPrompt)
        assertEquals(2048, cfg.maxTokens)
        assertEquals("CPU", cfg.backend)
        assertEquals(10, cfg.samplerTopK)
        assertEquals(0.8f, cfg.samplerTopP, 0.001f)
        assertEquals(0.5f, cfg.samplerTemperature, 0.001f)
        assertEquals("""{"tools":[]}""", cfg.toolsJson)
        assertEquals("""{"ctx":"val"}""", cfg.extraContextJson)
        assertEquals(history, cfg.initialHistory)
    }

    @Test
    fun `SessionConfig copy changes only specified fields`() {
        val original = SessionConfig(maxTokens = 1024)
        val copied = original.copy(backend = "CPU")
        assertEquals(1024, copied.maxTokens)
        assertEquals("CPU", copied.backend)
    }

    @Test
    fun `SessionConfig equals and hashCode`() {
        val a = SessionConfig(sessionId = "x", maxTokens = 512)
        val b = SessionConfig(sessionId = "x", maxTokens = 512)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── RequestMeta ──────────────────────────────────────────────────────

    @Test
    fun `RequestMeta construction with all fields`() {
        val meta = RequestMeta(
            requestId = "req-1",
            sessionId = "sess-1",
            textContent = "Hello world",
            role = "assistant",
            priority = 5,
        )
        assertEquals("req-1", meta.requestId)
        assertEquals("sess-1", meta.sessionId)
        assertEquals("Hello world", meta.textContent)
        assertEquals("assistant", meta.role)
        assertEquals(5, meta.priority)
    }

    @Test
    fun `RequestMeta default values`() {
        val meta = RequestMeta(requestId = "r", sessionId = "s")
        assertNull(meta.textContent)
        assertEquals("user", meta.role)
        assertEquals(0, meta.priority)
    }

    @Test
    fun `RequestMeta equals and hashCode`() {
        val a = RequestMeta("r1", "s1", "hi", "user", 0)
        val b = RequestMeta("r1", "s1", "hi", "user", 0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── ImageTransfer ────────────────────────────────────────────────────
    // ImageTransfer requires a ParcelFileDescriptor which needs Android runtime.
    // We test the data class shape by verifying the constructor signature compiles
    // and default values work.

    // ── AudioTransfer ────────────────────────────────────────────────────
    // Same limitation as ImageTransfer — requires ParcelFileDescriptor.

    // ── ToolResult ───────────────────────────────────────────────────────

    @Test
    fun `ToolResult construction`() {
        val tr = ToolResult(
            requestId = "req-2",
            toolName = "search",
            resultJson = """{"results":["a","b"]}""",
        )
        assertEquals("req-2", tr.requestId)
        assertNull(tr.callId)
        assertEquals("search", tr.toolName)
        assertEquals("""{"results":["a","b"]}""", tr.resultJson)
    }

    @Test
    fun `ToolResult with callId`() {
        val tr = ToolResult(
            requestId = "req-3",
            callId = "call-abc",
            toolName = "weather",
            resultJson = """{"temp":22}""",
        )
        assertEquals("req-3", tr.requestId)
        assertEquals("call-abc", tr.callId)
        assertEquals("weather", tr.toolName)
    }

    @Test
    fun `ToolResult equals and hashCode`() {
        val a = ToolResult("r", null, "t", "{}")
        val b = ToolResult("r", null, "t", "{}")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ToolResult copy preserves unchanged fields`() {
        val original = ToolResult("r", null, "calc", """{"ans":42}""")
        val copied = original.copy(toolName = "search")
        assertEquals("r", copied.requestId)
        assertNull(copied.callId)
        assertEquals("search", copied.toolName)
        assertEquals("""{"ans":42}""", copied.resultJson)
    }

    // ── ServiceStatus ────────────────────────────────────────────────────

    @Test
    fun `ServiceStatus construction with all fields`() {
        val ss = ServiceStatus(
            isEngineLoaded = true,
            engineWarming = false,
            activeSessionCount = 3,
            activeInferenceCount = 1,
            backend = "GPU",
            thermalBand = "COOL",
            isForeground = true,
            uptimeMs = 60_000L,
            memoryPressure = "LOW",
            availableRamMb = 4096,
            totalRamMb = 8192,
            maxSessions = 4,
            headroom = 0.75f,
        )
        assertTrue(ss.isEngineLoaded)
        assertFalse(ss.engineWarming)
        assertEquals(3, ss.activeSessionCount)
        assertEquals(1, ss.activeInferenceCount)
        assertEquals("GPU", ss.backend)
        assertEquals("COOL", ss.thermalBand)
        assertTrue(ss.isForeground)
        assertEquals(60_000L, ss.uptimeMs)
        assertEquals("LOW", ss.memoryPressure)
        assertEquals(4096L, ss.availableRamMb)
        assertEquals(8192L, ss.totalRamMb)
        assertEquals(4, ss.maxSessions)
        assertEquals(0.75f, ss.headroom!!, 0.001f)
    }

    @Test
    fun `ServiceStatus default values for new fields`() {
        val ss = ServiceStatus(
            isEngineLoaded = false,
            activeSessionCount = 0,
            activeInferenceCount = 0,
            backend = "CPU",
            thermalBand = "WARM",
            isForeground = false,
            uptimeMs = 0L,
        )
        assertEquals("NORMAL", ss.memoryPressure)
        assertEquals(0L, ss.availableRamMb)
        assertEquals(0L, ss.totalRamMb)
        assertEquals(0, ss.maxSessions)
        assertNull(ss.headroom)
        assertFalse(ss.engineWarming)
    }

    @Test
    fun `ServiceStatus equals and hashCode`() {
        val a = ServiceStatus(true, false, 1, 0, "GPU", "COOL", true, 100L)
        val b = ServiceStatus(true, false, 1, 0, "GPU", "COOL", true, 100L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── EngineInfo ───────────────────────────────────────────────────────

    @Test
    fun `EngineInfo construction`() {
        val ei = EngineInfo(
            modelId = "gemma-4-e2b-it",
            modelSizeBytes = 4_000_000_000L,
            backend = "GPU",
            maxTokens = 8192,
            initTimeSeconds = 3.5f,
            lastPrefillToksPerSec = 120.0f,
            lastDecodeToksPerSec = 45.5f,
        )
        assertEquals("gemma-4-e2b-it", ei.modelId)
        assertEquals(4_000_000_000L, ei.modelSizeBytes)
        assertEquals("GPU", ei.backend)
        assertEquals(8192, ei.maxTokens)
        assertEquals(3.5f, ei.initTimeSeconds, 0.001f)
        assertEquals(120.0f, ei.lastPrefillToksPerSec, 0.001f)
        assertEquals(45.5f, ei.lastDecodeToksPerSec, 0.001f)
    }

    @Test
    fun `EngineInfo equals and hashCode`() {
        val a = EngineInfo("gemma-4-e2b-it", 100L, "GPU", 4096, 1.0f, 50.0f, 30.0f)
        val b = EngineInfo("gemma-4-e2b-it", 100L, "GPU", 4096, 1.0f, 50.0f, 30.0f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `EngineInfo copy preserves unchanged fields`() {
        val original = EngineInfo("gemma-4-e2b-it", 100L, "GPU", 4096, 1.0f, 50.0f, 30.0f)
        val copied = original.copy(backend = "CPU")
        assertEquals("gemma-4-e2b-it", copied.modelId)
        assertEquals("CPU", copied.backend)
    }

    // ── HistoryTurn ─────────────────────────────────────────────────────

    @Test
    fun `HistoryTurn construction`() {
        val ht = HistoryTurn(role = "user", text = "Hello world")
        assertEquals("user", ht.role)
        assertEquals("Hello world", ht.text)
    }

    @Test
    fun `HistoryTurn equals and hashCode`() {
        val a = HistoryTurn("model", "response text")
        val b = HistoryTurn("model", "response text")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `HistoryTurn copy preserves unchanged fields`() {
        val original = HistoryTurn("user", "hello")
        val copied = original.copy(role = "model")
        assertEquals("model", copied.role)
        assertEquals("hello", copied.text)
    }

    @Test
    fun `HistoryTurn tool role`() {
        val ht = HistoryTurn(role = "tool", text = """{"result":"42"}""")
        assertEquals("tool", ht.role)
        assertEquals("""{"result":"42"}""", ht.text)
    }

    // ── SessionInfo ──────────────────────────────────────────────────────

    @Test
    fun `SessionInfo construction`() {
        val si = SessionInfo(
            sessionId = "sess-42",
            backend = "GPU",
            maxTokens = 4096,
            currentTokenCount = 128,
            turnCount = 3,
            createdAtMs = 1700000000000L,
            lastAccessedAtMs = 1700000060000L,
            isStreaming = true,
        )
        assertEquals("sess-42", si.sessionId)
        assertEquals("GPU", si.backend)
        assertEquals(4096, si.maxTokens)
        assertEquals(128, si.currentTokenCount)
        assertEquals(3, si.turnCount)
        assertEquals(1700000000000L, si.createdAtMs)
        assertEquals(1700000060000L, si.lastAccessedAtMs)
        assertTrue(si.isStreaming)
    }

    @Test
    fun `SessionInfo equals and hashCode`() {
        val a = SessionInfo("s", "GPU", 4096, 0, 0, 100L, 200L, false)
        val b = SessionInfo("s", "GPU", 4096, 0, 0, 100L, 200L, false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `SessionInfo isStreaming false`() {
        val si = SessionInfo("s", "CPU", 2048, 50, 1, 0L, 0L, false)
        assertFalse(si.isStreaming)
    }

    @Test
    fun `SessionInfo copy preserves unchanged fields`() {
        val original = SessionInfo("s", "GPU", 4096, 10, 2, 100L, 200L, true)
        val copied = original.copy(currentTokenCount = 50)
        assertEquals("s", copied.sessionId)
        assertEquals(50, copied.currentTokenCount)
        assertTrue(copied.isStreaming)
    }
}
