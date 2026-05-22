package com.adsamcik.mindlayer.service.ui

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.service.logging.LogCategory
import com.adsamcik.mindlayer.service.logging.LogDatabase
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.LogEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentLogsViewModelTest {

    private lateinit var db: LogDatabase
    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LogDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        LogDatabase.setInstance(db)
    }

    @After
    fun teardown() {
        LogDatabase.clearInstance()
        if (db.isOpen) db.close()
    }

    @Test
    fun `empty database produces empty logs and finishes loading`() = runTest {
        val viewModel = RecentLogsViewModel(application)
        viewModel.loadLogs()

        val state = awaitLoaded(viewModel) { it.logs.isEmpty() && !it.isLoading }
        assertTrue(state.logs.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `loadLogs maps DAO output preserving descending order and formatting`() = runTest {
        // Insert 5 entries with ascending timestamps; DAO.getRecent returns DESC.
        val sessionId = "abcdef0123456789"
        val dao = db.logDao()
        dao.insert(
            LogEntry(
                timestampMs = 100L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_START.key,
                sessionId = sessionId,
                backend = "GPU",
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 200L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_COMPLETE.key,
                sessionId = sessionId,
                backend = "GPU",
                durationMs = 1500L,
                tokensGenerated = 42,
                tokensPerSec = 28.5f,
                thermalBand = "WARM",
                memoryAvailableMb = 1024L,
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 300L,
                category = LogCategory.ERROR,
                event = LogEvent.REQUEST_ERROR.key,
                sessionId = sessionId,
                errorMessage = "boom",
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 400L,
                category = LogCategory.SESSION,
                event = LogEvent.SESSION_CREATED.key,
                sessionId = sessionId,
                extraJson = """{"maxTokens":2048}""",
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 500L,
                category = LogCategory.THERMAL,
                event = LogEvent.BAND_CHANGE.key,
                thermalBand = "HOT",
                backend = "CPU",
            ),
        )

        val viewModel = RecentLogsViewModel(application)
        viewModel.loadLogs()
        val state = awaitLoaded(viewModel) { it.logs.size == 5 && !it.isLoading }

        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(5, state.logs.size)

        // Newest first: thermal band_change at ts=500
        val newest = state.logs[0]
        assertEquals("THERMAL", newest.category)
        assertEquals("Band change", newest.event)
        assertTrue(newest.detail.contains("band=HOT"))
        assertTrue(newest.detail.contains("CPU"))

        // Index 1: session_created with extraJson fallback (no diagnostic fields besides session)
        val sessionCreated = state.logs[1]
        assertEquals("SESSION", sessionCreated.category)
        assertEquals("Session created", sessionCreated.event)
        // session field is present, so detail uses parts (not extraJson fallback)
        assertTrue(sessionCreated.detail.startsWith("session=abcdef01"))

        // Index 2: error
        val errorEntry = state.logs[2]
        assertEquals("ERROR", errorEntry.category)
        assertEquals("Request error", errorEntry.event)
        assertTrue(errorEntry.detail.contains("session=abcdef01"))
        assertTrue(errorEntry.detail.contains("boom"))

        // Index 3: request_complete with all diagnostic fields
        val complete = state.logs[3]
        assertEquals("INFERENCE", complete.category)
        assertEquals("Request complete", complete.event)
        assertTrue(complete.detail.contains("session=abcdef01"))
        assertTrue(complete.detail.contains("1500ms"))
        assertTrue(complete.detail.contains("42 tokens"))
        assertTrue(complete.detail.contains("28.5 tok/s"))
        assertTrue(complete.detail.contains("band=WARM"))
        assertTrue(complete.detail.contains("GPU"))
        assertTrue(complete.detail.contains("1024MB free"))

        // Index 4 oldest: request_start
        val start = state.logs[4]
        assertEquals("Request start", start.event)
    }

    @Test
    fun `extraJson fallback rejects arbitrary keys`() = runTest {
        db.logDao().insert(
            LogEntry(
                timestampMs = 1L,
                category = LogCategory.ENGINE,
                event = LogEvent.ENGINE_INIT.key,
                extraJson = """{"secret":"raw-model-tool-name","len":19}""",
            ),
        )

        val viewModel = RecentLogsViewModel(application)
        viewModel.loadLogs()
        val state = awaitLoaded(viewModel) { it.logs.size == 1 && !it.isLoading }

        val item = state.logs.single()
        assertNull(state.errorMessage)
        assertEquals("""{"len":19}""", item.detail)
        assertFalse(item.detail.contains("secret"))
        assertFalse(item.detail.contains("raw-model-tool-name"))
    }

    @Test
    fun `closed database surfaces an error message and stops loading`() = runTest {
        // Construct VM while DB is open (cached dao reference), then close DB.
        val viewModel = RecentLogsViewModel(application)
        db.close()

        viewModel.loadLogs()
        val state = awaitLoaded(viewModel, timeoutMs = 5_000L) { !it.isLoading }
        assertFalse(state.isLoading)
        assertTrue(state.logs.isEmpty())
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.isNotBlank())
    }
}
