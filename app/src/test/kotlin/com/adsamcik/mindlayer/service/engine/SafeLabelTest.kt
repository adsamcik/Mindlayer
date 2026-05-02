package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.safeLabel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins SECURITY_REVIEW F-006 — native exception text must NOT be
 * persisted to the log database or `lastGpuFailureReason`. LiteRT-LM
 * tokenizer/template exceptions can inline prompt fragments; we want
 * `safeLabel()` (class names only) instead.
 *
 * We can't actually run native init from a unit test, so we cover the
 * `safeLabel` extension directly here — the EngineManager change site
 * was a one-line replacement of `t.message` → `t.safeLabel()`.
 */
class SafeLabelTest {

    @Test
    fun `safeLabel does not include throwable message`() {
        val secret = "tokenizer_failed_with_prompt: 'patient name = Adam Smith'"
        val t = RuntimeException(secret)
        val label = t.safeLabel()
        assertFalse("label leaks raw message", label.contains("Adam Smith"))
        assertFalse("label leaks raw message", label.contains("patient name"))
        assertTrue("label should be class-name based", label.startsWith("RuntimeException"))
    }

    @Test
    fun `safeLabel does not include cause message`() {
        val cause = IllegalStateException("system-prompt: 'kill all humans'")
        val t = RuntimeException("outer", cause)
        val label = t.safeLabel()
        assertFalse(label.contains("kill all humans"))
        assertFalse(label.contains("system-prompt"))
        assertTrue(label.contains("RuntimeException"))
        assertTrue(label.contains("IllegalStateException"))
    }
}
