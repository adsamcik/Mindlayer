package com.adsamcik.mindlayer.service.logging

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [LogRepository] convenience builder methods.
 *
 * Uses MockK to capture LogEntry objects passed to the DAO, verifying
 * that each builder method produces correctly structured entries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogRepositoryTest {

    private lateinit var db: LogDatabase
    private lateinit var dao: LogDao
    private lateinit var repo: LogRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java)
            .allowMainThreadQueries()
            // Force synchronous query/transaction executors so the LogRepository drain loop
            // (running on Dispatchers.Unconfined) and the test-thread reads via dao.getRecent
            // serialize on the calling thread instead of racing on Room's default 4-thread
            // pool. Without this, the suspending Room operations dispatch to background
            // threads that aren't synchronized with runTest's scheduler, producing
            // intermittent "Expected exactly 1 log entry" failures on CI.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        dao = db.logDao()
        repo = LogRepository(dao, Dispatchers.Unconfined)
    }

    @After
    fun teardown() {
        repo.shutdown()
        db.close()
        unmockkAll()
    }

    /** Flush any pending drain-loop work, then return the single inserted entry. */
    private suspend fun TestScope.awaitSingleEntry(): LogEntry {
        advanceUntilIdle()
        val entries = dao.getRecent(10)
        assertEquals("Expected exactly 1 log entry", 1, entries.size)
        return entries[0]
    }

    private fun parsedExtra(entry: LogEntry) =
        Json.parseToJsonElement(entry.extraJson!!).jsonObject

    // --- logInferenceStart ---

    @Test
    fun `logInferenceStart creates entry with correct category event and fields`() = runTest {
        repo.logInferenceStart("req-1", "sess-1", "GPU")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.INFERENCE, e.category)
        assertEquals(LogEvent.REQUEST_START.key, e.event)
        assertEquals("req-1", e.requestId)
        assertEquals("sess-1", e.sessionId)
        assertEquals("GPU", e.backend)
        assertTrue(e.timestampMs > 0)
    }

    // --- logInferenceComplete ---

    @Test
    fun `logInferenceComplete includes duration tokens and tps`() = runTest {
        repo.logInferenceComplete(
            requestId = "req-2", sessionId = "sess-2", backend = "CPU",
            durationMs = 1500L, tokensGenerated = 42, tokensPerSec = 28.0f, prefillTps = 100.5f,
        )
        val e = awaitSingleEntry()

        assertEquals(LogCategory.INFERENCE, e.category)
        assertEquals(LogEvent.REQUEST_COMPLETE.key, e.event)
        assertEquals("req-2", e.requestId)
        assertEquals("sess-2", e.sessionId)
        assertEquals("CPU", e.backend)
        assertEquals(1500L, e.durationMs)
        assertEquals(42, e.tokensGenerated)
        assertEquals(28.0f, e.tokensPerSec!!, 0.01f)
        assertEquals(100.5f, e.prefillTokensPerSec!!, 0.01f)
    }

    // --- logInferenceError ---

    @Test
    fun `logInferenceError sanitizes error message`() = runTest {
        // "OOM crash" → spaces removed → "OOMcrash"
        repo.logInferenceError("req-3", "sess-3", "OOM crash")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ERROR, e.category)
        assertEquals(LogEvent.REQUEST_ERROR.key, e.event)
        assertEquals("req-3", e.requestId)
        assertEquals("sess-3", e.sessionId)
        // Sanitized: spaces stripped
        assertEquals("OOMcrash", e.errorMessage)
    }

    @Test
    fun `logInferenceError allows null sessionId`() = runTest {
        repo.logInferenceError("req-4", null, "No session")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ERROR, e.category)
        assertEquals(null, e.sessionId)
        // Sanitized: space stripped
        assertEquals("Nosession", e.errorMessage)
    }

    // --- M19: sanitizeErrorClass ---

    @Test
    fun `logInferenceError sanitizes long chatty string to le64 chars`() = runTest {
        // Input has spaces (stripped) and is very long (capped at 64)
        val longMessage = "leaked: SECRET PROMPT " + "x".repeat(200)
        repo.logInferenceError("req-san", "sess-san", longMessage)
        val e = awaitSingleEntry()

        assertNotNull(e.errorMessage)
        assertTrue("errorMessage should be <= 64 chars", e.errorMessage!!.length <= 64)
        assertFalse("errorMessage should not contain spaces", e.errorMessage.contains(" "))
    }

    @Test
    fun `logInferenceError preserves safe exception class names`() = runTest {
        repo.logInferenceError("req-cls", "sess-cls", "OutOfMemoryError-IOException")
        val e = awaitSingleEntry()

        assertEquals("OutOfMemoryError-IOException", e.errorMessage)
    }

    @Test
    fun `logInferenceError with blank message stores null`() = runTest {
        repo.logInferenceError("req-blank", "sess-blank", "   ")
        val e = awaitSingleEntry()

        assertNull(e.errorMessage)
    }

    // --- logThermalBandChange ---

    @Test
    fun `logThermalBandChange includes from and to bands`() = runTest {
        repo.logThermalBandChange("COOL", "WARM", "GPU")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.THERMAL, e.category)
        assertEquals(LogEvent.BAND_CHANGE.key, e.event)
        assertEquals("WARM", e.thermalBand)
        assertEquals("GPU", e.backend)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("COOL"))
        assertTrue(e.extraJson.contains("WARM"))
    }

    // --- logSessionCreated ---

    @Test
    fun `logSessionCreated includes maxTokens in extraJson`() = runTest {
        repo.logSessionCreated("sess-5", "GPU", 2048)
        val e = awaitSingleEntry()

        assertEquals(LogCategory.SESSION, e.category)
        assertEquals(LogEvent.SESSION_CREATED.key, e.event)
        assertEquals("sess-5", e.sessionId)
        assertEquals("GPU", e.backend)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("2048"))
    }

    @Test
    fun `logSessionCreated writes parseable numeric extraJson`() = runTest {
        repo.logSessionCreated("sess-json", "GPU", 2048)
        val extra = parsedExtra(awaitSingleEntry())

        assertEquals(2048, extra["maxTokens"]!!.jsonPrimitive.int)
    }

    // --- logSessionDestroyed ---

    @Test
    fun `logSessionDestroyed has correct event`() = runTest {
        repo.logSessionDestroyed("sess-6")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.SESSION, e.category)
        assertEquals(LogEvent.SESSION_DESTROYED.key, e.event)
        assertEquals("sess-6", e.sessionId)
    }

    // --- logSessionEvicted ---

    @Test
    fun `logSessionEvicted includes reason`() = runTest {
        repo.logSessionEvicted("sess-7", "memory_pressure")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.SESSION, e.category)
        assertEquals(LogEvent.SESSION_EVICTED.key, e.event)
        assertEquals("sess-7", e.sessionId)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("memory_pressure"))
    }

    @Test
    fun `logSessionEvicted escapes reason as valid JSON`() = runTest {
        val reason = "memory \"pressure\" at C:\\models\\gemma"

        repo.logSessionEvicted("sess-json", reason)
        val extra = parsedExtra(awaitSingleEntry())

        assertEquals(reason, extra["reason"]!!.jsonPrimitive.content)
    }

    // --- logMemoryPressure ---

    @Test
    fun `logMemoryPressure includes available and used memory`() = runTest {
        repo.logMemoryPressure("moderate", 2048, 8192)
        val e = awaitSingleEntry()

        assertEquals(LogCategory.MEMORY, e.category)
        assertEquals(LogEvent.PRESSURE_CHANGE.key, e.event)
        assertEquals(2048L, e.memoryAvailableMb)
        assertEquals(8192L - 2048L, e.memoryUsedMb)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("moderate"))
    }

    // --- logEngineInit ---

    @Test
    fun `logEngineInit includes backend duration and model filename only`() = runTest {
        repo.logEngineInit("GPU", 3500L, "/data/models/llama.bin")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ENGINE, e.category)
        assertEquals(LogEvent.ENGINE_INIT.key, e.event)
        assertEquals("GPU", e.backend)
        assertEquals(3500L, e.durationMs)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("llama.bin"))
        assertFalse(e.extraJson!!.contains("/data/models"))
    }

    @Test
    fun `logEngineInit stores model filename as valid JSON`() = runTest {
        val modelPath = "C:\\models\\Gemma \"4\"\\model.litertlm"

        repo.logEngineInit("GPU", 3500L, modelPath)
        val extra = parsedExtra(awaitSingleEntry())

        assertEquals("model.litertlm", extra["modelFile"]!!.jsonPrimitive.content)
    }

    // --- logEngineShutdown ---

    @Test
    fun `logEngineShutdown has correct event and backend`() = runTest {
        repo.logEngineShutdown("CPU")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ENGINE, e.category)
        assertEquals(LogEvent.ENGINE_SHUTDOWN.key, e.event)
        assertEquals("CPU", e.backend)
    }

    // --- cleanup ---

    @Test
    fun `cleanup deletes old entries via DAO`() = runTest {
        // Insert entries directly via DAO with known timestamps
        val now = System.currentTimeMillis()
        val eightDaysAgo = now - (8 * 24 * 60 * 60 * 1000L)
        val oneDayAgo = now - (1 * 24 * 60 * 60 * 1000L)

        dao.insert(LogEntry(
            timestampMs = eightDaysAgo,
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_COMPLETE.key,
        ))
        dao.insert(LogEntry(
            timestampMs = oneDayAgo,
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_COMPLETE.key,
        ))

        assertEquals(2, dao.totalCount())

        repo.cleanup(retentionDays = 7)

        assertEquals(1, dao.totalCount())
        val remaining = dao.getRecent(10)
        assertTrue(remaining[0].timestampMs >= oneDayAgo)
    }

    // --- backpressure ---

    private fun dummyEntry() = LogEntry(
        timestampMs = System.currentTimeMillis(),
        category = LogCategory.INFERENCE,
        event = LogEvent.REQUEST_START.key,
    )

    /**
     * log() must never block the caller even when the channel is full.
     * Uses a mock DAO that blocks indefinitely to keep the drain loop stuck,
     * then verifies that 10_000 non-blocking log() calls complete in well
     * under a second.
     */
    @Test(timeout = 10_000)
    fun `log does not block when channel is full`() {
        val drainStarted = java.util.concurrent.CountDownLatch(1)
        val blockDrain = java.util.concurrent.CountDownLatch(1)
        val blockingDao = mockk<LogDao>()
        coEvery { blockingDao.insertAll(any()) } coAnswers {
            drainStarted.countDown()
            blockDrain.await() // blocks the drain coroutine's IO thread
        }

        val capacity = 16
        val testRepo = LogRepository(blockingDao, Dispatchers.IO, bufferCapacity = capacity)

        // Send one entry to start the drain loop and get it stuck on the DAO.
        testRepo.log(dummyEntry())
        drainStarted.await()

        // Drain loop is now blocked. Channel buffer is empty (first entry claimed).
        // 10_000 log() calls should complete quickly: first `capacity` go to buffer, rest are dropped.
        val start = System.currentTimeMillis()
        repeat(10_000) { testRepo.log(dummyEntry()) }
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue("10_000 log() calls took ${elapsedMs}ms, expected <1000ms", elapsedMs < 1000)

        blockDrain.countDown()
        testRepo.shutdown()
    }

    /**
     * droppedLogCount() must increase when the channel overflows.
     */
    @Test(timeout = 10_000)
    fun `droppedLogCount increases when buffer overflows`() {
        val drainStarted = java.util.concurrent.CountDownLatch(1)
        val blockDrain = java.util.concurrent.CountDownLatch(1)
        val blockingDao = mockk<LogDao>()
        coEvery { blockingDao.insertAll(any()) } coAnswers {
            drainStarted.countDown()
            blockDrain.await()
        }

        val capacity = 16
        val testRepo = LogRepository(blockingDao, Dispatchers.IO, bufferCapacity = capacity)

        // Trigger drain loop and get it stuck.
        testRepo.log(dummyEntry())
        drainStarted.await()

        // Now fill beyond capacity so drops accumulate.
        repeat(capacity * 5) { testRepo.log(dummyEntry()) }

        assertTrue(
            "Expected droppedLogCount > 0 after overflow, got ${testRepo.droppedLogCount()}",
            testRepo.droppedLogCount() > 0L,
        )

        blockDrain.countDown()
        testRepo.shutdown()
    }
    @Test
    fun `new observability builders write expected structured events`() = runTest {
        repo.logRequestCancel("req-cancel", "sess-cancel")
        repo.logRateLimitReject("infer", uid = 42, cost = 1.5, requestId = "req-rate", sessionId = "sess-rate")
        repo.logAllowlistPendingRecorded(uid = 43, packageName = "com.example.app", sigShaPrefix = "abcdef123456")
        repo.logFgsPromoted(activeInferenceCount = 1)
        repo.logFgsDemoted(activeInferenceCount = 0)
        repo.logBackendSwitch("GPU", "CPU", "complete")
        repo.logBinderDeathClient(uid = 44, registrationId = "reg")
        repo.logBinderDeathSelf(uid = 45)
        repo.logCrashLoopThrottle(uid = 46, cooldownEndsAtMs = 1234L)
        repo.logStreamFrameTooLarge(frameBytes = 2_000_000, maxFrameBytes = 1_048_576)
        repo.logStreamBackpressure(timeoutMs = 5_000L)
        repo.logSessionQuotaExceeded("sess-quota", ownerUid = 47, ownedNow = 2, cap = 2, tierMaxSessions = 4)
        repo.logToolCallExit("req-tool", "sess-tool", result = "completed", pendingCount = 1)
        repo.logToolCallTimeout("req-timeout", "sess-tool", timeoutMs = 30_000L)

        advanceUntilIdle()
        val byEvent = dao.getRecent(50).associateBy { it.event }

        assertEquals(LogCategory.INFERENCE, byEvent.getValue(LogEvent.REQUEST_CANCEL.key).category)
        assertEquals(LogCategory.SECURITY, byEvent.getValue(LogEvent.RATE_LIMIT_REJECT.key).category)
        assertEquals(LogCategory.SECURITY, byEvent.getValue(LogEvent.ALLOWLIST_PENDING_RECORDED.key).category)
        assertEquals(LogCategory.ENGINE, byEvent.getValue(LogEvent.FGS_PROMOTED.key).category)
        assertEquals(LogCategory.ENGINE, byEvent.getValue(LogEvent.FGS_DEMOTED.key).category)
        assertEquals(LogCategory.ENGINE, byEvent.getValue(LogEvent.BACKEND_SWITCH.key).category)
        assertEquals(LogCategory.SECURITY, byEvent.getValue(LogEvent.BINDER_DEATH_CLIENT.key).category)
        assertEquals(LogCategory.SECURITY, byEvent.getValue(LogEvent.BINDER_DEATH_SELF.key).category)
        assertEquals(LogCategory.SECURITY, byEvent.getValue(LogEvent.CRASH_LOOP_THROTTLE.key).category)
        assertEquals(LogCategory.INFERENCE, byEvent.getValue(LogEvent.STREAM_FRAME_TOO_LARGE.key).category)
        assertEquals(LogCategory.INFERENCE, byEvent.getValue(LogEvent.STREAM_BACKPRESSURE.key).category)
        assertEquals(LogCategory.SESSION, byEvent.getValue(LogEvent.SESSION_QUOTA_EXCEEDED.key).category)
        assertEquals(LogCategory.INFERENCE, byEvent.getValue(LogEvent.TOOL_CALL_EXIT.key).category)
        assertEquals(LogCategory.INFERENCE, byEvent.getValue(LogEvent.TOOL_CALL_TIMEOUT.key).category)
    }
    @Test
    fun `recentErrorCount reflects recent completed error bucket`() = runTest {
        val now = System.currentTimeMillis()
        dao.insert(LogEntry(timestampMs = now - 5_000, category = LogCategory.ERROR, event = LogEvent.REQUEST_ERROR.key))
        dao.insert(LogEntry(timestampMs = now - 120_000, category = LogCategory.ERROR, event = LogEvent.REQUEST_ERROR.key))
        dao.insert(LogEntry(timestampMs = now - 5_000, category = LogCategory.INFERENCE, event = LogEvent.REQUEST_COMPLETE.key))

        assertEquals(1, repo.recentErrorCount(windowMs = 60_000L))
    }
}
