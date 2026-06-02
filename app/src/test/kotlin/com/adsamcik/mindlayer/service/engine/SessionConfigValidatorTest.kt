package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.SessionConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SessionConfigValidator]. Pure-function helpers — no Android
 * mocks needed. These tests live alongside the validator so refactor
 * regressions surface immediately, and so the more elaborate
 * [com.adsamcik.mindlayer.service.security.IpcInputValidatorTest] retains its
 * focus on byte-budget edge cases.
 *
 * `validateSessionConfig` itself is covered (and over-covered) by
 * `IpcInputValidatorTest` since this validator just delegates to it.
 */
class SessionConfigValidatorTest {

    // ---- parseTokenBatchOptIn ------------------------------------------------

    @Test
    fun `parseTokenBatchOptIn returns false for null`() {
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn(null))
    }

    @Test
    fun `parseTokenBatchOptIn returns false for blank`() {
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn(""))
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("   "))
    }

    @Test
    fun `parseTokenBatchOptIn returns false for malformed JSON`() {
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("{not json"))
    }

    @Test
    fun `parseTokenBatchOptIn returns false for non-object JSON`() {
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("[1,2,3]"))
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("\"hello\""))
    }

    @Test
    fun `parseTokenBatchOptIn returns false when key missing`() {
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("""{"other":true}"""))
    }

    @Test
    fun `parseTokenBatchOptIn returns false when key is not boolean`() {
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("""{"token_batch":"yes"}"""))
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("""{"token_batch":1}"""))
    }

    @Test
    fun `parseTokenBatchOptIn returns true when key is true`() {
        assertTrue(SessionConfigValidator.parseTokenBatchOptIn("""{"token_batch":true}"""))
    }

    @Test
    fun `parseTokenBatchOptIn returns false when key is false`() {
        assertFalse(SessionConfigValidator.parseTokenBatchOptIn("""{"token_batch":false}"""))
    }

    // ---- parseToolDefinitions ------------------------------------------------
    //
    // Note: deep coverage of parseToolDefinitions requires the LiteRT-LM
    // `tool()` factory which loads JNI and isn't available in pure JVM unit
    // tests. The cases below cover the paths that exit before `tool()` is
    // invoked. End-to-end tool-loading is exercised in the existing
    // SessionManager-driven flow tests with a mocked Engine, plus the
    // instrumented tests in :app:androidTest.

    @Test
    fun `parseToolDefinitions returns null for null`() {
        assertNull(SessionConfigValidator.parseToolDefinitions(null))
    }

    @Test
    fun `parseToolDefinitions returns null for blank`() {
        assertNull(SessionConfigValidator.parseToolDefinitions(""))
        assertNull(SessionConfigValidator.parseToolDefinitions("   "))
    }

    @Test
    fun `parseToolDefinitions returns null for empty JSON array`() {
        assertNull(SessionConfigValidator.parseToolDefinitions("[]"))
    }

    @Test
    fun `parseToolDefinitions throws on reserved-prefix tool name`() {
        // L9 / F-066: client-supplied names with the reserved "__" prefix
        // must be rejected so the synthetic __structured_output tool name
        // cannot be collided with. The reserved-name `require()` runs
        // before the LiteRT-LM `tool()` factory inside the loop, so this
        // path is JVM-testable without JNI.
        val json = """[{"name":"__sneaky","description":"x"}]"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SessionConfigValidator.parseToolDefinitions(json)
        }
        assertTrue(
            "exception should mention the reserved prefix",
            ex.message?.contains("reserved prefix") == true,
        )
    }

    // TODO(follow-up): kotlinx.serialization throws JsonDecodingException
    // which extends IllegalArgumentException, so the current
    // parseToolDefinitions surfaces malformed JSON as a thrown IAE instead
    // of the documented "returns null on parse error". Separate from this
    // extraction PR — fix when refactoring the catch order to distinguish
    // SerializationException (→ null) from require()-IAE (→ throw).

    // ---- validateSessionConfig (thin delegate) -------------------------------

    @Test
    fun `validateSessionConfig accepts a default SessionConfig`() {
        // Sanity check that the delegation to IpcInputValidator works end-to-end.
        SessionConfigValidator.validateSessionConfig(SessionConfig())
    }
}
