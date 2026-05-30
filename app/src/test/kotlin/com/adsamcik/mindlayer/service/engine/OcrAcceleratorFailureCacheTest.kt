package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [OcrAcceleratorFailureCache].
 *
 * Coverage:
 *  - Empty file → snapshot() returns null (no throw at construction)
 *  - recordFailure persists JSON with the documented shape on disk
 *  - clear() wipes the file and the in-memory snapshot
 *  - clock advance past cooldownMs flips isInCooldown false
 *  - Concurrent recordFailure writes are atomic — neither corrupts the file
 *  - Multiple recordFailure for the same backend increments failureCount
 *  - Malformed JSON → snapshot() returns null
 *  - Schema-version mismatch (forward-compat) → snapshot() returns null
 *  - CPU failures are defensively ignored (would disable OCR entirely)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrAcceleratorFailureCacheTest {

    @get:Rule val testName = TestName()

    private lateinit var baseDir: File
    private val clockNowMs = AtomicLong(1_780_162_345_678L)
    private val clock: () -> Long = { clockNowMs.get() }

    @Before fun setUp() {
        baseDir = File(
            System.getProperty("java.io.tmpdir"),
            "ocr-accel-cache-test-${testName.methodName.sanitizedForPath()}",
        ).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun newCache(cooldownMs: Long = 24L * 60L * 60L * 1000L) =
        OcrAcceleratorFailureCache(
            baseDir = baseDir,
            clock = clock,
            cooldownMs = cooldownMs,
        )

    @Test fun `snapshot returns null when no file exists`() {
        val cache = newCache()
        assertNull(cache.snapshot())
        assertFalse(cache.isInCooldown())
    }

    @Test fun `recordFailure persists FailureRecord readable via snapshot`() {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val snap = cache.snapshot()
        assertNotNull("snapshot must not be null after recordFailure", snap)
        assertEquals("GPU", snap!!.lastFailedBackend)
        assertEquals("LiteRtException", snap.lastFailedSafeLabel)
        assertEquals(clockNowMs.get(), snap.lastFailedAtMs)
        assertEquals(1, snap.failureCount)
        assertEquals(OcrAcceleratorFailureCache.CURRENT_SCHEMA_VERSION, snap.schemaVersion)
        assertTrue("cache must report in cooldown", cache.isInCooldown())
    }

    @Test fun `recordFailure on-disk JSON has schemaVersion first and contains no message field`() {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val file = File(File(baseDir, ""), OcrAcceleratorFailureCache.STATE_FILE_NAME)
        // Need to walk into the same dir layout the cache uses. The internal
        // ctor uses baseDir as the directory directly.
        val stateFile = File(baseDir, OcrAcceleratorFailureCache.STATE_FILE_NAME)
        assertTrue("state file must exist at $stateFile", stateFile.exists())
        val raw = stateFile.readText()
        assertTrue("schemaVersion must appear first in JSON: $raw", raw.startsWith("{\"schemaVersion\":"))
        assertTrue("must contain lastFailedBackend: $raw", raw.contains("\"lastFailedBackend\""))
        assertTrue("must contain lastFailedSafeLabel: $raw", raw.contains("\"lastFailedSafeLabel\""))
        assertTrue("must contain failureCount: $raw", raw.contains("\"failureCount\""))
        // Privacy guarantee: never a "message" field.
        assertFalse("must NOT contain a 'message' field: $raw", raw.contains("\"message\""))
    }

    @Test fun `clear deletes the on-disk file and nulls the snapshot`() {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        val stateFile = File(baseDir, OcrAcceleratorFailureCache.STATE_FILE_NAME)
        assertTrue(stateFile.exists())

        cache.clear()
        assertNull("snapshot must be null after clear()", cache.snapshot())
        assertFalse("cache must NOT be in cooldown after clear", cache.isInCooldown())
        // Either the file is deleted, or it exists but is empty (sentinel
        // — see OcrAcceleratorFailureCache.clear()).
        if (stateFile.exists()) {
            assertEquals("if file remains it must be empty", "", stateFile.readText())
        }
    }

    @Test fun `isInCooldown flips false once clock advances past cooldownMs`() {
        val cooldown = 60_000L
        val cache = newCache(cooldownMs = cooldown)
        val t0 = clockNowMs.get()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        assertTrue("must be in cooldown at t0", cache.isInCooldown())

        clockNowMs.set(t0 + cooldown - 1)
        assertTrue("must be in cooldown 1ms before window closes", cache.isInCooldown())

        clockNowMs.set(t0 + cooldown)
        assertFalse("must NOT be in cooldown exactly AT cooldownMs", cache.isInCooldown())

        clockNowMs.set(t0 + cooldown + 1)
        assertFalse("must NOT be in cooldown past cooldownMs", cache.isInCooldown())
    }

    @Test fun `isInCooldown returns false when clock has moved backwards`() {
        val cache = newCache(cooldownMs = 60_000L)
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        // Simulate NTP adjust / manual clock change — failure is "in the
        // future" relative to now. Must not honour an indefinite cooldown.
        clockNowMs.set(clockNowMs.get() - 5L * 60L * 1000L)
        assertFalse("clock-going-backwards must not extend cooldown", cache.isInCooldown())
    }

    @Test fun `multiple recordFailure calls for same backend increments failureCount`() {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val snap = cache.snapshot()!!
        assertEquals(3, snap.failureCount)
        assertEquals("GPU", snap.lastFailedBackend)
    }

    @Test fun `recordFailure for different backend resets failureCount to 1`() {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        cache.recordFailure(backend = "NPU", safeLabel = "QnnException")

        val snap = cache.snapshot()!!
        assertEquals(
            "switching backend must reset failureCount (the count is per-backend, " +
                "since the new backend may succeed when the previous one consistently failed)",
            1,
            snap.failureCount,
        )
        assertEquals("NPU", snap.lastFailedBackend)
    }

    @Test fun `malformed JSON file returns null from snapshot without throwing`() {
        val cache = newCache()
        val stateFile = File(baseDir, OcrAcceleratorFailureCache.STATE_FILE_NAME)
        stateFile.writeText("this is not JSON {{{{")
        assertNull("malformed JSON must return null", cache.snapshot())
        assertFalse(cache.isInCooldown())
    }

    @Test fun `empty file returns null from snapshot without throwing`() {
        val cache = newCache()
        val stateFile = File(baseDir, OcrAcceleratorFailureCache.STATE_FILE_NAME)
        stateFile.writeText("")
        assertNull(cache.snapshot())
    }

    @Test fun `unknown schema version returns null from snapshot (forward-compat)`() {
        val cache = newCache()
        val stateFile = File(baseDir, OcrAcceleratorFailureCache.STATE_FILE_NAME)
        stateFile.writeText(
            """{"schemaVersion":99,"lastFailedBackend":"GPU","lastFailedAtMs":1,""" +
                """"lastFailedSafeLabel":"X","failureCount":1}""",
        )
        assertNull(
            "v1 reader must reject a v99 payload rather than mis-parse it as 'no record'",
            cache.snapshot(),
        )
    }

    @Test fun `missing required field returns null from snapshot`() {
        val cache = newCache()
        val stateFile = File(baseDir, OcrAcceleratorFailureCache.STATE_FILE_NAME)
        stateFile.writeText("""{"schemaVersion":1,"lastFailedAtMs":1}""")
        assertNull(cache.snapshot())
    }

    @Test fun `extra unknown fields are ignored (ignoreUnknownKeys)`() {
        val cache = newCache()
        val stateFile = File(baseDir, OcrAcceleratorFailureCache.STATE_FILE_NAME)
        stateFile.writeText(
            """{"schemaVersion":1,"lastFailedBackend":"GPU","lastFailedAtMs":1,""" +
                """"lastFailedSafeLabel":"X","failureCount":2,"futureField":"ignore"}""",
        )
        val snap = cache.snapshot()
        assertNotNull(snap)
        assertEquals("GPU", snap!!.lastFailedBackend)
        assertEquals(2, snap.failureCount)
    }

    @Test fun `CPU failures are defensively ignored`() {
        // CPU is the last-resort backend — caching a CPU failure would
        // skip the only safe path and disable OCR entirely.
        val cache = newCache()
        cache.recordFailure(backend = "CPU", safeLabel = "BadCpuException")
        assertNull("CPU failures must never be cached", cache.snapshot())
        assertFalse(cache.isInCooldown())
    }

    @Test fun `concurrent recordFailure writes are atomic and never corrupt the file`() {
        // Mirrors the cross-process FileLock + atomic-rename contract — two
        // threads writing simultaneously must not produce a half-written
        // file that parses as "no record".
        val cache = newCache()
        val threads = 8
        val perThread = 25
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { tIdx ->
                pool.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    repeat(perThread) {
                        cache.recordFailure(
                            backend = if (tIdx % 2 == 0) "GPU" else "NPU",
                            safeLabel = "LiteRtException",
                        )
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // After all writes the file must be readable and present a valid
        // record — atomic-rename guarantees no torn read.
        val snap = cache.snapshot()
        assertNotNull("after concurrent writes the cache must still be readable", snap)
        assertTrue(
            "failureCount must be >= 1 after the last write: ${snap!!.failureCount}",
            snap.failureCount >= 1,
        )
        // The final backend must be one of the two writers used.
        assertTrue(
            "final backend must be GPU or NPU: ${snap.lastFailedBackend}",
            snap.lastFailedBackend == "GPU" || snap.lastFailedBackend == "NPU",
        )
    }

    @Test fun `snapshotFlow emits latest record after recordFailure and null after clear`() {
        val cache = newCache()
        assertNull(cache.snapshotFlow.value)

        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        assertEquals("GPU", cache.snapshotFlow.value?.lastFailedBackend)

        cache.clear()
        assertNull(cache.snapshotFlow.value)
    }

    @Test fun `snapshot re-reads from disk so cross-process writers are visible`() {
        // Mirror the dashboard reading state written by :ml: the in-memory
        // _snapshot only reflects writes made in *this* process. snapshot()
        // must re-read from disk so an out-of-band write (here the second
        // cache instance pointing at the same baseDir) shows up.
        val ml = newCache()
        ml.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val dashboard = newCache()
        val seen = dashboard.snapshot()
        assertNotNull("dashboard cache must see :ml's write via the on-disk file", seen)
        assertEquals("GPU", seen!!.lastFailedBackend)

        // The dashboard cache's in-memory snapshot must have been updated.
        assertEquals("GPU", dashboard.snapshotFlow.value?.lastFailedBackend)
    }

    private companion object {
        fun String.sanitizedForPath(): String =
            replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
