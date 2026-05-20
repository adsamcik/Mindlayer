package com.adsamcik.mindlayer.service.logging

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogRepositoryOcrEventsTest {
    private lateinit var db: LogDatabase
    private lateinit var dao: LogDao
    private lateinit var repo: LogRepository

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        dao = db.logDao()
        repo = LogRepository(dao, Dispatchers.Unconfined)
    }

    @After fun teardown() {
        repo.shutdown()
        db.close()
        unmockkAll()
    }

    @Test fun ocrBackendReadyPersistsMetadataOnly() = runTest {
        repo.logOcrBackendReady(backend = "CPU", bundleId = "paddleocr", durationMs = 12)
        advanceUntilIdle()
        val entry = dao.getRecent(10).single()
        assertEquals(LogEvent.OCR_BACKEND_READY, entry.event)
        assertEquals("CPU", entry.backend)
        assertEquals(12L, entry.durationMs)
        val extra = Json.parseToJsonElement(entry.extraJson!!).jsonObject
        assertEquals("paddleocr", extra["bundleId"].toString().trim("\"".single()))
        assertFalse(entry.extraJson!!.contains("Path"))
        assertFalse(entry.extraJson!!.contains("/data/"))
        assertNull(entry.errorMessage)
    }

    @Test fun ocrBackendShutdownPersistsMetadataOnly() = runTest {
        repo.logOcrBackendShutdown(backend = "CPU", bundleId = "paddleocr", durationMs = 3)
        advanceUntilIdle()
        val entry = dao.getRecent(10).single()
        assertEquals(LogEvent.OCR_BACKEND_SHUTDOWN, entry.event)
        assertEquals("CPU", entry.backend)
        assertEquals(3L, entry.durationMs)
        assertFalse(entry.extraJson!!.contains("detectionPath"))
        assertFalse(entry.extraJson!!.contains("recognitionPath"))
        assertFalse(entry.extraJson!!.contains("classifierPath"))
    }
}
