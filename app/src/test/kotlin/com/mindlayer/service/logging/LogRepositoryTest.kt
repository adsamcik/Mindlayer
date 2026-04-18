package com.mindlayer.service.logging

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            .build()
        dao = db.logDao()
        repo = LogRepository(dao)
    }

    @After
    fun teardown() {
        repo.shutdown()
        db.close()
        unmockkAll()
    }

    /** Wait for fire-and-forget coroutines to complete and return the single inserted entry. */
    private suspend fun awaitSingleEntry(): LogEntry {
        // Give the fire-and-forget coroutine time to complete
        Thread.sleep(200)
        val entries = dao.getRecent(10)
        assertEquals("Expected exactly 1 log entry", 1, entries.size)
        return entries[0]
    }

    // --- logInferenceStart ---

    @Test
    fun `logInferenceStart creates entry with correct category event and fields`() = runTest {
        repo.logInferenceStart("req-1", "sess-1", "GPU")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.INFERENCE, e.category)
        assertEquals(LogEvent.REQUEST_START, e.event)
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
        assertEquals(LogEvent.REQUEST_COMPLETE, e.event)
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
    fun `logInferenceError includes error message`() = runTest {
        repo.logInferenceError("req-3", "sess-3", "OOM crash")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ERROR, e.category)
        assertEquals(LogEvent.REQUEST_ERROR, e.event)
        assertEquals("req-3", e.requestId)
        assertEquals("sess-3", e.sessionId)
        assertEquals("OOM crash", e.errorMessage)
    }

    @Test
    fun `logInferenceError allows null sessionId`() = runTest {
        repo.logInferenceError("req-4", null, "No session")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ERROR, e.category)
        assertEquals(null, e.sessionId)
        assertEquals("No session", e.errorMessage)
    }

    // --- logThermalBandChange ---

    @Test
    fun `logThermalBandChange includes from and to bands`() = runTest {
        repo.logThermalBandChange("COOL", "WARM", "GPU")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.THERMAL, e.category)
        assertEquals(LogEvent.BAND_CHANGE, e.event)
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
        assertEquals(LogEvent.SESSION_CREATED, e.event)
        assertEquals("sess-5", e.sessionId)
        assertEquals("GPU", e.backend)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("2048"))
    }

    // --- logSessionDestroyed ---

    @Test
    fun `logSessionDestroyed has correct event`() = runTest {
        repo.logSessionDestroyed("sess-6")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.SESSION, e.category)
        assertEquals(LogEvent.SESSION_DESTROYED, e.event)
        assertEquals("sess-6", e.sessionId)
    }

    // --- logSessionEvicted ---

    @Test
    fun `logSessionEvicted includes reason`() = runTest {
        repo.logSessionEvicted("sess-7", "memory_pressure")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.SESSION, e.category)
        assertEquals(LogEvent.SESSION_EVICTED, e.event)
        assertEquals("sess-7", e.sessionId)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("memory_pressure"))
    }

    // --- logMemoryPressure ---

    @Test
    fun `logMemoryPressure includes available and used memory`() = runTest {
        repo.logMemoryPressure("moderate", 2048, 8192)
        val e = awaitSingleEntry()

        assertEquals(LogCategory.MEMORY, e.category)
        assertEquals(LogEvent.PRESSURE_CHANGE, e.event)
        assertEquals(2048L, e.memoryAvailableMb)
        assertEquals(8192L - 2048L, e.memoryUsedMb)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("moderate"))
    }

    // --- logEngineInit ---

    @Test
    fun `logEngineInit includes backend duration and modelPath`() = runTest {
        repo.logEngineInit("GPU", 3500L, "/data/models/llama.bin")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ENGINE, e.category)
        assertEquals(LogEvent.ENGINE_INIT, e.event)
        assertEquals("GPU", e.backend)
        assertEquals(3500L, e.durationMs)
        assertNotNull(e.extraJson)
        assertTrue(e.extraJson!!.contains("/data/models/llama.bin"))
    }

    // --- logEngineShutdown ---

    @Test
    fun `logEngineShutdown has correct event and backend`() = runTest {
        repo.logEngineShutdown("CPU")
        val e = awaitSingleEntry()

        assertEquals(LogCategory.ENGINE, e.category)
        assertEquals(LogEvent.ENGINE_SHUTDOWN, e.event)
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
            event = LogEvent.REQUEST_COMPLETE,
        ))
        dao.insert(LogEntry(
            timestampMs = oneDayAgo,
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_COMPLETE,
        ))

        assertEquals(2, dao.totalCount())

        repo.cleanup(retentionDays = 7)

        assertEquals(1, dao.totalCount())
        val remaining = dao.getRecent(10)
        assertTrue(remaining[0].timestampMs >= oneDayAgo)
    }
}
