package com.adsamcik.mindlayer.service.engine

import android.os.Bundle
import com.adsamcik.mindlayer.CancelResult
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
 * Tests for [DeferredStore] using a hand-rolled in-memory [DeferredDao] fake.
 *
 * The real [DeferredDatabase] is SQLCipher-backed (native libsqlcipher.so),
 * which is impractical to load under Robolectric. The fake reproduces the
 * SQL semantics encoded in the DAO annotations so we can exercise every
 * code path in [DeferredStore] without touching Room.
 *
 * Robolectric is still needed because `android.os.Bundle` (used for
 * metrics persistence roundtripping) is not a pure-JVM class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeferredStoreTest {

    private lateinit var dao: FakeDeferredDao
    private var now: Long = 1_000L
    private val clock: () -> Long = { now }

    @Before
    fun setUp() {
        dao = FakeDeferredDao()
        now = 1_000L
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun store(
        ttlMs: Long = 60_000L,
        maxRunning: Int = 4,
        maxPending: Int = 4,
        maxBytesUid: Long = 1024L * 1024L,
        maxBytesResult: Int = 256 * 1024,
    ) = DeferredStore(
        dao = dao,
        clock = clock,
        ttlMs = ttlMs,
        maxRunningPerUid = maxRunning,
        maxCompletedPendingPerUid = maxPending,
        maxResultBytesPerUid = maxBytesUid,
        maxResultBytesPerResult = maxBytesResult,
    )

    private fun meta(requestId: String = "req-1", sessionId: String = "sess-1", text: String? = "hi") =
        RequestMeta(requestId = requestId, sessionId = sessionId, textContent = text)

    // ── create() ───────────────────────────────────────────────────────────

    @Test
    fun `create returns handle with correct requestId and expiresAtMs`() = runTest {
        val s = store(ttlMs = 5_000L)
        now = 10_000L
        val handle = s.create(uid = 100, requestId = "req-1", meta = meta("req-1"), mediaCount = 0)
        assertNotNull(handle)
        assertEquals("req-1", handle!!.requestId)
        assertEquals(15_000L, handle.expiresAtMs)
    }

    @Test
    fun `create returns null when running cap is reached for that uid`() = runTest {
        val s = store(maxRunning = 2)
        assertNotNull(s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0))
        assertNotNull(s.create(uid = 1, requestId = "b", meta = meta("b"), mediaCount = 0))
        assertNull(s.create(uid = 1, requestId = "c", meta = meta("c"), mediaCount = 0))
        // Other uid is not affected.
        assertNotNull(s.create(uid = 2, requestId = "d", meta = meta("d"), mediaCount = 0))
    }

    @Test
    fun `create returns null when pending-completed cap is reached`() = runTest {
        val s = store(maxRunning = 16, maxPending = 2)
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        s.completeReady("a", uid = 1, text = "x", metrics = null)
        s.create(uid = 1, requestId = "b", meta = meta("b"), mediaCount = 0)
        s.completeReady("b", uid = 1, text = "y", metrics = null)
        // Two completed entries are pending fetch -> cap hit.
        assertNull(s.create(uid = 1, requestId = "c", meta = meta("c"), mediaCount = 0))
    }

    // ── fetch() ────────────────────────────────────────────────────────────

    @Test
    fun `fetch unknown requestId returns NOT_FOUND_OR_NOT_OWNED`() = runTest {
        val s = store()
        val r = s.fetch(uid = 1, requestId = "missing")
        assertEquals(DeferredResult.NOT_FOUND_OR_NOT_OWNED, r.status)
    }

    @Test
    fun `fetch wrong uid returns NOT_FOUND_OR_NOT_OWNED (anti-enumeration)`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        val r = s.fetch(uid = 2, requestId = "a")
        assertEquals(DeferredResult.NOT_FOUND_OR_NOT_OWNED, r.status)
    }

    @Test
    fun `fetch returns STILL_RUNNING for in-flight entry`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        val r = s.fetch(uid = 1, requestId = "a")
        assertEquals(DeferredResult.STILL_RUNNING, r.status)
    }

    @Test
    fun `fetch returns READY with text and marks fetched`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        s.completeReady("a", uid = 1, text = "hello world", metrics = null)
        val r = s.fetch(uid = 1, requestId = "a")
        assertEquals(DeferredResult.READY, r.status)
        assertEquals("hello world", r.text)
        // markFetched recorded a non-null fetchedAtMs in the dao.
        assertNotNull(dao.snapshot("a")?.fetchedAtMs)
    }

    @Test
    fun `fetch expired entry returns EXPIRED before prune (M-D1)`() = runTest {
        val s = store(ttlMs = 1_000L)
        now = 1_000L
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        // Move time past expiry. Entry is still in the database because
        // pruneExpired is only triggered by create/cancel/acknowledge, and
        // fetch must look up BEFORE delete so it can return EXPIRED rather
        // than NOT_FOUND.
        now = 999_999L
        val r = s.fetch(uid = 1, requestId = "a")
        assertEquals(DeferredResult.EXPIRED, r.status)
        assertEquals(MindlayerErrorCode.DEFERRED_EXPIRED, r.errorCodeInt)
        assertEquals("DEFERRED_EXPIRED", r.errorCodeName)
        // And the expired row was cleaned up so the next fetch sees NOT_FOUND.
        val r2 = s.fetch(uid = 1, requestId = "a")
        assertEquals(DeferredResult.NOT_FOUND_OR_NOT_OWNED, r2.status)
    }

    // ── cancel() ───────────────────────────────────────────────────────────

    @Test
    fun `cancel flips running to CANCELLED and second cancel returns ALREADY_FINISHED`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        assertEquals(CancelResult.CANCELLED, s.cancel(uid = 1, requestId = "a"))
        val snap = dao.snapshot("a")
        assertEquals(DeferredResult.CANCELLED, snap?.statusCode)
        assertEquals(CancelResult.ALREADY_FINISHED, s.cancel(uid = 1, requestId = "a"))
    }

    @Test
    fun `cancel cross-uid returns UNKNOWN`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        assertEquals(CancelResult.UNKNOWN, s.cancel(uid = 2, requestId = "a"))
    }

    @Test
    fun `cancel unknown requestId returns UNKNOWN`() = runTest {
        val s = store()
        assertEquals(CancelResult.UNKNOWN, s.cancel(uid = 1, requestId = "nope"))
    }

    // ── acknowledge() ──────────────────────────────────────────────────────

    @Test
    fun `acknowledge deletes a completed row and returns true`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        s.completeReady("a", uid = 1, text = "ok", metrics = null)
        assertTrue(s.acknowledge(uid = 1, requestId = "a"))
        assertNull(dao.snapshot("a"))
    }

    @Test
    fun `acknowledge returns false for unknown and cross-uid`() = runTest {
        val s = store()
        assertFalse(s.acknowledge(uid = 1, requestId = "missing"))
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        s.completeReady("a", uid = 1, text = "ok", metrics = null)
        assertFalse(s.acknowledge(uid = 2, requestId = "a"))
        // Still present after the failed cross-uid ack.
        assertNotNull(dao.snapshot("a"))
    }

    // ── failRunningOnInit() ────────────────────────────────────────────────

    @Test
    fun `failRunningOnInit flips all running entries to FAILED with INTERNAL`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        s.create(uid = 2, requestId = "b", meta = meta("b"), mediaCount = 0)
        s.create(uid = 1, requestId = "c", meta = meta("c"), mediaCount = 0)
        s.completeReady("c", uid = 1, text = "done", metrics = null) // not running

        val flipped = s.failRunningOnInit()
        assertEquals(2, flipped)
        assertEquals(DeferredResult.FAILED, dao.snapshot("a")?.statusCode)
        assertEquals(MindlayerErrorCode.INTERNAL, dao.snapshot("a")?.errorCodeInt)
        assertEquals("INTERNAL", dao.snapshot("a")?.errorCodeName)
        assertEquals(DeferredResult.FAILED, dao.snapshot("b")?.statusCode)
        // The pre-existing READY entry is untouched.
        assertEquals(DeferredResult.READY, dao.snapshot("c")?.statusCode)
    }

    // ── completeReady() truncation (M-D4) ─────────────────────────────────

    @Test
    fun `completeReady truncates text beyond maxResultBytesPerResult and flags truncated`() = runTest {
        val cap = 32
        val s = store(maxBytesResult = cap)
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        val tooBig = "x".repeat(cap * 4)
        s.completeReady("a", uid = 1, text = tooBig, metrics = Bundle())
        val r = s.fetch(uid = 1, requestId = "a")
        assertEquals(DeferredResult.READY, r.status)
        assertEquals(cap, r.text!!.length)
        assertTrue(
            "metrics should include truncated=true",
            r.metrics?.getBoolean(DeferredStore.Metrics.TRUNCATED, false) == true,
        )
    }

    @Test
    fun `completeReady does not flag truncated when within cap`() = runTest {
        val cap = 1024
        val s = store(maxBytesResult = cap)
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        s.completeReady("a", uid = 1, text = "small", metrics = Bundle())
        val r = s.fetch(uid = 1, requestId = "a")
        assertEquals("small", r.text)
        assertEquals(false, r.metrics?.getBoolean(DeferredStore.Metrics.TRUNCATED, false))
    }

    // ── metrics roundtrip (M-D6) ──────────────────────────────────────────

    @Test
    fun `metrics roundtrip preserves Int Long Float Double Boolean types`() = runTest {
        val s = store()
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        val metrics = Bundle().apply {
            putInt("tokensIn", 42)
            putLong("startedAtMs", 9_876_543_210L)
            putFloat("temperature", 0.7f)
            putDouble("avgLatency", 12.5)
            putBoolean("usedGpu", true)
        }
        s.completeReady("a", uid = 1, text = "ok", metrics = metrics)

        val r = s.fetch(uid = 1, requestId = "a")
        val out = r.metrics
        assertNotNull(out)
        // Int stays int.
        assertEquals(42, out!!.getInt("tokensIn"))
        // Long stays long — bug guarded against was Long being collapsed to Int.
        assertEquals(9_876_543_210L, out.getLong("startedAtMs"))
        // The JSON pipeline collapses Float to Double (Float has no JSON
        // representation distinct from Double); assert read-back as Double
        // with a tolerance matching float precision.
        assertEquals(0.7, out.getDouble("temperature"), 1e-6)
        assertEquals(12.5, out.getDouble("avgLatency"), 1e-9)
        assertEquals(true, out.getBoolean("usedGpu"))
    }

    // ── byte-quota FIFO eviction ──────────────────────────────────────────

    @Test
    fun `byte quota evicts oldest completed entry FIFO`() = runTest {
        // Cap per uid to 5 bytes; results of length 4 will force eviction.
        val s = store(maxRunning = 16, maxPending = 16, maxBytesUid = 5L)
        // Three entries of 4 chars each. After completing all three,
        // total = 12 bytes which exceeds 5; the loop evicts oldest first.
        s.create(uid = 1, requestId = "a", meta = meta("a"), mediaCount = 0)
        now = 100L
        s.completeReady("a", uid = 1, text = "AAAA", metrics = null)
        s.create(uid = 1, requestId = "b", meta = meta("b"), mediaCount = 0)
        now = 200L
        s.completeReady("b", uid = 1, text = "BBBB", metrics = null)
        s.create(uid = 1, requestId = "c", meta = meta("c"), mediaCount = 0)
        now = 300L
        s.completeReady("c", uid = 1, text = "CCCC", metrics = null)

        // The byte budget is 5; with only the newest entry (4 bytes), we
        // are within budget. Older `a` and `b` must have been evicted.
        assertNull("oldest 'a' must be evicted first", dao.snapshot("a"))
        assertNull("next-oldest 'b' must also be evicted", dao.snapshot("b"))
        assertNotNull("newest 'c' survives", dao.snapshot("c"))
    }
}
