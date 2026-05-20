package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrSessionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [LiteRtLmGemmaOcrExtractor].
 *
 * Robolectric runtime is used so `MindlayerLog.w` (which goes through
 * `android.util.Log`) does not throw "Method w in android.util.Log
 * not mocked" on the failure-path tests. The LiteRT-LM `Engine` class
 * is intentionally NOT loaded by these tests — it is compiled to JVM
 * class-file v65 (JDK 21) and the production wiring is split into
 * `LiteRtLmGemmaOcrExtractorProduction`. The extractor's API uses
 * `Any?` for the engine reference; tests pass a sentinel and bypass
 * the production conversation runner with a controllable lambda.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiteRtLmGemmaOcrExtractorTest {

    /** Opaque sentinel — pretends to be an Engine but never gets cast. */
    private val FAKE_ENGINE: Any = Any()

    private val sampleEvidence = OcrEvidencePackage(
        sessionId = "ocr-1-sample",
        frameId = 7L,
        frameIndex = 0,
        mode = OcrSessionConfig.MODE_RECEIPT,
        outputSchemaJson = """{"type":"object"}""",
        textLines = listOf(
            OcrTextLine("Total: 24.95", OcrFieldFusion.Confidence.HIGH),
            OcrTextLine("Tax: 1.95", OcrFieldFusion.Confidence.HIGH),
        ),
        barcodeAnchors = emptyList(),
        frameQuality = 0.9,
    )

    @Test fun `null engine returns EMPTY`() = runTest {
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { null },
            conversationRunner = { _, _ -> error("should not be called") },
        )
        val result = extractor.extract(sampleEvidence)
        assertEquals(OcrExtractionResult.EMPTY, result)
    }

    @Test fun `successful response is parsed into fields`() = runTest {
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { _, _ ->
                """{"total":"24.95","total_confidence":"high","tax":"1.95","tax_confidence":"medium"}"""
            },
        )
        val result = extractor.extract(sampleEvidence)
        assertEquals(2, result.fields.size)
        val byName = result.fields.associateBy { it.name }
        assertEquals("24.95", byName.getValue("total").value)
        assertEquals(OcrFieldFusion.Confidence.HIGH, byName.getValue("total").confidence)
        assertEquals("1.95", byName.getValue("tax").value)
        assertEquals(OcrFieldFusion.Confidence.MEDIUM, byName.getValue("tax").confidence)
    }

    @Test fun `null response returns EMPTY`() = runTest {
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { _, _ -> null },
        )
        val result = extractor.extract(sampleEvidence)
        assertEquals(OcrExtractionResult.EMPTY, result)
    }

    @Test fun `runner throwable surfaces as EMPTY`() = runTest {
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { _, _ -> throw RuntimeException("native crash") },
        )
        val result = extractor.extract(sampleEvidence)
        assertEquals(OcrExtractionResult.EMPTY, result)
    }

    @Test fun `prompt is passed verbatim to the runner`() = runTest {
        var capturedPrompt: String? = null
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { _, prompt ->
                capturedPrompt = prompt
                """{"x":"v","x_confidence":"high"}"""
            },
        )
        extractor.extract(sampleEvidence)
        assertTrue("prompt must include the system instruction", capturedPrompt!!.contains("<SYSTEM>"))
        assertTrue("prompt must include the schema", capturedPrompt!!.contains(sampleEvidence.outputSchemaJson))
        assertTrue("prompt must include the OCR lines", capturedPrompt!!.contains("Total: 24.95"))
    }

    @Test fun `engine passed to runner is the provider's engine`() = runTest {
        var captured: Any? = null
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { engine, _ ->
                captured = engine
                """{"x":"v","x_confidence":"high"}"""
            },
        )
        extractor.extract(sampleEvidence)
        assertEquals(FAKE_ENGINE, captured)
    }

    @Test fun `timeout surfaces as EMPTY`() = runTest {
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { _, _ ->
                // Stall longer than the configured cap.
                delay(2_000)
                """{"x":"v"}"""
            },
            extractionTimeoutMs = 100L,
        )
        val result = extractor.extract(sampleEvidence)
        assertEquals(OcrExtractionResult.EMPTY, result)
    }

    @Test fun `garbled output returns EMPTY`() = runTest {
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { _, _ -> "<thinking> ... </thinking>" },
        )
        val result = extractor.extract(sampleEvidence)
        assertEquals(OcrExtractionResult.EMPTY, result)
    }

    @Test fun `model-emitted markdown fences are tolerated`() = runTest {
        val extractor = LiteRtLmGemmaOcrExtractor(
            engineProvider = { FAKE_ENGINE },
            conversationRunner = { _, _ ->
                """```json
                |{"sku":"X-7","sku_confidence":"high"}
                |```""".trimMargin()
            },
        )
        val result = extractor.extract(sampleEvidence)
        assertEquals(1, result.fields.size)
        assertEquals("X-7", result.fields.single().value)
    }

    @Test fun `DEFAULT_EXTRACTION_TIMEOUT_MS is 30 seconds`() {
        assertEquals(30_000L, LiteRtLmGemmaOcrExtractor.DEFAULT_EXTRACTION_TIMEOUT_MS)
    }
}
