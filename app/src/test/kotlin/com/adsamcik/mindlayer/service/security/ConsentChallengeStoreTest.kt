package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ConsentChallengeStore]. The store is a pure in-memory
 * registry (no Context), but it logs via `MindlayerLog` on eviction, so the
 * suite runs under Robolectric to stub `android.util.Log`.
 *
 * Pins the security-critical invariants from the crypto/concurrency review:
 *  - single-use nonces (atomic single-winner consume)
 *  - TTL expiry
 *  - lookup is non-consuming
 *  - bounded outstanding set
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsentChallengeStoreTest {

    private var clock = 1_000L
    private fun store(maxOutstanding: Int = 256, ttlMs: Long = 5 * 60 * 1000L) =
        ConsentChallengeStore(
            maxOutstanding = maxOutstanding,
            ttlMs = ttlMs,
            timeSource = { clock },
            secureRandom = SecureRandom(),
        )

    private fun issue(s: ConsentChallengeStore, uid: Int = 10_001, pkg: String = "com.example") =
        s.issue(
            callerUid = uid,
            packageName = pkg,
            signingCertSha256 = "abc123",
            displayName = "Example",
            installSource = "com.android.vending",
            previousSigSha256 = null,
        )

    @Test
    fun `issue returns a record with a non-empty nonce and future expiry`() {
        val s = store()
        val rec = issue(s)
        assertTrue("nonce must be non-empty", rec.nonce.isNotEmpty())
        assertEquals(clock + 5 * 60 * 1000L, rec.expiresAtMs)
        assertEquals("com.example", rec.packageName)
        assertEquals(1, s.outstandingCount())
    }

    @Test
    fun `lookup returns the record without consuming it`() {
        val s = store()
        val rec = issue(s)
        assertNotNull(s.lookup(rec.nonce))
        assertNotNull("lookup must be repeatable", s.lookup(rec.nonce))
        assertEquals(1, s.outstandingCount())
    }

    @Test
    fun `consume returns the record exactly once`() {
        val s = store()
        val rec = issue(s)
        val first = s.consume(rec.nonce)
        assertNotNull("first consume wins", first)
        assertNull("second consume gets nothing", s.consume(rec.nonce))
        assertNull("lookup after consume is null", s.lookup(rec.nonce))
        assertEquals(0, s.outstandingCount())
    }

    @Test
    fun `lookup and consume return null for unknown nonce`() {
        val s = store()
        assertNull(s.lookup("does-not-exist"))
        assertNull(s.consume("does-not-exist"))
        assertNull(s.lookup(null))
        assertNull(s.consume(null))
        assertNull(s.lookup(""))
    }

    @Test
    fun `expired challenge is not returned by lookup or consume`() {
        val s = store(ttlMs = 1_000L)
        val rec = issue(s)
        clock += 1_001L
        assertNull("expired lookup", s.lookup(rec.nonce))
        assertNull("expired consume", s.consume(rec.nonce))
        assertEquals(0, s.outstandingCount())
    }

    @Test
    fun `outstanding cap evicts oldest`() {
        val s = store(maxOutstanding = 2)
        val a = issue(s, pkg = "com.a")
        clock += 1
        issue(s, pkg = "com.b")
        clock += 1
        issue(s, pkg = "com.c") // evicts the oldest (com.a)
        assertEquals(2, s.outstandingCount())
        assertNull("oldest challenge evicted", s.lookup(a.nonce))
    }

    @Test
    fun `concurrent consume yields exactly one winner`() {
        val s = store()
        val rec = issue(s)
        val threads = 16
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val winners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                go.await()
                if (s.consume(rec.nonce) != null) winners.incrementAndGet()
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("exactly one thread may consume a nonce", 1, winners.get())
    }

    @Test
    fun `nonces are unique across many issues`() {
        val s = store(maxOutstanding = 10_000)
        val seen = HashSet<String>()
        repeat(2_000) {
            clock += 1
            val rec = issue(s, pkg = "com.app$it")
            assertTrue("nonce collision at $it", seen.add(rec.nonce))
        }
    }
}
