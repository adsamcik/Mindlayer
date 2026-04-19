package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
}
