package com.adsamcik.mindlayer.service.ipc

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P-MEDIA: at-rest encryption of staged media in [SharedMemoryPool].
 *
 * The product invariant is that camera frames / audio buffers never linger as
 * plaintext on persistent storage. Because LiteRT-LM consumes media BY PATH
 * (`Content.ImageFile(path)`), the staged file on disk is AES-256-GCM ciphertext
 * (process-ephemeral key) and the plaintext the native decoder needs is only
 * materialized on demand and cleaned up with the request. These tests pin:
 *   1. encrypt -> materialize round-trips the exact bytes,
 *   2. the on-disk staged file is genuinely ciphertext (not the plaintext),
 *   3. the materialized temp keeps a decoder-friendly extension,
 *   4. tampered ciphertext fails GCM authentication (no silent plaintext leak),
 *   5. construction purges stale staging artifacts from a prior (crashed) run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedMemoryPoolEncryptionTest {

    private lateinit var cacheDir: File
    private lateinit var pool: SharedMemoryPool
    private val stagingDirName = "media_staging"

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
        File(cacheDir, stagingDirName).deleteRecursively()
        pool = SharedMemoryPool(cacheDir)
    }

    @After
    fun tearDown() {
        pool.cleanupAll()
        unmockkAll()
    }

    private fun knownBytes(): ByteArray = ByteArray(64 * 1024) { (it * 31 + 7).toByte() }

    private fun newSourceFile(name: String, bytes: ByteArray): File =
        File(cacheDir, name).apply { writeBytes(bytes) }

    @Test
    fun `encrypt then materialize round-trips the exact bytes`() {
        val original = knownBytes()
        val plain = newSourceFile("src_round.png", original)

        val enc = pool.encryptStagedFile(plain)
        try {
            assertFalse("plaintext source must be deleted after encryption", plain.exists())
            assertTrue("encrypted file must exist", enc.exists())
            assertFalse(
                "on-disk staged bytes must be ciphertext, not the plaintext",
                enc.readBytes().contentEquals(original),
            )

            val staged = StagedMedia("100:req-round", enc.absolutePath, "image/png") {}
            val plaintextPath = pool.materializePlaintext(staged)
            assertArrayEquals(
                "materialized plaintext must equal the original bytes",
                original,
                File(plaintextPath).readBytes(),
            )
        } finally {
            enc.delete()
        }
    }

    @Test
    fun `materialized plaintext keeps the original extension`() {
        val plain = newSourceFile("src_ext.jpg", knownBytes())
        val enc = pool.encryptStagedFile(plain)
        try {
            val staged = StagedMedia("100:req-ext", enc.absolutePath, "image/jpeg") {}
            val plaintextPath = pool.materializePlaintext(staged)
            assertTrue(
                "materialized temp must keep a decoder-friendly extension: $plaintextPath",
                plaintextPath.endsWith(".jpg"),
            )
        } finally {
            enc.delete()
        }
    }

    @Test
    fun `tampered ciphertext fails GCM authentication on materialize`() {
        val plain = newSourceFile("src_tamper.png", knownBytes())
        val enc = pool.encryptStagedFile(plain)
        try {
            // Flip a byte in the ciphertext body (past the 12-byte nonce prefix).
            val bytes = enc.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
            enc.writeBytes(bytes)

            val staged = StagedMedia("100:req-tamper", enc.absolutePath, "image/png") {}
            try {
                pool.materializePlaintext(staged)
                fail("materializePlaintext must throw on a tampered (GCM-authenticated) file")
            } catch (expected: Exception) {
                // GCM tag mismatch surfaces as an IOException wrapping
                // AEADBadTagException through CipherInputStream — any throw is
                // correct; the point is it must NOT silently emit plaintext.
            }
        } finally {
            enc.delete()
        }
    }

    @Test
    fun `construction purges stale staging artifacts`() {
        val stagingDir = File(cacheDir, stagingDirName).apply { mkdirs() }
        File(stagingDir, "stale_plaintext.png").writeBytes(byteArrayOf(1, 2, 3))
        File(stagingDir, "stale_ciphertext.png.enc").writeBytes(byteArrayOf(4, 5, 6))
        assertTrue("precondition: stale files present", (stagingDir.listFiles()?.size ?: 0) >= 2)

        // A fresh pool (simulating a process restart) must purge the directory.
        SharedMemoryPool(cacheDir)

        assertEquals(
            "construction must purge stale staging files left by a prior run",
            0,
            stagingDir.listFiles()?.size ?: 0,
        )
    }
}
