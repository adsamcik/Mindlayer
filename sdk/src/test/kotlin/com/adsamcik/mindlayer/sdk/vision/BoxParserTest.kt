package com.adsamcik.mindlayer.sdk.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [BoxParser]. Robolectric is used so the parser's
 * `VisionBoundingBox.toPixelRect` codepath is exercisable from tests
 * (the parser itself is pure JVM but downstream assertions reach Rect).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BoxParserTest {

    // ---- Canonical fenced output (matches docs example) --------------------

    @Test
    fun `parses canonical fenced docs example`() {
        val raw = """
            ```json
            [
              {"box_2d": [243, 252, 956, 415], "label": "person"},
              {"box_2d": [356, 606, 654, 802], "label": "cat"}
            ]
            ```
        """.trimIndent()
        val result = BoxParser.parse(raw)
        assertTrue("expected Success, got $result", result is DetectionResult.Success)
        val objs = result.objects
        assertEquals(2, objs.size)
        assertEquals("person", objs[0].label)
        assertEquals("cat", objs[1].label)
        // Spot-check coords: [243, 252, 956, 415] ⇒ left=.252 top=.243 right=.415 bottom=.956
        assertEquals(0.252f, objs[0].box.left, 1e-6f)
        assertEquals(0.243f, objs[0].box.top, 1e-6f)
    }

    @Test
    fun `parses fence without language tag`() {
        val raw = """
            ```
            [{"box_2d": [0, 0, 100, 100], "label": "dot"}]
            ```
        """.trimIndent()
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
        assertEquals("dot", result.objects.single().label)
    }

    @Test
    fun `parses bare unfenced array`() {
        val raw = """[{"box_2d": [100, 200, 300, 400], "label": "car"}]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals("car", result.objects.single().label)
    }

    @Test
    fun `parses array with prose preamble and trailing prose`() {
        val raw = """
            Here are the objects I found:
            [
              {"box_2d": [10, 20, 30, 40], "label": "a"},
              {"box_2d": [50, 60, 70, 80], "label": "b"}
            ]
            Let me know if you want more detail.
        """.trimIndent()
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(2, result.objects.size)
    }

    // ---- Failure branches --------------------------------------------------

    @Test
    fun `blank response returns NoStructuredOutput`() {
        assertTrue(BoxParser.parse("") is DetectionResult.NoStructuredOutput)
        assertTrue(BoxParser.parse("   \n\t  ") is DetectionResult.NoStructuredOutput)
    }

    @Test
    fun `pure prose returns NoStructuredOutput`() {
        val raw = "I cannot see any of the requested objects in this image."
        assertTrue(BoxParser.parse(raw) is DetectionResult.NoStructuredOutput)
    }

    @Test
    fun `unmatched brackets return NoStructuredOutput`() {
        // start `[` but no `]`
        assertTrue(BoxParser.parse("opening [ but never closes").let { it is DetectionResult.NoStructuredOutput })
    }

    @Test
    fun `malformed top-level JSON returns ParseError`() {
        // Bracketed but unparseable — `{` without closing.
        val raw = "[{\"box_2d\": [1,2,3,4], \"label\": \"x\""
        val result = BoxParser.parse(raw)
        assertTrue("expected ParseError, got $result", result is DetectionResult.ParseError)
        val err = result as DetectionResult.ParseError
        // Sanity: never leak model output / labels in error message.
        assertTrue("error message must not contain raw label", !err.message.contains("\"x\""))
    }

    @Test
    fun `bare JSON object without brackets returns NoStructuredOutput`() {
        // Top-level object with no `[` or `]` anywhere — extractJsonArrayPayload
        // can't find a candidate so we surface NoStructuredOutput rather than
        // trying to coerce an object into an array.
        val raw = """{"name": "single", "confidence": 0.9}"""
        assertTrue(BoxParser.parse(raw) is DetectionResult.NoStructuredOutput)
    }

    @Test
    fun `top-level array of scalars returns Success with empty objects`() {
        // A JSON array of non-object entries is structurally valid but contains
        // zero parseable detection entries — `Success(emptyList())`, not a
        // failure branch. Tested separately so the per-entry tolerance is
        // explicit (we silently skip non-objects, never throw on them).
        val raw = "[1, 2, 3, 4]"
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertTrue(result.objects.isEmpty())
    }

    @Test
    fun `empty JSON array returns Success with empty objects`() {
        val raw = "```json\n[]\n```"
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertTrue(result.objects.isEmpty())
    }

    // ---- Per-entry tolerance ----------------------------------------------

    @Test
    fun `skips entry with missing label`() {
        val raw = """[
            {"box_2d": [0, 0, 100, 100]},
            {"box_2d": [200, 200, 300, 300], "label": "ok"}
        ]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
        assertEquals("ok", result.objects.single().label)
    }

    @Test
    fun `skips entry with blank label`() {
        val raw = """[
            {"box_2d": [0, 0, 100, 100], "label": "   "},
            {"box_2d": [200, 200, 300, 300], "label": "ok"}
        ]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
    }

    @Test
    fun `skips entry with missing box_2d`() {
        val raw = """[
            {"label": "lonely"},
            {"box_2d": [200, 200, 300, 300], "label": "ok"}
        ]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
    }

    @Test
    fun `skips entry with wrong box_2d length`() {
        val raw = """[
            {"box_2d": [1, 2, 3], "label": "tooShort"},
            {"box_2d": [1, 2, 3, 4, 5], "label": "tooLong"},
            {"box_2d": [200, 200, 300, 300], "label": "ok"}
        ]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
        assertEquals("ok", result.objects.single().label)
    }

    @Test
    fun `skips entry with out-of-range coordinates`() {
        val raw = """[
            {"box_2d": [-10, 0, 100, 100], "label": "negative"},
            {"box_2d": [0, 0, 1500, 100], "label": "tooBig"},
            {"box_2d": [200, 200, 300, 300], "label": "ok"}
        ]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
    }

    @Test
    fun `skips inverted box`() {
        val raw = """[
            {"box_2d": [800, 0, 200, 100], "label": "y_inverted"},
            {"box_2d": [200, 200, 300, 300], "label": "ok"}
        ]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
    }

    @Test
    fun `tolerates non-string label primitives`() {
        // kotlinx.serialization in lenient mode returns the literal as a string;
        // primitive-numeric labels should not crash but should not produce an entry
        // unless coerced to a non-empty string. We accept either: passing or
        // skipping. Verify it does not crash.
        val raw = """[{"box_2d": [0,0,100,100], "label": 42}]"""
        val result = BoxParser.parse(raw)
        assertNotNull(result)
        // In lenient mode kotlinx treats 42 as a numeric primitive; contentOrNull
        // returns "42". Either outcome (1 object or 0) is acceptable; we just
        // assert the parser didn't throw.
        assertTrue(result is DetectionResult.Success || result is DetectionResult.ParseError)
    }

    @Test
    fun `tolerates extra fields per entry`() {
        val raw = """[{"box_2d": [0,0,100,100], "label": "x", "confidence": 0.9, "extra": "ignored"}]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals("x", result.objects.single().label)
    }

    @Test
    fun `tolerates skipped fields in mixed array`() {
        val raw = """[
            42,
            "not an object",
            {"box_2d": [0,0,100,100], "label": "valid"},
            null
        ]"""
        val result = BoxParser.parse(raw) as DetectionResult.Success
        assertEquals(1, result.objects.size)
        assertEquals("valid", result.objects.single().label)
    }

    // ---- extractJsonArrayPayload (internal helper) -------------------------

    @Test
    fun `extractJsonArrayPayload strips json fence`() {
        val raw = "```json\n[1, 2]\n```"
        assertEquals("[1, 2]", BoxParser.extractJsonArrayPayload(raw))
    }

    @Test
    fun `extractJsonArrayPayload strips uppercase fence`() {
        val raw = "```JSON\n[1, 2]\n```"
        assertEquals("[1, 2]", BoxParser.extractJsonArrayPayload(raw))
    }

    @Test
    fun `extractJsonArrayPayload trims to outermost brackets when no fence`() {
        val raw = "noise [1, 2] more noise"
        assertEquals("[1, 2]", BoxParser.extractJsonArrayPayload(raw))
    }

    @Test
    fun `extractJsonArrayPayload returns null when no array present`() {
        assertNull(BoxParser.extractJsonArrayPayload("just prose, no brackets"))
        assertNull(BoxParser.extractJsonArrayPayload(""))
    }
}
