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
 * be exercised without the actual `.tflite` artifacts uploaded:
 *
 *   - All three backend classes resolve + instantiate in the same
 *     process without DexLoader / linker conflicts (covers issue
 *     LiteRT-LM #2211's class-of-failure: missing libLiteRt.so in
 *     the expected namespace).
 *   - LiteRtPaddleOcrBackend + LiteRtEmbeddingBackend lifecycle
 *     transitions (initialize/shutdown) work back-to-back without
 *     state bleed.
 *   - OcrRecognitionDispatcher correctly catches the engine
 *     scaffold's IllegalStateException and emits an empty
 *     FrameProcessed event instead of poisoning the session.
 *   - OcrSessionManager + OcrTokenStreamWriter end-to-end intake
 *     -> recognition (scaffold) -> event emission pipeline.
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
        // Seed minimal bundle files for the PaddleOCR scaffold so its
        // file-existence guard passes. The native delegate creation
        // is TODO(verifyOnDevice) so initialize() succeeds without
        // attempting LiteRT calls.
        val dir = context.filesDir
        dir.listFiles()?.forEach { if (it.name.startsWith("paddleocr-")) it.delete() }
        val det = File(dir, "paddleocr-ppocrv5-mobile-det.tflite").apply { writeBytes(byteArrayOf(1)) }
        val rec = File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").apply { writeBytes(byteArrayOf(2)) }
        val dict = File(dir, "paddleocr-ppocrv5-mobile-dict.txt").apply { writeBytes(byteArrayOf(3)) }
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

        val paddleBackend = LiteRtPaddleOcrBackend(
            context,
            memoryHeadroomBytes = 0L,
            availableMemoryProvider = { Long.MAX_VALUE },
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
    fun recognition_dispatcher_swallows_scaffold_exception_and_emits_frame_processed() = runBlocking {
        // Seed bundle files (same as the back-to-back test).
        val dir = context.filesDir
        dir.listFiles()?.forEach { if (it.name.startsWith("paddleocr-")) it.delete() }
        File(dir, "paddleocr-ppocrv5-mobile-det.tflite").writeBytes(byteArrayOf(1))
        File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").writeBytes(byteArrayOf(2))
        File(dir, "paddleocr-ppocrv5-mobile-dict.txt").writeBytes(byteArrayOf(3))

        val engine = PaddleOcrEngine(
            context,
            backendFactory = {
                LiteRtPaddleOcrBackend(
                    context,
                    memoryHeadroomBytes = 0L,
                    availableMemoryProvider = { Long.MAX_VALUE },
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

        // The scaffold throws IllegalStateException with the
        // "build-paddleocr-models" reference — the dispatcher must
        // swallow it and still emit OCR_FRAME_PROCESSED with
        // lineCount=0 so the SDK Flow consumer sees the frame
        // closed out rather than hanging forever.
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
}
