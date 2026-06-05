package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ConsentChallengeStore]. The store is disk-backed (an
 * HMAC-signed JSON file under `filesDir`, so the consent nonce survives the
 * `:ml` Service being torn down between `requestConsentChallenge` and
 * `lookupChallenge`), so the suite runs under Robolectric for a real
 * `filesDir` and to stub `android.util.Log`.
 *
 * Pins the security-critical invariants from the crypto/concurrency review:
 *  - single-use nonces (atomic single-winner consume, now cross-process)
 *  - TTL expiry
 *  - lookup is non-consuming
 *  - bounded outstanding set
 *  - durability across new store instances (process-restart proxy)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsentChallengeStoreTest {

    private lateinit var context: Context
    private val dirCounter = AtomicInteger(0)
    private var clock = 1_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() {
        File(context.filesDir, "test_challenges").deleteRecursively()
    }

    private fun store(
        maxOutstanding: Int = 256,
        ttlMs: Long = 5 * 60 * 1000L,
        dirName: String = "test_challenges/s${dirCounter.incrementAndGet()}",
    ) =
        ConsentChallengeStore(
            context = context,
            dirName = dirName,
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
        // Reduced from the in-memory era's 2000: the disk-backed store does a
        // full read-modify-write per issue, so the growing-file cost is
        // quadratic. 300 still exercises uniqueness without a slow suite.
        val s = store(maxOutstanding = 10_000)
        val seen = HashSet<String>()
        repeat(300) {
            clock += 1
            val rec = issue(s, pkg = "com.app$it")
            assertTrue("nonce collision at $it", seen.add(rec.nonce))
        }
    }

    @Test
    fun `challenge survives a new store instance (process-restart proxy)`() {
        // This is the on-device bug: requestConsentChallenge issues into one
        // :ml Service instance, which is torn down before ConsentActivity
        // binds a fresh instance and calls lookupChallenge. A disk-backed
        // store must hand the SAME challenge to a brand-new instance pointed
        // at the same dir.
        val dir = "test_challenges/shared_restart"
        val issuer = store(dirName = dir)
        val rec = issue(issuer)

        val resolver = store(dirName = dir) // simulates the recreated :ml
        val looked = resolver.lookup(rec.nonce)
        assertNotNull("a new store instance must resolve a prior nonce", looked)
        assertEquals(rec.nonce, looked!!.nonce)
        assertEquals("com.example", looked.packageName)
        assertEquals("abc123", looked.signingCertSha256)

        // And consume from the new instance is still single-use.
        assertNotNull(resolver.consume(rec.nonce))
        assertNull(store(dirName = dir).consume(rec.nonce))
    }

    @Test
    fun `tampered challenge file is rejected`() {
        val dir = "test_challenges/tamper"
        val s = store(dirName = dir)
        val rec = issue(s)
        val file = File(context.filesDir, "$dir/consent_challenges.json")
        assertTrue(file.exists())
        // Flip the package name without re-signing → HMAC mismatch → ignored.
        val poisoned = file.readText().replace("com.example", "com.attacker")
        file.writeText(poisoned)
        assertNull("tampered nonce binding must not resolve", store(dirName = dir).lookup(rec.nonce))
    }
}
