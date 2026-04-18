package com.mindlayer.service.ui

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mindlayer.service.logging.LogCategory
import com.mindlayer.service.logging.LogDatabase
import com.mindlayer.service.logging.LogEntry
import com.mindlayer.service.logging.LogEvent
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
class SessionDetailViewModelTest {

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
    fun `unknown session id surfaces empty message and finishes loading`() = runTest {
        val viewModel = SessionDetailViewModel(application)
        val sessionId = "missing-session-id-1234567890"
        viewModel.loadSession(sessionId)

        val state = awaitLoaded(viewModel) { !it.isLoading }
        assertFalse(state.isLoading)
        assertEquals(sessionId, state.sessionId)
        assertEquals(formatSessionIdForDisplay(sessionId), state.displayId)
        assertNotNull(state.emptyMessage)
        assertTrue(state.events.isEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun `loadSession orders events newest first and computes aggregates`() = runTest {
        val dao = db.logDao()
        val sessionId = "sess-detail-0123456789"
        dao.insert(
            LogEntry(
                timestampMs = 1_000L,
                category = LogCategory.SESSION,
                event = LogEvent.SESSION_CREATED,
                sessionId = sessionId,
                backend = "gpu",
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 2_000L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_COMPLETE,
                sessionId = sessionId,
                requestId = "req-1",
                backend = "gpu",
                durationMs = 1_000L,
                tokensGenerated = 20,
                tokensPerSec = 10.0f,
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 3_000L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_COMPLETE,
                sessionId = sessionId,
                requestId = "req-2",
                backend = "gpu",
                durationMs = 1_500L,
                tokensGenerated = 30,
                tokensPerSec = 30.0f,
            ),
        )
        dao.insert(
            LogEntry(
                timestampMs = 4_000L,
                category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_START,
                sessionId = sessionId,
            ),
        )

        val viewModel = SessionDetailViewModel(application)
        viewModel.loadSession(sessionId)
        val state = awaitLoaded(viewModel) { it.events.size == 4 && !it.isLoading }

        assertFalse(state.isLoading)
        assertNull(state.emptyMessage)
        assertNull(state.errorMessage)
        assertEquals(sessionId, state.sessionId)
        assertEquals(formatSessionIdForDisplay(sessionId), state.displayId)
        assertEquals("GPU", state.backend)
        assertEquals(4, state.eventCount)
        assertEquals(2, state.inferenceCount)
        assertEquals(50, state.totalTokens)
        // Average of 10 and 30
        assertEquals("20.0", state.avgTokensPerSec)

        // Newest first ordering
        assertEquals("Request Start", state.events[0].event)
        assertEquals("Request Complete", state.events[1].event)
        assertEquals("Request Complete", state.events[2].event)
        assertEquals("Session Created", state.events[3].event)

        // Labels
        assertTrue(state.startedLabel.isNotBlank())
        assertTrue(state.lastEventLabel.isNotBlank())
        assertTrue(state.durationLabel.isNotBlank())
    }

    @Test
    fun `entries with all-null diagnostic fields produce no crash and empty avg`() = runTest {
        val sessionId = "sess-bare"
        db.logDao().insert(
            LogEntry(
                timestampMs = 100L,
                category = LogCategory.SESSION,
                event = LogEvent.SESSION_CREATED,
                sessionId = sessionId,
            ),
        )
        db.logDao().insert(
            LogEntry(
                timestampMs = 200L,
                category = LogCategory.SESSION,
                event = LogEvent.SESSION_DESTROYED,
                sessionId = sessionId,
            ),
        )

        val viewModel = SessionDetailViewModel(application)
        viewModel.loadSession(sessionId)
        val state = awaitLoaded(viewModel) { it.events.size == 2 && !it.isLoading }

        assertFalse(state.isLoading)
        assertNull(state.backend)
        assertEquals(0, state.inferenceCount)
        assertEquals(0, state.totalTokens)
        assertEquals("", state.avgTokensPerSec)
        // Both events present, no crash
        assertEquals(2, state.events.size)
        state.events.forEach { event ->
            // detail may be empty if no diagnostic fields; just ensure no NPE happened
            assertNotNull(event.detail)
        }
    }

    @Test
    fun `closed database surfaces an error message and stops loading`() = runTest {
        val viewModel = SessionDetailViewModel(application)
        db.close()
        viewModel.loadSession("any-session")
        val state = awaitLoaded(viewModel, timeoutMs = 5_000L) { !it.isLoading }
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.isNotBlank())
    }
}
