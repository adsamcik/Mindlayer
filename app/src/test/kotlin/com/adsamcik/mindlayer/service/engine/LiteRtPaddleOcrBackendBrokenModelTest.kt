package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Coverage for the init-time guard that rejects a stale / mis-converted
 * PP-OCRv5 recognition model containing an unresolved `ONNX_LAYERNORMALIZATION`
 * custom op (which fails to invoke on every accelerator and otherwise silently
 * degrades OCR to 0 recognised lines). Mirrors the build-time guard in
 * `scripts/build-paddleocr-models/convert.sh`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtPaddleOcrBackendBrokenModelTest {

    private lateinit var context: Context
    private lateinit var modelDir: File

    @Before fun setUp() {
        LiteRtAcceleratorResolver.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(context.filesDir, "paddleocr-broken-model-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After fun tearDown() {
        LiteRtAcceleratorResolver.resetForTesting()
    }

    private fun bundleWithRec(recBytes: ByteArray): PaddleOcrModelInfo {
        File(modelDir, "det.tflite").writeBytes(byteArrayOf(1))
        val rec = File(modelDir, "rec.tflite").apply { writeBytes(recBytes) }
        File(modelDir, "cls.tflite").writeBytes(byteArrayOf(3))
        val dict = File(modelDir, "dict.txt").apply { writeText(validDictionary()) }
        return PaddleOcrModelInfo(
            id = "paddleocr-ppocrv5-mobile",
            displayName = "PaddleOCR PP-OCRv5 mobile",
            detectionPath = File(modelDir, "det.tflite").absolutePath,
            recognitionPath = rec.absolutePath,
            classifierPath = File(modelDir, "cls.tflite").absolutePath,
            dictionaryPath = dict.absolutePath,
            totalSizeBytes = 4L,
            detSha256 = null,
            recSha256 = null,
            clsSha256 = null,
            dictSha256 = null,
        )
    }

    @Test fun `init rejects a rec model containing the unresolved LayerNorm custom op`() = runTest {
        // Embed the marker the way a mis-converted tflite carries it (an ASCII
        // custom_code string in the flatbuffer), surrounded by other bytes.
        val recBytes = "TFL3".toByteArray() + ByteArray(64) +
            "ONNX_LAYERNORMALIZATION".toByteArray() + ByteArray(64)
        var runnerCreated = false
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> runnerCreated = true; FakeRunner() },
        )

        val ex = runCatching {
            backend.initialize(bundleWithRec(recBytes), preferredBackend = "CPU")
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $ex", ex is IllegalStateException)
        assertTrue(
            "message must name the offending op: ${ex?.message}",
            ex!!.message!!.contains("ONNX_LAYERNORMALIZATION"),
        )
        assertFalse("guard must reject before the runner is ever created", runnerCreated)
        assertFalse(backend.isInitialized)
    }

    @Test fun `init accepts a clean rec model with no LayerNorm marker`() = runTest {
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            runnerFactory = { _, _ -> FakeRunner() },
        )

        backend.initialize(bundleWithRec("TFL3 clean rec model bytes".toByteArray()), preferredBackend = "CPU")

        assertTrue(backend.isInitialized)
    }

    private class FakeRunner : PaddleOcrLiteRtRunner {
        override fun runDetection(input: FloatArray): FloatArray = FloatArray(8 * 8)
        override fun runOrientation(input: FloatArray): FloatArray? = floatArrayOf(1f, 0f)
        override fun runRecognition(input: FloatArray): FloatArray = FloatArray(101)
        override fun close() {}
    }

    private companion object {
        fun validDictionary(): String = buildString {
            append("A\n")
            append("B\n")
            repeat(98) { append("tok").append(it).append("\n") }
        }
    }
}
