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
class SessionHistoryViewModelTest {

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
    fun `empty database produces empty list and finishes loading`() = runTest {
        val viewModel = SessionHistoryViewModel(application)
        viewModel.loadSessions()

        val state = awaitLoaded(viewModel) { !it.isLoading }
        assertTrue(state.sessions.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `loadSessions maps DAO rows`() = runTest {
        val dao = db.logDao()
        val sessionA = "aaaaaaaaaaaaaaaaaaaaaaaa"
        val sessionB = "bbbbbbbbbbbbbbbbbbbbbbbb"
        // session A: two completes; backend gpu (lowercase) -> uppercased
        dao.insert(
            LogEntry(
                timestampMs = 100L,
                category = LogCategory.SESSION,
                event = LogEvent.SESSION_CREATED,
                sessionId = sessionA,
                backend = "gpu",
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 200L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_COMPLETE,
                sessionId = sessionA,
                backend = "gpu",
                tokensGenerated = 50,
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 300L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_COMPLETE,
                sessionId = sessionA,
                backend = "gpu",
                tokensGenerated = 25,
            ),
        )
        // session B: most recent activity, no backend on its rows
        dao.insert(
            LogEntry(
                timestampMs = 1_000L,
                category = LogCategory.SESSION,
                event = LogEvent.SESSION_CREATED,
                sessionId = sessionB,
            ),
        )

        val viewModel = SessionHistoryViewModel(application)
        viewModel.loadSessions()
        val state = awaitLoaded(viewModel) { it.sessions.size == 2 && !it.isLoading }

        assertNull(state.errorMessage)
        assertEquals(2, state.sessions.size)

        // Most recent first by lastEventMs
        val first = state.sessions[0]
        assertEquals(sessionB, first.sessionId)
        assertEquals(formatSessionIdForDisplay(sessionB), first.displayId)
        assertNull(first.backend)
        assertEquals(0, first.inferenceCount)
        assertEquals(0, first.totalTokens)
        assertNotNull(first.lastActiveLabel)
        assertTrue(first.lastActiveLabel.isNotBlank())

        val second = state.sessions[1]
        assertEquals(sessionA, second.sessionId)
        assertEquals(formatSessionIdForDisplay(sessionA), second.displayId)
        assertEquals("GPU", second.backend)
        assertEquals(2, second.inferenceCount)
        assertEquals(75, second.totalTokens)
        assertTrue(second.createdLabel.isNotBlank())
    }

    @Test
    fun `blank backend on rows is mapped to null`() = runTest {
        val dao = db.logDao()
        dao.insert(
            LogEntry(
                timestampMs = 10L,
                category = LogCategory.SESSION,
                event = LogEvent.SESSION_CREATED,
                sessionId = "sess-blank",
                backend = "   ",
            ),
        )
        val viewModel = SessionHistoryViewModel(application)
        viewModel.loadSessions()
        val state = awaitLoaded(viewModel) { it.sessions.isNotEmpty() && !it.isLoading }
        assertEquals(1, state.sessions.size)
        assertNull(state.sessions[0].backend)
    }

    @Test
    fun `result is capped at 100 sessions by DAO limit`() = runTest {
        val dao = db.logDao()
        // Insert 105 distinct sessions
        repeat(105) { i ->
            dao.insert(
                LogEntry(
                    timestampMs = 1_000L + i,
                    category = LogCategory.SESSION,
                    event = LogEvent.SESSION_CREATED,
                    sessionId = "sess-$i",
                ),
            )
        }
        val viewModel = SessionHistoryViewModel(application)
        viewModel.loadSessions()
        val state = awaitLoaded(viewModel) { it.sessions.isNotEmpty() && !it.isLoading }
        assertTrue("expected ≤ 100, got ${state.sessions.size}", state.sessions.size <= 100)
        assertEquals(100, state.sessions.size)
    }

    @Test
    fun `closed database surfaces error and stops loading`() = runTest {
        val viewModel = SessionHistoryViewModel(application)
        db.close()
        viewModel.loadSessions()
        val state = awaitLoaded(viewModel, timeoutMs = 5_000L) { !it.isLoading }
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.isNotBlank())
    }
}
