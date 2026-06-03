package com.adsamcik.mindlayer.sdk.vision

import android.graphics.Bitmap
import android.util.Log
import com.adsamcik.mindlayer.sdk.InferenceHandle
import com.adsamcik.mindlayer.sdk.InferenceRequest
import com.adsamcik.mindlayer.sdk.Mindlayer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior tests for the [Mindlayer.detectObjects] / [locateObject] /
 * [captionImage] / [describeImage] / [countItems] extension surface in
 * [VisionTasks].
 *
 * Strategy: mock the canonical [Mindlayer.infer] / [Mindlayer.describe]
 * primitives, capture the builder lambda or the prompt string, and run
 * the real extension. Lets us assert (a) the helper called the right
 * canonical entry, (b) it passed the canonical prompt shape, (c) it parsed
 * the canned model output correctly into [DetectedObject]s, and (d) it
 * routes failure cases to the documented branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VisionTasksTest {

    @Before
    fun setUp() {
        // VisionTasks logs metadata-only diagnostics on failure branches —
        // mock android.util.Log so Robolectric doesn't complain about
        // unmocked native log calls during failure assertions.
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun textHandle(result: String): InferenceHandle.Text =
        mockk<InferenceHandle.Text>().also { coEvery { it.awaitText() } returns result }

    // ---- detectObjects: parses canned Gemma output -------------------------

    @Test
    fun `detectObjects parses canonical fenced JSON into DetectedObjects`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        val canned = """
            ```json
            [
              {"box_2d": [243, 252, 956, 415], "label": "person"},
              {"box_2d": [356, 606, 654, 802], "label": "cat"}
            ]
            ```
        """.trimIndent()
        coEvery { sut.infer(capture(build)) } returns textHandle(canned)

        val objs = sut.detectObjects(image, listOf("person", "cat"))

        assertEquals(2, objs.size)
        assertEquals("person", objs[0].label)
        assertEquals("cat", objs[1].label)
        assertEquals(0.252f, objs[0].box.left, 1e-6f)
    }

    @Test
    fun `detectObjects builds canonical detect prompt and includes the image`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns textHandle("[]")

        sut.detectObjects(image, listOf("person", "car"), maxObjects = 16)

        val b = InferenceRequest.Builder().apply(build.captured)
        val prompt = requireNotNull(b.promptText) { "prompt text must be set on the builder" }
        assertContainsIgnoreCase(prompt, "detect")
        assertTrue("prompt must list labels", prompt.contains("person, car"))
        assertContainsIgnoreCase(prompt, "json")
        assertContainsIgnoreCase(prompt, "at most 16")
        // image must be attached
        assertEquals(1, b.imageInputs.size)
        // ephemeral session, not pinned to an id
        assertNull(b.sessionId)
        assertTrue(b.sessionConfigure != null)
    }

    @Test
    fun `detectObjects returns empty list on NoStructuredOutput response`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.infer(any()) } returns textHandle("I do not see any of those objects.")

        val objs = sut.detectObjects(image, listOf("person"))

        assertTrue("expected empty list on non-JSON response", objs.isEmpty())
    }

    @Test
    fun `detectObjects respects maxObjects soft cap on the returned list`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        // Model over-shares: emits 5 objects when caller asked for at most 2.
        val canned = """
            [
              {"box_2d": [0, 0, 100, 100], "label": "a"},
              {"box_2d": [100, 100, 200, 200], "label": "b"},
              {"box_2d": [200, 200, 300, 300], "label": "c"},
              {"box_2d": [300, 300, 400, 400], "label": "d"},
              {"box_2d": [400, 400, 500, 500], "label": "e"}
            ]
        """.trimIndent()
        coEvery { sut.infer(any()) } returns textHandle(canned)

        val objs = sut.detectObjects(image, listOf("anything"), maxObjects = 2)

        assertEquals(2, objs.size)
        assertEquals(listOf("a", "b"), objs.map { it.label })
    }

    @Test
    fun `detectObjects rejects maxObjects below 1`() {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                sut.detectObjects(image, listOf("x"), maxObjects = 0)
            }
        }
    }

    @Test
    fun `detectObjects rejects empty label list`() {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                sut.detectObjects(image, emptyList())
            }
        }
    }

    // ---- detectObjectsResult: tri-state branches ---------------------------

    @Test
    fun `detectObjectsResult returns Success for parseable JSON`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.infer(any()) } returns textHandle("""[{"box_2d":[0,0,100,100],"label":"x"}]""")

        val r = sut.detectObjectsResult(image, listOf("x"))

        assertTrue(r is DetectionResult.Success)
        assertEquals(1, r.objects.size)
    }

    @Test
    fun `detectObjectsResult returns NoStructuredOutput for prose`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.infer(any()) } returns textHandle("Sorry, I can't help with that.")

        val r = sut.detectObjectsResult(image, listOf("x"))

        assertTrue("expected NoStructuredOutput, got $r", r is DetectionResult.NoStructuredOutput)
    }

    @Test
    fun `detectObjectsResult returns ParseError for malformed JSON`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.infer(any()) } returns textHandle("[{\"box_2d\":[1,2,3,4],\"label\":\"x\"")

        val r = sut.detectObjectsResult(image, listOf("x"))

        assertTrue("expected ParseError, got $r", r is DetectionResult.ParseError)
    }

    // ---- locateObject ------------------------------------------------------

    @Test
    fun `locateObject returns the first match`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        val canned = """
            ```json
            [{"box_2d":[100, 200, 300, 400], "label":"red car"}]
            ```
        """.trimIndent()
        coEvery { sut.infer(any()) } returns textHandle(canned)

        val obj = sut.locateObject(image, "the red car on the left")

        assertNotNull(obj)
        assertEquals("red car", obj!!.label)
    }

    @Test
    fun `locateObject returns null when nothing parseable`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.infer(any()) } returns textHandle("I couldn't find that in the image.")

        val obj = sut.locateObject(image, "a unicorn")

        assertNull(obj)
    }

    @Test
    fun `locateObject builds canonical locate prompt`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        val build = slot<InferenceRequest.Builder.() -> Unit>()
        coEvery { sut.infer(capture(build)) } returns textHandle("[]")

        sut.locateObject(image, "the green door")

        val b = InferenceRequest.Builder().apply(build.captured)
        val prompt = requireNotNull(b.promptText)
        assertTrue(prompt.contains("box_2d"))
        assertTrue(prompt.contains("the green door"))
    }

    // ---- captionImage / describeImage delegate via describe(...) -----------

    @Test
    fun `captionImage delegates to Mindlayer describe with caption template`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        val promptSlot = slot<String>()
        coEvery { sut.describe(capture(promptSlot), any(), any()) } returns "a cat"

        val result = sut.captionImage(image, CaptionStyle.Short)

        assertEquals("a cat", result)
        assertContainsIgnoreCase(promptSlot.captured, "caption")
    }

    @Test
    fun `describeImage delegates to Mindlayer describe with describe template`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        val promptSlot = slot<String>()
        coEvery { sut.describe(capture(promptSlot), any(), any()) } returns "scene"

        val result = sut.describeImage(image, DescribeDetail.Medium, focus = "lighting")

        assertEquals("scene", result)
        assertContainsIgnoreCase(promptSlot.captured, "describe")
        assertTrue(promptSlot.captured.contains("lighting"))
    }

    // ---- countItems --------------------------------------------------------

    @Test
    fun `countItems parses integer from first line`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.describe(any(), any(), any()) } returns "7\napproximate"

        val count = sut.countItems(image, "apples")

        assertEquals(7, count)
    }

    @Test
    fun `countItems extracts integer from messy first line`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.describe(any(), any(), any()) } returns "About 12 visible.\nApproximate."

        val count = sut.countItems(image, "people")

        assertEquals(12, count)
    }

    @Test
    fun `countItems returns null on non-numeric response`() = runTest {
        val sut = mockk<Mindlayer>(relaxed = false)
        val image = mockk<Bitmap>()
        coEvery { sut.describe(any(), any(), any()) } returns "many to count"

        val count = sut.countItems(image, "people")

        assertNull(count)
    }

    private fun assertContainsIgnoreCase(haystack: String, needle: String) {
        assertTrue(
            "expected '$haystack' to contain '$needle' (case-insensitive)",
            haystack.contains(needle, ignoreCase = true),
        )
    }
}
