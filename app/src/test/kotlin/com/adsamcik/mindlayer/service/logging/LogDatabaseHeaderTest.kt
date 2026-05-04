package com.adsamcik.mindlayer.service.logging

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LogDatabase.Companion.isPlaintextSqlite]. Pure file IO — no Android
 * dependencies, runs on the JVM. Covers the M16 fix: we must NOT delete a file whose
 * first 16 bytes do not match SQLite's plaintext magic header.
 */
class LogDatabaseHeaderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ml-log-db-header-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun missingFile_returnsFalse() {
        val f = File(tempDir, "missing.db")
        assertFalse(LogDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun emptyFile_returnsFalse() {
        val f = File(tempDir, "empty.db").apply { createNewFile() }
        assertFalse(LogDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun fileShorterThanMagic_returnsFalse() {
        val f = File(tempDir, "short.db").apply { writeBytes(byteArrayOf(0x53, 0x51, 0x4C)) }
        assertFalse(LogDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun exactMagicHeader_returnsTrue() {
        val f = File(tempDir, "plain.db").apply { writeBytes(LogDatabase.SQLITE_MAGIC) }
        assertTrue(LogDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun plaintextSqlitePrefix_returnsTrue() {
        // Real SQLite files have "SQLite format 3\u0000" then 84 bytes of header.
        val payload = LogDatabase.SQLITE_MAGIC + ByteArray(84) { 0x42 }
        val f = File(tempDir, "real.db").apply { writeBytes(payload) }
        assertTrue(LogDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun randomBytes_returnsFalse() {
        // SQLCipher randomises the entire 100-byte header, so an encrypted DB starts with
        // arbitrary bytes — never the SQLite magic.
        val random = ByteArray(128) { ((it * 31 + 7) and 0xFF).toByte() }
        val f = File(tempDir, "encrypted.db").apply { writeBytes(random) }
        assertFalse(LogDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun magicWithoutTrailingNul_returnsFalse() {
        // Tampered/truncated magic must not be accepted.
        val almost = "SQLite format 3 ".toByteArray(Charsets.US_ASCII)
        check(almost.size == LogDatabase.SQLITE_MAGIC.size)
        val f = File(tempDir, "almost.db").apply { writeBytes(almost) }
        assertFalse(LogDatabase.isPlaintextSqlite(f))
    }

    @Test
    fun directoryNotFile_returnsFalse() {
        val dir = File(tempDir, "subdir").apply { mkdirs() }
        assertFalse(LogDatabase.isPlaintextSqlite(dir))
    }

    @Test
    fun magicConstantIsExactlySixteenBytes() {
        // Guards against accidental edits to SQLITE_MAGIC: the SQLite spec mandates exactly
        // 16 bytes including the trailing NUL.
        org.junit.Assert.assertEquals(16, LogDatabase.SQLITE_MAGIC.size)
        org.junit.Assert.assertEquals(0.toByte(), LogDatabase.SQLITE_MAGIC[15])
    }
}
