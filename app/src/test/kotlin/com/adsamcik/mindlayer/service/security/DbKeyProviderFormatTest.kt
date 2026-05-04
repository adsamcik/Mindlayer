package com.adsamcik.mindlayer.service.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the [DbKeyProvider] binary key-record format introduced by the H7 fix.
 * These exercise pure encode/decode logic — no Android Keystore, no SharedPreferences, no
 * filesystem — so they run on the build server without instrumentation.
 *
 * Behavioural tests (AEADBadTag → quarantine, KeyPermanentlyInvalidated → forceReset,
 * legacy-prefs migration) require a real Keystore + Context and live in the existing
 * `androidTest` suite.
 */
class DbKeyProviderFormatTest {

    @Test
    fun roundTrip_preservesIvAndWrappedBytes() {
        val iv = ByteArray(12) { it.toByte() }
        val wrapped = ByteArray(48) { (0xFF - it).toByte() }
        val record = DbKeyProvider.KeyRecord(iv, wrapped)

        val encoded = DbKeyProvider.encodeKeyRecord(record)
        val decoded = DbKeyProvider.decodeKeyRecord(encoded)

        assertEquals(record, decoded)
        assertArrayEquals(iv, decoded!!.iv)
        assertArrayEquals(wrapped, decoded.wrapped)
    }

    @Test
    fun encoded_startsWithMagicAndVersion() {
        val record = DbKeyProvider.KeyRecord(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val encoded = DbKeyProvider.encodeKeyRecord(record)

        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(DbKeyProvider.MAGIC, buf.int)
        assertEquals(DbKeyProvider.VERSION, buf.short)
    }

    @Test
    fun decode_rejectsWrongMagic() {
        val record = DbKeyProvider.KeyRecord(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val encoded = DbKeyProvider.encodeKeyRecord(record).copyOf()
        // Flip the first byte — magic mismatch must yield null, not a corrupted record.
        encoded[0] = (encoded[0].toInt() xor 0xFF).toByte()
        assertNull(DbKeyProvider.decodeKeyRecord(encoded))
    }

    @Test
    fun decode_rejectsWrongVersion() {
        val record = DbKeyProvider.KeyRecord(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val encoded = DbKeyProvider.encodeKeyRecord(record).copyOf()
        // Bump version field (offset 4, 2 LE bytes) to 0x00FF.
        encoded[4] = 0xFF.toByte()
        encoded[5] = 0x00.toByte()
        assertNull(DbKeyProvider.decodeKeyRecord(encoded))
    }

    @Test
    fun decode_rejectsTruncatedPayload() {
        val record = DbKeyProvider.KeyRecord(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val encoded = DbKeyProvider.encodeKeyRecord(record)
        val truncated = encoded.copyOf(encoded.size - 5)
        assertNull(DbKeyProvider.decodeKeyRecord(truncated))
    }

    @Test
    fun decode_rejectsEmptyAndShortBuffers() {
        assertNull(DbKeyProvider.decodeKeyRecord(ByteArray(0)))
        assertNull(DbKeyProvider.decodeKeyRecord(ByteArray(4)))
        assertNull(DbKeyProvider.decodeKeyRecord(ByteArray(11)))
    }

    @Test
    fun decode_rejectsImpossibleIvLength() {
        // Build a buffer with magic+version correct but an absurd ivLen.
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(DbKeyProvider.MAGIC)
        buf.putShort(DbKeyProvider.VERSION)
        buf.putShort(9999.toShort())
        buf.putInt(1) // bogus wrappedLen
        assertNull(DbKeyProvider.decodeKeyRecord(buf.array()))
    }

    @Test
    fun decode_rejectsZeroLengthFields() {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(DbKeyProvider.MAGIC)
        buf.putShort(DbKeyProvider.VERSION)
        buf.putShort(0)
        buf.putInt(0)
        assertNull(DbKeyProvider.decodeKeyRecord(buf.array()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsEmptyIv() {
        DbKeyProvider.encodeKeyRecord(DbKeyProvider.KeyRecord(ByteArray(0), ByteArray(48)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsEmptyWrapped() {
        DbKeyProvider.encodeKeyRecord(DbKeyProvider.KeyRecord(ByteArray(12), ByteArray(0)))
    }

    @Test
    fun encodedSize_matchesHeaderPlusBodies() {
        val iv = ByteArray(12)
        val wrapped = ByteArray(48)
        val encoded = DbKeyProvider.encodeKeyRecord(DbKeyProvider.KeyRecord(iv, wrapped))
        // 4 (magic) + 2 (version) + 2 (ivLen) + 12 (iv) + 4 (wrappedLen) + 48 (wrapped) = 72
        assertEquals(72, encoded.size)
    }
}
