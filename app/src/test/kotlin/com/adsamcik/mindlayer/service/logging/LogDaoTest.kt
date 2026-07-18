package com.adsamcik.mindlayer.service.logging

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
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
class LogDaoTest {

    private lateinit var db: LogDatabase
    private lateinit var dao: LogDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.logDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    // --- helpers ---

    private fun entry(
        timestampMs: Long = 1000L,
        category: String = LogCategory.INFERENCE,
        event: String = LogEvent.REQUEST_COMPLETE.key,
        sessionId: String? = null,
        requestId: String? = null,
        backend: String? = null,
        durationMs: Long? = null,
        tokensGenerated: Int? = null,
        tokensPerSec: Float? = null,
        thermalBand: String? = null,
        errorMessage: String? = null,
        extraJson: String? = null,
    ) = LogEntry(
        timestampMs = timestampMs,
        category = category,
        event = event,
        sessionId = sessionId,
        requestId = requestId,
        backend = backend,
        durationMs = durationMs,
        tokensGenerated = tokensGenerated,
        tokensPerSec = tokensPerSec,
        thermalBand = thermalBand,
        errorMessage = errorMessage,
        extraJson = extraJson,
    )

    // --- insert & retrieve ---

    @Test
    fun `insert and retrieve single log entry`() = runTest {
        val e = entry(timestampMs = 5000, sessionId = "s1", requestId = "r1")
        val id = dao.insert(e)
        assertTrue(id > 0)

        val results = dao.getRecent(10)
        assertEquals(1, results.size)
        assertEquals("s1", results[0].sessionId)
        assertEquals("r1", results[0].requestId)
    }

    @Test
    fun `usage log table exposes composite query indexes`() {
        val indexes = indexNames("usage_logs")

        assertTrue(indexes.contains("index_usage_logs_request_timestamp"))
        assertTrue(indexes.contains("index_usage_logs_session_timestamp"))
        assertTrue(indexes.contains("index_usage_logs_category_event_timestamp"))
        assertTrue(indexes.contains("index_usage_logs_backend_event_timestamp"))
    }

    @Test
    fun `getRecent returns entries in reverse chronological order`() = runTest {
        dao.insert(entry(timestampMs = 100))
        dao.insert(entry(timestampMs = 300))
        dao.insert(entry(timestampMs = 200))

        val results = dao.getRecent(10)
        assertEquals(3, results.size)
        assertEquals(300L, results[0].timestampMs)
        assertEquals(200L, results[1].timestampMs)
        assertEquals(100L, results[2].timestampMs)
    }

    @Test
    fun `getRecent respects limit parameter`() = runTest {
        repeat(10) { dao.insert(entry(timestampMs = it.toLong())) }

        val results = dao.getRecent(3)
        assertEquals(3, results.size)
    }

    @Test
    fun `getByCategory filters correctly`() = runTest {
        dao.insert(entry(category = LogCategory.INFERENCE))
        dao.insert(entry(category = LogCategory.THERMAL))
        dao.insert(entry(category = LogCategory.INFERENCE))
        dao.insert(entry(category = LogCategory.ERROR))

        val inference = dao.getByCategory(LogCategory.INFERENCE)
        assertEquals(2, inference.size)
        assertTrue(inference.all { it.category == LogCategory.INFERENCE })

        val thermal = dao.getByCategory(LogCategory.THERMAL)
        assertEquals(1, thermal.size)
    }

    @Test
    fun `getBySession filters correctly`() = runBlocking {
        val results = withContext(Dispatchers.IO) {
            dao.insert(entry(sessionId = "sess-A", timestampMs = 100))
            dao.insert(entry(sessionId = "sess-B", timestampMs = 200))
            dao.insert(entry(sessionId = "sess-A", timestampMs = 300))

            dao.getBySession("sess-A")
        }
        assertEquals(2, results.size)
        assertTrue(results.all { it.sessionId == "sess-A" })
    }

    @Test
    fun `getByRequest filters correctly and returns in ASC order`() = runTest {
        dao.insert(entry(requestId = "req-1", timestampMs = 300))
        dao.insert(entry(requestId = "req-2", timestampMs = 100))
        dao.insert(entry(requestId = "req-1", timestampMs = 100))

        val results = dao.getByRequest("req-1")
        assertEquals(2, results.size)
        // ASC order
        assertEquals(100L, results[0].timestampMs)
        assertEquals(300L, results[1].timestampMs)
    }

    // --- aggregates ---

    @Test
    fun `totalInferenceCount counts only INFERENCE request_complete entries`() = runTest {
        dao.insert(entry(category = LogCategory.INFERENCE, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(category = LogCategory.INFERENCE, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(category = LogCategory.INFERENCE, event = LogEvent.REQUEST_START.key))
        dao.insert(entry(category = LogCategory.ERROR, event = LogEvent.REQUEST_COMPLETE.key))

        assertEquals(2, dao.totalInferenceCount())
    }

    @Test
    fun `averageTokensPerSec computes correctly across entries`() = runTest {
        dao.insert(entry(tokensPerSec = 10.0f, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(tokensPerSec = 20.0f, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(tokensPerSec = 30.0f, event = LogEvent.REQUEST_COMPLETE.key))

        val avg = dao.averageTokensPerSec()!!
        assertEquals(20.0f, avg, 0.01f)
    }

    @Test
    fun `averageTokensPerSec ignores entries without tokensPerSec`() = runTest {
        dao.insert(entry(tokensPerSec = 10.0f, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(tokensPerSec = null, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(tokensPerSec = 30.0f, event = LogEvent.REQUEST_COMPLETE.key))

        val avg = dao.averageTokensPerSec()!!
        assertEquals(20.0f, avg, 0.01f)
    }

    @Test
    fun `totalTokensGenerated sums correctly`() = runTest {
        dao.insert(entry(tokensGenerated = 100))
        dao.insert(entry(tokensGenerated = 250))
        dao.insert(entry(tokensGenerated = 50))

        assertEquals(400L, dao.totalTokensGenerated())
    }

    @Test
    fun `averageInferenceDurationMs computes correctly`() = runTest {
        dao.insert(entry(durationMs = 1000, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(durationMs = 2000, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(durationMs = 3000, event = LogEvent.REQUEST_COMPLETE.key))

        val avg = dao.averageInferenceDurationMs()!!
        assertEquals(2000.0f, avg, 0.01f)
    }

    @Test
    fun `averageInferenceDurationMs only includes request_complete events`() = runTest {
        dao.insert(entry(durationMs = 1000, event = LogEvent.REQUEST_COMPLETE.key))
        dao.insert(entry(durationMs = 9999, event = LogEvent.REQUEST_START.key))
        dao.insert(entry(durationMs = 3000, event = LogEvent.REQUEST_COMPLETE.key))

        val avg = dao.averageInferenceDurationMs()!!
        assertEquals(2000.0f, avg, 0.01f)
    }

    @Test
    fun `errorCountSince filters by timestamp correctly`() = runTest {
        dao.insert(entry(category = LogCategory.ERROR, timestampMs = 500))
        dao.insert(entry(category = LogCategory.ERROR, timestampMs = 1500))
        dao.insert(entry(category = LogCategory.ERROR, timestampMs = 2500))
        dao.insert(entry(category = LogCategory.INFERENCE, timestampMs = 2000))

        assertEquals(2, dao.errorCountSince(1000))
        assertEquals(1, dao.errorCountSince(2000))
        assertEquals(0, dao.errorCountSince(3000))
    }

    @Test
    fun `thermalBandDistribution groups and counts correctly`() = runTest {
        dao.insert(entry(category = LogCategory.THERMAL, event = LogEvent.BAND_CHANGE.key, thermalBand = "COOL"))
        dao.insert(entry(category = LogCategory.THERMAL, event = LogEvent.BAND_CHANGE.key, thermalBand = "COOL"))
        dao.insert(entry(category = LogCategory.THERMAL, event = LogEvent.BAND_CHANGE.key, thermalBand = "WARM"))
        dao.insert(entry(category = LogCategory.THERMAL, event = LogEvent.BAND_CHANGE.key, thermalBand = "HOT"))
        // Non-THERMAL entry with thermalBand should be excluded
        dao.insert(entry(category = LogCategory.INFERENCE, event = LogEvent.BAND_CHANGE.key, thermalBand = "COOL"))

        val dist = dao.thermalBandDistribution()
        val map = dist.associate { it.thermalBand to it.count }

        assertEquals(3, dist.size)
        assertEquals(2, map["COOL"])
        assertEquals(1, map["WARM"])
        assertEquals(1, map["HOT"])
    }

    // --- cleanup ---

    @Test
    fun `deleteOlderThan removes old entries and keeps new ones`() = runTest {
        dao.insert(entry(timestampMs = 100))
        dao.insert(entry(timestampMs = 200))
        dao.insert(entry(timestampMs = 300))
        dao.insert(entry(timestampMs = 400))

        val deleted = dao.deleteOlderThan(250)
        assertEquals(2, deleted)

        val remaining = dao.getRecent(10)
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.timestampMs >= 300 })
    }

    @Test
    fun `totalCount returns correct count`() = runTest {
        assertEquals(0, dao.totalCount())

        dao.insert(entry())
        dao.insert(entry())
        dao.insert(entry())

        assertEquals(3, dao.totalCount())
    }

    @Test
    fun `latestInitFailure clears after successful recovery event`() = runTest {
        dao.insert(entry(
            timestampMs = 100,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = """{"failureCategory":"NativeError"}""",
        ))
        assertEquals(100L, dao.latestInitFailure()?.timestampMs)

        dao.insert(entry(
            timestampMs = 200,
            category = LogCategory.ENGINE,
            event = LogEvent.OCR_BACKEND_READY.key,
            backend = "CPU",
        ))

        assertNull(dao.latestInitFailure())
    }

    @Test
    fun `latestBackendDecisionByFeature returns independent latest rows`() = runTest {
        dao.insert(entry(
            timestampMs = 100,
            category = LogCategory.ENGINE,
            event = LogEvent.BACKEND_DECISION.key,
            backend = "CPU",
            extraJson = """{"feature":"chat","reason":"fallback","attempted":[]}""",
        ))
        dao.insert(entry(
            timestampMs = 200,
            category = LogCategory.ENGINE,
            event = LogEvent.BACKEND_DECISION.key,
            backend = "GPU",
            extraJson = """{"feature":"ocr","reason":"preferred","attempted":[]}""",
        ))
        dao.insert(entry(
            timestampMs = 300,
            category = LogCategory.ENGINE,
            event = LogEvent.BACKEND_DECISION.key,
            backend = "NPU",
            extraJson = """{"feature":"chat","reason":"thermal","attempted":[]}""",
        ))

        assertEquals("NPU", dao.latestBackendDecisionByFeature("chat")?.backend)
        assertEquals("GPU", dao.latestBackendDecisionByFeature("ocr")?.backend)
        assertNull(dao.latestBackendDecisionByFeature("embeddings"))
    }

    @Test
    fun `latestInitFailureByFeature isolates roles and clears on matching recovery`() = runTest {
        dao.insert(entry(
            timestampMs = 100,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = """{"failureCategory":"ModelMissing","feature":"chat"}""",
        ))
        dao.insert(entry(
            timestampMs = 200,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = """{"failureCategory":"ModelMissing","feature":"ocr"}""",
        ))

        assertEquals(100L, dao.latestInitFailureByFeature("chat")?.timestampMs)
        assertEquals(200L, dao.latestInitFailureByFeature("ocr")?.timestampMs)

        dao.insert(entry(
            timestampMs = 300,
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT_SUCCESS.key,
        ))

        assertNull(dao.latestInitFailureByFeature("chat"))
        assertEquals(200L, dao.latestInitFailureByFeature("ocr")?.timestampMs)
    }

    @Test
    fun `recovered backend fallback remains visible until clean init or shutdown`() = runTest {
        dao.insert(entry(
            timestampMs = 100,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = """{"failureCategory":"BackendUnavailable","feature":"chat"}""",
        ))
        dao.insert(entry(
            timestampMs = 200,
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT_SUCCESS.key,
        ))
        dao.insert(entry(
            timestampMs = 300,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = """{"failureCategory":"BackendUnavailable","feature":"chat"}""",
        ))

        assertEquals(300L, dao.latestInitFailureByFeature("chat")?.timestampMs)

        dao.insert(entry(
            timestampMs = 400,
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_SHUTDOWN.key,
        ))

        assertNull(dao.latestInitFailureByFeature("chat"))

        dao.insert(entry(
            timestampMs = 500,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = """{"failureCategory":"ModelMissing","feature":"chat"}""",
        ))

        assertEquals(500L, dao.latestInitFailureByFeature("chat")?.timestampMs)

        dao.insert(entry(
            timestampMs = 600,
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT_SUCCESS.key,
        ))

        assertNull(dao.latestInitFailureByFeature("chat"))
    }

    // --- empty database defaults ---

    @Test
    fun `empty database - totalInferenceCount returns 0`() = runTest {
        assertEquals(0, dao.totalInferenceCount())
    }

    @Test
    fun `empty database - averageTokensPerSec returns null`() = runTest {
        assertNull(dao.averageTokensPerSec())
    }

    @Test
    fun `empty database - totalTokensGenerated returns null`() = runTest {
        assertNull(dao.totalTokensGenerated())
    }

    @Test
    fun `empty database - averageInferenceDurationMs returns null`() = runTest {
        assertNull(dao.averageInferenceDurationMs())
    }

    @Test
    fun `empty database - errorCountSince returns 0`() = runTest {
        assertEquals(0, dao.errorCountSince(0))
    }

    @Test
    fun `empty database - thermalBandDistribution returns empty list`() = runTest {
        assertTrue(dao.thermalBandDistribution().isEmpty())
    }

    @Test
    fun `empty database - getRecent returns empty list`() = runTest {
        assertTrue(dao.getRecent(10).isEmpty())
    }

    // --- insertAll ---

    @Test
    fun `insertAll inserts multiple entries`() = runTest {
        val entries = listOf(
            entry(timestampMs = 1),
            entry(timestampMs = 2),
            entry(timestampMs = 3),
        )
        dao.insertAll(entries)
        assertEquals(3, dao.totalCount())
    }

    private fun indexNames(tableName: String): Set<String> {
        val names = mutableSetOf<String>()
        val cursor = db.openHelper.readableDatabase.query("PRAGMA index_list(`$tableName`)")
        try {
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names += cursor.getString(nameColumn)
            }
        } finally {
            cursor.close()
        }
        return names
    }
}
