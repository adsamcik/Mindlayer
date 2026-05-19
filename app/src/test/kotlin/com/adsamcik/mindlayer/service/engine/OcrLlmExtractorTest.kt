package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrSessionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM tests for the [OcrLlmExtractor] surface area:
 *  - [OcrExtractionContext] equality + toString redaction.
 *  - [OcrEvidencePackage] init-time invariants.
 *  - [OcrExtractedField] init-time invariants + redacted toString.
 *  - [OcrExtractionResult.EMPTY] singleton wiring.
 *  - [NoOpOcrLlmExtractor] always returns [OcrExtractionResult.EMPTY].
 *  - A user-defined extractor's return value passes through unchanged.
 */
class OcrLlmExtractorTest {

    @Test fun `NoOpOcrLlmExtractor returns EMPTY`() = runBlocking {
        val ext = NoOpOcrLlmExtractor()
        val result = ext.extract(sampleEvidence())
        assertEquals(OcrExtractionResult.EMPTY, result)
        assertTrue(result.fields.isEmpty())
        assertNull(result.rawJson)
    }

    @Test fun `OcrExtractionResult EMPTY is the documented singleton`() {
        assertNotNull(OcrExtractionResult.EMPTY)
        assertTrue(OcrExtractionResult.EMPTY.fields.isEmpty())
        assertNull(OcrExtractionResult.EMPTY.rawJson)
    }

    @Test fun `OcrExtractedField rejects blank name`() {
        try {
            OcrExtractedField(name = "  ", value = "x", confidence = OcrFieldFusion.Confidence.HIGH)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    @Test fun `OcrExtractedField toString redacts value`() {
        val f = OcrExtractedField(
            name = "secret_field",
            value = "user-pii-12345",
            confidence = OcrFieldFusion.Confidence.MEDIUM,
        )
        val s = f.toString()
        assertTrue("name must appear", s.contains("secret_field"))
        assertFalse("raw value must not leak in toString", s.contains("user-pii-12345"))
        assertTrue("length hint must appear", s.contains("valueLen=14"))
    }

    @Test fun `OcrEvidencePackage rejects negative frameId`() {
        try {
            OcrEvidencePackage(
                sessionId = "s",
                frameId = -1L,
                frameIndex = 0,
                mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
                outputSchemaJson = "{}",
                textLines = emptyList(),
                barcodeAnchors = emptyList(),
                frameQuality = 0.5,
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frameId"))
        }
    }

    @Test fun `OcrEvidencePackage rejects out-of-range quality`() {
        try {
            OcrEvidencePackage(
                sessionId = "s",
                frameId = 0L,
                frameIndex = 0,
                mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
                outputSchemaJson = "{}",
                textLines = emptyList(),
                barcodeAnchors = emptyList(),
                frameQuality = 1.5,
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("frameQuality"))
        }
    }

    @Test fun `custom extractor result passes through unchanged`() = runBlocking {
        val expected = OcrExtractionResult(
            fields = listOf(
                OcrExtractedField("total", "24.95", OcrFieldFusion.Confidence.HIGH),
                OcrExtractedField("tax", "1.95", OcrFieldFusion.Confidence.MEDIUM),
            ),
            rawJson = """{"total":"24.95","tax":"1.95"}""",
        )
        val ext = object : OcrLlmExtractor {
            override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult = expected
        }
        val out = ext.extract(sampleEvidence())
        assertEquals(expected, out)
        assertEquals(2, out.fields.size)
        assertEquals("total", out.fields[0].name)
    }

    @Test fun `OcrExtractionContext toString redacts schema`() {
        val ctx = OcrExtractionContext(
            mode = OcrSessionConfig.MODE_RECEIPT,
            outputSchemaJson = """{"hideMe":"verysecret"}""",
        )
        val s = ctx.toString()
        assertTrue(s.contains("mode=${OcrSessionConfig.MODE_RECEIPT}"))
        assertFalse("raw schema must not leak in toString", s.contains("verysecret"))
        assertTrue("schema-length hint must appear", s.contains("schemaLen="))
    }

    @Test fun `OcrEvidencePackage toString redacts schema and barcode counts`() {
        val ev = sampleEvidence().copy(
            outputSchemaJson = """{"hidden":"data"}""",
            textLines = listOf(
                OcrTextLine("x", OcrFieldFusion.Confidence.LOW),
                OcrTextLine("y", OcrFieldFusion.Confidence.LOW),
            ),
            barcodeAnchors = listOf(
                BarcodeAnchor("QR_CODE", "https://example.com", null, 1L),
            ),
        )
        val s = ev.toString()
        assertFalse("raw schema must not leak", s.contains("hidden"))
        assertFalse("barcode value must not leak", s.contains("example.com"))
        assertTrue("line count must appear", s.contains("lines=2"))
        assertTrue("barcode count must appear", s.contains("barcodes=1"))
    }

    @Test fun `two extractors with different results stay isolated`() = runBlocking {
        val a = object : OcrLlmExtractor {
            override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult =
                OcrExtractionResult(fields = listOf(OcrExtractedField("a", "1", OcrFieldFusion.Confidence.HIGH)))
        }
        val b = object : OcrLlmExtractor {
            override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult =
                OcrExtractionResult(fields = listOf(OcrExtractedField("b", "2", OcrFieldFusion.Confidence.LOW)))
        }
        val ra = a.extract(sampleEvidence())
        val rb = b.extract(sampleEvidence())
        assertNotEquals(ra, rb)
        assertEquals("a", ra.fields.single().name)
        assertEquals("b", rb.fields.single().name)
    }

    // ── helpers ──

    private fun sampleEvidence(): OcrEvidencePackage = OcrEvidencePackage(
        sessionId = "ocr-1-sample",
        frameId = 7L,
        frameIndex = 0,
        mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        outputSchemaJson = """{"type":"object"}""",
        textLines = listOf(OcrTextLine("hello", OcrFieldFusion.Confidence.MEDIUM)),
        barcodeAnchors = emptyList(),
        frameQuality = 0.9,
    )
}
