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
    fun `safeLabelWithDetail strips message for IllegalArgumentException (may embed native content)`() {
        // SECURITY: IAE can be thrown from native LiteRT-LM tokenizer /
        // template code with prompt fragments inlined. Because the
        // allowlist keys on type not throw-site, IAE detail is NOT
        // surfaced. Boundary-validator detail is surfaced separately via
        // ServiceBinder's INVALID_REQUEST translation, not this helper.
        val t = IllegalArgumentException("system-prompt: 'patient Adam Smith' rejected")
        val labelled = t.safeLabelWithDetail()
        assertFalse("IAE message must not leak", labelled.contains("Adam Smith"))
        assertFalse(labelled.contains("system-prompt"))
        assertEquals("IllegalArgumentException", labelled)
    }

    @Test
    fun `safeLabelWithDetail strips message for IllegalStateException`() {
        val t = IllegalStateException("decode produced: 'secret model output'")
        val labelled = t.safeLabelWithDetail()
        assertFalse("ISE message must not leak", labelled.contains("secret model output"))
        assertEquals("IllegalStateException", labelled)
    }

    @Test
    fun `safeLabelWithDetail strips message for native LiteRT-family exceptions`() {
        // We can't depend on the LiteRT AAR from a JVM unit test, so we
        // mimic the package prefix with a local subclass. The fix removed
        // the `com.google.ai.edge.litert` prefix match; native exception
        // messages (which can embed prompt text) must now be class-only.
        val t = FakeLiteRtLmException("Failed to decode prompt: 'PII here'")
        val labelled = t.safeLabelWithDetail()
        assertFalse("native message must not leak", labelled.contains("PII here"))
        assertFalse(labelled.contains("Failed to decode prompt"))
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
        val t = java.io.FileNotFoundException(veryLong)
        val labelled = t.safeLabelWithDetail(maxMessageChars = 64)
        // Class header is "FileNotFoundException(" + 64 chars + ")".
        assertTrue(
            "labelled length should be bounded by maxMessageChars (got ${labelled.length})",
            labelled.length <= "FileNotFoundException(".length + 64 + 1,
        )
    }

    @Test
    fun `safeLabelWithDetail strips newlines from message to prevent log injection`() {
        val t = java.io.FileNotFoundException("config invalid\n[FAKE] AdminGranted=true")
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

        val blankMsg = java.io.FileNotFoundException("   ")
        // Blank message falls through to base label.
        assertEquals("FileNotFoundException", blankMsg.safeLabelWithDetail())
    }

    /** Mimics a `com.google.ai.edge.litert*` runtime exception by class name. */
    private class FakeLiteRtLmException(message: String) : RuntimeException(message)
}
