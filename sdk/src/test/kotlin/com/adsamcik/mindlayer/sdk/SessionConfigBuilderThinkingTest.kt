package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for v1.1 [SessionConfigBuilder.enableThinking] — the SDK
 * surface that opts a session into Gemma 4 thinking mode by writing a
 * `{"thinking":{"enable":...}}` envelope into `SessionConfig.extraContextJson`.
 *
 * The wire contract is consumed by `SessionManager.parseThinkingOptIn`
 * on the service side; these tests pin the JSON shape the SDK emits so
 * the two sides stay in sync.
 */
class SessionConfigBuilderThinkingTest {

    @Test
    fun `enableThinking with default true sets the envelope`() {
        val cfg = SessionConfigBuilder().apply { enableThinking() }.build()
        val env = Json.parseToJsonElement(cfg.extraContextJson!!).jsonObject
        val thinking = env["thinking"]!!.jsonObject
        assertEquals(true, thinking["enable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `enableThinking false explicitly disables the flag`() {
        // Honoured for completeness — a caller toggling a stored
        // preference wants the explicit false to round-trip.
        val cfg = SessionConfigBuilder().apply { enableThinking(false) }.build()
        val env = Json.parseToJsonElement(cfg.extraContextJson!!).jsonObject
        val thinking = env["thinking"]!!.jsonObject
        assertEquals(false, thinking["enable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `enableThinking absent leaves extraContextJson null`() {
        val cfg = SessionConfigBuilder().build()
        assertNull(
            "no extraContext should be emitted unless something opts in",
            cfg.extraContextJson,
        )
    }

    @Test
    fun `enableThinking merges with prior tokenBatching envelope`() {
        val cfg = SessionConfigBuilder().apply {
            tokenBatching(true)
            enableThinking()
        }.build()
        val env = Json.parseToJsonElement(cfg.extraContextJson!!).jsonObject
        assertEquals(
            "token_batch envelope must survive the thinking merge",
            true,
            env["token_batch"]!!.jsonPrimitive.boolean,
        )
        assertEquals(
            "thinking envelope must be present alongside token_batch",
            true,
            env["thinking"]!!.jsonObject["enable"]!!.jsonPrimitive.boolean,
        )
    }

    @Test
    fun `enableThinking merges with prior jsonOutput structured_output envelope`() {
        val cfg = SessionConfigBuilder().apply {
            jsonOutput {
                schema("""{"type":"object","properties":{"x":{"type":"string"}}}""")
            }
            enableThinking()
        }.build()
        val env = Json.parseToJsonElement(cfg.extraContextJson!!).jsonObject
        assertNotNull(
            "structured_output envelope must survive the thinking merge",
            env["structured_output"],
        )
        assertNotNull(
            "thinking envelope must be present alongside structured_output",
            env["thinking"],
        )
    }

    @Test
    fun `enableThinking called twice keeps the latest value`() {
        val cfg = SessionConfigBuilder().apply {
            enableThinking(false)
            enableThinking(true)
        }.build()
        val env = Json.parseToJsonElement(cfg.extraContextJson!!).jsonObject
        assertEquals(true, env["thinking"]!!.jsonObject["enable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `enableThinking is parsed by the service-side opt-in shape`() {
        // Smoke-test that the JSON we emit matches one of the two shapes
        // SessionManager.parseThinkingOptIn accepts — i.e. the nested
        // {"thinking":{"enable":true}} form. We can't import the service
        // class from the SDK module, but we can replicate the parse
        // here to lock the contract.
        val cfg = SessionConfigBuilder().apply { enableThinking() }.build()
        val env = Json.parseToJsonElement(cfg.extraContextJson!!).jsonObject
        val node = env["thinking"]
        assertNotNull("thinking key required", node)
        val obj = node!!.jsonObject
        val flag = obj["enable"]!!.jsonPrimitive.boolean
        assertTrue("service-side parseThinkingOptIn expects enable=true", flag)
    }
}
