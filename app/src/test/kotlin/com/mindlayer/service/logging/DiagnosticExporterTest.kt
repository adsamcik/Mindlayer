package com.mindlayer.service.logging

import android.os.SystemClock
import android.util.Log
import com.mindlayer.SessionInfo
import com.mindlayer.service.engine.DeviceTier
import com.mindlayer.service.engine.EngineManager
import com.mindlayer.service.engine.MemoryBudget
import com.mindlayer.service.engine.MemoryPressure
import com.mindlayer.service.engine.MemorySnapshot
import com.mindlayer.service.engine.SessionManager
import com.mindlayer.service.engine.ThermalBand
import com.mindlayer.service.engine.ThermalMonitor
import com.mindlayer.service.engine.ThermalPolicy
import com.mindlayer.service.engine.ThermalSample
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DiagnosticExporter]: validates JSON structure, field presence,
 * and correct values for each section of the diagnostic snapshot.
 */
class DiagnosticExporterTest {

    private lateinit var engineManager: EngineManager
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sessionManager: SessionManager
    private lateinit var logDao: LogDao
    private lateinit var exporter: DiagnosticExporter

    private val defaultPolicy = ThermalPolicy(
        band = ThermalBand.WARM,
        recommendedBackend = "GPU",
        burstSeconds = 10,
        restSeconds = 2,
        chunkTokens = 96,
    )
    private val defaultSample = ThermalSample(
        status = 1,
        headroomNow = 3.5f,
        headroom10s = 2.8f,
        timestampMs = 5000L,
    )
    private val defaultSnapshot = MemorySnapshot(
        availableMb = 5000L,
        totalMb = 12000L,
        lowMemory = false,
        pressure = MemoryPressure.NORMAL,
        recommendedMaxTokens = 16384,
    )
    private val defaultTier = DeviceTier(
        maxSessions = 4,
        defaultMaxTokens = 8192,
        maxMaxTokens = 32768,
        deviceRamMb = 12000L,
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)

        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { SystemClock.elapsedRealtime() } returns 300_000L

        engineManager = mockk(relaxed = true) {
            every { isInitialized } returns true
            every { currentBackend } returns "GPU"
            every { initTimeSeconds } returns 2.3f
            every { modelPath } returns "/data/app/model.litertlm"
        }
        thermalMonitor = mockk(relaxed = true) {
            every { currentPolicy } returns MutableStateFlow(defaultPolicy)
            every { latestSample } returns MutableStateFlow(defaultSample)
            every { canReenableGpu() } returns true
        }
        memoryBudget = mockk(relaxed = true) {
            every { currentSnapshot() } returns defaultSnapshot
            every { deviceTier } returns defaultTier
        }
        sessionManager = mockk(relaxed = true) {
            every { listSessions() } returns listOf(
                SessionInfo("s1", "GPU", 4096, 120, 5, 1000, 2000, false),
                SessionInfo("s2", "CPU", 2048, 50, 2, 1500, 2500, true),
            )
        }
        logDao = mockk(relaxed = true)
        coEvery { logDao.getRecent(50) } returns listOf(
            LogEntry(
                id = 1,
                timestampMs = 10000L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_COMPLETE,
                sessionId = "s1",
                requestId = "r1",
                backend = "GPU",
                durationMs = 500L,
                tokensGenerated = 100,
                tokensPerSec = 25.0f,
            ),
        )
        coEvery { logDao.totalInferenceCount() } returns 42
        coEvery { logDao.averageTokensPerSec() } returns 30.5f
        coEvery { logDao.totalTokensGenerated() } returns 10_000L
        coEvery { logDao.averageInferenceDurationMs() } returns 450.0f
        coEvery { logDao.errorCountSince(any()) } returns 3

        exporter = DiagnosticExporter(
            engineManager = engineManager,
            thermalMonitor = thermalMonitor,
            memoryBudget = memoryBudget,
            sessionManager = sessionManager,
            logDao = logDao,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- Helpers ------------------------------------------------------------

    private suspend fun exportJson(): JsonObject =
        Json.parseToJsonElement(exporter.export()).jsonObject

    // ---- Top-level fields ---------------------------------------------------

    @Test
    fun `export contains timestamp field`() = runTest {
        val json = exportJson()
        assertTrue(json.containsKey("timestamp"))
        assertTrue(json["timestamp"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun `export contains version field`() = runTest {
        val json = exportJson()
        assertEquals("0.1.0", json["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `export contains uptimeMs field`() = runTest {
        val json = exportJson()
        assertEquals(300_000L, json["uptimeMs"]!!.jsonPrimitive.long)
    }

    // ---- Engine section -----------------------------------------------------

    @Test
    fun `engine section has loaded field`() = runTest {
        val engine = exportJson()["engine"]!!.jsonObject
        assertTrue(engine["loaded"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `engine section has backend field`() = runTest {
        val engine = exportJson()["engine"]!!.jsonObject
        assertEquals("GPU", engine["backend"]!!.jsonPrimitive.content)
    }

    @Test
    fun `engine section has initTimeSeconds field`() = runTest {
        val engine = exportJson()["engine"]!!.jsonObject
        assertEquals(2.3f, engine["initTimeSeconds"]!!.jsonPrimitive.float, 0.01f)
    }

    @Test
    fun `engine section has modelPath field`() = runTest {
        val engine = exportJson()["engine"]!!.jsonObject
        assertEquals("/data/app/model.litertlm", engine["modelPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `engine section has lastGpuFailureReason null when no failure`() = runTest {
        every { engineManager.lastGpuFailureReason } returns null

        val engine = exportJson()["engine"]!!.jsonObject
        assertTrue(engine.containsKey("lastGpuFailureReason"))
        assertTrue(engine["lastGpuFailureReason"] is JsonNull)
    }

    @Test
    fun `engine section has lastGpuFailureReason with reason when GPU failed`() = runTest {
        every { engineManager.lastGpuFailureReason } returns "RuntimeException: GPU driver crash caused by UnsupportedOperationException: compute shaders"

        val engine = exportJson()["engine"]!!.jsonObject
        assertEquals(
            "RuntimeException: GPU driver crash caused by UnsupportedOperationException: compute shaders",
            engine["lastGpuFailureReason"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `engine section omits modelPath when it throws`() = runTest {
        every { engineManager.modelPath } throws IllegalStateException("not found")

        val engine = exportJson()["engine"]!!.jsonObject
        assertFalse(engine.containsKey("modelPath"))
    }

    @Test
    fun `engine section reflects uninitialized state`() = runTest {
        every { engineManager.isInitialized } returns false
        every { engineManager.currentBackend } returns "NONE"
        every { engineManager.initTimeSeconds } returns 0f

        val engine = exportJson()["engine"]!!.jsonObject
        assertFalse(engine["loaded"]!!.jsonPrimitive.boolean)
        assertEquals("NONE", engine["backend"]!!.jsonPrimitive.content)
    }

    // ---- Thermal section ----------------------------------------------------

    @Test
    fun `thermal section has band field`() = runTest {
        val thermal = exportJson()["thermal"]!!.jsonObject
        assertEquals("WARM", thermal["band"]!!.jsonPrimitive.content)
    }

    @Test
    fun `thermal section has recommendedBackend field`() = runTest {
        val thermal = exportJson()["thermal"]!!.jsonObject
        assertEquals("GPU", thermal["recommendedBackend"]!!.jsonPrimitive.content)
    }

    @Test
    fun `thermal section has burstSeconds field`() = runTest {
        val thermal = exportJson()["thermal"]!!.jsonObject
        assertEquals(10, thermal["burstSeconds"]!!.jsonPrimitive.int)
    }

    @Test
    fun `thermal section has restSeconds field`() = runTest {
        val thermal = exportJson()["thermal"]!!.jsonObject
        assertEquals(2, thermal["restSeconds"]!!.jsonPrimitive.int)
    }

    @Test
    fun `thermal section has chunkTokens field`() = runTest {
        val thermal = exportJson()["thermal"]!!.jsonObject
        assertEquals(96, thermal["chunkTokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `thermal section has canReenableGpu field`() = runTest {
        val thermal = exportJson()["thermal"]!!.jsonObject
        assertTrue(thermal["canReenableGpu"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `thermal section has headroom fields from sample`() = runTest {
        val thermal = exportJson()["thermal"]!!.jsonObject
        assertEquals(1, thermal["status"]!!.jsonPrimitive.int)
        assertEquals(2.8f, thermal["headroom10s"]!!.jsonPrimitive.float, 0.01f)
        assertEquals(3.5f, thermal["headroomNow"]!!.jsonPrimitive.float, 0.01f)
    }

    @Test
    fun `thermal section handles null headroom10s gracefully`() = runTest {
        val sampleNoHeadroom = ThermalSample(
            status = 0,
            headroomNow = null,
            headroom10s = null,
            timestampMs = 1000L,
        )
        every { thermalMonitor.latestSample } returns MutableStateFlow(sampleNoHeadroom)

        val thermal = exportJson()["thermal"]!!.jsonObject
        // status should still be present
        assertEquals(0, thermal["status"]!!.jsonPrimitive.int)
        // headroom fields should be absent (not null, just not set)
        assertFalse(thermal.containsKey("headroom10s"))
        assertFalse(thermal.containsKey("headroomNow"))
    }

    @Test
    fun `thermal section handles null sample (no thermal data)`() = runTest {
        every { thermalMonitor.latestSample } returns MutableStateFlow(null)

        val thermal = exportJson()["thermal"]!!.jsonObject
        // Band and policy fields should still be present
        assertEquals("WARM", thermal["band"]!!.jsonPrimitive.content)
        // No status/headroom fields
        assertFalse(thermal.containsKey("status"))
        assertFalse(thermal.containsKey("headroom10s"))
    }

    // ---- Memory section -----------------------------------------------------

    @Test
    fun `memory section has pressure field`() = runTest {
        val mem = exportJson()["memory"]!!.jsonObject
        assertEquals("NORMAL", mem["pressure"]!!.jsonPrimitive.content)
    }

    @Test
    fun `memory section has availableMb field`() = runTest {
        val mem = exportJson()["memory"]!!.jsonObject
        assertEquals(5000L, mem["availableMb"]!!.jsonPrimitive.long)
    }

    @Test
    fun `memory section has totalMb field`() = runTest {
        val mem = exportJson()["memory"]!!.jsonObject
        assertEquals(12000L, mem["totalMb"]!!.jsonPrimitive.long)
    }

    @Test
    fun `memory section has lowMemory field`() = runTest {
        val mem = exportJson()["memory"]!!.jsonObject
        assertFalse(mem["lowMemory"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `memory section has recommendedMaxTokens field`() = runTest {
        val mem = exportJson()["memory"]!!.jsonObject
        assertEquals(16384, mem["recommendedMaxTokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `memory section has deviceTier sub-object`() = runTest {
        val tier = exportJson()["memory"]!!.jsonObject["deviceTier"]!!.jsonObject
        assertEquals(4, tier["maxSessions"]!!.jsonPrimitive.int)
        assertEquals(8192, tier["defaultMaxTokens"]!!.jsonPrimitive.int)
        assertEquals(12000L, tier["deviceRamMb"]!!.jsonPrimitive.long)
    }

    // ---- Sessions section ---------------------------------------------------

    @Test
    fun `sessions section has activeCount field`() = runTest {
        val sessions = exportJson()["sessions"]!!.jsonObject
        assertEquals(2, sessions["activeCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `sessions section has list with correct entries`() = runTest {
        val list = exportJson()["sessions"]!!.jsonObject["list"]!!.jsonArray
        assertEquals(2, list.size)

        val s1 = list[0].jsonObject
        assertEquals("s1", s1["sessionId"]!!.jsonPrimitive.content)
        assertEquals("GPU", s1["backend"]!!.jsonPrimitive.content)
        assertEquals(4096, s1["maxTokens"]!!.jsonPrimitive.int)
        assertEquals(120, s1["currentTokenCount"]!!.jsonPrimitive.int)
        assertEquals(5, s1["turnCount"]!!.jsonPrimitive.int)
        assertFalse(s1["isStreaming"]!!.jsonPrimitive.boolean)

        val s2 = list[1].jsonObject
        assertEquals("s2", s2["sessionId"]!!.jsonPrimitive.content)
        assertTrue(s2["isStreaming"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `sessions handles empty sessions list`() = runTest {
        every { sessionManager.listSessions() } returns emptyList()

        val sessions = exportJson()["sessions"]!!.jsonObject
        assertEquals(0, sessions["activeCount"]!!.jsonPrimitive.int)
        assertEquals(0, sessions["list"]!!.jsonArray.size)
    }

    // ---- recentLogs section -------------------------------------------------

    @Test
    fun `recentLogs is a JSON array`() = runTest {
        val logs = exportJson()["recentLogs"]
        assertNotNull(logs)
        assertTrue(logs is JsonArray)
    }

    @Test
    fun `recentLogs contains log entry fields`() = runTest {
        val logs = exportJson()["recentLogs"]!!.jsonArray
        assertEquals(1, logs.size)

        val entry = logs[0].jsonObject
        assertEquals(10000L, entry["timestamp"]!!.jsonPrimitive.long)
        assertEquals(LogCategory.INFERENCE, entry["category"]!!.jsonPrimitive.content)
        assertEquals(LogEvent.REQUEST_COMPLETE, entry["event"]!!.jsonPrimitive.content)
        assertEquals("s1", entry["sessionId"]!!.jsonPrimitive.content)
        assertEquals("r1", entry["requestId"]!!.jsonPrimitive.content)
        assertEquals("GPU", entry["backend"]!!.jsonPrimitive.content)
        assertEquals(500L, entry["durationMs"]!!.jsonPrimitive.long)
        assertEquals(100, entry["tokensGenerated"]!!.jsonPrimitive.int)
        assertEquals(25.0f, entry["tokensPerSec"]!!.jsonPrimitive.float, 0.01f)
    }

    @Test
    fun `recentLogs omits null optional fields`() = runTest {
        coEvery { logDao.getRecent(50) } returns listOf(
            LogEntry(
                timestampMs = 999L,
                category = LogCategory.ENGINE,
                event = LogEvent.ENGINE_INIT,
            ),
        )

        val logs = exportJson()["recentLogs"]!!.jsonArray
        val entry = logs[0].jsonObject
        assertFalse(entry.containsKey("sessionId"))
        assertFalse(entry.containsKey("requestId"))
        assertFalse(entry.containsKey("backend"))
        assertFalse(entry.containsKey("durationMs"))
        assertFalse(entry.containsKey("tokensGenerated"))
        assertFalse(entry.containsKey("tokensPerSec"))
        assertFalse(entry.containsKey("thermalBand"))
        assertFalse(entry.containsKey("errorMessage"))
    }

    @Test
    fun `recentLogs handles empty log list`() = runTest {
        coEvery { logDao.getRecent(50) } returns emptyList()

        val logs = exportJson()["recentLogs"]!!.jsonArray
        assertEquals(0, logs.size)
    }

    @Test
    fun `recentLogs includes thermalBand and errorMessage when present`() = runTest {
        coEvery { logDao.getRecent(50) } returns listOf(
            LogEntry(
                timestampMs = 1L,
                category = LogCategory.ERROR,
                event = LogEvent.GENERAL_ERROR,
                thermalBand = "HOT",
                errorMessage = "out of memory",
            ),
        )

        val entry = exportJson()["recentLogs"]!!.jsonArray[0].jsonObject
        assertEquals("HOT", entry["thermalBand"]!!.jsonPrimitive.content)
        assertEquals("out of memory", entry["errorMessage"]!!.jsonPrimitive.content)
    }

    // ---- Stats section ------------------------------------------------------

    @Test
    fun `stats has totalInferences field`() = runTest {
        val stats = exportJson()["stats"]!!.jsonObject
        assertEquals(42, stats["totalInferences"]!!.jsonPrimitive.int)
    }

    @Test
    fun `stats has avgTokensPerSec field`() = runTest {
        val stats = exportJson()["stats"]!!.jsonObject
        assertEquals(30.5f, stats["avgTokensPerSec"]!!.jsonPrimitive.float, 0.01f)
    }

    @Test
    fun `stats has totalTokensGenerated field`() = runTest {
        val stats = exportJson()["stats"]!!.jsonObject
        assertEquals(10_000L, stats["totalTokensGenerated"]!!.jsonPrimitive.long)
    }

    @Test
    fun `stats has avgInferenceDurationMs field`() = runTest {
        val stats = exportJson()["stats"]!!.jsonObject
        assertEquals(450.0f, stats["avgInferenceDurationMs"]!!.jsonPrimitive.float, 0.01f)
    }

    @Test
    fun `stats has errorsLast24h field`() = runTest {
        val stats = exportJson()["stats"]!!.jsonObject
        assertEquals(3, stats["errorsLast24h"]!!.jsonPrimitive.int)
    }

    @Test
    fun `stats handles null avgTokensPerSec gracefully`() = runTest {
        coEvery { logDao.averageTokensPerSec() } returns null

        val stats = exportJson()["stats"]!!.jsonObject
        assertFalse(stats.containsKey("avgTokensPerSec"))
    }

    @Test
    fun `stats handles null totalTokensGenerated gracefully`() = runTest {
        coEvery { logDao.totalTokensGenerated() } returns null

        val stats = exportJson()["stats"]!!.jsonObject
        assertFalse(stats.containsKey("totalTokensGenerated"))
    }

    @Test
    fun `stats handles null avgInferenceDurationMs gracefully`() = runTest {
        coEvery { logDao.averageInferenceDurationMs() } returns null

        val stats = exportJson()["stats"]!!.jsonObject
        assertFalse(stats.containsKey("avgInferenceDurationMs"))
    }

    @Test
    fun `stats handles all nulls gracefully`() = runTest {
        coEvery { logDao.averageTokensPerSec() } returns null
        coEvery { logDao.totalTokensGenerated() } returns null
        coEvery { logDao.averageInferenceDurationMs() } returns null

        val stats = exportJson()["stats"]!!.jsonObject
        // totalInferences and errorsLast24h are always present (non-nullable)
        assertEquals(42, stats["totalInferences"]!!.jsonPrimitive.int)
        assertEquals(3, stats["errorsLast24h"]!!.jsonPrimitive.int)
        assertFalse(stats.containsKey("avgTokensPerSec"))
        assertFalse(stats.containsKey("totalTokensGenerated"))
        assertFalse(stats.containsKey("avgInferenceDurationMs"))
    }

    // ---- Full export validity -----------------------------------------------

    @Test
    fun `export produces valid JSON string`() = runTest {
        val raw = exporter.export()
        // Should not throw
        val parsed = Json.parseToJsonElement(raw)
        assertTrue(parsed is JsonObject)
    }

    @Test
    fun `export contains all top-level sections`() = runTest {
        val json = exportJson()
        val expected = listOf("timestamp", "version", "engine", "thermal", "memory",
            "sessions", "recentLogs", "stats", "uptimeMs")
        for (key in expected) {
            assertTrue("Missing key: $key", json.containsKey(key))
        }
    }
}
