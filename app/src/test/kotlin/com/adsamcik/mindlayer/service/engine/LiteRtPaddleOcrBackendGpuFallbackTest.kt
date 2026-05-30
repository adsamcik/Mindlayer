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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Coverage for the GPU/NPU → CPU runtime fallback added to
 * [LiteRtPaddleOcrBackend.initialize].
 *
 * Production scenario: the LiteRT OpenGL delegate fails to compile the
 * PP-OCRv5 mobile model (RELU_0_TO_1 missing on the device), throwing
 * `LiteRtException` and bricking OCR for the whole process. The backend now
 * retries once on CPU before giving up. [LowMemoryException] stays terminal
 * because memory pressure is a real signal the caller needs to surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtPaddleOcrBackendGpuFallbackTest {

    private lateinit var context: Context
    private lateinit var bundle: PaddleOcrModelInfo
    private lateinit var modelDir: File

    @Before fun setUp() {
        LiteRtAcceleratorResolver.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(context.filesDir, "paddleocr-fallback-test").apply {
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
            detSha256 = null,
            recSha256 = null,
            clsSha256 = null,
            dictSha256 = null,
        )
    }

    @After fun tearDown() {
        LiteRtAcceleratorResolver.resetForTesting()
    }

    // ── 1. GPU throws LiteRtException → CPU succeeds → activeBackend == CPU ──

    @Test fun `GPU init throws LiteRtException then CPU fallback succeeds`() = runTest {
        // Emulator OpenGL path: native code throws a generic Throwable that
        // is NOT a LowMemoryException. The backend must retry on CPU.
        val attempts = mutableListOf<String>()
        val cpuRunner = FakePaddleOcrLiteRtRunner()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                if (backendLabel != "CPU") {
                    // Use a synthetic Throwable subclass — production hits
                    // `com.google.ai.edge.litert.LiteRtException` which we
                    // can't import here. The backend catches all non-
                    // LowMemoryException throwables uniformly.
                    throw SyntheticLiteRtException("Failed to compile model")
                }
                cpuRunner
            },
        )

        backend.initialize(bundle, preferredBackend = null)

        assertEquals(listOf("GPU", "CPU"), attempts)
        assertEquals("CPU", backend.activeBackend)
        assertTrue(backend.isInitialized)
        assertEquals(bundle.id, backend.currentBundle?.id)
        assertFalse("CPU runner must stay live after success", cpuRunner.closed)
    }

    // ── 2. GPU throws non-LowMem Throwable → CPU succeeds ──

    @Test fun `GPU init throws RuntimeException then CPU fallback succeeds`() = runTest {
        val attempts = mutableListOf<String>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                if (backendLabel != "CPU") {
                    throw RuntimeException("synthetic GPU delegate failure")
                }
                FakePaddleOcrLiteRtRunner()
            },
        )

        backend.initialize(bundle, preferredBackend = null)

        assertEquals(listOf("GPU", "CPU"), attempts)
        assertEquals("CPU", backend.activeBackend)
        assertTrue(backend.isInitialized)
    }

    // ── 3. GPU throws LowMemoryException → no fallback, throws ──

    @Test fun `GPU init throwing LowMemoryException does not trigger CPU fallback`() = runTest {
        // LowMemoryException is reserved for memory pressure and must
        // propagate to the engine layer untouched. The backend must NOT
        // pretend RAM exists by retrying on CPU.
        val attempts = mutableListOf<String>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                throw LowMemoryException(availMb = 8, requiredMb = 256)
            },
        )

        val ex = runCatching { backend.initialize(bundle, preferredBackend = null) }.exceptionOrNull()

        assertTrue("expected LowMemoryException, got $ex", ex is LowMemoryException)
        assertEquals(
            "fallback must not retry when LowMemoryException is thrown",
            listOf("GPU"),
            attempts,
        )
        assertEquals("NONE", backend.activeBackend)
        assertFalse(backend.isInitialized)
        assertNull(backend.currentBundle)
    }

    // ── 4. GPU throws AND CPU throws → final throw is the CPU exception ──

    @Test fun `GPU and CPU both throw produces CPU side exception`() = runTest {
        val attempts = mutableListOf<String>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                if (backendLabel == "CPU") {
                    throw RuntimeException("synthetic CPU compile failure")
                } else {
                    throw RuntimeException("synthetic GPU compile failure")
                }
            },
        )

        val ex = runCatching { backend.initialize(bundle, preferredBackend = null) }.exceptionOrNull()

        // Kotlin coroutines stack-trace recovery may wrap the throw in a new
        // RuntimeException with the same message; the contract is "the CPU
        // exception escapes, not the GPU one", which we discriminate by
        // message rather than reference identity.
        assertTrue("expected RuntimeException, got $ex", ex is RuntimeException)
        assertEquals(
            "final throw must be the CPU-side exception, not the GPU-side",
            "synthetic CPU compile failure",
            ex!!.message,
        )
        assertEquals(listOf("GPU", "CPU"), attempts)
        assertEquals("NONE", backend.activeBackend)
        assertFalse(backend.isInitialized)
    }

    // ── 5. preferredBackend=CPU explicit path → no fallback dance ──

    @Test fun `caller forced CPU throws once and does not retry`() = runTest {
        // Per the contract: if the caller explicitly asked for CPU and CPU
        // failed, that is terminal. No double-init, no resolver mutation.
        val attempts = mutableListOf<String>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                throw RuntimeException("synthetic CPU failure")
            },
        )

        val ex = runCatching { backend.initialize(bundle, preferredBackend = "CPU") }.exceptionOrNull()

        assertTrue("expected RuntimeException, got $ex", ex is RuntimeException)
        assertEquals(
            "CPU-forced path must attempt init exactly once (no fallback dance)",
            listOf("CPU"),
            attempts,
        )
        assertEquals("NONE", backend.activeBackend)
        // Resolver must NOT have a fallback decision recorded — the original
        // CPU decision from resolveBackend() is the only entry.
        val decision = LiteRtAcceleratorResolver.latestDecision("ocr")
        assertNotNull(decision)
        assertEquals("CPU", decision!!.backend)
        assertEquals("REQUESTED_CPU", decision.reason)
    }

    // ── 6. preferredBackend=GPU explicit path → fallback to CPU on failure ──

    @Test fun `caller forced GPU still falls back to CPU on failure`() = runTest {
        // The caller asked for GPU as a preference, not a guarantee. When the
        // GPU delegate fails to compile, OCR should not go permanently dark.
        val attempts = mutableListOf<String>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                if (backendLabel != "CPU") {
                    throw RuntimeException("synthetic GPU compile failure")
                }
                FakePaddleOcrLiteRtRunner()
            },
        )

        backend.initialize(bundle, preferredBackend = "GPU")

        assertEquals(listOf("GPU", "CPU"), attempts)
        assertEquals(
            "active backend must reflect the actual loaded runner (CPU after fallback)",
            "CPU",
            backend.activeBackend,
        )
        assertTrue(backend.isInitialized)
    }

    // ── 7. Resolver picks CPU outright → no fallback logic invoked ──

    @Test fun `resolver picking CPU outright performs a single init attempt`() = runTest {
        // When the resolver selects CPU upfront (today triggered only by an
        // explicit "CPU" request), the backend must not enter the fallback
        // dance — verified via the injected runner factory.
        val attempts = mutableListOf<String>()
        val cpuRunner = FakePaddleOcrLiteRtRunner()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                cpuRunner
            },
        )

        backend.initialize(bundle, preferredBackend = "CPU")

        assertEquals(
            "CPU-outright path must invoke the runner factory exactly once",
            listOf("CPU"),
            attempts,
        )
        assertEquals("CPU", backend.activeBackend)
        assertTrue(backend.isInitialized)
    }

    // ── 8. Resolver decision after fallback reflects CPU + documented reason ──

    @Test fun `latestDecision ocr after fallback reflects CPU and GPU_INIT_FAILED_FALLBACK_CPU`() =
        runTest {
            val backend = LiteRtPaddleOcrBackend.forTesting(
                context = context,
                runnerFactory = { _, backendLabel ->
                    if (backendLabel != "CPU") throw RuntimeException("GPU compile failure")
                    FakePaddleOcrLiteRtRunner()
                },
            )

            backend.initialize(bundle, preferredBackend = null)

            val decision = LiteRtAcceleratorResolver.latestDecision("ocr")
            assertNotNull("resolver must hold a decision after init", decision)
            assertEquals(
                "active backend in the decision must reflect what is actually loaded",
                "CPU",
                decision!!.backend,
            )
            assertEquals(
                "fallback reason must match the documented constant",
                "GPU_INIT_FAILED_FALLBACK_CPU",
                decision.reason,
            )
            assertTrue(
                "attempted chain must record the failed GPU attempt: ${decision.attempted}",
                decision.attempted.any { (b, _) -> b == "GPU" },
            )
            assertTrue(
                "attempted chain must record the CPU selection: ${decision.attempted}",
                decision.attempted.any { (b, _) -> b == "CPU" },
            )
        }

    @Test fun `NPU init failure falls back to CPU with NPU_INIT_FAILED_FALLBACK_CPU`() = runTest {
        // Force the resolver to return NPU by passing requested = "NPU" with
        // an SoC/lib environment that the probe allow-lists.
        LiteRtAcceleratorResolver.setEnvironmentForTesting(
            apiLevel = 34,
            socModel = "sm8650",
            libs = listOf("libQnnHtpV75.so"),
        )

        val attempts = mutableListOf<String>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                attempts += backendLabel
                if (backendLabel != "CPU") throw RuntimeException("synthetic NPU compile failure")
                FakePaddleOcrLiteRtRunner()
            },
        )

        backend.initialize(bundle, preferredBackend = "NPU")

        assertEquals(listOf("NPU", "CPU"), attempts)
        assertEquals("CPU", backend.activeBackend)
        val decision = LiteRtAcceleratorResolver.latestDecision("ocr")
        assertEquals("CPU", decision?.backend)
        assertEquals("NPU_INIT_FAILED_FALLBACK_CPU", decision?.reason)
    }

    @Test fun `successful GPU init records resolver decision unchanged`() = runTest {
        // The fallback path only fires on failure. A clean GPU success must
        // leave the resolver decision exactly as resolveBackend() produced it.
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> FakePaddleOcrLiteRtRunner() },
        )

        backend.initialize(bundle, preferredBackend = null)

        assertEquals("GPU", backend.activeBackend)
        val decision = LiteRtAcceleratorResolver.latestDecision("ocr")
        assertEquals("GPU", decision?.backend)
        assertEquals("DEFAULT_GPU_THEN_CPU_CHAIN", decision?.reason)
    }

    // ── Test helpers ──────────────────────────────────────────────────────

    /**
     * Stand-in for `com.google.ai.edge.litert.LiteRtException`. We can't
     * import the LiteRT type into JVM tests, but the backend catches all
     * non-[LowMemoryException] throwables identically, so a typed subclass
     * is sufficient for the contract.
     */
    private class SyntheticLiteRtException(message: String) : RuntimeException(message)

    private class FakePaddleOcrLiteRtRunner : PaddleOcrLiteRtRunner {
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
    }
}
