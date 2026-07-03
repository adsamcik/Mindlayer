package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [JsonOutputBuilder] DSL and its integration with
 * [SessionConfigBuilder.jsonOutput].
 *
 * The wire contract is `{"structured_output": {"schema": ..., "strategy": ..., "max_retries": ...}}`
 * consumed by `StructuredOutputHelper.parseConfig` service-side.
 */
class JsonOutputBuilderTest {

    private val schemaStr = """
        {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}
    """.trimIndent()

    @Test
    fun `schema string produces structured_output envelope`() {
        val b = JsonOutputBuilder()
        b.schema(schemaStr)
        val env = b.build()

        val so = env["structured_output"]!!.jsonObject
        assertEquals("prompt_and_validate", so["strategy"]!!.jsonPrimitive.content)
        assertEquals(3, so["max_retries"]!!.jsonPrimitive.int)
        assertEquals("object", so["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `schema JsonObject is passed through unchanged`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
        }
        val env = JsonOutputBuilder().apply { schema(schema) }.build()
        val actual = env["structured_output"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals("object", actual["type"]!!.jsonPrimitive.content)
        assertEquals(false, actual["additionalProperties"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `tool_routing strategy serialises correctly`() {
        val env = JsonOutputBuilder().apply {
            schema(schemaStr)
            strategy(JsonOutputStrategy.ToolRouting)
        }.build()
        assertEquals(
            "tool_routing",
            env["structured_output"]!!.jsonObject["strategy"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `maxRetries is honored`() {
        val env = JsonOutputBuilder().apply {
            schema(schemaStr)
            maxRetries(0)
        }.build()
        assertEquals(
            0,
            env["structured_output"]!!.jsonObject["max_retries"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `missing schema throws`() {
        val b = JsonOutputBuilder()
        assertThrows(IllegalStateException::class.java) { b.build() }
    }

    @Test
    fun `blank schema throws`() {
        val b = JsonOutputBuilder()
        assertThrows(IllegalArgumentException::class.java) { b.schema("   ") }
    }

    @Test
    fun `non-object schema throws`() {
        val b = JsonOutputBuilder()
        assertThrows(IllegalArgumentException::class.java) { b.schema("[1,2,3]") }
    }

    @Test
    fun `malformed schema json throws`() {
        val b = JsonOutputBuilder()
        assertThrows(IllegalArgumentException::class.java) { b.schema("{not json") }
    }

    @Test
    fun `negative maxRetries throws`() {
        val b = JsonOutputBuilder()
        assertThrows(IllegalArgumentException::class.java) { b.maxRetries(-1) }
    }

    // ---- mergeExtraContext -----------------------------------------------

    @Test
    fun `merge into null base returns addition`() {
        val addition = buildJsonObject { put("a", 1) }
        val merged = Json.parseToJsonElement(mergeExtraContext(null, addition)).jsonObject
        assertEquals(1, merged["a"]!!.jsonPrimitive.int)
    }

    @Test
    fun `merge preserves unrelated keys from base`() {
        val base = """{"grounding":"foo","structured_output":{"old":true}}"""
        val addition = buildJsonObject {
            put("structured_output", buildJsonObject { put("new", true) })
        }
        val merged = Json.parseToJsonElement(mergeExtraContext(base, addition)).jsonObject
        assertEquals("foo", merged["grounding"]!!.jsonPrimitive.content)
        // addition wins on conflict
        assertNotNull(merged["structured_output"]!!.jsonObject["new"])
        assertTrue(
            "old key should be overridden",
            merged["structured_output"]!!.jsonObject["old"] == null,
        )
    }

    @Test
    fun `merge with malformed base falls back to addition`() {
        val addition = buildJsonObject { put("a", 1) }
        val merged = Json.parseToJsonElement(mergeExtraContext("{not json", addition)).jsonObject
        assertEquals(1, merged["a"]!!.jsonPrimitive.int)
    }

    // ---- SessionScope.jsonOutput (public DSL) ----------------------------

    /** Minimal [SessionScope] to exercise the interface's default [jsonOutput]. */
    private class TestSessionScope : SessionScope {
        override var systemPrompt: String? = null
        override var maxTokens: Int? = null
        override var historyPolicy: HistoryPolicy = HistoryPolicy.METADATA_ONLY
        override var toolsJson: String? = null
        override var extraContextJson: String? = null
    }

    @Test
    fun `SessionScope jsonOutput writes structured_output envelope to extraContextJson`() {
        val scope = TestSessionScope().apply {
            jsonOutput {
                schema(schemaStr)
                strategy(JsonOutputStrategy.PromptAndValidate)
            }
        }
        val env = Json.parseToJsonElement(scope.extraContextJson!!).jsonObject
        val so = env["structured_output"]!!.jsonObject
        assertEquals("prompt_and_validate", so["strategy"]!!.jsonPrimitive.content)
        assertEquals(3, so["max_retries"]!!.jsonPrimitive.int)
        assertEquals("object", so["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `SessionScope jsonOutput carries enum schema, tool_routing and maxRetries`() {
        val scope = TestSessionScope().apply {
            jsonOutput {
                schema(
                    """{"type":"object","properties":{"status":{"type":"string","enum":["active","inactive"]}},"required":["status"]}""",
                )
                strategy(JsonOutputStrategy.ToolRouting)
                maxRetries(5)
                validationDepth(JsonValidationDepth.CALLER_VALIDATES)
            }
        }
        val so = Json.parseToJsonElement(scope.extraContextJson!!).jsonObject["structured_output"]!!.jsonObject
        assertEquals("tool_routing", so["strategy"]!!.jsonPrimitive.content)
        assertEquals(5, so["max_retries"]!!.jsonPrimitive.int)
        assertEquals("none", so["validation_depth"]!!.jsonPrimitive.content)
        val statusEnum = so["schema"]!!.jsonObject["properties"]!!
            .jsonObject["status"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals("active", statusEnum[0].jsonPrimitive.content)
        assertEquals("inactive", statusEnum[1].jsonPrimitive.content)
    }

    @Test
    fun `SessionScope jsonOutput merges without clobbering existing extraContextJson keys`() {
        val scope = TestSessionScope().apply {
            extraContextJson = """{"thinking":{"enable":true}}"""
            jsonOutput { schema(schemaStr) }
        }
        val env = Json.parseToJsonElement(scope.extraContextJson!!).jsonObject
        assertEquals(
            true,
            env["thinking"]!!.jsonObject["enable"]!!.jsonPrimitive.boolean,
        )
        assertNotNull(env["structured_output"])
    }
}
