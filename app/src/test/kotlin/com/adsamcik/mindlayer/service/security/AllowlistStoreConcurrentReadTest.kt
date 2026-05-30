package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bug #5 regression: prove [AllowlistStore] survives concurrent reads
 * without throwing [java.nio.channels.OverlappingFileLockException].
 *
 * Before the fix, two binder threads calling `isAllowed` simultaneously
 * raced for the same kernel-level [java.nio.channels.FileLock] on
 * `allowlist.lock` / `allowlist.hmac` and the second thread blew up
 * inside `SharedFileLockTable.checkList`. The exception was swallowed
 * by `readSignedArray`, the parsed entries list became `null`, and
 * `authorizeCall` mis-classified an already-approved caller as
 * un-approved with `MLERR:6001:App not authorized — user approval
 * required`. The canonical first-party startup pattern
 * (`registerClient` racing `getCapabilities` on two binder threads)
 * hits this on every cold connect.
 *
 * This test approves a single caller, then hammers `isAllowed` from
 * many threads in parallel and asserts that:
 *   1. No thread throws OverlappingFileLockException (or anything).
 *   2. Every read returns the approved verdict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistStoreConcurrentReadTest {

    @get:Rule
    val testName = TestName()

    private lateinit var context: Context
    private lateinit var store: AllowlistStore
    private lateinit var dirName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dirName = "concurrent_allowlist_${testName.methodName.sanitize()}"
        File(context.filesDir, dirName).deleteRecursively()
        store = AllowlistStore(context, dirName)
    }

    @After
    fun tearDown() {
        File(context.filesDir, dirName).deleteRecursively()
    }

    @Test
    fun `concurrent isAllowed reads never throw and always agree`() {
        val pkg = "com.example.firstparty"
        val sig = "d".repeat(64)
        store.approveDirect(pkg, sig, "First Party")
        assertTrue(store.isAllowed(pkg, sig))

        val threads = 16
        val readsPerThread = 256
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = mutableListOf<Throwable>()
        val approved = AtomicInteger(0)
        val rejected = AtomicInteger(0)

        repeat(threads) {
            executor.submit {
                try {
                    start.await()
                    repeat(readsPerThread) {
                        val ok = store.isAllowed(pkg, sig)
                        if (ok) approved.incrementAndGet() else rejected.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(
            "threads did not finish within 30s",
            done.await(30, TimeUnit.SECONDS),
        )
        executor.shutdownNow()

        synchronized(errors) {
            assertTrue(
                "concurrent isAllowed threw ${errors.size} exception(s); first=${errors.firstOrNull()}",
                errors.isEmpty(),
            )
        }
        assertEquals(
            "every read should have returned true for the approved caller",
            threads * readsPerThread,
            approved.get(),
        )
        assertEquals(0, rejected.get())
    }

    @Test
    fun `concurrent mixed reads and writes never throw`() {
        // Simulates dashboard polling (list/listPending reads) while
        // approve/revoke writes happen — same FileLock contention surface.
        val sig = "e".repeat(64)
        store.approveDirect("com.example.a", sig, "A")
        store.approveDirect("com.example.b", sig, "B")

        val threads = 12
        val opsPerThread = 64
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = mutableListOf<Throwable>()

        repeat(threads) { id ->
            executor.submit {
                try {
                    start.await()
                    repeat(opsPerThread) {
                        when (id % 4) {
                            0 -> store.isAllowed("com.example.a", sig)
                            1 -> store.list()
                            2 -> store.listPending()
                            3 -> store.isDenied("com.example.a", sig)
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(
            "mixed-op threads did not finish within 30s",
            done.await(30, TimeUnit.SECONDS),
        )
        executor.shutdownNow()

        synchronized(errors) {
            assertTrue(
                "mixed concurrent ops threw ${errors.size} exception(s); first=${errors.firstOrNull()}",
                errors.isEmpty(),
            )
        }
    }

    private fun String.sanitize(): String = replace(Regex("[^A-Za-z0-9_]"), "_")
}
