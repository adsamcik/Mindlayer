package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.logging.LogDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins SECURITY_REVIEW F-005 — `getDiagnostics()` previously leaked
 * cross-tenant session lists, request IDs, error messages, and host
 * details (modelPath, lastGpuFailureReason) to any authorized caller.
 *
 * After the fix, an external caller (`scopeUid != null`) sees only:
 *  - their own sessions
 *  - log entries tied to their own sessions
 *  - no `errorMessage`, no `modelPath`, no `lastGpuFailureReason`
 *  - no global aggregate stats
 *
 * Self-UID dashboard (`scopeUid == null`) keeps full unfiltered output.
 */
class DiagnosticExporterScopingTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 200_000L
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun mockSession(id: String, ownerUid: Int): com.adsamcik.mindlayer.SessionInfo =
        com.adsamcik.mindlayer.SessionInfo(
            sessionId = id,
            backend = "GPU",
            maxTokens = 2048,
            currentTokenCount = 0,
            turnCount = 0,
            createdAtMs = 0L,
            lastAccessedAtMs = 0L,
            isStreaming = false,
        )

    private fun setUp(
        sessions: Map<Int, List<com.adsamcik.mindlayer.SessionInfo>>,
        recentLogs: List<com.adsamcik.mindlayer.service.logging.LogEntry>,
    ): DiagnosticExporter {
        // Use flat `every` calls — the trailing-lambda mockk { … } form
        // hits a pre-existing mockk + Java 21 incompatibility on this
        // project (same root cause as the existing ServiceBinderTest
        // baseline failures).
        val engine = mockk<EngineManager>(relaxed = true)
        every { engine.isInitialized } returns true
        every { engine.currentBackend } returns "GPU"
        every { engine.initTimeSeconds } returns 1.5f
        every { engine.lastGpuFailureReason } returns "secret-prompt-fragment-leak"
        every { engine.modelPath } returns "/data/data/x/files/secret-model.litertlm"

        val thermal = mockk<ThermalMonitor>(relaxed = true)
        every { thermal.currentPolicy } returns MutableStateFlow(
            ThermalPolicy(ThermalBand.COOL, "GPU", 12, 0, 128),
        )
        every { thermal.latestSample } returns MutableStateFlow(null)
        every { thermal.canReenableGpu() } returns true

        val mem = mockk<MemoryBudget>(relaxed = true)
        every { mem.currentSnapshot() } returns MemorySnapshot(
            availableMb = 6000L, totalMb = 12_000L, lowMemory = false,
            pressure = MemoryPressure.NORMAL, recommendedMaxTokens = 8192,
        )
        every { mem.deviceTier } returns DeviceTier(4, 4096, 8192, 12_000L)

        val sm = mockk<SessionManager>(relaxed = true)
        every { sm.listSessions() } returns sessions.values.flatten()
        for ((uid, owned) in sessions) {
            every { sm.listSessionsOwnedBy(uid) } returns owned
        }

        val dao = mockk<LogDao>(relaxed = true)
        coEvery { dao.getRecent(50) } returns recentLogs
        coEvery { dao.totalInferenceCount() } returns 42
        coEvery { dao.averageTokensPerSec() } returns 25.0f
        coEvery { dao.totalTokensGenerated() } returns 1234L
        coEvery { dao.averageInferenceDurationMs() } returns 100.0f
        coEvery { dao.errorCountSince(any()) } returns 3

        return DiagnosticExporter(engine, thermal, mem, sm, dao)
    }

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `external caller sees only own sessions`() = runBlocking {
        val sessionsByUid = mapOf(
            1234 to listOf(mockSession("s-A", 1234)),
            5678 to listOf(mockSession("s-B", 5678), mockSession("s-C", 5678)),
        )
        val exp = setUp(sessionsByUid, recentLogs = emptyList())

        val externalDump = parse(exp.export(scopeUid = 1234))
        val sessions = externalDump["sessions"]!!.jsonObject["list"]!!.jsonArray
        assertEquals(1, sessions.size)
        assertEquals(
            "s-A",
            sessions[0].jsonObject["sessionId"]!!.jsonPrimitive.contentOrNull,
        )
    }

    @Test
    fun `external caller does not see modelPath or lastGpuFailureReason`() = runBlocking {
        val exp = setUp(mapOf(1234 to emptyList()), emptyList())
        val externalDump = parse(exp.export(scopeUid = 1234))
        val engine = externalDump["engine"]!!.jsonObject
        assertFalse(engine.containsKey("modelPath"))
        assertFalse(engine.containsKey("lastGpuFailureReason"))
    }

    @Test
    fun `self-UID still sees modelPath and global stats`() = runBlocking {
        val exp = setUp(mapOf(1234 to emptyList()), emptyList())
        val selfDump = parse(exp.export(scopeUid = null))
        val engine = selfDump["engine"]!!.jsonObject
        assertTrue("self should see modelPath", engine.containsKey("modelPath"))
        assertTrue(
            "self should see lastGpuFailureReason",
            engine.containsKey("lastGpuFailureReason"),
        )
        assertNotNull("self should see stats", selfDump["stats"])
    }

    @Test
    fun `external caller log filter excludes other UIDs and global engine logs`() =
        runBlocking {
            val sessionsByUid = mapOf(
                1234 to listOf(mockSession("s-mine", 1234)),
                5678 to listOf(mockSession("s-other", 5678)),
            )
            val logs = listOf(
                com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = 1L,
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.INFERENCE,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.REQUEST_START,
                    sessionId = "s-mine",
                    requestId = "r-1",
                ),
                com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = 2L,
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.INFERENCE,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.REQUEST_START,
                    sessionId = "s-other",
                    requestId = "r-2",
                ),
                com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = 3L,
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.ENGINE,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.ENGINE_INIT,
                ),
            )
            val exp = setUp(sessionsByUid, logs)

            val externalDump = parse(exp.export(scopeUid = 1234))
            val recentLogs = externalDump["recentLogs"]!!.jsonArray
            assertEquals(1, recentLogs.size)
            assertEquals(
                "s-mine",
                recentLogs[0].jsonObject["sessionId"]!!.jsonPrimitive.contentOrNull,
            )
        }

    @Test
    fun `external caller does not see errorMessage even for own session`() =
        runBlocking {
            val sessionsByUid = mapOf(1234 to listOf(mockSession("s-mine", 1234)))
            val logs = listOf(
                com.adsamcik.mindlayer.service.logging.LogEntry(
                    timestampMs = 1L,
                    category = com.adsamcik.mindlayer.service.logging.LogCategory.ERROR,
                    event = com.adsamcik.mindlayer.service.logging.LogEvent.REQUEST_ERROR,
                    sessionId = "s-mine",
                    errorMessage = "tokenizer refused chunk: 'leaked-prompt-fragment'",
                ),
            )
            val exp = setUp(sessionsByUid, logs)

            val externalDump = parse(exp.export(scopeUid = 1234))
            val recentLogs = externalDump["recentLogs"]!!.jsonArray
            assertEquals(1, recentLogs.size)
            assertNull(
                "errorMessage must be redacted for external callers",
                recentLogs[0].jsonObject["errorMessage"],
            )
        }

    @Test
    fun `external caller does not see global aggregate stats`() = runBlocking {
        val exp = setUp(mapOf(1234 to emptyList()), emptyList())
        val externalDump = parse(exp.export(scopeUid = 1234))
        assertNull(
            "external caller must not see aggregate stats",
            externalDump["stats"],
        )
    }
}
