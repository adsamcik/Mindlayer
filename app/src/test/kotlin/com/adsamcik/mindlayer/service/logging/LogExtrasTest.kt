package com.adsamcik.mindlayer.service.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [logExtraJson] builder and the privacy-critical string
 * sanitisers in `LogExtras.kt`.
 *
 * [sanitizeErrorClass] and [redactedFileName] are the last line of defence that
 * keeps prompt fragments / absolute paths out of persisted log rows and
 * diagnostic exports (F-006). A regression here is a privacy leak, so each rule
 * is pinned explicitly.
 */
class LogExtrasTest {

    @Test
    fun `logExtraJson serialises the built object`() {
        val json = logExtraJson {
            put("len", 19)
            put("backend", "GPU")
        }
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(19, obj["len"]!!.jsonPrimitive.int)
        assertEquals("GPU", obj["backend"]!!.jsonPrimitive.content)
    }

    @Test
    fun `logExtraJson on empty builder is an empty object`() {
        assertEquals("{}", logExtraJson { })
    }

    @Test
    fun `redactedFileName keeps only the basename for posix paths`() {
        assertEquals("model.litertlm", "/data/user/0/com.app/files/model.litertlm".redactedFileName())
    }

    @Test
    fun `redactedFileName keeps only the basename for windows paths`() {
        assertEquals("c.txt", "a\\b\\c.txt".redactedFileName())
    }

    @Test
    fun `redactedFileName handles mixed separators`() {
        assertEquals("leaf", "/posix/mixed\\leaf".redactedFileName())
    }

    @Test
    fun `redactedFileName is a no-op for a bare filename`() {
        assertEquals("plain.bin", "plain.bin".redactedFileName())
    }

    @Test
    fun `sanitizeErrorClass returns null for null or blank input`() {
        assertNull(sanitizeErrorClass(null))
        assertNull(sanitizeErrorClass(""))
        assertNull(sanitizeErrorClass("   "))
    }

    @Test
    fun `sanitizeErrorClass strips whitespace and arrows from a chained label`() {
        // Doc example: "OutOfMemoryError -> IOException" -> "OutOfMemoryError-IOException"
        assertEquals("OutOfMemoryError-IOException", sanitizeErrorClass("OutOfMemoryError -> IOException"))
    }

    @Test
    fun `sanitizeErrorClass keeps the identifier-safe character set`() {
        assertEquals("com.example.Foo_Bar:Baz-1", sanitizeErrorClass("com.example.Foo_Bar:Baz-1"))
    }

    @Test
    fun `sanitizeErrorClass drops prompt-like punctuation and spaces`() {
        // A native error message that embedded a prompt fragment must be reduced
        // to identifier-safe characters only — no spaces, quotes, or slashes.
        val raw = "RuntimeException: please summarise the user's secret note!"
        val safe = sanitizeErrorClass(raw)!!
        assertFalse(safe.contains(" "))
        assertFalse(safe.contains("'"))
        assertFalse(safe.contains("!"))
        assertTrue(safe.startsWith("RuntimeException"))
    }

    @Test
    fun `sanitizeErrorClass caps the result before filtering at 64 chars`() {
        // The implementation takes the first 64 chars, then filters — so the
        // result is never longer than 64.
        val raw = "A".repeat(200)
        val safe = sanitizeErrorClass(raw)!!
        assertEquals(64, safe.length)
    }

    @Test
    fun `sanitizeErrorClass returns null when nothing survives filtering`() {
        assertNull(sanitizeErrorClass(">>> @@@ ### "))
    }
}
