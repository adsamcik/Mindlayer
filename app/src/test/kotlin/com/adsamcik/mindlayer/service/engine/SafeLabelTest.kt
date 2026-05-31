package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.logging.safeLabelWithDetail
import org.junit.Assert.assertEquals
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

    // ─── safeLabelWithDetail allowlist tests ────────────────────────────
    //
    // The allowlist must NEVER include any exception class whose message
    // can contain caller-supplied strings, prompt fragments, or model
    // output. The tests here are the contract: when you add a new class
    // to the allowlist in MindlayerLog.kt, add a paired test below
    // proving the messages are safe.

    @Test
    fun `safeLabelWithDetail strips message for non-allowlisted exceptions`() {
        // A generic RuntimeException could be thrown from native LiteRT-LM
        // tokenizer/template code with prompt fragments inlined.
        val secret = "patient: 'Adam Smith'"
        val t = RuntimeException(secret)
        val labelled = t.safeLabelWithDetail()
        assertFalse(
            "labelled output must NOT include arbitrary RuntimeException message",
            labelled.contains("Adam Smith"),
        )
        assertEquals("RuntimeException", labelled)
    }

    @Test
    fun `safeLabelWithDetail includes message for IllegalArgumentException (boundary validators)`() {
        // Boundary-validator IAEs throw fixed field labels + numeric
        // bounds — never user content. Surfacing the message is a UX
        // win for SDK callers debugging config issues.
        val t = IllegalArgumentException("maxTokens must be between 128 and 8192, got 32")
        val labelled = t.safeLabelWithDetail()
        assertTrue(
            "IAE message should be included for actionability",
            labelled.contains("maxTokens must be between 128 and 8192"),
        )
        assertTrue(labelled.startsWith("IllegalArgumentException("))
        assertTrue(labelled.endsWith(")"))
    }

    @Test
    fun `safeLabelWithDetail includes message for IllegalStateException`() {
        val t = IllegalStateException("engine not initialised")
        val labelled = t.safeLabelWithDetail()
        assertTrue(labelled.contains("engine not initialised"))
        assertTrue(labelled.startsWith("IllegalStateException("))
    }

    @Test
    fun `safeLabelWithDetail includes message for FileNotFoundException`() {
        val t = java.io.FileNotFoundException("/data/data/com.adsamcik.mindlayer/files/missing.tflite")
        val labelled = t.safeLabelWithDetail()
        assertTrue(labelled.contains("missing.tflite"))
    }

    @Test
    fun `safeLabelWithDetail includes message for OverlappingFileLockException`() {
        val t = java.nio.channels.OverlappingFileLockException()
        // OverlappingFileLockException has a null message by spec, so the
        // detail variant falls back to the base label.
        assertEquals("OverlappingFileLockException", t.safeLabelWithDetail())
    }

    @Test
    fun `safeLabelWithDetail truncates very long messages`() {
        val veryLong = "x".repeat(500)
        val t = IllegalArgumentException(veryLong)
        val labelled = t.safeLabelWithDetail(maxMessageChars = 64)
        // Class header is "IllegalArgumentException(" + 64 chars + ")".
        assertTrue(
            "labelled length should be bounded by maxMessageChars (got ${labelled.length})",
            labelled.length <= "IllegalArgumentException(".length + 64 + 1,
        )
    }

    @Test
    fun `safeLabelWithDetail strips newlines from message to prevent log injection`() {
        val t = IllegalArgumentException("config invalid\n[FAKE] AdminGranted=true")
        val labelled = t.safeLabelWithDetail()
        assertFalse("must not contain newlines (log spoofing)", labelled.contains('\n'))
        assertFalse(
            "later lines must be discarded entirely",
            labelled.contains("FAKE"),
        )
        assertTrue(labelled.contains("config invalid"))
    }

    @Test
    fun `safeLabelWithDetail handles null and blank messages`() {
        val nullMsg = RuntimeException()
        assertEquals("RuntimeException", nullMsg.safeLabelWithDetail())

        val blankMsg = IllegalArgumentException("   ")
        // Blank message falls through to base label.
        assertEquals("IllegalArgumentException", blankMsg.safeLabelWithDetail())
    }
}
