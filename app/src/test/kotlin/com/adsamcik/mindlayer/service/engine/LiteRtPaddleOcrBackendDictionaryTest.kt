package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for the dictionary-size sanity check inside
 * [LiteRtPaddleOcrBackend.loadDictionary], exercised indirectly via
 * [LiteRtPaddleOcrBackend.initialize].
 *
 * The production PP-OCRv5 mobile dict contains **18,384** characters
 * (multilingual CJK + Latin + symbols), well above the old 10,000 upper
 * bound. The guard was raised to 50,000. These tests pin the new bounds so
 * any future regression is caught immediately in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtPaddleOcrBackendDictionaryTest {

    private lateinit var context: Context
    private lateinit var modelDir: File
    private lateinit var bundle: PaddleOcrModelInfo

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(context.filesDir, "paddleocr-dict-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val det = File(modelDir, "det.tflite").apply { writeBytes(byteArrayOf(1)) }
        val rec = File(modelDir, "rec.tflite").apply { writeBytes(byteArrayOf(2)) }
        val dict = File(modelDir, "dict.txt").apply { writeText(dictOfSize(128)) }
        bundle = PaddleOcrModelInfo(
            id = "paddleocr-dict-test",
            displayName = "dict-test",
            detectionPath = det.absolutePath,
            recognitionPath = rec.absolutePath,
            classifierPath = null,
            dictionaryPath = dict.absolutePath,
            totalSizeBytes = 3L,
            detSha256 = null,
            recSha256 = null,
            clsSha256 = null,
            dictSha256 = null,
        )
    }

    private fun backend() = LiteRtPaddleOcrBackend.forTesting(
        context = context,
        runnerFactory = { _, _ -> NoOpRunner },
    )

    // ── valid cases ─────────────────────────────────────────────────────────

    @Test fun `18384-line multilingual dict is accepted`() = runTest {
        File(bundle.dictionaryPath).writeText(dictOfSize(18_384))
        val ex = runCatching { backend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected no exception, got $ex", ex == null)
    }

    @Test fun `50000-line dict at upper boundary is accepted`() = runTest {
        File(bundle.dictionaryPath).writeText(dictOfSize(50_000))
        val ex = runCatching { backend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected no exception, got $ex", ex == null)
    }

    // ── invalid cases ────────────────────────────────────────────────────────

    @Test fun `50001-line dict just above upper boundary is rejected`() = runTest {
        File(bundle.dictionaryPath).writeText(dictOfSize(50_001))
        val ex = runCatching { backend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
        assertTrue(ex!!.message?.contains("50001") == true)
    }

    @Test fun `99-line dict just below lower boundary is rejected`() = runTest {
        File(bundle.dictionaryPath).writeText(dictOfSize(99))
        val ex = runCatching { backend().initialize(bundle, "CPU") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $ex", ex is IllegalArgumentException)
        assertTrue(ex!!.message?.contains("99") == true)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private companion object {
        /** Builds a newline-separated token list of exactly [n] non-empty lines. */
        fun dictOfSize(n: Int): String = buildString(capacity = n * 8) {
            repeat(n) { i -> append("tok").append(i).append('\n') }
        }

        /** A no-op runner — lets initialize() succeed without loading native libs. */
        val NoOpRunner = object : PaddleOcrLiteRtRunner {
            override fun runDetection(input: FloatArray): FloatArray = FloatArray(1)
            override fun runOrientation(input: FloatArray): FloatArray? = null
            override fun runRecognition(input: FloatArray): FloatArray = FloatArray(1)
            override fun close() = Unit
        }
    }
}
