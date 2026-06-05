package com.adsamcik.mindlayer.service.ipc

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * F-076: bounds enforcement on [SharedMemoryPool] — per-request count
 * cap (`MAX_PFDS_PER_REQUEST`), global PFD count cap
 * (`MAX_GLOBAL_ACTIVE_PFDS`), global staged-bytes cap
 * (`MAX_GLOBAL_STAGED_BYTES`).
 *
 * The pool sits between an AIDL caller and LiteRT-LM; without these caps
 * a misbehaving client could exhaust file descriptors or fill the cache
 * partition by submitting many concurrent media transfers. Bounds must
 * apply BEFORE the per-stage timeout — rejecting at the gate is much
 * cheaper than letting the watchdog fire.
 *
 * # Why we exercise reservation primitives directly
 *
 * The full `stageImage` flow eventually calls `Bitmap.compress(PNG, …)`
 * (raw-pixels path) or `BitmapFactory.decodeFile(…)` (encoded-image
 * probe). Neither runs faithfully on a JVM unit test under Robolectric
 * — see the comment on [BitmapCompressOversizeTest], and the existing
 * pre-existing failures in `SharedMemoryPoolTest` /
 * `SharedMemoryPoolSecurityTest` for the same reason. We therefore
 * verify the reservation accounting via the `@VisibleForTesting`
 * [SharedMemoryPool.tryReserve] / [SharedMemoryPool.releaseReservation]
 * surface and the synchronous [SharedMemoryPool.precheckBounds] gate —
 * the same code paths the production binder thread hits at the
 * orchestrator's pre-flight gate (see [InferenceOrchestrator]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedMemoryPoolBoundsTest {

    private lateinit var cacheDir: File
    private lateinit var pool: SharedMemoryPool

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        pool = SharedMemoryPool(cacheDir)
    }

    @After
    fun tearDown() {
        pool.cleanupAll()
        unmockkAll()
    }

    // =========================================================================
    // Acceptance criterion 1: 17th reservation trips MAX_GLOBAL_ACTIVE_PFDS
    // =========================================================================

    @Test
    fun `17 reservations trip MAX_GLOBAL_ACTIVE_PFDS on the 17th`() {
        repeat(SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS) { i ->
            // Every reservation has a distinct scopedKey so per-request
            // cap (MAX_PFDS_PER_REQUEST=2) doesn't fire first.
            pool.tryReserve("123:req-$i", addBytes = 1024L)
        }
        try {
            pool.tryReserve("123:req-OVER", addBytes = 1024L)
            fail("17th tryReserve should have thrown SharedMemoryPoolExhaustedException")
        } catch (e: SharedMemoryPoolExhaustedException) {
            assertEquals("global_active_pfds", e.reason)
            assertTrue(
                "currentCount should reflect the 16 active reservations: ${e.currentCount}",
                e.currentCount >= SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS,
            )
            assertEquals(SharedMemoryPool.DEFAULT_RETRY_AFTER_MS, e.retryAfterMs)
        }
        // The 17th attempt rolled back its increments — counters must
        // still reflect exactly the 16 successful reservations.
        val (count, _) = pool.reservationSnapshot()
        assertEquals(SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS, count)
    }

    // =========================================================================
    // Acceptance criterion 2: byte cap (100 MB then over-cap addition)
    // =========================================================================

    @Test
    fun `100 MB then 150 MB exceeds MAX_GLOBAL_STAGED_BYTES`() {
        val mb100: Long = 100L * 1024L * 1024L
        val mb150: Long = 150L * 1024L * 1024L
        // First 100 MB reservation fits within the 200 MB cap.
        pool.tryReserve("123:req-bytes-1", addBytes = mb100)
        val (_, bytesAfterFirst) = pool.reservationSnapshot()
        assertEquals(mb100, bytesAfterFirst)

        // 100 + 150 = 250 MB > 200 MB cap.
        try {
            pool.tryReserve("123:req-bytes-2", addBytes = mb150)
            fail("second tryReserve should have thrown when sum exceeds 200 MB cap")
        } catch (e: SharedMemoryPoolExhaustedException) {
            assertEquals("global_staged_bytes", e.reason)
            assertEquals(SharedMemoryPool.DEFAULT_RETRY_AFTER_MS, e.retryAfterMs)
        }

        // The second reservation rolled back — bytes counter must still
        // reflect only the first 100 MB.
        val (countAfter, bytesAfter) = pool.reservationSnapshot()
        assertEquals(1, countAfter)
        assertEquals(mb100, bytesAfter)
    }

    @Test
    fun `precheckBounds rejects when sum of bytes exceeds MAX_GLOBAL_STAGED_BYTES`() {
        // The synchronous binder-thread gate (orchestrator pre-flight
        // path) — non-mutating snapshot check.
        val firstFitsExactly: Long = SharedMemoryPool.MAX_GLOBAL_STAGED_BYTES
        val justOverByOne: Long = SharedMemoryPool.MAX_GLOBAL_STAGED_BYTES + 1L
        assertNull(
            "exactly-at-cap must NOT be rejected",
            pool.precheckBounds(numImages = 1, numAudios = 0, expectedBytes = firstFitsExactly),
        )
        val ex = pool.precheckBounds(numImages = 1, numAudios = 0, expectedBytes = justOverByOne)
        assertNotNull("one byte over the cap must be rejected", ex)
        assertEquals("global_staged_bytes", ex!!.reason)
        assertEquals(SharedMemoryPool.DEFAULT_RETRY_AFTER_MS, ex.retryAfterMs)
    }

    // =========================================================================
    // Acceptance criterion 3: cleanup releases the reservation
    // =========================================================================

    @Test
    fun `cleanup releases the slot so subsequent staging succeeds`() {
        pool.tryReserve("123:req-clean-1", addBytes = 1024L)
        val (count1, bytes1) = pool.reservationSnapshot()
        assertEquals(1, count1)
        assertEquals(1024L, bytes1)

        pool.cleanup("123:req-clean-1")

        val (count2, bytes2) = pool.reservationSnapshot()
        assertEquals("cleanup must decrement the count to 0", 0, count2)
        assertEquals("cleanup must decrement bytes to 0", 0L, bytes2)

        // A fresh reservation succeeds.
        pool.tryReserve("123:req-clean-2", addBytes = 2048L)
        val (count3, bytes3) = pool.reservationSnapshot()
        assertEquals(1, count3)
        assertEquals(2048L, bytes3)
    }

    // =========================================================================
    // Acceptance criterion 4: cleanupAll resets counters
    // =========================================================================

    @Test
    fun `cleanupAll resets counters and subsequent staging works`() {
        // Fill the pool to half capacity.
        repeat(8) { i ->
            pool.tryReserve("123:req-all-$i", addBytes = 1024L)
        }
        val (countBefore, _) = pool.reservationSnapshot()
        assertEquals(8, countBefore)

        pool.cleanupAll()

        val (countAfter, bytesAfter) = pool.reservationSnapshot()
        assertEquals("cleanupAll must reset count to 0", 0, countAfter)
        assertEquals("cleanupAll must reset bytes to 0", 0L, bytesAfter)

        // Confirm we can fill the pool to capacity again — proves the
        // counters didn't leak past the reset.
        repeat(SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS) { i ->
            pool.tryReserve("123:req-after-$i", addBytes = 1024L)
        }
        val (countAfterFill, _) = pool.reservationSnapshot()
        assertEquals(SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS, countAfterFill)
    }

    // =========================================================================
    // Acceptance criterion 5: concurrent stress — count cap respected
    // =========================================================================

    @Test
    fun `20 concurrent reservations respect the count cap`() = runBlocking(Dispatchers.IO) {
        val attempts = 20
        val successes = AtomicInteger(0)
        val rejections = AtomicInteger(0)

        val deferreds = (0 until attempts).map { i ->
            async {
                try {
                    withContext(Dispatchers.IO) {
                        pool.tryReserve("123:req-conc-$i", addBytes = 1024L)
                    }
                    successes.incrementAndGet()
                } catch (e: SharedMemoryPoolExhaustedException) {
                    rejections.incrementAndGet()
                }
            }
        }
        deferreds.awaitAll()

        // The atomic increment-then-check guarantees no double-counting
        // under concurrent access: at the end the counter must be ≤ cap,
        // and at least some attempts must have been rejected.
        assertEquals(
            "All 20 attempts must resolve to either success or rejection",
            attempts,
            successes.get() + rejections.get(),
        )
        val (count, _) = pool.reservationSnapshot()
        assertTrue(
            "Reserved count must never exceed cap (got $count > ${SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS})",
            count <= SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS,
        )
        assertTrue(
            "At least some attempts must have been rejected (got ${rejections.get()})",
            rejections.get() > 0,
        )
        assertTrue(
            "Successes must not exceed cap (got ${successes.get()})",
            successes.get() <= SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS,
        )
        assertEquals(
            "Successes must equal MAX_GLOBAL_ACTIVE_PFDS (no slot left unfilled)",
            SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS,
            successes.get(),
        )
    }

    // =========================================================================
    // Per-request cap: third reservation on the SAME scopedKey trips it
    // =========================================================================

    @Test
    fun `third reservation on same scopedKey trips MAX_PFDS_PER_REQUEST`() {
        val scopedKey = "123:req-perreq"
        pool.tryReserve(scopedKey, addBytes = 1024L)
        pool.tryReserve(scopedKey, addBytes = 1024L)
        // Per-request cap is 2; the third reservation on the same
        // scopedKey must throw even though the global cap has plenty
        // of headroom.
        try {
            pool.tryReserve(scopedKey, addBytes = 1024L)
            fail("third tryReserve on same scopedKey should have thrown")
        } catch (e: SharedMemoryPoolExhaustedException) {
            assertEquals("per_request_pfds", e.reason)
        }
        // Per-request rejection rolled back the global counters too.
        val (count, _) = pool.reservationSnapshot()
        assertEquals("per-request rejection must not have inflated global count", 2, count)
    }

    // =========================================================================
    // releaseReservation
    // =========================================================================

    @Test
    fun `releaseReservation rolls back individual reservations`() {
        pool.tryReserve("123:req-rel-1", addBytes = 1024L)
        pool.tryReserve("123:req-rel-1", addBytes = 2048L)
        val (count1, bytes1) = pool.reservationSnapshot()
        assertEquals(2, count1)
        assertEquals(3072L, bytes1)

        pool.releaseReservation("123:req-rel-1", count = 1, bytes = 1024L)

        val (count2, bytes2) = pool.reservationSnapshot()
        assertEquals(1, count2)
        assertEquals(2048L, bytes2)
    }

    // =========================================================================
    // Double-release / idempotency regression (P0). releaseReservation must
    // never drive the global counters negative or leak capacity, even when it
    // is called twice, after cleanup(), or with a larger count/bytes than the
    // per-request ledger actually holds. Before the fix the global counters
    // were decremented unconditionally BEFORE the ledger lookup, so a
    // double-release (e.g. a staging-failure release racing a cleanup) drove
    // activeCount/activeBytes negative and corrupted pool accounting.
    // =========================================================================

    @Test
    fun `double releaseReservation does not drive counters negative`() {
        pool.tryReserve("123:req-dbl", addBytes = 4096L)
        pool.releaseReservation("123:req-dbl", count = 1, bytes = 4096L)
        // Second (duplicate) release for the same key must be a safe no-op.
        pool.releaseReservation("123:req-dbl", count = 1, bytes = 4096L)

        val (count, bytes) = pool.reservationSnapshot()
        assertEquals("count must clamp at 0, not go negative", 0, count)
        assertEquals("bytes must clamp at 0, not go negative", 0L, bytes)
    }

    @Test
    fun `releaseReservation after cleanup is a no-op`() {
        pool.tryReserve("123:req-after-clean", addBytes = 8192L)
        pool.cleanup("123:req-after-clean")
        // A staging-failure path that releases AFTER cleanup already ran must
        // not double-decrement the globals.
        pool.releaseReservation("123:req-after-clean", count = 1, bytes = 8192L)

        val (count, bytes) = pool.reservationSnapshot()
        assertEquals(0, count)
        assertEquals(0L, bytes)
    }

    @Test
    fun `releaseReservation clamps when asked to release more than is held`() {
        pool.tryReserve("123:req-clamp", addBytes = 1024L)
        // Ask to release far more than the single 1-PFD / 1024-byte reservation.
        pool.releaseReservation("123:req-clamp", count = 5, bytes = 1_000_000L)

        val (count, bytes) = pool.reservationSnapshot()
        assertEquals("over-release must clamp to the ledger, not go negative", 0, count)
        assertEquals(0L, bytes)
    }

    @Test
    fun `counters recover to full capacity after a double-release`() {
        pool.tryReserve("123:req-x", addBytes = 1024L)
        pool.releaseReservation("123:req-x", count = 1, bytes = 1024L)
        // Duplicate release — would have driven activeCount negative before the
        // fix, letting the fill below overshoot the cap.
        pool.releaseReservation("123:req-x", count = 1, bytes = 1024L)

        repeat(SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS) { i ->
            pool.tryReserve("123:req-fill-$i", addBytes = 1024L)
        }
        val (count, _) = pool.reservationSnapshot()
        assertEquals(SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS, count)
        // And the (cap+1)th still trips the global cap exactly — proving no
        // negative drift and no leaked capacity.
        try {
            pool.tryReserve("123:req-over", addBytes = 1024L)
            fail("should trip global cap after a clean fill")
        } catch (e: SharedMemoryPoolExhaustedException) {
            assertEquals("global_active_pfds", e.reason)
        }
    }

    // =========================================================================
    // precheckBounds (the synchronous binder-thread gate)
    // =========================================================================

    @Test
    fun `precheckBounds returns null when capacity is available`() {
        assertNull(pool.precheckBounds(numImages = 1, numAudios = 0, expectedBytes = 1024L))
        assertNull(pool.precheckBounds(numImages = 1, numAudios = 1, expectedBytes = 50L * 1024 * 1024))
    }

    @Test
    fun `precheckBounds detects per-request cap violation`() {
        val ex = pool.precheckBounds(numImages = 2, numAudios = 1, expectedBytes = 1024L)
        assertNotNull(ex)
        assertEquals("per_request_pfds", ex!!.reason)
    }

    @Test
    fun `precheckBounds detects active-pfd-cap violation when pool already full`() {
        repeat(SharedMemoryPool.MAX_GLOBAL_ACTIVE_PFDS) { i ->
            pool.tryReserve("123:req-fill-$i", addBytes = 1024L)
        }
        val ex = pool.precheckBounds(numImages = 1, numAudios = 0, expectedBytes = 1024L)
        assertNotNull(ex)
        assertEquals("global_active_pfds", ex!!.reason)
    }

    // =========================================================================
    // Wire-message shape (used by SDK retryAfterMs parser)
    // =========================================================================

    @Test
    fun `exception message exposes structured retryAfterMs payload`() {
        val ex = SharedMemoryPoolExhaustedException(
            reason = "global_active_pfds",
            currentCount = 16,
            currentBytes = 0L,
            retryAfterMs = 1500L,
        )
        assertTrue(
            "message must embed retryAfterMs marker so SDK regex can parse it: ${ex.message}",
            (ex.message ?: "").contains("retryAfterMs=1500"),
        )
    }
}
