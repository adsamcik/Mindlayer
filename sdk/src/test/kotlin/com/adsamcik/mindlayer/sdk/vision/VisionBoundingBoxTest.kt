package com.adsamcik.mindlayer.sdk.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [VisionBoundingBox] coordinate math and validation.
 *
 * Robolectric is required because [VisionBoundingBox.toPixelRect] returns an
 * `android.graphics.Rect`, which is not a pure-JVM type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VisionBoundingBoxTest {

    // ---- Construction invariants -------------------------------------------

    @Test
    fun `accepts valid unit-square box`() {
        VisionBoundingBox(0f, 0f, 1f, 1f)
        VisionBoundingBox(0.25f, 0.25f, 0.75f, 0.75f)
    }

    @Test
    fun `rejects NaN`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionBoundingBox(Float.NaN, 0f, 1f, 1f)
        }
    }

    @Test
    fun `rejects infinity`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionBoundingBox(0f, 0f, Float.POSITIVE_INFINITY, 1f)
        }
    }

    @Test
    fun `rejects out-of-range`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionBoundingBox(-0.1f, 0f, 1f, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionBoundingBox(0f, 0f, 1.1f, 1f)
        }
    }

    @Test
    fun `rejects inverted box`() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionBoundingBox(0.7f, 0f, 0.3f, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionBoundingBox(0f, 0.7f, 1f, 0.3f)
        }
    }

    // ---- fromBox2d (Gemma's [y1, x1, y2, x2] 0..1000 grid) -----------------

    @Test
    fun `fromBox2d maps Gemma docs example correctly`() {
        // The image-vision docs use box_2d = [243, 252, 956, 415] labelled "person".
        // [y1=243, x1=252, y2=956, x2=415]  ⇒  left=.252, top=.243, right=.415, bottom=.956
        val box = VisionBoundingBox.fromBox2d(listOf(243f, 252f, 956f, 415f))
        assertNotNull(box)
        box!!
        assertEquals(0.252f, box.left, 1e-6f)
        assertEquals(0.243f, box.top, 1e-6f)
        assertEquals(0.415f, box.right, 1e-6f)
        assertEquals(0.956f, box.bottom, 1e-6f)
    }

    @Test
    fun `fromBox2d maps simple round numbers`() {
        // box_2d = [100, 200, 300, 400]  ⇒  left=.2, top=.1, right=.4, bottom=.3
        val box = VisionBoundingBox.fromBox2d(listOf(100f, 200f, 300f, 400f))!!
        assertEquals(0.2f, box.left, 1e-6f)
        assertEquals(0.1f, box.top, 1e-6f)
        assertEquals(0.4f, box.right, 1e-6f)
        assertEquals(0.3f, box.bottom, 1e-6f)
    }

    @Test
    fun `fromBox2d allows full-image and zero-area edges`() {
        assertNotNull(VisionBoundingBox.fromBox2d(listOf(0f, 0f, 1000f, 1000f)))
        // zero-area "point" boxes — Gemma occasionally emits these
        assertNotNull(VisionBoundingBox.fromBox2d(listOf(500f, 500f, 500f, 500f)))
    }

    @Test
    fun `fromBox2d rejects wrong length`() {
        assertNull(VisionBoundingBox.fromBox2d(listOf(1f, 2f, 3f)))
        assertNull(VisionBoundingBox.fromBox2d(listOf(1f, 2f, 3f, 4f, 5f)))
        assertNull(VisionBoundingBox.fromBox2d(emptyList()))
    }

    @Test
    fun `fromBox2d rejects non-finite values`() {
        assertNull(VisionBoundingBox.fromBox2d(listOf(Float.NaN, 0f, 100f, 100f)))
        assertNull(VisionBoundingBox.fromBox2d(listOf(0f, 0f, Float.POSITIVE_INFINITY, 100f)))
    }

    @Test
    fun `fromBox2d rejects out-of-range values`() {
        assertNull(VisionBoundingBox.fromBox2d(listOf(-1f, 0f, 500f, 500f)))
        assertNull(VisionBoundingBox.fromBox2d(listOf(0f, 0f, 1001f, 500f)))
    }

    @Test
    fun `fromBox2d rejects inverted box`() {
        // y2 < y1
        assertNull(VisionBoundingBox.fromBox2d(listOf(800f, 0f, 200f, 500f)))
        // x2 < x1
        assertNull(VisionBoundingBox.fromBox2d(listOf(0f, 800f, 500f, 200f)))
    }

    // ---- toPixelRect (clamping, rounding, dimension projection) ------------

    @Test
    fun `toPixelRect on landscape image scales coordinates`() {
        val box = VisionBoundingBox(0.25f, 0.5f, 0.75f, 1.0f)
        val rect = box.toPixelRect(imageWidth = 1000, imageHeight = 500)
        assertEquals(250, rect.left)
        assertEquals(250, rect.top)
        assertEquals(750, rect.right)
        assertEquals(500, rect.bottom)
    }

    @Test
    fun `toPixelRect on portrait image scales coordinates`() {
        val box = VisionBoundingBox(0.1f, 0.2f, 0.9f, 0.8f)
        val rect = box.toPixelRect(imageWidth = 500, imageHeight = 1000)
        assertEquals(50, rect.left)
        assertEquals(200, rect.top)
        assertEquals(450, rect.right)
        assertEquals(800, rect.bottom)
    }

    @Test
    fun `toPixelRect uses floor on top-left and ceil on bottom-right`() {
        // Coordinates that don't land on integer pixel boundaries — we want
        // the resulting Rect to strictly *contain* the model's region.
        val box = VisionBoundingBox(0.1234f, 0.4321f, 0.8765f, 0.5678f)
        val rect = box.toPixelRect(imageWidth = 100, imageHeight = 100)
        // left = floor(12.34) = 12; top = floor(43.21) = 43
        assertEquals(12, rect.left)
        assertEquals(43, rect.top)
        // right = ceil(87.65) = 88; bottom = ceil(56.78) = 57
        assertEquals(88, rect.right)
        assertEquals(57, rect.bottom)
    }

    @Test
    fun `toPixelRect clamps to image bounds`() {
        // Edge-flush box should produce right=W and bottom=H exactly.
        val box = VisionBoundingBox(0f, 0f, 1f, 1f)
        val rect = box.toPixelRect(imageWidth = 640, imageHeight = 480)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(640, rect.right)
        assertEquals(480, rect.bottom)
    }

    @Test
    fun `toPixelRect rejects non-positive dimensions`() {
        val box = VisionBoundingBox(0f, 0f, 1f, 1f)
        assertThrows(IllegalArgumentException::class.java) { box.toPixelRect(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { box.toPixelRect(100, -1) }
    }

    @Test
    fun `width and height fractions are correct`() {
        val box = VisionBoundingBox(0.25f, 0.1f, 0.75f, 0.6f)
        assertEquals(0.5f, box.widthFraction, 1e-6f)
        assertEquals(0.5f, box.heightFraction, 1e-6f)
    }

    @Test
    fun `toPixelRect right stays at least left after rounding`() {
        // Degenerate point box: ceil(left*W) must not exceed clampedLeft.
        val box = VisionBoundingBox(0.5f, 0.5f, 0.5f, 0.5f)
        val rect = box.toPixelRect(100, 100)
        assertTrue("right ($rect) must be >= left", rect.right >= rect.left)
        assertTrue("bottom ($rect) must be >= top", rect.bottom >= rect.top)
    }
}
