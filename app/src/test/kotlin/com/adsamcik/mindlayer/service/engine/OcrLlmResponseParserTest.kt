package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [OcrLlmResponseParser].
 *
 * Verifies fence stripping, partial-key handling, confidence parsing,
 * blank-value filtering, and brace-balanced extraction tolerance.
 */
class OcrLlmResponseParserTest {

    @Test fun `clean JSON object parses to fields with paired confidence`() {
        val raw = """
            {
              "total": "24.95",
              "total_confidence": "high",
              "tax": "1.95",
              "tax_confidence": "medium"
            }
        """.trimIndent()
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(2, result.fields.size)
        val byName = result.fields.associateBy { it.name }
        assertEquals("24.95", byName.getValue("total").value)
        assertEquals(OcrFieldFusion.Confidence.HIGH, byName.getValue("total").confidence)
        assertEquals(OcrFieldFusion.Confidence.MEDIUM, byName.getValue("tax").confidence)
    }

    @Test fun `markdown json fence is stripped`() {
        val raw = """
            ```json
            {"sku": "ABC-123", "sku_confidence": "low"}
            ```
        """.trimIndent()
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        assertEquals("ABC-123", result.fields.single().value)
        assertEquals(OcrFieldFusion.Confidence.LOW, result.fields.single().confidence)
    }

    @Test fun `plain markdown fence without language tag is stripped`() {
        val raw = "```\n{\"sku\":\"ABC\",\"sku_confidence\":\"high\"}\n```"
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        assertEquals(OcrFieldFusion.Confidence.HIGH, result.fields.single().confidence)
    }

    @Test fun `leading prose is tolerated by brace-balanced extraction`() {
        val raw = """Sure, here is the extracted JSON:

            {
              "name": "Adam",
              "name_confidence": "high"
            }
        """.trimIndent()
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        assertEquals("Adam", result.fields.single().value)
    }

    @Test fun `unknown confidence value defaults to MEDIUM`() {
        val raw = """{"x":"v","x_confidence":"bananas"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(OcrFieldFusion.Confidence.MEDIUM, result.fields.single().confidence)
    }

    @Test fun `missing confidence sibling defaults to MEDIUM`() {
        val raw = """{"foo":"bar"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        assertEquals("bar", result.fields.single().value)
        assertEquals(OcrFieldFusion.Confidence.MEDIUM, result.fields.single().confidence)
    }

    @Test fun `numbers are stringified verbatim`() {
        val raw = """{"price":24.95,"qty":3,"price_confidence":"high"}"""
        val result = OcrLlmResponseParser.parse(raw)
        val byName = result.fields.associateBy { it.name }
        assertEquals("24.95", byName.getValue("price").value)
        assertEquals("3", byName.getValue("qty").value)
    }

    @Test fun `booleans are stringified`() {
        val raw = """{"taxable":true,"taxable_confidence":"low"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals("true", result.fields.single().value)
        assertEquals(OcrFieldFusion.Confidence.LOW, result.fields.single().confidence)
    }

    @Test fun `null fields are dropped`() {
        val raw = """{"shipping":null,"name":"Adam"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        assertEquals("Adam", result.fields.single().value)
    }

    @Test fun `blank string values are dropped`() {
        val raw = """{"empty":"","present":"yes"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        assertEquals("present", result.fields.single().name)
    }

    @Test fun `confidence-only keys are not emitted as fields`() {
        val raw = """{"_confidence":"high","name":"Adam","name_confidence":"high"}"""
        val result = OcrLlmResponseParser.parse(raw)
        // The `_confidence`-suffixed key matches the sibling pattern and is skipped.
        // Only `name` should surface.
        assertEquals(1, result.fields.size)
        assertEquals("name", result.fields.single().name)
    }

    @Test fun `nested object is preserved as JSON string`() {
        val raw = """{"address":{"zip":"94105","city":"SF"},"address_confidence":"high"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        val v = result.fields.single().value
        assertTrue("nested JSON must survive: $v", v.contains("zip"))
        assertTrue(v.contains("94105"))
    }

    @Test fun `empty input returns EMPTY`() {
        assertEquals(OcrExtractionResult.EMPTY, OcrLlmResponseParser.parse(""))
        assertEquals(OcrExtractionResult.EMPTY, OcrLlmResponseParser.parse("   "))
    }

    @Test fun `unparseable input returns EMPTY`() {
        assertEquals(OcrExtractionResult.EMPTY, OcrLlmResponseParser.parse("This is just prose."))
        assertEquals(OcrExtractionResult.EMPTY, OcrLlmResponseParser.parse("{ no closing brace"))
        assertEquals(OcrExtractionResult.EMPTY, OcrLlmResponseParser.parse("[not an object]"))
    }

    @Test fun `array root returns EMPTY`() {
        val raw = """["a","b"]"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(OcrExtractionResult.EMPTY, result)
    }

    @Test fun `rawJson is the fence-stripped balanced object`() {
        val raw = "```json\n{\"x\":\"v\"}\nsome trailing junk\n```"
        val result = OcrLlmResponseParser.parse(raw)
        // brace-balanced extractor keeps only up to the matching '}'.
        assertEquals("""{"x":"v"}""", result.rawJson)
    }

    @Test fun `confidence values are case-insensitive`() {
        val tests = mapOf(
            "HIGH" to OcrFieldFusion.Confidence.HIGH,
            "High" to OcrFieldFusion.Confidence.HIGH,
            " low " to OcrFieldFusion.Confidence.LOW,
            "Medium" to OcrFieldFusion.Confidence.MEDIUM,
            "h" to OcrFieldFusion.Confidence.HIGH,
            "L" to OcrFieldFusion.Confidence.LOW,
        )
        for ((input, expected) in tests) {
            val raw = """{"x":"v","x_confidence":"$input"}"""
            val result = OcrLlmResponseParser.parse(raw)
            assertEquals(
                "input=$input should map to $expected",
                expected,
                result.fields.single().confidence,
            )
        }
    }

    @Test fun `braces inside strings do not confuse the extractor`() {
        val raw = """{"note":"value with } embedded","note_confidence":"high"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        assertEquals("value with } embedded", result.fields.single().value)
    }

    @Test fun `escaped quotes inside strings are honored`() {
        val raw = """{"note":"she said \"hi\"","note_confidence":"medium"}"""
        val result = OcrLlmResponseParser.parse(raw)
        assertEquals(1, result.fields.size)
        // Kotlinx parses the escapes, so the value is the unescaped form.
        assertTrue(result.fields.single().value.contains("she said"))
    }

    @Test fun `extractTopLevelJsonObject returns null when no opening brace`() {
        assertNull(OcrLlmResponseParser.extractTopLevelJsonObject("no json here"))
    }
}
