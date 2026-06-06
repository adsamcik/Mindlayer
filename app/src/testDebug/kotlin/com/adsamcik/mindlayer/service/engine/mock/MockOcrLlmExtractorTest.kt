package com.adsamcik.mindlayer.service.engine.mock

import com.adsamcik.mindlayer.service.engine.OcrEvidencePackage
import com.adsamcik.mindlayer.service.engine.OcrFieldFusion
import com.adsamcik.mindlayer.service.engine.OcrTextLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [MockOcrLlmExtractor]: plausible `[mock]` fields for a frame
 * with recognised lines, and EMPTY for a frame with none.
 */
class MockOcrLlmExtractorTest {

    private val extractor = MockOcrLlmExtractor()

    private fun evidence(lines: List<OcrTextLine>) = OcrEvidencePackage(
        sessionId = "s1",
        frameId = 1L,
        frameIndex = 0,
        mode = 0,
        outputSchemaJson = "{\"type\":\"object\"}",
        textLines = lines,
        barcodeAnchors = emptyList(),
        frameQuality = 0.9,
    )

    @Test
    fun `returns mock fields for a non-empty frame`() = runBlocking {
        val result = extractor.extract(
            evidence(listOf(OcrTextLine("[mock] INVOICE", OcrFieldFusion.Confidence.HIGH))),
        )
        assertTrue(result.fields.isNotEmpty())
        assertTrue(result.fields.any { it.name == "mock_status" && it.value.startsWith("[mock]") })
        assertNotNull(result.rawJson)
    }

    @Test
    fun `returns EMPTY for a frame with no lines`() = runBlocking {
        val result = extractor.extract(evidence(emptyList()))
        assertTrue(result.fields.isEmpty())
        assertEquals(null, result.rawJson)
    }

    @Test
    fun `line count field reflects evidence`() = runBlocking {
        val result = extractor.extract(
            evidence(
                listOf(
                    OcrTextLine("[mock] a", OcrFieldFusion.Confidence.MEDIUM),
                    OcrTextLine("[mock] b", OcrFieldFusion.Confidence.MEDIUM),
                ),
            ),
        )
        val countField = result.fields.firstOrNull { it.name == "mock_line_count" }
        assertNotNull(countField)
        assertEquals("2", countField!!.value)
        assertFalse(result.fields.isEmpty())
    }
}
