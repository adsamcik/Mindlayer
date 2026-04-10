package com.mindlayer.sdk

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import com.mindlayer.AudioTransfer
import com.mindlayer.ImageTransfer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [MediaTransfer] factory methods.
 *
 * Uses Robolectric for Android framework classes (Bitmap, ParcelFileDescriptor,
 * SharedMemory).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaTransferTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  fromImageFile
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `fromImageFile_createsReadOnlyPfd`() {
        val tempFile = File.createTempFile("test_image", ".png")
        try {
            tempFile.writeBytes(ByteArray(256) { it.toByte() })

            val transfer = MediaTransfer.fromImageFile("req-img-1", tempFile)

            assertNotNull(transfer.source)
            assertEquals("req-img-1", transfer.requestId)
            // PFD should be usable (not null/closed)
            assertNotNull(transfer.source.fileDescriptor)

            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromImageFile_setsCorrectMimeType_png`() {
        val tempFile = File.createTempFile("test_image", ".png")
        try {
            tempFile.writeBytes(ByteArray(100))
            val transfer = MediaTransfer.fromImageFile("req-png", tempFile)

            assertEquals("image/png", transfer.mimeType)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromImageFile_setsCorrectMimeType_jpeg`() {
        val tempFile = File.createTempFile("test_image", ".jpg")
        try {
            tempFile.writeBytes(ByteArray(100))
            val transfer = MediaTransfer.fromImageFile("req-jpg", tempFile)

            assertEquals("image/jpeg", transfer.mimeType)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromImageFile_setsCorrectMimeType_webp`() {
        val tempFile = File.createTempFile("test_image", ".webp")
        try {
            tempFile.writeBytes(ByteArray(100))
            val transfer = MediaTransfer.fromImageFile("req-webp", tempFile)

            assertEquals("image/webp", transfer.mimeType)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromImageFile_setsIsSharedMemoryFalse`() {
        val tempFile = File.createTempFile("test_image", ".png")
        try {
            tempFile.writeBytes(ByteArray(64))
            val transfer = MediaTransfer.fromImageFile("req-no-shm", tempFile)

            assertFalse(transfer.isSharedMemory)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromImageFile_setsPayloadBytes`() {
        val tempFile = File.createTempFile("test_image", ".png")
        try {
            val data = ByteArray(512) { 0xAB.toByte() }
            tempFile.writeBytes(data)
            val transfer = MediaTransfer.fromImageFile("req-size", tempFile)

            assertEquals(512, transfer.payloadBytes)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromImageFile_encodedImage_widthHeightZero`() {
        val tempFile = File.createTempFile("test_image", ".jpg")
        try {
            tempFile.writeBytes(ByteArray(128))
            val transfer = MediaTransfer.fromImageFile("req-dim", tempFile)

            // Encoded files have 0 width/height (no decode)
            assertEquals(0, transfer.width)
            assertEquals(0, transfer.height)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  fromAudioFile
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `fromAudioFile_createsReadOnlyPfd`() {
        val tempFile = File.createTempFile("test_audio", ".wav")
        try {
            tempFile.writeBytes(ByteArray(200))
            val transfer = MediaTransfer.fromAudioFile("req-aud-1", tempFile)

            assertNotNull(transfer.source)
            assertNotNull(transfer.source.fileDescriptor)
            assertEquals("req-aud-1", transfer.requestId)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromAudioFile_setsCorrectMimeType_wav`() {
        val tempFile = File.createTempFile("test_audio", ".wav")
        try {
            tempFile.writeBytes(ByteArray(100))
            val transfer = MediaTransfer.fromAudioFile("req-wav", tempFile)

            assertEquals("audio/wav", transfer.mimeType)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromAudioFile_setsCorrectMimeType_mp3`() {
        val tempFile = File.createTempFile("test_audio", ".mp3")
        try {
            tempFile.writeBytes(ByteArray(100))
            val transfer = MediaTransfer.fromAudioFile("req-mp3", tempFile)

            assertEquals("audio/mpeg", transfer.mimeType)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromAudioFile_setsCorrectMimeType_ogg`() {
        val tempFile = File.createTempFile("test_audio", ".ogg")
        try {
            tempFile.writeBytes(ByteArray(100))
            val transfer = MediaTransfer.fromAudioFile("req-ogg", tempFile)

            assertEquals("audio/ogg", transfer.mimeType)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromAudioFile_isNotSharedMemory`() {
        val tempFile = File.createTempFile("test_audio", ".wav")
        try {
            tempFile.writeBytes(ByteArray(100))
            val transfer = MediaTransfer.fromAudioFile("req-aud-no-shm", tempFile)

            assertFalse(transfer.isSharedMemory)
            transfer.source.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromAudioFile_nonExistentFile_throws`() {
        val missingFile = File("/nonexistent/path/audio.wav")
        try {
            MediaTransfer.fromAudioFile("req-missing", missingFile)
            fail("Expected exception for non-existent file")
        } catch (e: Exception) {
            // FileNotFoundException or similar is expected
            assertTrue(
                e is java.io.FileNotFoundException ||
                    e is IllegalArgumentException ||
                    e.message?.contains("No such file") == true ||
                    e.message?.contains("not found") == true ||
                    e.message?.contains("ENOENT") == true,
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  fromBitmap
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @Ignore("Requires real Android FD internals")
    @Config(sdk = [33]) // API 33 > 27 → SharedMemory path
    fun `fromBitmap_api27plus_usesSharedMemory`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-shm", bitmap)

            assertTrue(transfer.isSharedMemory)
            assertNotNull(transfer.source)
            transfer.source.close()
        } finally {
            bitmap.recycle()
        }
    }

    @Ignore("Requires real Android FD internals")
    @Test
    fun `fromBitmap_setsCorrectDimensions`() {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-dim", bitmap)

            assertEquals(120, transfer.width)
            assertEquals(80, transfer.height)
            transfer.source.close()
        } finally {
            bitmap.recycle()
        }
    }

    @Ignore("Requires real Android FD internals")
    @Test
    fun `fromBitmap_setsRgbaFormat`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-fmt", bitmap)

            assertEquals(PixelFormat.RGBA_8888, transfer.pixelFormat)
            transfer.source.close()
        } finally {
            bitmap.recycle()
        }
    }

    @Ignore("Requires real Android FD internals")
    @Test
    fun `fromBitmap_setsCorrectRowStride`() {
        val bitmap = Bitmap.createBitmap(50, 30, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-stride", bitmap)

            // ARGB_8888: 4 bytes per pixel → rowStride >= width * 4
            assertTrue(transfer.rowStride >= 50 * 4)
            transfer.source.close()
        } finally {
            bitmap.recycle()
        }
    }

    @Ignore("Requires real Android FD internals")
    @Test
    fun `fromBitmap_setsCorrectPayloadBytes`() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-payload", bitmap)

            assertEquals(bitmap.allocationByteCount, transfer.payloadBytes)
            transfer.source.close()
        } finally {
            bitmap.recycle()
        }
    }

    @Ignore("Requires real Android FD internals")
    @Test
    fun `fromBitmap_nullMimeType_forRawPixels`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-raw", bitmap)

            // Raw pixel transport has null mimeType
            assertEquals(null, transfer.mimeType)
            transfer.source.close()
        } finally {
            bitmap.recycle()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  fromAudioBytes
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @Ignore("Requires real Android FD internals")
    @Config(sdk = [33])
    fun `fromAudioBytes_smallPayload_works`() {
        val payload = ByteArray(256) { (it % 128).toByte() }
        val transfer = MediaTransfer.fromAudioBytes("req-bytes", payload, "audio/wav")

        assertNotNull(transfer.source)
        assertEquals("req-bytes", transfer.requestId)
        assertEquals("audio/wav", transfer.mimeType)
        transfer.source.close()
    }

    @Test
    @Ignore("Requires real Android FD internals")
    @Config(sdk = [33])
    fun `fromAudioBytes_api27plus_usesSharedMemory`() {
        val payload = ByteArray(100)
        val transfer = MediaTransfer.fromAudioBytes("req-aud-shm", payload, "audio/mpeg")

        assertTrue(transfer.isSharedMemory)
        transfer.source.close()
    }

    @Test
    @Ignore("Requires real Android FD internals")
    @Config(sdk = [33])
    fun `fromAudioBytes_preservesMimeType`() {
        val payload = ByteArray(50)
        val transfer = MediaTransfer.fromAudioBytes("req-mime", payload, "audio/flac")

        assertEquals("audio/flac", transfer.mimeType)
        transfer.source.close()
    }

    @Test
    @Config(sdk = [26]) // API 26 → pipe-based fallback
    fun `fromAudioBytes_api26_usesPipeFallback`() {
        val payload = ByteArray(64) { 0x55 }
        val transfer = MediaTransfer.fromAudioBytes("req-pipe", payload, "audio/wav")

        assertFalse(transfer.isSharedMemory)
        assertEquals("audio/wav", transfer.mimeType)
        assertNotNull(transfer.source)
        transfer.source.close()
    }

    @Test
    @Ignore("Requires real Android FD internals")
    @Config(sdk = [26]) // API 26 → pipe-based fallback for bitmap too
    fun `fromBitmap_api26_usesPipeFallback`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-bmp-pipe", bitmap)

            assertFalse(transfer.isSharedMemory)
            assertEquals("image/png", transfer.mimeType) // pipe path compresses to PNG
            assertEquals(16, transfer.width)
            assertEquals(16, transfer.height)
            transfer.source.close()
        } finally {
            bitmap.recycle()
        }
    }
}
