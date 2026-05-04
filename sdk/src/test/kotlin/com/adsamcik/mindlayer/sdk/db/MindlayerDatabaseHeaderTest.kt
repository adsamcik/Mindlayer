package com.adsamcik.mindlayer.sdk.db

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MindlayerDatabase.Companion.isPlaintextSqlite]. Mirrors the
 * service-side test; covers the SDK half of the M16 fix.
 */
class MindlayerDatabaseHeaderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ml-sdk-db-header-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun missingFile_returnsFalse() {
        val f = File(tempDir, "missing.db")
        assertFalse(MindlayerDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun emptyFile_returnsFalse() {
        val f = File(tempDir, "empty.db").apply { createNewFile() }
        assertFalse(MindlayerDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun shortFile_returnsFalse() {
        val f = File(tempDir, "short.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(MindlayerDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun exactMagic_returnsTrue() {
        val f = File(tempDir, "plain.db").apply { writeBytes(MindlayerDatabase.SQLITE_MAGIC) }
        assertTrue(MindlayerDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun plaintextWithBody_returnsTrue() {
        val f = File(tempDir, "real.db").apply {
            writeBytes(MindlayerDatabase.SQLITE_MAGIC + ByteArray(200) { 0x33 })
        }
        assertTrue(MindlayerDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun encryptedRandomBytes_returnsFalse() {
        val random = ByteArray(256) { ((it * 17 + 5) and 0xFF).toByte() }
        val f = File(tempDir, "encrypted.db").apply { writeBytes(random) }
        assertFalse(MindlayerDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun magicConstantIsExactlySixteenBytes() {
        assertEquals(16, MindlayerDatabase.SQLITE_MAGIC.size)
        assertEquals(0.toByte(), MindlayerDatabase.SQLITE_MAGIC[15])
    }
}
