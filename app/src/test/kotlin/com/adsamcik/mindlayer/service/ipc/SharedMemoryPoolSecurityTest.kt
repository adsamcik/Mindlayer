package com.adsamcik.mindlayer.service.ipc

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pins SECURITY_REVIEW F-001 / F-004 / F-011 / F-016 / F-060 — staging
 * pool security regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedMemoryPoolSecurityTest {

    private lateinit var cacheDir: File
    private lateinit var pool: SharedMemoryPool

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cacheDir = File(context.cacheDir, "smp_sec_test").apply {
            deleteRecursively(); mkdirs()
        }
        pool = SharedMemoryPool(cacheDir)
    }

    @After
    fun tearDown() {
        pool.cleanupAll()
        cacheDir.deleteRecursively()
    }

    private fun createPfdFromBytes(bytes: ByteArray, suffix: String = "bin"): ParcelFileDescriptor {
        val tmp = File.createTempFile("smp_secsrc_", ".$suffix", cacheDir)
        FileOutputStream(tmp).use { it.write(bytes) }
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // ── F-001: image-bomb dimensions rejected at validator level ────────

    @Test(expected = IllegalArgumentException::class)
    fun `stageImage rejects raw 50000x50000 dimensions`() {
        val pfd = createPfdFromBytes(byteArrayOf(0))
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 50_000, height = 50_000,
            pixelFormat = 1, rowStride = 50_000 * 4,
            payloadBytes = 64,
            source = pfd, isSharedMemory = true, mimeType = null,
        )
        try {
            pool.stageImage("u:abc", xfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    // ── F-004: requestId never appears in staging-file path ─────────────

    @Test
    fun `staging filename never contains requestId chars`() {
        val pfd = createPfdFromBytes(byteArrayOf(1, 2, 3))
        val xfer = AudioTransfer(
            requestId = "rrequest_id_12345",
            mimeType = "audio/wav",
            source = pfd,
        )
        // Use a "scopedKey" that has the requestId substring in it.
        val staged = pool.stageAudio("999:rrequest_id_12345", xfer)
        val file = File(staged.filePath)
        assertTrue(file.exists())
        // The filename should be the prefix + UUID + extension; the
        // request id is NOT interpolated into the path.
        assertFalse(
            "filename leaked requestId: ${file.name}",
            file.name.contains("rrequest_id_12345"),
        )
    }

    // ── F-004: even if a malformed requestId reaches the pool, the
    // canonical-path check ensures no escape from staging dir ───────────

    @Test
    fun `staging file is always under staging dir`() {
        val pfd = createPfdFromBytes(byteArrayOf(5))
        val xfer = AudioTransfer("abc", "audio/wav", pfd)
        val staged = pool.stageAudio("u:abc", xfer)
        val stagingRoot = File(cacheDir, "media_staging").canonicalPath
        assertTrue(
            "${staged.filePath} escaped $stagingRoot",
            File(staged.filePath).canonicalPath
                .startsWith(stagingRoot + File.separator),
        )
    }

    // ── F-011: PFD closed even when validation rejects the transfer ─────

    @Test
    fun `stageImage closes PFD on early validation failure`() {
        val pfd = createPfdFromBytes(byteArrayOf(0))
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 50_000, height = 50_000, // image-bomb dims
            pixelFormat = 1, rowStride = 50_000 * 4,
            payloadBytes = 64,
            source = pfd, isSharedMemory = true, mimeType = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            pool.stageImage("u:abc", xfer)
        }
        // PFD should already be closed by the pool's failure handler.
        assertFalse(
            "PFD must be closed after validation failure",
            pfd.fileDescriptor.valid(),
        )
    }

    // ── F-060: unknown MIME rejected at validator ───────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `stageImage rejects svg mimeType`() {
        val pfd = createPfdFromBytes(byteArrayOf(0))
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 0, height = 0,
            pixelFormat = 0, rowStride = 0,
            payloadBytes = 100,
            source = pfd, isSharedMemory = false, mimeType = "image/svg+xml",
        )
        try {
            pool.stageImage("u:abc", xfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    @Test
    fun `stageImage rejects encoded sharedMemory declared larger than backing fd`() {
        val pfd = createPfdFromBytes(byteArrayOf(1, 2, 3), "png")
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 0, height = 0,
            pixelFormat = 0, rowStride = 0,
            payloadBytes = 64,
            source = pfd, isSharedMemory = true, mimeType = "image/png",
        )
        assertThrows(IllegalArgumentException::class.java) {
            pool.stageImage("u:abc", xfer)
        }
    }

    @Test
    fun `stageImage rejects unparseable encoded image before native handoff`() {
        val pfd = createPfdFromBytes(byteArrayOf(1, 2, 3), "png")
        val xfer = ImageTransfer(
            requestId = "abc",
            width = 0, height = 0,
            pixelFormat = 0, rowStride = 0,
            payloadBytes = 3,
            source = pfd, isSharedMemory = false, mimeType = "image/png",
        )
        assertThrows(IllegalArgumentException::class.java) {
            pool.stageImage("u:abc", xfer)
        }
    }

    @org.junit.Ignore(
        "Robolectric does not faithfully emulate Linux pipe semantics — a " +
            "blocking AutoCloseInputStream.read() on a never-drained pipe " +
            "doesn't actually block under Robolectric's JVM-pipe shim, so " +
            "the cleanup-cancels-the-read assertion is meaningless here. " +
            "The behavior is exercised on-device by the orchestrator's audio " +
            "staging timeout (F-010) and verified by " +
            "InferenceOrchestratorBackpressureTest. Re-enable as an " +
            "instrumented (androidTest) test if/when one is added."
    )
    @Test
    fun `cleanup closes active blocking media source`() {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                pool.stageAudio("u:block", AudioTransfer("block", "audio/wav", readEnd))
            }

            Thread.sleep(100)
            pool.cleanup("u:block")

            assertThrows(ExecutionException::class.java) {
                future.get(2, TimeUnit.SECONDS)
            }
            assertFalse("active PFD should be closed by cleanup", readEnd.fileDescriptor.valid())
        } finally {
            try { writeEnd.close() } catch (_: Throwable) {}
            executor.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stageAudio rejects unknown mimeType`() {
        val pfd = createPfdFromBytes(byteArrayOf(0))
        val xfer = AudioTransfer(
            requestId = "abc",
            mimeType = "audio/x-malicious",
            source = pfd,
        )
        try {
            pool.stageAudio("u:abc", xfer)
        } finally {
            try { pfd.close() } catch (_: Throwable) {}
        }
    }
}
