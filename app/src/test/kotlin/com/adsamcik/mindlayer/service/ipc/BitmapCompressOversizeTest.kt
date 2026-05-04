package com.adsamcik.mindlayer.service.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * F-063: the PNG compress path is wrapped in a [SharedMemoryPool.CountingOutputStream]
 * that throws `IOException("png_too_large")` once the byte count exceeds
 * `MAX_MEDIA_BYTES`. The full Bitmap+compress flow can't run on a JVM unit
 * test (it needs the platform image-encoder backend), so we exercise the
 * counting wrapper directly — the same wrapper that bounds the actual
 * encoder output.
 */
class BitmapCompressOversizeTest {

    @Test
    fun `bytes below cap pass through unchanged`() {
        val sink = ByteArrayOutputStream()
        val capped = SharedMemoryPool.CountingOutputStream(sink, maxBytes = 10L, label = "x")
        capped.write(byteArrayOf(1, 2, 3, 4, 5), 0, 5)
        capped.write(0xAB)
        assertEquals(6, sink.size())
    }

    @Test
    fun `single oversized array write throws`() {
        val sink = ByteArrayOutputStream()
        val capped = SharedMemoryPool.CountingOutputStream(sink, maxBytes = 4L, label = "png_too_large")
        val payload = ByteArray(5) { it.toByte() }
        val ex = assertThrows(IOException::class.java) {
            capped.write(payload, 0, payload.size)
        }
        assertEquals("png_too_large", ex.message)
    }

    @Test
    fun `cumulative writes that exceed cap throw at the boundary`() {
        val sink = ByteArrayOutputStream()
        val capped = SharedMemoryPool.CountingOutputStream(sink, maxBytes = 8L, label = "png_too_large")
        // 8 bytes total — must succeed.
        capped.write(ByteArray(4), 0, 4)
        capped.write(ByteArray(4), 0, 4)
        // The 9th byte must throw.
        val ex = assertThrows(IOException::class.java) { capped.write(0x42) }
        assertEquals("png_too_large", ex.message)
    }

    @Test
    fun `single-byte write past cap throws`() {
        val sink = ByteArrayOutputStream()
        val capped = SharedMemoryPool.CountingOutputStream(sink, maxBytes = 1L, label = "png_too_large")
        capped.write(0x01)
        val ex = assertThrows(IOException::class.java) { capped.write(0x02) }
        assertTrue(ex.message?.contains("png_too_large") == true)
    }

    @Test
    fun `flush and close are forwarded`() {
        val sink = ByteArrayOutputStream()
        val capped = SharedMemoryPool.CountingOutputStream(sink, maxBytes = 100L, label = "x")
        capped.write(0x99)
        capped.flush()
        capped.close()
        assertEquals(1, sink.size())
    }
}
