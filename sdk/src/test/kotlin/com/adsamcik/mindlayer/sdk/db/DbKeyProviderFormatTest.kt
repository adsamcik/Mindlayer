package com.adsamcik.mindlayer.sdk.db

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the SDK [DbKeyProvider] binary key-record format introduced by the
 * H7 fix. Mirrors the service-side test.
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
        encoded[0] = (encoded[0].toInt() xor 0xFF).toByte()
        assertNull(DbKeyProvider.decodeKeyRecord(encoded))
    }

    @Test
    fun decode_rejectsWrongVersion() {
        val record = DbKeyProvider.KeyRecord(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val encoded = DbKeyProvider.encodeKeyRecord(record).copyOf()
        encoded[4] = 0xFF.toByte()
        encoded[5] = 0x00.toByte()
        assertNull(DbKeyProvider.decodeKeyRecord(encoded))
    }

    @Test
    fun decode_rejectsTruncated() {
        val record = DbKeyProvider.KeyRecord(ByteArray(12) { 1 }, ByteArray(48) { 2 })
        val encoded = DbKeyProvider.encodeKeyRecord(record)
        assertNull(DbKeyProvider.decodeKeyRecord(encoded.copyOf(encoded.size - 5)))
    }

    @Test
    fun decode_rejectsEmpty() {
        assertNull(DbKeyProvider.decodeKeyRecord(ByteArray(0)))
        assertNull(DbKeyProvider.decodeKeyRecord(ByteArray(11)))
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
        val encoded = DbKeyProvider.encodeKeyRecord(
            DbKeyProvider.KeyRecord(ByteArray(12), ByteArray(48)),
        )
        assertEquals(72, encoded.size)
    }
}
