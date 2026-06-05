package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Untrusted-input validation guard for [EmbeddingBlobCrypto.decrypt].
 *
 * Deferred embedding blobs are read back from disk and decrypted; the header is
 * attacker-influenceable (a tampered/truncated file). These tests pin that the
 * header parser fails CLOSED — every malformed header is rejected with
 * `IllegalArgumentException` BEFORE any key derivation or cipher work, so a bad
 * blob can never reach the AES-GCM stage with attacker-chosen lengths. (These
 * checks run before `deriveKey`, so they need no Android Keystore — the
 * round-trip / GCM-tamper paths are covered by instrumented tests that exercise
 * the real Keystore-backed key.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbeddingBlobCryptoTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun header(magic: Int, version: Byte, nonceLen: Byte, ciphertextLen: Int, bodyBytes: Int = 0): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES + bodyBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(magic)
            .put(version)
            .put(nonceLen)
            .putInt(ciphertextLen)
            .array()

    @Test
    fun `decrypt rejects a truncated header`() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingBlobCrypto.decrypt(ctx, UID, ByteArray(HEADER_BYTES - 1))
        }
    }

    @Test
    fun `decrypt rejects a bad magic`() {
        val bytes = header(magic = 0x00000000, version = 1, nonceLen = 12, ciphertextLen = 32, bodyBytes = 44)
        assertThrows(IllegalArgumentException::class.java) { EmbeddingBlobCrypto.decrypt(ctx, UID, bytes) }
    }

    @Test
    fun `decrypt rejects an unsupported version`() {
        val bytes = header(magic = MAGIC, version = 9, nonceLen = 12, ciphertextLen = 32, bodyBytes = 44)
        assertThrows(IllegalArgumentException::class.java) { EmbeddingBlobCrypto.decrypt(ctx, UID, bytes) }
    }

    @Test
    fun `decrypt rejects an invalid nonce length`() {
        val bytes = header(magic = MAGIC, version = 1, nonceLen = 8, ciphertextLen = 32, bodyBytes = 40)
        assertThrows(IllegalArgumentException::class.java) { EmbeddingBlobCrypto.decrypt(ctx, UID, bytes) }
    }

    @Test
    fun `decrypt rejects an inconsistent ciphertext length`() {
        // ciphertextLen must be > GCM tag (16). 16 is not > 16 -> reject before
        // any key/cipher work.
        val bytes = header(magic = MAGIC, version = 1, nonceLen = 12, ciphertextLen = 16)
        assertThrows(IllegalArgumentException::class.java) { EmbeddingBlobCrypto.decrypt(ctx, UID, bytes) }
    }

    private companion object {
        private const val UID = 10_123
        private const val MAGIC = 0x4D454231 // "MEB1"
        private const val HEADER_BYTES = 4 + 1 + 1 + 4
    }
}
