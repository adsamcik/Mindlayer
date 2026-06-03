package com.adsamcik.mindlayer.service.engine

import android.util.Log
import com.adsamcik.mindlayer.SessionConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SessionConfigValidator]. Pure-function helpers — most paths
 * need no Android mocks, but the malformed-JSON catch logs via
 * [com.adsamcik.mindlayer.service.logging.MindlayerLog] (which delegates to
 * `android.util.Log`), so we stub the static call up-front. These tests live
 * alongside the validator so refactor regressions surface immediately, and so
 * the more elaborate
 * [com.adsamcik.mindlayer.service.security.IpcInputValidatorTest] retains its
 * focus on byte-budget edge cases.
 *
 * `validateSessionConfig` itself is covered (and over-covered) by
 * `IpcInputValidatorTest` since this validator just delegates to it.
 */
class SessionConfigValidatorTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

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
    fun `parseToolDefinitions returns null on malformed JSON`() {
        // The malformed-JSON path is delicate: kotlinx.serialization throws
        // JsonDecodingException, which extends IllegalArgumentException
        // (via SerializationException). The validator catches
        // SerializationException BEFORE IllegalArgumentException so the
        // documented "returns null on parse error" contract holds, and
        // reserved-name require()-IAE keeps surfacing for security.
        assertNull(SessionConfigValidator.parseToolDefinitions("[{not_json"))
        assertNull(SessionConfigValidator.parseToolDefinitions("not even an array"))
        assertNull(SessionConfigValidator.parseToolDefinitions("{"))
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

    // ---- validateSessionConfig (thin delegate) -------------------------------

    @Test
    fun `validateSessionConfig accepts a default SessionConfig`() {
        // Sanity check that the delegation to IpcInputValidator works end-to-end.
        SessionConfigValidator.validateSessionConfig(SessionConfig())
    }
}
