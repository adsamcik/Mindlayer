package com.adsamcik.mindlayer.sdk.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot-style tests for [VisionPrompts] focused on the *durable* contract
 * pieces, not exact wording. These pieces are what the rest of the SDK and
 * the model contract depend on:
 *
 * - [VisionPrompts.detect] must include the label list and a "json" hint.
 * - [VisionPrompts.locate] must include `box_2d` and request a single entry.
 * - [VisionPrompts.count] must surface the exact/approximate caveat so
 *   callers know not to treat the result as exact for dense scenes.
 *
 * Tests intentionally do not assert exact prompt wording — letting prompts
 * be tuned without re-asserting tests on every word change keeps the
 * snapshot durable.
 */
class VisionPromptsTest {

    // ---- detect ------------------------------------------------------------

    @Test
    fun `detect joins labels with commas and includes JSON bias`() {
        val prompt = VisionPrompts.detect(listOf("person", "car", "traffic light"))
        assertContainsIgnoreCase(prompt, "detect")
        assertTrue("prompt must contain 'person'", prompt.contains("person"))
        assertTrue("prompt must contain 'car'", prompt.contains("car"))
        assertTrue("prompt must contain 'traffic light'", prompt.contains("traffic light"))
        // join uses ", " (no Oxford comma)
        assertTrue("expected '..., car, traffic light'", prompt.contains("car, traffic light"))
        // JSON bias is critical — without it Gemma produces prose
        assertContainsIgnoreCase(prompt, "json")
        // The explicit box_2d schema hint is required: the on-device E2B
        // variant falls back to a content string array without it
        // (verified end-to-end on emulator, PR #138). Lock it in.
        assertTrue("prompt must reference 'box_2d'", prompt.contains("box_2d"))
        assertTrue(
            "prompt must spell out the [y1, x1, y2, x2] schema",
            prompt.contains("[y1, x1, y2, x2]"),
        )
        assertTrue("prompt must mention the 0..1000 grid", prompt.contains("0..1000"))
    }

    @Test
    fun `detect with maxObjects includes 'at most' clause`() {
        val prompt = VisionPrompts.detect(listOf("x"), maxObjects = 5)
        assertContainsIgnoreCase(prompt, "at most 5")
    }

    @Test
    fun `detect trims label whitespace`() {
        val prompt = VisionPrompts.detect(listOf("  person  ", " car "))
        assertTrue("must contain trimmed 'person, car'", prompt.contains("person, car"))
        // Pre-trim padding should not leak into the prompt
        assertFalse("must not contain double-space artifact", prompt.contains("  "))
    }

    @Test
    fun `detect rejects empty labels`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.detect(emptyList())
        }
    }

    @Test
    fun `detect rejects blank entries`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.detect(listOf("ok", "  "))
        }
    }

    @Test
    fun `detect rejects too many labels`() {
        val tooMany = List(VisionPrompts.MAX_LABELS + 1) { "label$it" }
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.detect(tooMany)
        }
    }

    @Test
    fun `detect rejects oversize label`() {
        val tooLong = "a".repeat(VisionPrompts.MAX_LABEL_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.detect(listOf(tooLong))
        }
    }

    // ---- locate ------------------------------------------------------------

    @Test
    fun `locate references box_2d and asks for one entry`() {
        val prompt = VisionPrompts.locate("the red car on the left")
        assertTrue("prompt must reference 'box_2d'", prompt.contains("box_2d"))
        assertContainsIgnoreCase(prompt, "one")
        // include the original query
        assertTrue("prompt must include the query", prompt.contains("the red car on the left"))
    }

    @Test
    fun `locate rejects blank description`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.locate("   ")
        }
    }

    @Test
    fun `locate rejects oversize description`() {
        val tooLong = "a".repeat(VisionPrompts.MAX_FREEFORM_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.locate(tooLong)
        }
    }

    // ---- caption -----------------------------------------------------------

    @Test
    fun `caption Short asks for a single short caption`() {
        val p = VisionPrompts.caption(CaptionStyle.Short)
        assertContainsIgnoreCase(p, "caption")
        assertContainsIgnoreCase(p, "short")
    }

    @Test
    fun `caption Descriptive asks for a paragraph`() {
        val p = VisionPrompts.caption(CaptionStyle.Descriptive)
        assertContainsIgnoreCase(p, "paragraph")
    }

    @Test
    fun `caption Hashtags asks for hash-prefixed lines`() {
        val p = VisionPrompts.caption(CaptionStyle.Hashtags)
        assertContainsIgnoreCase(p, "hashtag")
        assertTrue("must reference '#' character", p.contains("#"))
    }

    // ---- describe ----------------------------------------------------------

    @Test
    fun `describe at each detail level produces distinct prompts`() {
        val s = VisionPrompts.describe(DescribeDetail.Short)
        val m = VisionPrompts.describe(DescribeDetail.Medium)
        val l = VisionPrompts.describe(DescribeDetail.Long)
        assertEquals(3, setOf(s, m, l).size)
    }

    @Test
    fun `describe with focus appends focus clause`() {
        val withFocus = VisionPrompts.describe(focus = "the wildlife")
        val withoutFocus = VisionPrompts.describe()
        assertTrue("focused prompt must include 'wildlife'", withFocus.contains("wildlife"))
        assertFalse("unfocused prompt must not include 'wildlife'", withoutFocus.contains("wildlife"))
    }

    @Test
    fun `describe with blank or null focus is treated as no focus`() {
        val nullFocus = VisionPrompts.describe(focus = null)
        val blankFocus = VisionPrompts.describe(focus = "   ")
        assertEquals(nullFocus, blankFocus)
    }

    @Test
    fun `describe rejects oversize focus`() {
        val tooLong = "x".repeat(VisionPrompts.MAX_FREEFORM_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.describe(focus = tooLong)
        }
    }

    // ---- count -------------------------------------------------------------

    @Test
    fun `count surfaces exact-or-approximate caveat`() {
        val p = VisionPrompts.count("people")
        assertTrue("count prompt must mention 'people'", p.contains("people"))
        assertTrue("count prompt must mention integer reply", p.contains("integer"))
        // Without this caveat, callers will treat dense-scene counts as exact;
        // the docs explicitly call this out as a model limitation. Lock it in.
        assertContainsIgnoreCase(p, "approximate")
    }

    @Test
    fun `count rejects blank input`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.count("  \n  ")
        }
    }

    @Test
    fun `count rejects oversize input`() {
        val tooLong = "x".repeat(VisionPrompts.MAX_FREEFORM_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) {
            VisionPrompts.count(tooLong)
        }
    }

    private fun assertContainsIgnoreCase(haystack: String, needle: String) {
        assertTrue(
            "expected '$haystack' to contain '$needle' (case-insensitive)",
            haystack.contains(needle, ignoreCase = true),
        )
    }
}
