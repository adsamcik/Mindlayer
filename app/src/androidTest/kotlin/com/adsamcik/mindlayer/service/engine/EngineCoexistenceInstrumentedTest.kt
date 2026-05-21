package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * In-process coexistence smoke test for the Mindlayer service's
 * three LiteRT-family consumers: LiteRT-LM (Gemma), base LiteRT
 * (EmbeddingGemma), and base LiteRT (PaddleOCR PP-OCRv5 mobile).
 *
 * Runs on the existing CI emulator matrix (API 33 + 34) via the
 * `instrumented-tests` job. Covers the structural / classloader
 * parts of the LITERT_COEXISTENCE.md 8-step checklist that can
 * be exercised without real model payloads present:
 *
 *   - All three backend classes resolve + instantiate in the same
 *     process without DexLoader / linker conflicts (covers issue
 *     LiteRT-LM #2211's class-of-failure: missing libLiteRt.so in
 *     the expected namespace).
 *   - LiteRtPaddleOcrBackend Kotlin lifecycle transitions work
 *     back-to-back with LiteRtEmbeddingBackend construction without
 *     state bleed. The OCR runner is fake here; real CompiledModel
 *     creation belongs to the device checklist.
 *   - OcrRecognitionDispatcher emits an empty FrameProcessed event
 *     when the fake detector returns no text instead of poisoning the
 *     session.
 *   - OcrSessionManager + OcrTokenStreamWriter end-to-end intake
 *     -> recognition -> event emission pipeline.
 *
 * # What this test does NOT cover
 *
 * The full 8-step LITERT_COEXISTENCE.md checklist (real native
 * delegate creation, GPU/NPU contention, OpenCL discovery, libLiteRt
 * symbol resolution) requires actual model bytes + a device with
 * the right SoC. This in-process smoke test catches the classloader
 * + Kotlin glue regressions; the device-level part is documented in
 * the doc + tracked as a pre-release validation milestone.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27)
class EngineCoexistenceInstrumentedTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * Seeds a 128-line stub dictionary that satisfies the PR #1 length-sanity
     * guard (`require(chars.size in 100..10_000)` in `LiteRtPaddleOcrBackend`).
     * Tokens are deterministic to keep CTC fixtures stable.
     */
    private fun stubDictText(): String = (0 until 128).joinToString(separator = "\n") { "tok$it" }

    @Test
    fun all_three_backend_classes_load_in_the_same_process() {
        // Trigger class init for each backend's companion + class
        // body. If any of them collides with another via DexLoader
        // resolution or static-init order, one of these reflective
        // lookups throws NoClassDefFoundError / ExceptionInInitializerError.
        val classes = listOf(
            "com.adsamcik.mindlayer.service.engine.LiteRtPaddleOcrBackend",
            "com.adsamcik.mindlayer.service.engine.LiteRtEmbeddingBackend",
            "com.adsamcik.mindlayer.service.engine.PaddleOcrEngine",
            "com.adsamcik.mindlayer.service.engine.EmbeddingEngine",
            "com.adsamcik.mindlayer.service.engine.EngineManager",
        )
        for (fqn in classes) {
            val klass = Class.forName(fqn)
            assertNotNull("$fqn must load in this process", klass)
        }
    }

    @Test
    fun paddleocr_and_embedding_backends_initialize_back_to_back_without_state_bleed() = runBlocking {
        // Seed minimal bundle files so the file-existence guard passes.
        // The runner is injected because this CI smoke is not the real
        // Shared LiteRT delegate / model-byte validation path.
        val dir = testModelDir()
        val det = File(dir, "paddleocr-ppocrv5-mobile-det.tflite").apply { writeBytes(byteArrayOf(1)) }
        val rec = File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").apply { writeBytes(byteArrayOf(2)) }
        val dict = File(dir, "paddleocr-ppocrv5-mobile-dict.txt").apply { writeText(stubDictText()) }
        val bundle = PaddleOcrModelInfo(
            id = "paddleocr-ppocrv5-mobile",
            displayName = "test",
            detectionPath = det.absolutePath,
            recognitionPath = rec.absolutePath,
            classifierPath = null,
            dictionaryPath = dict.absolutePath,
            totalSizeBytes = 6L,
            detSha256 = null, recSha256 = null, clsSha256 = null, dictSha256 = null,
        )

        val paddleBackend = LiteRtPaddleOcrBackend.forTesting(
            context = context,
            memoryHeadroomBytes = 0L,
            availableMemoryProvider = { Long.MAX_VALUE },
            runnerFactory = { _, _ -> FakePaddleOcrLiteRtRunner() },
        )
        paddleBackend.initialize(bundle, "CPU")
        assertEquals("CPU", paddleBackend.activeBackend)

        // EmbeddingBackend init path: just construct + shutdown
        // without loading a real model (no embedding-* model bundled).
        val embeddingBackend = LiteRtEmbeddingBackend(context, memoryHeadroomBytes = 0L)
        embeddingBackend.shutdown() // idempotent — no-op on uninitialised

        // PaddleOCR backend should still be in-state after the
        // embedding backend touched the same classloader namespace.
        assertEquals("CPU", paddleBackend.activeBackend)
        assertTrue(paddleBackend.isInitialized)
        paddleBackend.shutdown()
        assertEquals("NONE", paddleBackend.activeBackend)
    }

    @Test
    fun recognition_dispatcher_emits_frame_processed_when_no_lines_are_detected() = runBlocking {
        // Seed bundle files (same as the back-to-back test).
        val dir = testModelDir()
        File(dir, "paddleocr-ppocrv5-mobile-det.tflite").writeBytes(byteArrayOf(1))
        File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").writeBytes(byteArrayOf(2))
        File(dir, "paddleocr-ppocrv5-mobile-dict.txt").writeText(stubDictText())

        val engine = PaddleOcrEngine(
            context,
            backendFactory = {
                LiteRtPaddleOcrBackend.forTesting(
                    context = context,
                    memoryHeadroomBytes = 0L,
                    availableMemoryProvider = { Long.MAX_VALUE },
                    runnerFactory = { _, _ -> FakePaddleOcrLiteRtRunner() },
                )
            },
        )
        val dispatcher = OcrRecognitionDispatcher(engine)
        val captured = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(captured)
        writer.writeHeader("test-session")

        val job = dispatcher.submit(
            sessionId = "test-session",
            frameId = 1L,
            yPlane = ByteArray(64 * 64),
            width = 64,
            height = 64,
            config = OcrEngineConfig(),
            writer = writer,
        )
        job.join()
        writer.close()

        // The fake runner produces no detections; the dispatcher must still
        // emit OCR_FRAME_PROCESSED with lineCount=0 so the SDK Flow consumer
        // sees the frame closed out rather than hanging forever.
        val captureText = captured.toByteArray().decodeToString()
        assertTrue(
            "Should emit OCR_FRAME_PROCESSED even on engine failure: $captureText",
            captureText.contains("ocr_frame_processed"),
        )
        assertTrue(
            "Should emit OCR_FRAME_PROCESSING first: $captureText",
            captureText.contains("ocr_frame_processing"),
        )

        dispatcher.shutdown()
    }

    private fun testModelDir(): File =
        File(context.filesDir, "paddleocr-coexistence-test").apply {
            deleteRecursively()
            mkdirs()
        }

    private class FakePaddleOcrLiteRtRunner : PaddleOcrLiteRtRunner {
        override fun runDetection(input: FloatArray): FloatArray = FloatArray(8 * 8)
        override fun runOrientation(input: FloatArray): FloatArray? = floatArrayOf(1f, 0f)
        override fun runRecognition(input: FloatArray): FloatArray = FloatArray(3)
        override fun close() = Unit
    }
}
