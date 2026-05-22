package com.adsamcik.mindlayer.service.security

import android.content.Context
import com.adsamcik.mindlayer.service.engine.DeferredDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object EmbeddingBlobCrypto {
    private val aad = "mindlayer-embed-blob-v1".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    fun encrypt(context: Context, uid: Int, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(context, uid)
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            ByteBuffer.allocate(HEADER_BYTES + nonce.size + ciphertext.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC)
                .put(VERSION)
                .put(nonce.size.toByte())
                .putInt(ciphertext.size)
                .put(nonce)
                .put(ciphertext)
                .array()
        } finally {
            Arrays.fill(key, 0)
        }
    }

    fun decrypt(context: Context, uid: Int, bytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() >= HEADER_BYTES) { "embedding blob header too short" }
        require(buffer.int == MAGIC) { "embedding blob magic mismatch" }
        require(buffer.get() == VERSION) { "embedding blob version unsupported" }
        val nonceLen = buffer.get().toInt() and 0xff
        require(nonceLen == NONCE_BYTES) { "embedding blob nonce length invalid" }
        val ciphertextLen = buffer.int
        require(ciphertextLen > GCM_TAG_BYTES && ciphertextLen == buffer.remaining() - nonceLen) {
            "embedding blob ciphertext length invalid"
        }
        val nonce = ByteArray(nonceLen)
        buffer.get(nonce)
        val ciphertext = ByteArray(ciphertextLen)
        buffer.get(ciphertext)
        val key = deriveKey(context, uid)
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw SecurityException("embedding blob authentication failed", e)
        } finally {
            Arrays.fill(key, 0)
        }
    }

    private fun deriveKey(context: Context, uid: Int): ByteArray {
        val base = DbKeyProvider.get(context, DeferredDatabase.DB_NAME)
        return try {
            hkdfSha256(
                ikm = base,
                salt = "mindlayer-embed-blob-v1:uid:$uid".toByteArray(Charsets.UTF_8),
                info = aad,
                length = KEY_BYTES,
            )
        } finally {
            Arrays.fill(base, 0)
        }
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        var previous = ByteArray(0)
        return try {
            val out = ByteArray(length)
            var offset = 0
            var counter = 1
            while (offset < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                val next = mac.doFinal()
                Arrays.fill(previous, 0)
                previous = next
                val copy = minOf(next.size, length - offset)
                next.copyInto(out, offset, 0, copy)
                offset += copy
                counter++
            }
            out
        } finally {
            Arrays.fill(previous, 0)
            Arrays.fill(prk, 0)
        }
    }

    private const val MAGIC = 0x4D454231 // MEB1
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val HEADER_BYTES = 4 + 1 + 1 + 4
    private const val AES_GCM = "AES/GCM/NoPadding"
}
