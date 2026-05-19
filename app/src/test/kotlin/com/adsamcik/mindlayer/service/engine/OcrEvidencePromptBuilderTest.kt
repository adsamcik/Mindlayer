package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrSessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [OcrEvidencePromptBuilder].
 *
 * Verifies the prompt template shape, mode-label mapping, barcode +
 * line emission, truncation behavior, and that the schema text is
 * forwarded verbatim.
 */
class OcrEvidencePromptBuilderTest {

    @Test fun `prompt contains system instruction and response sentinel`() {
        val prompt = OcrEvidencePromptBuilder.build(buildEvidence())
        assertTrue("missing <SYSTEM>", prompt.contains("<SYSTEM>"))
        assertTrue("missing <RESPONSE>", prompt.contains("<RESPONSE>"))
        assertTrue(
            "missing instruction about JSON-only output",
            prompt.contains("Output ONLY raw JSON"),
        )
    }

    @Test fun `prompt forwards schema text verbatim`() {
        val schema = """{"type":"object","properties":{"total":{"type":"string"}}}"""
        val prompt = OcrEvidencePromptBuilder.build(buildEvidence(schemaJson = schema))
        assertTrue("schema not forwarded", prompt.contains(schema))
    }

    @Test fun `prompt emits ocr lines in 1-based order`() {
        val evidence = buildEvidence(
            textLines = listOf(
                line("Total: \$24.95"),
                line("Tax: \$1.95"),
                line("Subtotal: \$23.00"),
            ),
        )
        val prompt = OcrEvidencePromptBuilder.build(evidence)
        val totalIdx = prompt.indexOf("1. Total: \$24.95")
        val taxIdx = prompt.indexOf("2. Tax: \$1.95")
        val subIdx = prompt.indexOf("3. Subtotal: \$23.00")
        assertTrue("missing line 1", totalIdx > 0)
        assertTrue("missing line 2", taxIdx > totalIdx)
        assertTrue("missing line 3", subIdx > taxIdx)
    }

    @Test fun `prompt emits barcode section only when anchors present`() {
        val withBarcodes = OcrEvidencePromptBuilder.build(
            buildEvidence(
                barcodes = listOf(
                    BarcodeAnchor("EAN_13", "4006381333931", null, 1L),
                ),
            ),
        )
        assertTrue(withBarcodes.contains("<BARCODES>"))
        assertTrue(withBarcodes.contains("format=EAN_13"))
        assertTrue(withBarcodes.contains("value=4006381333931"))

        val withoutBarcodes = OcrEvidencePromptBuilder.build(buildEvidence(barcodes = emptyList()))
        assertFalse("<BARCODES> should not appear when no anchors", withoutBarcodes.contains("<BARCODES>"))
    }

    @Test fun `mode label maps each OcrSessionConfig MODE_ constant`() {
        val tests = listOf(
            OcrSessionConfig.MODE_GENERAL_DOCUMENT to "general document",
            OcrSessionConfig.MODE_RECEIPT to "receipt",
            OcrSessionConfig.MODE_ID_CARD to "id card",
            OcrSessionConfig.MODE_WHITEBOARD to "whiteboard",
            OcrSessionConfig.MODE_SCREEN_CAPTURE to "screen capture",
        )
        for ((mode, expectedLabel) in tests) {
            val prompt = OcrEvidencePromptBuilder.build(buildEvidence(mode = mode))
            assertTrue(
                "mode=$mode should yield label '$expectedLabel' but prompt was: ${prompt.take(200)}",
                prompt.contains("<MODE>: $expectedLabel"),
            )
        }
    }

    @Test fun `unknown mode falls back to general document`() {
        val prompt = OcrEvidencePromptBuilder.build(buildEvidence(mode = 999))
        assertTrue(prompt.contains("<MODE>: general document"))
    }

    @Test fun `prompt includes frame metadata in OCR header`() {
        val prompt = OcrEvidencePromptBuilder.build(
            buildEvidence(frameId = 42L, textLines = listOf(line("hello")), frameQuality = 0.82),
        )
        assertTrue("missing frame id", prompt.contains("frame=42"))
        assertTrue("missing line count", prompt.contains("lines=1"))
        assertTrue("missing quality", prompt.contains("quality=0.82"))
    }

    @Test fun `quality formats with leading zero on fractional`() {
        // 0.05 → "0.05"; 1.0 → "1.00"
        val a = OcrEvidencePromptBuilder.build(buildEvidence(frameQuality = 0.05))
        assertTrue(a.contains("quality=0.05"))
        val b = OcrEvidencePromptBuilder.build(buildEvidence(frameQuality = 1.0))
        assertTrue(b.contains("quality=1.00"))
    }

    @Test fun `prompt truncates when over MAX_PROMPT_CHARS and marks tail`() {
        // Build a giant evidence package — lots of long lines.
        val bigLines = List(2000) { line("X".repeat(80)) }
        val prompt = OcrEvidencePromptBuilder.build(buildEvidence(textLines = bigLines))
        assertTrue(
            "prompt length ${prompt.length} should not exceed cap + TRUNCATED marker",
            prompt.length <= OcrEvidencePromptBuilder.MAX_PROMPT_CHARS + "\n<TRUNCATED>".length,
        )
        assertTrue("should mark truncation", prompt.endsWith("<TRUNCATED>"))
    }

    @Test fun `prompt is never empty and starts with SYSTEM`() {
        val prompt = OcrEvidencePromptBuilder.build(buildEvidence())
        assertNotNull(prompt)
        assertTrue(prompt.startsWith("<SYSTEM>"))
    }

    @Test fun `barcodes appear before OCR section`() {
        val prompt = OcrEvidencePromptBuilder.build(
            buildEvidence(
                barcodes = listOf(BarcodeAnchor("QR_CODE", "https://example.com", null, 1L)),
                textLines = listOf(line("body")),
            ),
        )
        val barcodesIdx = prompt.indexOf("<BARCODES>")
        val ocrIdx = prompt.indexOf("<OCR>")
        assertTrue("barcode section must precede OCR section", barcodesIdx in 0 until ocrIdx)
    }

    @Test fun `MAX_PROMPT_CHARS exposed for tests`() {
        assertEquals(24_576, OcrEvidencePromptBuilder.MAX_PROMPT_CHARS)
    }

    // ── helpers ──

    private fun buildEvidence(
        sessionId: String = "ocr-1-test",
        frameId: Long = 1L,
        frameIndex: Int = 0,
        mode: Int = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        schemaJson: String = """{"type":"object"}""",
        textLines: List<OcrTextLine> = listOf(line("hello")),
        barcodes: List<BarcodeAnchor> = emptyList(),
        frameQuality: Double = 1.0,
    ): OcrEvidencePackage = OcrEvidencePackage(
        sessionId = sessionId,
        frameId = frameId,
        frameIndex = frameIndex,
        mode = mode,
        outputSchemaJson = schemaJson,
        textLines = textLines,
        barcodeAnchors = barcodes,
        frameQuality = frameQuality,
    )

    private fun line(text: String): OcrTextLine = OcrTextLine(
        text = text,
        confidence = OcrFieldFusion.Confidence.HIGH,
    )
}
