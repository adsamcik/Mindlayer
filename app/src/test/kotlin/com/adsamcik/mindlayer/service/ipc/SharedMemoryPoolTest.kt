package com.adsamcik.mindlayer.service.ipc

import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
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

    private fun validPngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
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

        val result = pool.stageAudio("req-audio-1", transfer)

        assertEquals("req-audio-1", result.scopedKey)
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

        val result = pool.stageAudio("req-audio-2", transfer)
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
            requestId = "req_audio_sanitize",
            mimeType = "audio/wav",
            source = pfd,
            isSharedMemory = false,
        )

        val result = pool.stageAudio("999:req_audio_sanitize", transfer)
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
            pool.stageAudio("req-audio-oversized", transfer)
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
            pool.stageAudio("req-audio-declared-oversized", transfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    // =========================================================================
    // stageImage (non-SharedMemory, encoded image via PFD)
    // =========================================================================

    @Test
    fun `stageImage with PNG PFD copies to cache and returns correct path`() {
        val pngBytes = validPngBytes()
        val pfd = createPfdFromBytes(pngBytes, "png")
        val transfer = ImageTransfer(
            requestId = "req-img-1",
            width = 0,
            height = 0,
            pixelFormat = 0,
            rowStride = 0,
            payloadBytes = pngBytes.size,
            source = pfd,
            isSharedMemory = false,
            mimeType = "image/png",
        )

        val result = pool.stageImage("req-img-1", transfer)

        assertEquals("req-img-1", result.scopedKey)
        assertEquals("image/png", result.mimeType)
        assertTrue("File path should end with .png", result.filePath.endsWith(".png"))
        assertTrue("Staged file should exist", File(result.filePath).exists())

        val stagedContent = File(result.filePath).readBytes()
        assertTrue("Staged file content should match source", pngBytes.contentEquals(stagedContent))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stageImage rejects SharedMemory source shorter than declared payload`() {
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

        pool.stageImage("req-short-read", transfer)
    }

    @Test
    fun `stageImage re-creates staging dir when Android cache trimming deletes it`() {
        // Android's cache-trimming policy is allowed to delete any file or
        // directory under cacheDir under disk pressure. Reproduce: stage one
        // image (succeeds, ensures staging dir exists), then wipe the
        // staging dir to mimic a system-driven cleanup, then stage again
        // and verify the second staging still succeeds and the file is
        // really written. Before the createStagingFile.mkdirs() fix this
        // second stage threw FileNotFoundException(ENOENT) which the
        // binder wrapped (misleadingly) as "ocrImage decode failed".
        val firstPng = validPngBytes()
        val firstResult = pool.stageImage(
            "req-resilient-1",
            ImageTransfer(
                requestId = "req-resilient-1",
                width = 0,
                height = 0,
                pixelFormat = 0,
                rowStride = 0,
                payloadBytes = firstPng.size,
                source = createPfdFromBytes(firstPng, "png"),
                isSharedMemory = false,
                mimeType = "image/png",
            ),
        )
        assertTrue("First staging should write a file", File(firstResult.filePath).exists())

        val stagingDir = File(cacheDir, "media_staging")
        assertTrue("Staging dir should exist after first stage", stagingDir.isDirectory)
        stagingDir.listFiles()?.forEach { it.delete() }
        assertTrue(
            "Staging dir should be deletable to simulate cache trimming",
            stagingDir.delete(),
        )
        assertFalse("Staging dir should not exist after wipe", stagingDir.exists())

        val secondPng = validPngBytes()
        val secondResult = pool.stageImage(
            "req-resilient-2",
            ImageTransfer(
                requestId = "req-resilient-2",
                width = 0,
                height = 0,
                pixelFormat = 0,
                rowStride = 0,
                payloadBytes = secondPng.size,
                source = createPfdFromBytes(secondPng, "png"),
                isSharedMemory = false,
                mimeType = "image/png",
            ),
        )
        val secondStaged = File(secondResult.filePath)
        assertTrue(
            "Staging dir should be re-created on second stage",
            stagingDir.isDirectory,
        )
        assertTrue(
            "Staged file should exist on disk after cache trim recovery",
            secondStaged.exists(),
        )
        assertTrue(
            "Staged file content should match the source after recovery",
            secondPng.contentEquals(secondStaged.readBytes()),
        )
    }

    // =========================================================================
    // cleanup(scopedKey)
    // =========================================================================

    @Test
    fun `cleanup deletes staged files for the given request`() {
        val pfd = createPfdFromBytes(byteArrayOf(10, 20, 30), "wav")
        val transfer = AudioTransfer(
            requestId = "req-clean-1",
            mimeType = "audio/wav",
            source = pfd,
        )
        val result = pool.stageAudio("req-clean-1", transfer)
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

        val r1 = pool.stageAudio("req-all-1", AudioTransfer("req-all-1", "audio/wav", pfd1))
        val r2 = pool.stageAudio("req-all-2", AudioTransfer("req-all-2", "audio/mp3", pfd2))

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

        val rA = pool.stageAudio("req-A", AudioTransfer("req-A", "audio/wav", pfdA))
        val rB = pool.stageAudio("req-B", AudioTransfer("req-B", "audio/wav", pfdB))

        val fA = File(rA.filePath)
        val fB = File(rB.filePath)

        pool.cleanup("req-A")

        assertFalse("Request A file should be deleted", fA.exists())
        assertTrue("Request B file should NOT be deleted", fB.exists())
    }

    @Test
    fun `cleanup deletes every staged file for repeated requestId`() {
        val results = (1..2).map { index ->
            pool.stageAudio("req-repeat", AudioTransfer("req-repeat", "audio/wav", createPfdFromBytes(byteArrayOf(index.toByte()))))
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

        val result = pool.stageAudio("req-staged", transfer)

        assertEquals("req-staged", result.scopedKey)
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
            pool.stageImage("123:req-oversized", transfer)
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
            pool.stageImage("123:req-zero", transfer)
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

    // =========================================================================
    // H1 — pixel format allowlist
    // =========================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `validatePixelBufferLayout rejects pixel formats outside allowlist`() {
        // RGBA_F16 / unknown sentinel must not silently coerce to ARGB_8888.
        validatePixelBufferLayout(
            width = 4,
            height = 4,
            pixelFormat = 0x1234, // arbitrary non-allowlisted value
            rowStride = 0,
            bufferSize = 64,
        )
    }

    @Test
    fun `validatePixelBufferLayout accepts RGB_565 from allowlist`() {
        // RGB_565 has 2 bpp → 4*4*2 = 32 bytes.
        val tight = validatePixelBufferLayout(
            width = 4,
            height = 4,
            pixelFormat = PixelFormat.RGB_565,
            rowStride = 0,
            bufferSize = 32,
        )
        assertEquals(32L, tight)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `validatePixelBufferLayout rejects pixel-count overflow before allocation`() {
        // 100_000 * 100_000 * 4 ≈ 40 GB → must fail the Long pixel-count guard
        // even though each individual dimension is under MAX_IMAGE_DIM only
        // when the cap is large enough — here both are over the cap so the
        // dimension check fires first; this asserts dimension cap is enforced.
        validatePixelBufferLayout(
            width = 100_000,
            height = 100_000,
            pixelFormat = PixelFormat.RGBA_8888,
            rowStride = 0,
            bufferSize = Int.MAX_VALUE,
        )
    }

    // =========================================================================
    // H5 — assertSafePfdType: regular files accepted, fstat-unavailable allowed
    // =========================================================================

    @Test
    fun `assertSafePfdType permits regular file PFDs`() {
        val pfd = createPfdFromBytes(byteArrayOf(1, 2, 3), "bin")
        try {
            // Should not throw — temp file is a regular file (or fstat is
            // stubbed under Robolectric, in which case the helper must permit).
            pool.assertSafePfdType(pfd)
        } finally {
            pfd.close()
        }
    }
}
