package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.SessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Malformed JSON is logged but does NOT throw — session is still
        // created without tools. Reserved-name violations DO throw (covered
        // separately below) because those are security-relevant.
        assertNull(SessionConfigValidator.parseToolDefinitions("[{not_json"))
    }

    @Test
    fun `parseToolDefinitions captures declared tool names`() {
        val json = """[{"name":"get_weather","description":"x"},{"name":"set_alarm"}]"""
        val parsed = SessionConfigValidator.parseToolDefinitions(json)
        assertNotNull(parsed)
        assertEquals(setOf("get_weather", "set_alarm"), parsed!!.names)
        assertEquals(2, parsed.providers.size)
    }

    @Test
    fun `parseToolDefinitions tolerates tools without name field`() {
        val json = """[{"description":"unnamed tool"}]"""
        val parsed = SessionConfigValidator.parseToolDefinitions(json)
        assertNotNull(parsed)
        assertTrue("nameless tool yields empty names set", parsed!!.names.isEmpty())
        assertEquals(1, parsed.providers.size)
    }

    @Test
    fun `parseToolDefinitions throws on reserved-prefix tool name`() {
        // L9 / F-066: client-supplied names with the reserved "__" prefix
        // must be rejected so the synthetic __structured_output tool name
        // cannot be collided with.
        val json = """[{"name":"__sneaky","description":"x"}]"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            SessionConfigValidator.parseToolDefinitions(json)
        }
        assertTrue(
            "exception should mention the reserved prefix",
            ex.message?.contains("reserved prefix") == true,
        )
    }

    @Test
    fun `parseToolDefinitions returns providers whose execute throws in manual mode`() {
        // Manual mode means the SDK forwards calls back to the client; the
        // framework should never invoke the providers locally. Verify the
        // provider's tool.execute() throws if someone bypasses that contract.
        val parsed = SessionConfigValidator.parseToolDefinitions(
            """[{"name":"echo","description":"x"}]"""
        )!!
        val provider = parsed.providers.first()
        // ToolProvider exposes the tool via a builder; invoking its tool's
        // execute() should surface the manual-mode guard. We invoke
        // ToolProvider through reflection to avoid coupling the test to a
        // specific LiteRT-LM internal API surface.
        val toolProperty = provider.javaClass.methods
            .first { it.name == "getTool" || it.name == "tool" }
        val tool = toolProperty.invoke(provider)
        val executeMethod = tool.javaClass.methods.first { it.name == "execute" }
        val ex = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            executeMethod.invoke(tool, "{}")
        }
        assertTrue(
            "expected manual-mode guard, got ${ex.cause}",
            ex.cause is IllegalStateException,
        )
    }

    // ---- validateSessionConfig (thin delegate) -------------------------------

    @Test
    fun `validateSessionConfig accepts a default SessionConfig`() {
        // Sanity check that the delegation to IpcInputValidator works end-to-end.
        SessionConfigValidator.validateSessionConfig(SessionConfig())
    }
}
