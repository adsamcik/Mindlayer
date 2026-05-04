package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-030: ensures attacker-controlled application labels can't masquerade as
 * trusted brands via direction overrides, zero-width characters, NFKC
 * compatibility forms, or oversize input.
 */
class CallerVerifierLabelSanitizationTest {

    @Test
    fun `null and blank input return null`() {
        assertNull(CallerVerifier.sanitizeLabel(null))
        assertNull(CallerVerifier.sanitizeLabel(""))
        assertNull(CallerVerifier.sanitizeLabel("   "))
        assertNull(CallerVerifier.sanitizeLabel("\t\n"))
    }

    @Test
    fun `RTL override is stripped`() {
        // U+202E (right-to-left override) — common attack hides true package.
        val input = "\u202EgooglepwA"
        val out = CallerVerifier.sanitizeLabel(input)
        assertEquals("googlepwA", out)
    }

    @Test
    fun `zero-width joiner and zero-width space are stripped`() {
        // U+200B is zero-width space (Cf)
        val input = "Foo\u200BBar"
        assertEquals("FooBar", CallerVerifier.sanitizeLabel(input))
    }

    @Test
    fun `control character is stripped`() {
        val input = "Hello\u0007World"
        assertEquals("HelloWorld", CallerVerifier.sanitizeLabel(input))
    }

    @Test
    fun `NFKC compatibility form is normalised`() {
        // U+FB01 (LATIN SMALL LIGATURE FI) → "fi"
        assertEquals("file", CallerVerifier.sanitizeLabel("\uFB01le"))
    }

    @Test
    fun `oversize label is truncated to MAX_LABEL_LEN`() {
        val long = "a".repeat(200)
        val out = CallerVerifier.sanitizeLabel(long)
        assertEquals(CallerVerifier.MAX_LABEL_LEN, out!!.length)
    }

    @Test
    fun `label that becomes empty after sanitisation returns null`() {
        // U+202E (Cf) + U+200B (Cf) + U+00AD (Cf, soft hyphen) — all stripped.
        val input = "\u202E\u200B\u00AD"
        assertNull(CallerVerifier.sanitizeLabel(input))
    }

    @Test
    fun `legitimate emoji is preserved`() {
        val emoji = "Photos \uD83D\uDCF7"
        // NFKC-stable; emoji is in Cs/So. Cs surrogates are filtered as Cn
        // when matched as standalone code points — but the surrogate pair
        // forms a valid Unicode 1F4F7 (CAMERA) which is class So, not Cn.
        val out = CallerVerifier.sanitizeLabel(emoji)!!
        assertEquals(emoji, out)
    }

    @Test
    fun `CJK label is preserved`() {
        // 카카오톡 = KakaoTalk, common Korean app name (all Lo, kept).
        val out = CallerVerifier.sanitizeLabel("카카오톡")
        assertEquals("카카오톡", out)
    }

    @Test
    fun `whitespace at edges is trimmed`() {
        assertEquals("x", CallerVerifier.sanitizeLabel("    x    "))
    }
}
