package com.mindlayer.service.ipc

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.mindlayer.AudioTransfer
import com.mindlayer.ImageTransfer
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
import java.io.File
import java.io.FileOutputStream

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
}
