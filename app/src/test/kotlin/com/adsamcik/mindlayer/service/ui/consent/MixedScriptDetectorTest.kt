package com.adsamcik.mindlayer.service.ui.consent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MixedScriptDetector]. Pure JVM logic — no Android deps.
 */
class MixedScriptDetectorTest {

    @Test fun `plain latin label is not flagged`() {
        assertFalse(MixedScriptDetector.isMixedScript("Starlit Coffee"))
    }

    @Test fun `latin with digits and punctuation is not flagged`() {
        assertFalse(MixedScriptDetector.isMixedScript("App 2.0 — Pro!"))
    }

    @Test fun `null and blank are not flagged`() {
        assertFalse(MixedScriptDetector.isMixedScript(null))
        assertFalse(MixedScriptDetector.isMixedScript("   "))
    }

    @Test fun `latin mixed with cyrillic homoglyph is flagged`() {
        // "Bank" with a Cyrillic 'а' (U+0430) in place of Latin 'a'.
        val homoglyph = "B\u0430nk"
        assertTrue(MixedScriptDetector.isMixedScript(homoglyph))
    }

    @Test fun `pure cyrillic label is not flagged`() {
        // Single-script (all Cyrillic) is legitimate.
        assertFalse(MixedScriptDetector.isMixedScript("Банк"))
    }

    @Test fun `pure cjk label is not flagged`() {
        assertFalse(MixedScriptDetector.isMixedScript("天気"))
    }

    @Test fun `latin mixed with greek is flagged`() {
        // Latin 'O' + Greek 'μ' + Latin 'ega'
        assertTrue(MixedScriptDetector.isMixedScript("O\u03bcega"))
    }
}
