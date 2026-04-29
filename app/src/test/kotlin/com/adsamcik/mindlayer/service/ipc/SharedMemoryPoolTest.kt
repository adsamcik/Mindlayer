package com.adsamcik.mindlayer.service.ipc

import android.graphics.PixelFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedMemoryPoolTest {

    private lateinit var cacheDir: File
    private lateinit var pool: SharedMemoryPool

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        pool = SharedMemoryPool(cacheDir)
    }

    @After
    fun tearDown() {
        pool.cleanupAll()
        unmockkAll()
    }

    // -- Helpers ---------------------------------------------------------------

    /** Create a temp file with [content] and return a PFD opened for reading. */
    private fun createPfdFromBytes(content: ByteArray, extension: String = "bin"): ParcelFileDescriptor {
        val tmp = File.createTempFile("test_source_", ".$extension", cacheDir)
        tmp.writeBytes(content)
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun createSparsePfd(sizeBytes: Long, extension: String = "bin"): ParcelFileDescriptor {
        val tmp = File.createTempFile("test_sparse_", ".$extension", cacheDir)
        RandomAccessFile(tmp, "rw").use { it.setLength(sizeBytes) }
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // =========================================================================
    // stageAudio
    // =========================================================================

    @Test
    fun `stageAudio with PFD copies to cache and returns correct StagedMedia`() {
        val audioBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF" header stub
        val pfd = createPfdFromBytes(audioBytes, "wav")
        val transfer = AudioTransfer(
            requestId = "req-audio-1",
            mimeType = "audio/wav",
            source = pfd,
            isSharedMemory = false,
        )

        val result = pool.stageAudio(transfer)

        assertEquals("req-audio-1", result.requestId)
        assertEquals("audio/wav", result.mimeType)
        assertTrue("Staged file should exist", File(result.filePath).exists())
        assertTrue("File path should end with .wav", result.filePath.endsWith(".wav"))

        val stagedContent = File(result.filePath).readBytes()
        assertTrue("Staged file content should match source", audioBytes.contentEquals(stagedContent))
    }

    @Test
    fun `stageAudio staged file exists in cacheDir subtree`() {
        val pfd = createPfdFromBytes(byteArrayOf(1, 2, 3), "mp3")
        val transfer = AudioTransfer(
            requestId = "req-audio-2",
            mimeType = "audio/mp3",
            source = pfd,
            isSharedMemory = false,
        )

        val result = pool.stageAudio(transfer)
        val staged = File(result.filePath)

        assertTrue("Staged file should exist", staged.exists())
        assertTrue(
            "Staged file should be under cacheDir",
            staged.absolutePath.startsWith(cacheDir.absolutePath),
        )
    }

    @Test
    fun `stageAudio sanitizes requestId before creating staged filename`() {
        val pfd = createPfdFromBytes(byteArrayOf(1, 2, 3), "wav")
        val transfer = AudioTransfer(
            requestId = "../escape\\req:1",
            mimeType = "audio/wav",
            source = pfd,
            isSharedMemory = false,
        )

        val result = pool.stageAudio(transfer)
        val staged = File(result.filePath)

        assertEquals(File(cacheDir, "media_staging").canonicalPath, staged.parentFile!!.canonicalPath)
        assertFalse("Staged filename should not contain parent traversal", staged.name.contains(".."))
        assertFalse("Staged filename should not contain slash", staged.name.contains('/'))
        assertFalse("Staged filename should not contain backslash", staged.name.contains('\\'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stageAudio rejects oversized SharedMemory stat size before copying`() {
        val pfd = createSparsePfd(101L * 1024L * 1024L, "wav")
        val transfer = AudioTransfer(
            requestId = "req-audio-oversized",
            mimeType = "audio/wav",
            source = pfd,
            isSharedMemory = true,
        )

        try {
            pool.stageAudio(transfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stageAudio rejects declared payload over limit before copying`() {
        val pfd = createPfdFromBytes(byteArrayOf(1), "wav")
        val transfer = AudioTransfer(
            requestId = "req-audio-declared-oversized",
            mimeType = "audio/wav",
            source = pfd,
            isSharedMemory = false,
            payloadBytes = 101 * 1024 * 1024,
        )

        try {
            pool.stageAudio(transfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    // =========================================================================
    // stageImage (non-SharedMemory, encoded image via PFD)
    // =========================================================================

    @Test
    fun `stageImage with JPEG PFD copies to cache and returns correct path`() {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val pfd = createPfdFromBytes(jpegBytes, "jpg")
        val transfer = ImageTransfer(
            requestId = "req-img-1",
            width = 0,
            height = 0,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = jpegBytes.size,
            source = pfd,
            isSharedMemory = false,
            mimeType = "image/jpeg",
        )

        val result = pool.stageImage(transfer)

        assertEquals("req-img-1", result.requestId)
        assertEquals("image/jpeg", result.mimeType)
        assertTrue("File path should end with .jpg", result.filePath.endsWith(".jpg"))
        assertTrue("Staged file should exist", File(result.filePath).exists())

        val stagedContent = File(result.filePath).readBytes()
        assertTrue("Staged file content should match source", jpegBytes.contentEquals(stagedContent))
    }

    @Test(expected = EOFException::class)
    fun `stageImage throws EOFException when SharedMemory source is shorter than declared payload`() {
        val pfd = createPfdFromBytes(byteArrayOf(1, 2), "png")
        val transfer = ImageTransfer(
            requestId = "req-short-read",
            width = 0,
            height = 0,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = 4,
            source = pfd,
            isSharedMemory = true,
            mimeType = "image/png",
        )

        pool.stageImage(transfer)
    }

    // =========================================================================
    // cleanup(requestId)
    // =========================================================================

    @Test
    fun `cleanup deletes staged files for the given request`() {
        val pfd = createPfdFromBytes(byteArrayOf(10, 20, 30), "wav")
        val transfer = AudioTransfer(
            requestId = "req-clean-1",
            mimeType = "audio/wav",
            source = pfd,
        )
        val result = pool.stageAudio(transfer)
        val staged = File(result.filePath)
        assertTrue("File should exist before cleanup", staged.exists())

        pool.cleanup("req-clean-1")

        assertFalse("File should be deleted after cleanup", staged.exists())
    }

    @Test
    fun `cleanup for non-existent requestId does not crash`() {
        // Should be a no-op, no exception
        pool.cleanup("req-does-not-exist")
    }

    // =========================================================================
    // cleanupAll
    // =========================================================================

    @Test
    fun `cleanupAll deletes all staged files`() {
        val pfd1 = createPfdFromBytes(byteArrayOf(1), "wav")
        val pfd2 = createPfdFromBytes(byteArrayOf(2), "mp3")

        val r1 = pool.stageAudio(AudioTransfer("req-all-1", "audio/wav", pfd1))
        val r2 = pool.stageAudio(AudioTransfer("req-all-2", "audio/mp3", pfd2))

        val f1 = File(r1.filePath)
        val f2 = File(r2.filePath)
        assertTrue(f1.exists())
        assertTrue(f2.exists())

        pool.cleanupAll()

        assertFalse("File 1 should be deleted", f1.exists())
        assertFalse("File 2 should be deleted", f2.exists())
    }

    // =========================================================================
    // Isolated cleanup across requests
    // =========================================================================

    @Test
    fun `cleanup of request A does not affect request B`() {
        val pfdA = createPfdFromBytes(byteArrayOf(0xAA.toByte()), "wav")
        val pfdB = createPfdFromBytes(byteArrayOf(0xBB.toByte()), "wav")

        val rA = pool.stageAudio(AudioTransfer("req-A", "audio/wav", pfdA))
        val rB = pool.stageAudio(AudioTransfer("req-B", "audio/wav", pfdB))

        val fA = File(rA.filePath)
        val fB = File(rB.filePath)

        pool.cleanup("req-A")

        assertFalse("Request A file should be deleted", fA.exists())
        assertTrue("Request B file should NOT be deleted", fB.exists())
    }

    @Test
    fun `cleanup deletes every staged file for repeated requestId`() {
        val results = (1..3).map { index ->
            pool.stageAudio(AudioTransfer("req-repeat", "audio/wav", createPfdFromBytes(byteArrayOf(index.toByte()))))
        }
        val files = results.map { File(it.filePath) }
        assertTrue(files.all { it.exists() })

        pool.cleanup("req-repeat")

        assertTrue("All files for the repeated request should be deleted", files.none { it.exists() })
    }

    // =========================================================================
    // StagedMedia data class
    // =========================================================================

    @Test
    fun `StagedMedia has correct properties and cleanup callable`() {
        val pfd = createPfdFromBytes(byteArrayOf(42), "ogg")
        val transfer = AudioTransfer("req-staged", "audio/ogg", pfd)

        val result = pool.stageAudio(transfer)

        assertEquals("req-staged", result.requestId)
        assertEquals("audio/ogg", result.mimeType)
        assertTrue(result.filePath.isNotEmpty())

        val staged = File(result.filePath)
        assertTrue(staged.exists())

        // Invoke the cleanup lambda directly
        result.cleanup()
        assertFalse("Cleanup callable should delete the file", staged.exists())
    }

    // =========================================================================
    // IPC hardening — payload size bounds
    // =========================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `stageImage rejects SharedMemory transfer exceeding MAX_MEDIA_BYTES`() {
        // payloadBytes above the 100 MiB cap must be rejected before any alloc.
        val pfd = createPfdFromBytes(byteArrayOf(0), "bin")
        val transfer = ImageTransfer(
            requestId = "req-oversized",
            width = 1,
            height = 1,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = 200 * 1024 * 1024,
            source = pfd,
            isSharedMemory = true,
            mimeType = "image/png",
        )
        try {
            pool.stageImage(transfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stageImage rejects zero-byte SharedMemory payload`() {
        val pfd = createPfdFromBytes(byteArrayOf(0), "bin")
        val transfer = ImageTransfer(
            requestId = "req-zero",
            width = 1,
            height = 1,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = 0,
            source = pfd,
            isSharedMemory = true,
            mimeType = "image/png",
        )
        try {
            pool.stageImage(transfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    // =========================================================================
    // M4 — validatePixelBufferLayout dimension guard
    // =========================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `validatePixelBufferLayout rejects width exceeding MAX_IMAGE_DIM`() {
        // width=20000 > 8192 must throw before any Bitmap allocation
        validatePixelBufferLayout(
            width = 20000,
            height = 1,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 0,
            bufferSize = 20000 * 4,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validatePixelBufferLayout rejects height exceeding MAX_IMAGE_DIM`() {
        validatePixelBufferLayout(
            width = 1,
            height = 20000,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 0,
            bufferSize = 20000 * 4,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validatePixelBufferLayout rejects zero width`() {
        validatePixelBufferLayout(
            width = 0,
            height = 4,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 0,
            bufferSize = 16,
        )
    }

    // =========================================================================
    // C2 — validatePixelBufferLayout rowStride support
    // =========================================================================

    @Test
    fun `validatePixelBufferLayout tight rowStride returns correct tight size`() {
        // width=4, height=4, bpp=4 → tight = 16*4 = 64
        val tightSize = validatePixelBufferLayout(
            width = 4,
            height = 4,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 0, // 0 means tight
            bufferSize = 64,
        )
        assertEquals(64L, tightSize)
    }

    @Test
    fun `validatePixelBufferLayout non-tight rowStride accepts padded buffer and returns tight size`() {
        // width=4, height=4, bpp=4 → tightRowBytes=16, rowStride=20, buffer=20*4=80
        val tightSize = validatePixelBufferLayout(
            width = 4,
            height = 4,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 20,
            bufferSize = 80,
        )
        assertEquals(64L, tightSize) // tight = 16*4
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validatePixelBufferLayout rejects buffer sized for tight layout when rowStride indicates padding`() {
        // rowStride=20 means buffer must be 20*4=80, not 64
        validatePixelBufferLayout(
            width = 4,
            height = 4,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 20,
            bufferSize = 64,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validatePixelBufferLayout rejects rowStride smaller than width times bpp`() {
        // rowStride=10 < tightRowBytes=16 is invalid
        validatePixelBufferLayout(
            width = 4,
            height = 4,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 10,
            bufferSize = 40,
        )
    }

    @Test
    fun `bytesPerPixel returns 4 for ARGB_8888 and 2 for RGB_565`() {
        assertEquals(4, bytesPerPixel(android.graphics.Bitmap.Config.ARGB_8888))
        assertEquals(2, bytesPerPixel(android.graphics.Bitmap.Config.RGB_565))
    }
}
