package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dir = context.filesDir
        dir.listFiles()?.forEach { it.delete() }
        val det = File(dir, "paddleocr-ppocrv5-mobile-det.tflite").apply { writeBytes(byteArrayOf(1)) }
        val rec = File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").apply { writeBytes(byteArrayOf(2)) }
        val cls = File(dir, "paddleocr-ppocrv5-mobile-cls.tflite").apply { writeBytes(byteArrayOf(3)) }
        val dict = File(dir, "paddleocr-ppocrv5-mobile-dict.txt").apply { writeText("A\nB\n") }
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
        assertEquals("GPU", backend.activeBackend)
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

        assertEquals(listOf("CPU", "GPU"), requestedBackends)
        assertEquals("GPU", backend.activeBackend)
        assertEquals(2, runners.size)
        assertTrue(runners[0].closed)
        assertEquals(false, runners[1].closed)
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
            dictionaryPath = File(context.filesDir, "missing-dict.txt").absolutePath,
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { backend.initialize(badBundle, "GPU") }
        }

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

        val child = launch(initJob) {
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
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { backend().initialize(bundle) }
        }
        assertTrue("Error should mention det: ${ex.message}", ex.message!!.contains("det"))
    }

    @Test fun `initialize fails when rec file missing`() = runTest {
        File(bundle.recognitionPath).delete()
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { backend().initialize(bundle) }
        }
        assertTrue("Error should mention rec: ${ex.message}", ex.message!!.contains("rec"))
    }

    @Test fun `initialize fails when dict file missing`() = runTest {
        File(bundle.dictionaryPath).delete()
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { backend().initialize(bundle) }
        }
        assertTrue("Error should mention dict: ${ex.message}", ex.message!!.contains("dict"))
    }

    @Test fun `initialize fails when memory headroom is insufficient`() = runTest {
        assertThrows(LowMemoryException::class.java) {
            kotlinx.coroutines.runBlocking {
                backend(availableMemoryProvider = { 1L }).initialize(bundle)
            }
        }
    }

    @Test fun `recognise throws when not initialised`() = runTest {
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                backend().recognise(ByteArray(8 * 8), 8, 8)
            }
        }
    }

    @Test fun `recognise decodes detection orientation and CTC output`() = runTest {
        val fakeRunner = FakePaddleOcrLiteRtRunner(
            detectionOutput = heatmap(8, Rect(left = 1, top = 2, right = 6, bottom = 5, score = 0.9f)),
            orientationOutput = floatArrayOf(0.1f, 0.9f),
            recognitionOutput = ctcOutput(classCount = 3, indices = intArrayOf(1, 1, 0, 2)),
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
            floatArrayOf(0.125f, 0.25f, 0.75f, 0.25f, 0.75f, 0.625f, 0.125f, 0.625f),
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
            recognitionOutput = ctcOutput(classCount = 3, indices = intArrayOf(1)),
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
            recognitionOutput = ctcOutput(classCount = 3, indices = intArrayOf(2)),
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
            floatArrayOf(0.5f, 0.5f, 1f, 0.5f, 1f, 1f, 0.5f, 1f),
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
        private val recognitionOutput: FloatArray = ctcOutput(classCount = 3, indices = intArrayOf(1)),
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
        var closed: Boolean = false
            private set

        override fun runDetection(input: FloatArray): FloatArray {
            detectionInputSize = input.size
            return detectionOutput
        }

        override fun runOrientation(input: FloatArray): FloatArray? {
            orientationCalls++
            orientationInputSize = input.size
            return orientationOutput
        }

        override fun runRecognition(input: FloatArray): FloatArray {
            recognitionCalls++
            recognitionInputSize = input.size
            return recognitionOutput
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
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
