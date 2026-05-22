package com.adsamcik.mindlayer.service.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-JVM (Robolectric) tests for [MediaPartYPlaneExtractor].
 *
 * Covers only the [MediaPartYPlaneExtractor.bitmapToYPlane] helper.
 * The full [MediaPartYPlaneExtractor.extractY] entry point goes
 * through the live [com.adsamcik.mindlayer.service.ipc.SharedMemoryPool]
 * staging pipeline whose real-`SharedMemory` coverage lives in the
 * pool's own instrumented tests (see also
 * `EmbeddingShmLayoutInstrumentedTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaPartYPlaneExtractorTest {

    @Test fun `bitmapToYPlane returns width times height bytes`() {
        val w = 8; val h = 6
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        assertEquals(w * h, y.size)
    }

    @Test fun `solid black bitmap converts to all-zero Y plane`() {
        val w = 8; val h = 8
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        for (i in y.indices) {
            assertEquals("pixel $i should be 0", 0.toByte(), y[i])
        }
    }

    @Test fun `solid white bitmap converts to ~255 Y plane`() {
        val w = 8; val h = 8
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        for (i in y.indices) {
            // Integer-rounded BT.601: 77*255 + 150*255 + 29*255 + 128 = 65408
            // >>> 8 = 255 — exact.
            assertEquals("pixel $i should be 255 (unsigned)", 255.toByte(), y[i])
        }
    }

    @Test fun `solid red bitmap converts to BT-601 integer luma 77`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED) // 0xFFFF0000
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        // Integer approximation: (77*255 + 128) >>> 8 = 19763 >>> 8 = 77.
        // The textbook BT.601 float coefficients would give 0.299 * 255 = 76,
        // but the (77, 150, 29) / 256 weights we use sum exactly to 256, which
        // is the standard libjpeg approximation and rounds Red slightly up.
        for (b in y) {
            assertEquals("Red 255 -> Y 77 (integer approx)", 77.toByte(), b)
        }
    }

    @Test fun `solid green bitmap converts to BT-601 integer luma 149`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN) // 0xFF00FF00
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        // Integer approximation: (150*255 + 128) >>> 8 = 38378 >>> 8 = 149.
        for (b in y) {
            assertEquals("Green 255 -> Y 149", 149.toByte(), b)
        }
    }

    @Test fun `solid blue bitmap converts to BT-601 integer luma 29`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE) // 0xFF0000FF
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        // Integer approximation: (29*255 + 128) >>> 8 = 7523 >>> 8 = 29.
        for (b in y) {
            assertEquals("Blue 255 -> Y 29", 29.toByte(), b)
        }
    }

    @Test fun `bitmap with horizontal black-white stripes converts to alternating Y rows`() {
        val w = 4
        val h = 4
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (yy in 0 until h) {
            val color = if (yy % 2 == 0) Color.BLACK else Color.WHITE
            for (xx in 0 until w) {
                bitmap.setPixel(xx, yy, color)
            }
        }
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val expected = if (yy % 2 == 0) 0.toByte() else 255.toByte()
                assertEquals("row $yy col $xx", expected, y[yy * w + xx])
            }
        }
    }

    @Test fun `bitmapToYPlane respects row-major order`() {
        // Construct a bitmap with a single bright pixel at (3, 1).
        // Verify that pixel appears at index 1*width + 3 in the Y plane,
        // not anywhere else.
        val w = 8; val h = 4
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        bitmap.setPixel(3, 1, Color.WHITE)
        val y = MediaPartYPlaneExtractor.bitmapToYPlane(bitmap)
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val expected = if (yy == 1 && xx == 3) 255.toByte() else 0.toByte()
                assertEquals("($xx,$yy)", expected, y[yy * w + xx])
            }
        }
    }

    @Test fun `MAX_Y_PIXELS guards against pixel bombs at 24 megapixels`() {
        // We don't actually allocate a 24 megapixel bitmap in tests (that
        // would blow up Robolectric's heap); we just pin the constant so a
        // future tuning pass can't silently raise the cap.
        assertEquals(24_000_000, MediaPartYPlaneExtractor.MAX_Y_PIXELS)
    }

    @Test fun `extractY rejects oversized raw Y-plane before allocation and closes source`() {
        val pipe = ParcelFileDescriptor.createPipe()
        val source = pipe[0]
        try {
            val part = MediaPart(
                requestId = "ocr-raw",
                kind = MediaPart.KIND_IMAGE,
                mimeType = IpcInputValidator.OCR_RAW_Y_PLANE_MIME,
                source = source,
                isSharedMemory = false,
                payloadBytes = 32_000_000L,
                width = 8_000,
                height = 4_000,
                rowStride = 8_000,
            )

            assertThrows(SecurityException::class.java) {
                MediaPartYPlaneExtractor.extractY(part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:test")
            }
            assertFalse(source.fileDescriptor.valid())
        } finally {
            runCatching { pipe[1].close() }
        }
    }

    @Test fun `ExtractedYFrame validates dimensions match yPlane size`() {
        // Constructor invariant: yPlane.size == width * height.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MediaPartYPlaneExtractor.ExtractedYFrame(ByteArray(50), 10, 10)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            MediaPartYPlaneExtractor.ExtractedYFrame(ByteArray(0), 0, 0)
        }
    }

    @Test fun `ExtractedYFrame equality is by content`() {
        val a = MediaPartYPlaneExtractor.ExtractedYFrame(ByteArray(64) { 1 }, 8, 8)
        val b = MediaPartYPlaneExtractor.ExtractedYFrame(ByteArray(64) { 1 }, 8, 8)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = MediaPartYPlaneExtractor.ExtractedYFrame(ByteArray(64) { 2 }, 8, 8)
        assertTrue(a != c)
    }

    @Test fun `ExtractedYFrame toString does not leak bytes`() {
        val a = MediaPartYPlaneExtractor.ExtractedYFrame(ByteArray(64) { it.toByte() }, 8, 8)
        val s = a.toString()
        assertTrue("toString should mention dims", s.contains("8x8"))
        assertTrue("toString should report byte count, not data", s.contains("bytes=64"))
        // No raw integer pixel dump.
        assertTrue("no raw byte array marker", !s.contains("[B"))
    }
}
