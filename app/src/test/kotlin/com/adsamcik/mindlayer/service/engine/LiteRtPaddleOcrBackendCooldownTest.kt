package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Cooldown short-circuit + failure-cache interaction tests for
 * [LiteRtPaddleOcrBackend].
 *
 * Verifies the four cooldown invariants documented in
 * `LiteRtPaddleOcrBackend.initialize()`:
 *
 *  1. `preferredBackend != null` short-circuits both cooldown skip AND
 *     the cache write — the caller is asking for a specific backend.
 *  2. Cooldown skip only triggers when the caller didn't specify AND the
 *     resolver picked non-CPU AND the cache says recent failure.
 *  3. Cache is cleared on success of the once-failing backend.
 *  4. `LowMemoryException` is NEVER cached and never triggers fallback.
 *
 * Pairs with [OcrAcceleratorFailureCacheTest], which covers the cache
 * class in isolation, and [LiteRtPaddleOcrBackendGpuFallbackTest], which
 * covers the sister agent's underlying GPU→CPU fallback contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtPaddleOcrBackendCooldownTest {

    @get:Rule val testName = TestName()

    private lateinit var context: Context
    private lateinit var bundle: PaddleOcrModelInfo
    private lateinit var modelDir: File
    private lateinit var cacheDir: File
    private val clockNowMs = AtomicLong(1_780_162_345_678L)
    private val clock: () -> Long = { clockNowMs.get() }
    private val cooldownMs = 60_000L

    @Before fun setUp() {
        LiteRtAcceleratorResolver.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(context.filesDir, "paddleocr-cooldown-${testName.methodName.sanitizedForPath()}")
            .apply {
                deleteRecursively()
                mkdirs()
            }
        cacheDir = File(context.filesDir, "ocr-accel-cache-${testName.methodName.sanitizedForPath()}")
            .apply {
                deleteRecursively()
                mkdirs()
            }
        val det = File(modelDir, "paddleocr-ppocrv5-mobile-det.tflite").apply { writeBytes(byteArrayOf(1)) }
        val rec = File(modelDir, "paddleocr-ppocrv5-mobile-rec.tflite").apply { writeBytes(byteArrayOf(2)) }
        val cls = File(modelDir, "paddleocr-ppocrv5-mobile-cls.tflite").apply { writeBytes(byteArrayOf(3)) }
        val dict = File(modelDir, "paddleocr-ppocrv5-mobile-dict.txt").apply { writeText(validDictionary()) }
        bundle = PaddleOcrModelInfo(
            id = "paddleocr-ppocrv5-mobile",
            displayName = "PaddleOCR PP-OCRv5 mobile",
            detectionPath = det.absolutePath,
            recognitionPath = rec.absolutePath,
            classifierPath = cls.absolutePath,
            dictionaryPath = dict.absolutePath,
            totalSizeBytes = 4L,
            detSha256 = null, recSha256 = null, clsSha256 = null, dictSha256 = null,
        )
    }

    @After fun tearDown() {
        LiteRtAcceleratorResolver.resetForTesting()
    }

    private fun newCache(): OcrAcceleratorFailureCache = OcrAcceleratorFailureCache(
        baseDir = cacheDir,
        clock = clock,
        cooldownMs = cooldownMs,
    )

    private fun backendWith(
        cache: OcrAcceleratorFailureCache,
        runnerFactory: PaddleOcrLiteRtRunnerFactory,
    ): LiteRtPaddleOcrBackend = LiteRtPaddleOcrBackend.forTesting(
        context = context,
        memoryHeadroomBytes = 0L,
        availableMemoryProvider = { Long.MAX_VALUE },
        runnerFactory = runnerFactory,
        failureCache = cache,
        clock = clock,
    )

    // ── 1. Cooldown active + null preferredBackend + resolver GPU
    //       → initNative called once with CPU ───────────────────────────────

    @Test fun `cooldown active and null preferredBackend short-circuits to CPU once`() = runTest {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val attempts = mutableListOf<String>()
        val backend = backendWith(cache) { _, label ->
            attempts += label
            FakeRunner()
        }

        backend.initialize(bundle, preferredBackend = null)

        assertEquals(
            "cooldown short-circuit must skip the doomed GPU init and call CPU exactly once",
            listOf("CPU"),
            attempts,
        )
        assertEquals("CPU", backend.activeBackend)
        assertTrue(backend.isInitialized)
        // The cache must still be present — cooldown skip does NOT clear,
        // because we never actually re-tested the GPU.
        assertNotNull("cooldown skip must NOT clear the cache", cache.snapshot())
        assertEquals("GPU", cache.snapshot()!!.lastFailedBackend)
    }

    // ── 2. Cooldown active + explicit preferredBackend="GPU"
    //       → caller pick honoured, cache ignored ───────────────────────────

    @Test fun `cooldown ignored when caller explicitly forces GPU`() = runTest {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val attempts = mutableListOf<String>()
        val backend = backendWith(cache) { _, label ->
            attempts += label
            FakeRunner()
        }

        backend.initialize(bundle, preferredBackend = "GPU")

        assertEquals(
            "explicit caller pick must override cooldown — second-guessing the caller violates the contract",
            listOf("GPU"),
            attempts,
        )
        assertEquals("GPU", backend.activeBackend)
        // The cache MUST be cleared because the explicit GPU init succeeded.
        assertNull(
            "successful non-CPU init must clear the cache (transient failure is not sticky)",
            cache.snapshot(),
        )
    }

    // ── 3. Cooldown active + resolver picks CPU outright
    //       → initNative called once with CPU, no skip log ───────────────────

    @Test fun `cooldown plus resolver-CPU picks CPU once without skip log`() = runTest {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val attempts = mutableListOf<String>()
        val backend = backendWith(cache) { _, label ->
            attempts += label
            FakeRunner()
        }

        // Explicit CPU request -> resolver picks CPU -> cooldown short-circuit
        // condition (resolvedRaw != "CPU") is false, so no skip log fires.
        backend.initialize(bundle, preferredBackend = "CPU")

        assertEquals(listOf("CPU"), attempts)
        assertEquals("CPU", backend.activeBackend)
        // CPU success does NOT clear the cache: the cache is keyed by the
        // formerly-failing accelerator (GPU). CPU has nothing to say about
        // whether GPU has recovered.
        assertNotNull(
            "CPU-outright success must NOT clear a GPU failure record",
            cache.snapshot(),
        )
    }

    // ── 4. Cooldown expired + GPU succeeds → cache cleared ───────────────────

    @Test fun `cooldown expired and GPU succeeds clears cache and uses GPU`() = runTest {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")
        // Advance clock past the cooldown window.
        clockNowMs.set(clockNowMs.get() + cooldownMs + 1)
        assertFalse("precondition: cache must NOT be in cooldown", cache.isInCooldown())

        val attempts = mutableListOf<String>()
        val backend = backendWith(cache) { _, label ->
            attempts += label
            FakeRunner()
        }

        backend.initialize(bundle, preferredBackend = null)

        assertEquals(listOf("GPU"), attempts)
        assertEquals("GPU", backend.activeBackend)
        assertNull("successful GPU init must clear the cache", cache.snapshot())
    }

    // ── 5. Cooldown expired + GPU fails → cache records GPU then CPU fallback ──

    @Test fun `cooldown expired and GPU fails records cache and falls back to CPU`() = runTest {
        val cache = newCache()
        // Pre-seed with a stale record (expired so it does NOT short-circuit).
        cache.recordFailure(backend = "GPU", safeLabel = "OldException")
        clockNowMs.set(clockNowMs.get() + cooldownMs + 1)
        assertFalse(cache.isInCooldown())

        val attempts = mutableListOf<String>()
        val backend = backendWith(cache) { _, label ->
            attempts += label
            if (label != "CPU") throw RuntimeException("synthetic GPU compile failure")
            FakeRunner()
        }

        backend.initialize(bundle, preferredBackend = null)

        assertEquals(listOf("GPU", "CPU"), attempts)
        assertEquals("CPU", backend.activeBackend)

        // The cache must record the NEW GPU failure — the stale record is
        // replaced (same backend → failureCount increments to 2). The record
        // must reflect the CURRENT clock so a fresh cooldown window starts.
        val snap = cache.snapshot()
        assertNotNull("GPU failure must be persisted before the CPU fallback", snap)
        assertEquals("GPU", snap!!.lastFailedBackend)
        assertEquals(
            "same-backend re-failure must increment failureCount, not reset to 1",
            2,
            snap.failureCount,
        )
        assertEquals(clockNowMs.get(), snap.lastFailedAtMs)
    }

    // ── 6. LowMemoryException from GPU init → cache UNCHANGED, exception propagates ──

    @Test fun `LowMemoryException from GPU does not touch the cache and propagates`() = runTest {
        val cache = newCache()
        // Pre-seed with a known record; we'll assert it is BYTE-IDENTICAL
        // after the LowMemoryException.
        cache.recordFailure(backend = "GPU", safeLabel = "PriorFailure")
        // Advance past cooldown so the LowMem path is reached (not skip).
        clockNowMs.set(clockNowMs.get() + cooldownMs + 1)
        val before = cache.snapshot()
        assertNotNull(before)

        val attempts = mutableListOf<String>()
        val backend = backendWith(cache) { _, label ->
            attempts += label
            throw LowMemoryException(availMb = 8, requiredMb = 256)
        }

        val ex = runCatching { backend.initialize(bundle, preferredBackend = null) }.exceptionOrNull()
        assertTrue("expected LowMemoryException, got $ex", ex is LowMemoryException)
        assertEquals(
            "LowMemory must NOT trigger CPU fallback",
            listOf("GPU"),
            attempts,
        )

        val after = cache.snapshot()
        assertNotNull(after)
        assertEquals(
            "LowMemory must NOT mutate the failure cache (failureCount unchanged)",
            before!!.failureCount,
            after!!.failureCount,
        )
        assertEquals(
            "LowMemory must NOT mutate the failure cache (timestamp unchanged)",
            before.lastFailedAtMs,
            after.lastFailedAtMs,
        )
        assertEquals(
            "LowMemory must NOT mutate the failure cache (safeLabel unchanged)",
            before.lastFailedSafeLabel,
            after.lastFailedSafeLabel,
        )
    }

    // ── 7. Cooldown skip records a resolver decision with the documented reason ──

    @Test fun `cooldown skip records resolver decision with _COOLDOWN_SKIP_CPU reason`() = runTest {
        val cache = newCache()
        cache.recordFailure(backend = "GPU", safeLabel = "LiteRtException")

        val backend = backendWith(cache) { _, _ -> FakeRunner() }
        backend.initialize(bundle, preferredBackend = null)

        val decision = LiteRtAcceleratorResolver.latestDecision("ocr")
        assertNotNull("resolver must record a cooldown-skip decision", decision)
        assertEquals("CPU", decision!!.backend)
        assertTrue(
            "reason must use the _COOLDOWN_SKIP_CPU constant suffix; got '${decision.reason}'",
            decision.reason.contains("COOLDOWN_SKIP_CPU"),
        )
        assertTrue(
            "reason must include the resolver-picked backend that was skipped; got '${decision.reason}'",
            decision.reason.startsWith("GPU"),
        )
        assertTrue(
            "attempted chain must mark the resolver pick as cooldown-skip: ${decision.attempted}",
            decision.attempted.any { (b, why) -> b == "GPU" && why == "cooldown-skip" },
        )
    }

    // ── 8. GPU fails first (records failure) then GPU succeeds later → cache cleared ──

    @Test fun `transient GPU failure clears once GPU eventually succeeds`() = runTest {
        // Real-world: device boots cold, OpenCL not yet ready, GPU fails.
        // Sister fallback puts us on CPU. Next process restart after the
        // cooldown advances should retry GPU, and once GPU succeeds the cache
        // is cleared so future cold starts do not pay the cooldown lookup.
        val cache = newCache()
        var attempts = mutableListOf<String>()
        val firstBackend = backendWith(cache) { _, label ->
            attempts += label
            if (label != "CPU") throw RuntimeException("transient GPU failure")
            FakeRunner()
        }
        firstBackend.initialize(bundle, preferredBackend = null)
        assertEquals(listOf("GPU", "CPU"), attempts)
        assertNotNull("cache must record the first GPU failure", cache.snapshot())
        assertEquals("GPU", cache.snapshot()!!.lastFailedBackend)

        // Advance clock past cooldown to simulate a later process restart.
        clockNowMs.set(clockNowMs.get() + cooldownMs + 1)
        assertFalse(cache.isInCooldown())

        // New backend instance, GPU now works.
        attempts = mutableListOf()
        val secondBackend = backendWith(cache) { _, label ->
            attempts += label
            FakeRunner()
        }
        secondBackend.initialize(bundle, preferredBackend = null)

        assertEquals(listOf("GPU"), attempts)
        assertEquals("GPU", secondBackend.activeBackend)
        assertNull(
            "GPU recovery must clear the cache so future cold starts skip the cooldown lookup",
            cache.snapshot(),
        )
    }

    // ── 9. Caller-forced CPU never writes the cache, even on failure ─────────

    @Test fun `caller-forced CPU failure never writes the cache`() = runTest {
        val cache = newCache()
        assertNull(cache.snapshot())

        val backend = backendWith(cache) { _, _ -> throw RuntimeException("CPU failure") }
        runCatching { backend.initialize(bundle, preferredBackend = "CPU") }

        assertNull(
            "CPU as the explicitly-requested backend must NEVER be cached " +
                "— defensive guard inside recordFailure",
            cache.snapshot(),
        )
    }

    private class FakeRunner : PaddleOcrLiteRtRunner {
        var closed: Boolean = false
            private set

        override fun runDetection(input: FloatArray): FloatArray = FloatArray(8 * 8)
        override fun runOrientation(input: FloatArray): FloatArray? = floatArrayOf(1f, 0f)
        override fun runRecognition(input: FloatArray): FloatArray = FloatArray(101)
        override fun close() { closed = true }
    }

    private companion object {
        fun validDictionary(): String = buildString {
            append("A\n")
            append("B\n")
            repeat(98) { append("tok").append(it).append("\n") }
        }

        fun String.sanitizedForPath(): String =
            replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
