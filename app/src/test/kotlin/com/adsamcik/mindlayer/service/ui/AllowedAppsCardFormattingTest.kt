package com.adsamcik.mindlayer.service.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AllowedAppsCardFormattingTest {

    @Test
    fun `empty string returns missing placeholder`() {
        assertEquals("(missing)", formatCertHash(""))
    }

    @Test
    fun `blank string returns missing placeholder`() {
        assertEquals("(missing)", formatCertHash("   "))
    }

    @Test
    fun `64 char hex is chunked into 8 groups of 8 separated by spaces`() {
        val hex = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val expected = "abcdef01 23456789 abcdef01 23456789 abcdef01 23456789 abcdef01 23456789"
        assertEquals(expected, formatCertHash(hex))
    }

    @Test
    fun `63 char hex returned as-is (shorter than 64)`() {
        val short = "a".repeat(63)
        assertEquals(short, formatCertHash(short))
    }

    @Test
    fun `65 char input still chunks from position 0 with trailing group`() {
        val hex65 = "a".repeat(65)
        // chunked(8) on 65 chars: 8 groups of 8 + 1 trailing char
        val expected = "aaaaaaaa aaaaaaaa aaaaaaaa aaaaaaaa aaaaaaaa aaaaaaaa aaaaaaaa aaaaaaaa a"
        assertEquals(expected, formatCertHash(hex65))
    }

    @Test
    fun `real sha256 hex formats correctly`() {
        val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val expected = "e3b0c442 98fc1c14 9afbf4c8 996fb924 27ae41e4 649b934c a495991b 7852b855"
        assertEquals(expected, formatCertHash(sha))
    }
}
