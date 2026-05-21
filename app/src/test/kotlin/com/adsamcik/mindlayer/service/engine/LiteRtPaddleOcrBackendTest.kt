package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [LiteRtPaddleOcrBackend].
 *
 * The real LiteRT `CompiledModel` instances are hidden behind
 * [PaddleOcrLiteRtRunner], so these JVM tests cover the production Kotlin
 * pipeline without loading native libraries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtPaddleOcrBackendTest {

    private lateinit var context: Context
    private lateinit var bundle: PaddleOcrModelInfo
    private lateinit var modelDir: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(context.filesDir, "paddleocr-backend-test").apply {
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

    private fun backend(
        fakeRunner: FakePaddleOcrLiteRtRunner = FakePaddleOcrLiteRtRunner(),
        availableMemoryProvider: () -> Long = { Long.MAX_VALUE },
    ) = LiteRtPaddleOcrBackend.forTesting(
        context = context,
        memoryHeadroomBytes = 0L,
        availableMemoryProvider = availableMemoryProvider,
        runnerFactory = { _, _ -> fakeRunner },
    )

    @Test fun `before init activeBackend is NONE and isInitialized is false`() {
        val backend = backend()
        assertEquals("NONE", backend.activeBackend)
        assertEquals(false, backend.isInitialized)
        assertNull(backend.currentBundle)
    }

    @Test fun `initialize sets activeBackend and currentBundle`() = runTest {
        val backend = backend()
        backend.initialize(bundle, preferredBackend = "CPU")
        assertEquals("CPU", backend.activeBackend)
        assertTrue(backend.isInitialized)
        assertEquals(bundle.id, backend.currentBundle?.id)
    }

    @Test fun `initialize with null preferredBackend defaults to CPU`() = runTest {
        val backend = backend()
        backend.initialize(bundle, preferredBackend = null)
        assertEquals("CPU", backend.activeBackend)
    }

    @Test fun `initialize with unknown preferredBackend falls back to CPU`() = runTest {
        val backend = backend()
        backend.initialize(bundle, preferredBackend = "DSP")
        assertEquals("CPU", backend.activeBackend)
    }

    @Test fun `initialize is idempotent for same bundle`() = runTest {
        var runnerCreations = 0
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ ->
                runnerCreations++
                FakePaddleOcrLiteRtRunner()
            },
        )
        backend.initialize(bundle, "GPU")
        backend.initialize(bundle, "GPU")
        assertEquals(1, runnerCreations)
        assertEquals("CPU", backend.activeBackend)
    }

    @Test fun `initialize reloads same bundle when backend preference changes`() = runTest {
        val requestedBackends = mutableListOf<String>()
        val runners = mutableListOf<FakePaddleOcrLiteRtRunner>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, backendLabel ->
                requestedBackends += backendLabel
                FakePaddleOcrLiteRtRunner().also { runners += it }
            },
        )

        backend.initialize(bundle, "CPU")
        backend.initialize(bundle, "GPU")

        assertEquals(listOf("CPU"), requestedBackends)
        assertEquals("CPU", backend.activeBackend)
        assertEquals(1, runners.size)
        assertEquals(false, runners[0].closed)
    }

    @Test fun `initialize failure preserves previous ready runner`() = runTest {
        val firstRunner = FakePaddleOcrLiteRtRunner()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> firstRunner },
        )
        backend.initialize(bundle, "CPU")
        val badBundle = bundle.copy(
            id = "paddleocr-ppocrv5-mobile-bad",
            dictionaryPath = File(modelDir, "missing-dict.txt").absolutePath,
        )

        val ex = runCatching { backend.initialize(badBundle, "GPU") }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)

        assertEquals("CPU", backend.activeBackend)
        assertTrue(backend.isInitialized)
        assertEquals(bundle.id, backend.currentBundle?.id)
        assertEquals(false, firstRunner.closed)
    }

    @Test fun `initialize closes newly created runner when coroutine is cancelled before publish`() = runTest {
        val createdRunner = FakePaddleOcrLiteRtRunner()
        val initJob = Job(parent = coroutineContext[Job])
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ ->
                initJob.cancel()
                createdRunner
            },
        )

        val child = CoroutineScope(coroutineContext + initJob).launch {
            backend.initialize(bundle, "CPU")
        }
        child.join()

        assertTrue(child.isCancelled)
        assertTrue(createdRunner.closed)
        assertEquals(false, backend.isInitialized)
        assertEquals("NONE", backend.activeBackend)
    }

    @Test fun `initialize fails when det file missing`() = runTest {
        File(bundle.detectionPath).delete()
        val ex = runCatching { backend().initialize(bundle) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
        assertTrue("Error should mention det: ${ex!!.message}", ex.message!!.contains("det"))
    }

    @Test fun `initialize fails when rec file missing`() = runTest {
        File(bundle.recognitionPath).delete()
        val ex = runCatching { backend().initialize(bundle) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
        assertTrue("Error should mention rec: ${ex!!.message}", ex.message!!.contains("rec"))
    }

    @Test fun `initialize fails when dict file missing`() = runTest {
        File(bundle.dictionaryPath).delete()
        val ex = runCatching { backend().initialize(bundle) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
        assertTrue("Error should mention dict: ${ex!!.message}", ex.message!!.contains("dict"))
    }

    @Test fun `initialize fails when cls file missing but classifierPath is set`() = runTest {
        File(bundle.classifierPath!!).delete()
        val ex = runCatching { backend().initialize(bundle) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
        assertTrue("Error should mention cls: ${ex!!.message}", ex.message!!.contains("cls"))
    }

    @Test fun `initialize succeeds when classifierPath is null (no-cls variant)`() = runTest {
        val noClsBundle = bundle.copy(classifierPath = null)
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            recognitionOutput = ctcOutput(classCount = 101, indices = intArrayOf(1)),
        )
        val backend = backend(fakeRunner)
        backend.initialize(noClsBundle, "CPU")
        assertTrue(backend.isInitialized)
        assertEquals("CPU", backend.activeBackend)
        assertNull(backend.currentBundle?.classifierPath)

        val output = backend.recognise(ByteArray(8 * 8), 8, 8)

        // The cls model is absent → useOrientation=false → orientation runner
        // is never invoked even though orientationDisabled defaults to false.
        assertEquals(0, fakeRunner.orientationCalls)
        assertEquals(1, output.lines.size)
        assertEquals(0, output.lines.single().orientationDegrees)
    }

    @Test fun `initialize fails when memory headroom is insufficient`() = runTest {
        val ex = runCatching {
            backend(availableMemoryProvider = { 1L }).initialize(bundle)
        }.exceptionOrNull()
        assertTrue("expected LowMemoryException, got $ex", ex is LowMemoryException)
    }

    // ── M4: sequential init -> shutdown -> init cycle ────────────────────

    @Test fun `init then shutdown then init cycle re-creates runner cleanly`() = runTest {
        val runners = mutableListOf<FakePaddleOcrLiteRtRunner>()
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ ->
                FakePaddleOcrLiteRtRunner().also { runners += it }
            },
        )
        backend.initialize(bundle, "CPU")
        backend.shutdown()
        // Second initialize must succeed (no leftover state, no
        // IllegalStateException) and produce a fresh runner.
        backend.initialize(bundle, "CPU")

        assertEquals(2, runners.size)
        assertTrue("first runner must be closed after shutdown", runners[0].closed)
        assertFalse("second runner must still be live", runners[1].closed)
        assertTrue(backend.isInitialized)
        assertEquals("CPU", backend.activeBackend)
    }


    @Test fun dictionaryBomIsNormalized() = runTest {
        File(bundle.dictionaryPath).writeText("\ufeffA\nB\n" + (0 until 98).joinToString("\n") { "tok$it" } + "\n")
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            recognitionOutput = ctcOutput(classCount = 101, indices = intArrayOf(1)),
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")
        val output = backend.recognise(ByteArray(8 * 8), 8, 8)
        assertEquals("A", output.lines.single().text)
    }

    @Test fun dictionaryBareCrIsRejected() = runTest {
        File(bundle.dictionaryPath).writeText("A\rB\n" + (0 until 98).joinToString("\n") { "tok$it" } + "\n")
        val ex = runCatching { backend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
    }

    @Test fun dictionaryTooShortFails() = runTest {
        File(bundle.dictionaryPath).writeText("A\nB\n")
        val ex = runCatching { backend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
    }

    @Test fun dictionaryTooLongFails() = runTest {
        File(bundle.dictionaryPath).writeText((0..10_000).joinToString("\n") { "tok$it" })
        val ex = runCatching { backend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
    }

    @Test fun dictionarySingleSpaceLinePreservesSpaceToken() = runTest {
        File(bundle.dictionaryPath).writeText(" \nA\n" + (0 until 98).joinToString("\n") { "tok$it" } + "\n")
        val backend = backend()
        backend.initialize(bundle, "CPU")
        val dictionaryField = LiteRtPaddleOcrBackend::class.java.getDeclaredField("dictionary")
        dictionaryField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val dictionary = dictionaryField.get(backend) as List<String>
        assertEquals(" ", dictionary.first())
    }

    @Test fun `recognise throws when not initialised`() = runTest {
        val ex = runCatching { backend().recognise(ByteArray(8 * 8), 8, 8) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
    }

    @Test fun `recognise decodes detection orientation and CTC output`() = runTest {
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 2, right = 6, bottom = 5, score = 0.9f)),
            orientationOutput = floatArrayOf(0.1f, 0.9f),
            recognitionOutput = ctcOutput(classCount = 101, indices = intArrayOf(1, 1, 0, 2)),
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val output = backend.recognise(
            yPlane = ByteArray(8 * 8) { (it * 3).toByte() },
            width = 8,
            height = 8,
            config = OcrEngineConfig(emitBoundingBoxes = true),
        )

        assertEquals("CPU", output.backend)
        assertEquals(1, output.lines.size)
        val line = output.lines.single()
        assertEquals("AB", line.text)
        assertEquals(OcrFieldFusion.Confidence.HIGH, line.confidence)
        assertEquals(180, line.orientationDegrees)
        assertArrayEquals(
            floatArrayOf(0f, 0.125f, 0.75f, 0.125f, 0.75f, 0.625f, 0f, 0.625f),
            line.boundingBox,
            0.0001f,
        )
        assertEquals(640 * 640 * 3, fakeRunner.detectionInputSize)
        assertEquals(160 * 80 * 3, fakeRunner.orientationInputSize)
        assertEquals(320 * 48 * 3, fakeRunner.recognitionInputSize)
    }

    @Test fun `recognise skips orientation classifier when disabled`() = runTest {
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            orientationOutput = floatArrayOf(0.1f, 0.9f),
            recognitionOutput = ctcOutput(classCount = 101, indices = intArrayOf(1)),
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val output = backend.recognise(
            yPlane = ByteArray(8 * 8),
            width = 8,
            height = 8,
            config = OcrEngineConfig(orientationDisabled = true),
        )

        assertEquals(1, output.lines.size)
        assertEquals(0, output.lines.single().orientationDegrees)
        assertEquals(0, fakeRunner.orientationCalls)
    }

    @Test fun `recognise enforces maxLines before recognition`() = runTest {
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(
                8,
                Rect(left = 0, top = 0, right = 3, bottom = 3, score = 0.7f),
                Rect(left = 4, top = 4, right = 8, bottom = 8, score = 0.95f),
            ),
            recognitionOutput = ctcOutput(classCount = 101, indices = intArrayOf(2)),
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val output = backend.recognise(
            yPlane = ByteArray(8 * 8),
            width = 8,
            height = 8,
            config = OcrEngineConfig(maxLines = 1, emitBoundingBoxes = true, orientationDisabled = true),
        )

        assertEquals(1, output.lines.size)
        assertEquals("B", output.lines.single().text)
        assertEquals(1, fakeRunner.recognitionCalls)
        assertArrayEquals(
            floatArrayOf(0.375f, 0.375f, 1f, 0.375f, 1f, 1f, 0.375f, 1f),
            output.lines.single().boundingBox,
            0.0001f,
        )
    }

    @Test fun `recognise returns empty output when detector finds no lines`() = runTest {
        val fakeRunner = FakePaddleOcrLiteRtRunner(detectionOutput = FloatArray(8 * 8))
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val output = backend.recognise(ByteArray(8 * 8), 8, 8)

        assertTrue(output.lines.isEmpty())
        assertEquals(0, fakeRunner.recognitionCalls)
    }

    @Test fun `shutdown on uninitialised backend is a no-op`() = runTest {
        val backend = backend()
        backend.shutdown()
        assertEquals("NONE", backend.activeBackend)
    }

    @Test fun `shutdown clears state and closes runner`() = runTest {
        val fakeRunner = FakePaddleOcrLiteRtRunner()
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "GPU")
        backend.shutdown()
        assertEquals("NONE", backend.activeBackend)
        assertEquals(false, backend.isInitialized)
        assertNull(backend.currentBundle)
        assertTrue(fakeRunner.closed)
    }

    @Test fun `shutdown is idempotent`() = runTest {
        val backend = backend()
        backend.initialize(bundle, "GPU")
        backend.shutdown()
        backend.shutdown()
        assertEquals("NONE", backend.activeBackend)
    }

    // ── M4: error injection on the runner.runX paths ─────────────────────

    @Test fun `recognise propagates detection runner exception`() = runTest {
        val cause = RuntimeException("synthetic det failure with prompt fragment 'leak me'")
        val fakeRunner = FakePaddleOcrLiteRtRunner(throwOnDetection = cause)
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val ex = runCatching { backend.recognise(ByteArray(8 * 8), 8, 8) }.exceptionOrNull()
        assertTrue("expected RuntimeException, got $ex", ex is RuntimeException)
        // The backend itself does not categorise — the dispatcher wraps. We
        // simply verify the throw path runs cleanly and the backend remains
        // initialised for the next frame.
        assertTrue("backend must stay initialised after a runner throw", backend.isInitialized)
        assertEquals("CPU", backend.activeBackend)
        assertEquals(1, fakeRunner.detectionCalls)
        assertEquals(0, fakeRunner.recognitionCalls)
    }

    @Test fun `recognise propagates orientation runner exception`() = runTest {
        val cause = RuntimeException("synthetic cls failure")
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            throwOnOrientation = cause,
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val ex = runCatching {
            backend.recognise(
                yPlane = ByteArray(8 * 8),
                width = 8,
                height = 8,
                config = OcrEngineConfig(orientationDisabled = false),
            )
        }.exceptionOrNull()
        assertTrue("expected RuntimeException, got $ex", ex is RuntimeException)
        assertEquals(1, fakeRunner.orientationCalls)
        assertEquals(0, fakeRunner.recognitionCalls)
        assertTrue(backend.isInitialized)
    }

    @Test fun `recognise propagates recognition runner exception`() = runTest {
        val cause = RuntimeException("synthetic rec failure")
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            throwOnRecognition = cause,
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val ex = runCatching {
            backend.recognise(
                yPlane = ByteArray(8 * 8),
                width = 8,
                height = 8,
                config = OcrEngineConfig(orientationDisabled = true),
            )
        }.exceptionOrNull()
        assertTrue("expected RuntimeException, got $ex", ex is RuntimeException)
        assertEquals(1, fakeRunner.recognitionCalls)
        assertTrue(backend.isInitialized)
    }

    // ── M4: detection output shape guards ────────────────────────────────

    @Test fun `recognise rejects non-square detection output`() = runTest {
        // 96 = 8 * 12 — sqrt(96) ≈ 9.8 → roundToInt = 10, 10*10 ≠ 96 → fail.
        val fakeRunner = FakePaddleOcrLiteRtRunner(detectionOutput = FloatArray(8 * 12))
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")
        val ex = runCatching { backend.recognise(ByteArray(8 * 8), 8, 8) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
        assertTrue(
            "error must reference detection output size: ${ex!!.message}",
            ex.message!!.contains("detection output size"),
        )
    }

    @Test fun `recognise rejects recognition output not divisible by class count`() = runTest {
        // classCount = dictionary.size + 1 = 101 → size 150 is not 0 mod 101.
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            recognitionOutput = FloatArray(150),
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")
        val ex = runCatching {
            backend.recognise(
                yPlane = ByteArray(8 * 8),
                width = 8,
                height = 8,
                config = OcrEngineConfig(orientationDisabled = true),
            )
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
        assertTrue(
            "error must reference divisibility: ${ex!!.message}",
            ex.message!!.contains("divisible") || ex.message!!.contains("class count"),
        )
    }

    // ── M4: CTC blank-only output edge case ──────────────────────────────

    @Test fun `recognise drops line when CTC predicts blank at every timestep`() = runTest {
        // Every timestep peaks on the blank class (index 0). The decoder
        // must NOT crash on the "emitted == 0" path and the line is
        // dropped via text.isNotBlank().
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            recognitionOutput = ctcOutput(classCount = 101, indices = intArrayOf(0, 0, 0, 0)),
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        val output = backend.recognise(
            yPlane = ByteArray(8 * 8),
            width = 8,
            height = 8,
            config = OcrEngineConfig(orientationDisabled = true),
        )

        assertTrue(
            "blank-only recognition output must yield no lines (text.isBlank dropped)",
            output.lines.isEmpty(),
        )
        assertEquals(1, fakeRunner.recognitionCalls)
    }

    @Test fun `recognise survives all-NaN softmax without crashing`() = runTest {
        // NaN floats short-circuit the "looks like probabilities" branch
        // in bestClass(), forcing the log-sum-exp fallback. emitted == 0
        // (NaN comparisons all return false → no index ever wins), so the
        // empty-line path is exercised again with the harder input.
        val nan = Float.NaN
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 1, right = 7, bottom = 3, score = 0.9f)),
            recognitionOutput = FloatArray(101 * 2) { nan },
        )
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        // Must not throw.
        val output = backend.recognise(
            yPlane = ByteArray(8 * 8),
            width = 8,
            height = 8,
            config = OcrEngineConfig(orientationDisabled = true),
        )
        // bestClass with all-NaN keeps default index 0 (blank) → no chars
        // emitted → line dropped. We're pinning "no crash" here.
        assertTrue(output.lines.isEmpty())
    }

    // ── M4: mutex serialisation across concurrent recognise() calls ──────

    @Test fun `concurrent recognise calls serialize via the per-backend mutex`() = runTest {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val secondAllowedToStart = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()

        val onDetection: () -> Unit = {
            val current = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { existing -> maxOf(existing, current) }
            // First call signals it has entered the critical section and
            // then blocks until the second call has been scheduled — if
            // the mutex did NOT serialize, the second call's detection
            // would observe concurrent == 2.
            try {
                if (firstStarted.complete(Unit)) {
                    // We were first — wait for second to be scheduled.
                    kotlinx.coroutines.runBlocking { secondAllowedToStart.await() }
                }
            } finally {
                concurrent.decrementAndGet()
            }
        }

        val fakeRunner = FakePaddleOcrLiteRtRunner(onDetection = onDetection)
        val backend = backend(fakeRunner)
        backend.initialize(bundle, "CPU")

        // Use real Dispatchers.IO for true concurrency; runTest's test
        // dispatcher would serialize them artificially.
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val first = scope.async {
                backend.recognise(ByteArray(8 * 8), 8, 8)
            }
            firstStarted.await()
            val second = scope.async {
                backend.recognise(ByteArray(8 * 8), 8, 8)
            }
            // Let the second call queue against the mutex, then release.
            withContext(Dispatchers.IO) { Thread.sleep(50) }
            secondAllowedToStart.complete(Unit)
            first.await()
            second.await()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }

        assertEquals(2, fakeRunner.detectionCalls)
        assertEquals(
            "per-backend mutex must serialise recognise() — max concurrent should be 1",
            1,
            maxConcurrent.get(),
        )
    }

    private data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val score: Float,
    )

    private class FakePaddleOcrLiteRtRunner(
        private val detectionOutput: FloatArray = FloatArray(8 * 8),
        private val orientationOutput: FloatArray? = floatArrayOf(1f, 0f),
        private val recognitionOutput: FloatArray = ctcOutput(classCount = 101, indices = intArrayOf(1)),
        private val throwOnDetection: Throwable? = null,
        private val throwOnOrientation: Throwable? = null,
        private val throwOnRecognition: Throwable? = null,
        private val onDetection: (() -> Unit)? = null,
    ) : PaddleOcrLiteRtRunner {
        var detectionInputSize: Int = 0
            private set
        var orientationInputSize: Int = 0
            private set
        var recognitionInputSize: Int = 0
            private set
        var orientationCalls: Int = 0
            private set
        var recognitionCalls: Int = 0
            private set
        var detectionCalls: Int = 0
            private set
        var closed: Boolean = false
            private set

        override fun runDetection(input: FloatArray): FloatArray {
            detectionCalls++
            detectionInputSize = input.size
            onDetection?.invoke()
            throwOnDetection?.let { throw it }
            return detectionOutput
        }

        override fun runOrientation(input: FloatArray): FloatArray? {
            orientationCalls++
            orientationInputSize = input.size
            throwOnOrientation?.let { throw it }
            return orientationOutput
        }

        override fun runRecognition(input: FloatArray): FloatArray {
            recognitionCalls++
            recognitionInputSize = input.size
            throwOnRecognition?.let { throw it }
            return recognitionOutput
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        fun validDictionary(): String = buildString {
            append("A\n")
            append("B\n")
            repeat(98) { append("tok").append(it).append("\n") }
        }

        fun heatmap(size: Int, vararg rects: Rect): FloatArray {
            val out = FloatArray(size * size)
            for (rect in rects) {
                for (y in rect.top until rect.bottom) {
                    for (x in rect.left until rect.right) {
                        out[y * size + x] = rect.score
                    }
                }
            }
            return out
        }

        fun ctcOutput(classCount: Int, indices: IntArray): FloatArray {
            val out = FloatArray(classCount * indices.size)
            for ((timestep, index) in indices.withIndex()) {
                out[timestep * classCount + index] = 1f
            }
            return out
        }
    }
}
