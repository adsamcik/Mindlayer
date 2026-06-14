package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [ToolsBuilder] / [ToolBuilder] tool-calling DSL.
 *
 * The DSL is the typed front door for declaring function-calling tools on a
 * session. Its job is to fail at *construction* time — before the wire envelope
 * is ever sent to `:ml` — so a caller never ships a malformed tool catalog. The
 * wire envelope is a JSON array of `{name, description?, parameters}` objects;
 * these tests pin both the validation rules and the emitted shape.
 */
class ToolDslTest {

    private fun build(block: ToolsBuilder.() -> Unit): String =
        ToolsBuilder().apply(block).build()

    private val objectSchema = """{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}"""

    @Test
    fun `single tool emits name description and parameters in a JSON array`() {
        val json = build {
            tool("get_weather") {
                description("Get current weather for a city")
                parameters(objectSchema)
            }
        }

        val arr = Json.parseToJsonElement(json).jsonArray
        assertEquals(1, arr.size)
        val tool = arr[0].jsonObject
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)
        assertEquals("Get current weather for a city", tool["description"]!!.jsonPrimitive.content)
        assertEquals("object", tool["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool without parameters emits a default object schema`() {
        val json = build { tool("ping") }
        val tool = Json.parseToJsonElement(json).jsonArray[0].jsonObject
        // description is omitted when unset
        assertNull(tool["description"])
        assertEquals("object", tool["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multiple tools accumulate in declaration order`() {
        val json = build {
            tool("a")
            tool("b")
            tool("c")
        }
        val names = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun `blank tool name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { build { tool("   ") } }
    }

    @Test
    fun `tool name over the max length is rejected`() {
        val tooLong = "x".repeat(ToolsBuilder.MAX_TOOL_NAME_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) { build { tool(tooLong) } }
    }

    @Test
    fun `tool name at exactly the max length is accepted`() {
        val maxName = "x".repeat(ToolsBuilder.MAX_TOOL_NAME_LEN)
        val json = build { tool(maxName) }
        assertEquals(maxName, Json.parseToJsonElement(json).jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reserved double-underscore prefix is rejected`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { build { tool("__schema_router") } }
        assertTrue(ex.message!!.contains(ToolsBuilder.RESERVED_PREFIX))
    }

    @Test
    fun `duplicate tool names are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build {
                tool("dup")
                tool("dup")
            }
        }
    }

    @Test
    fun `exceeding the max tool count is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build {
                repeat(ToolsBuilder.MAX_TOOLS + 1) { i -> tool("tool_$i") }
            }
        }
    }

    @Test
    fun `exactly the max tool count is accepted`() {
        val json = build { repeat(ToolsBuilder.MAX_TOOLS) { i -> tool("tool_$i") } }
        assertEquals(ToolsBuilder.MAX_TOOLS, Json.parseToJsonElement(json).jsonArray.size)
    }

    @Test
    fun `non-JSON parameters string is rejected as IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            build { tool("t") { parameters("not json {") } }
        }
        assertTrue(ex.message!!.contains("not valid JSON"))
    }

    @Test
    fun `parameters that parse to a non-object are rejected`() {
        // valid JSON, but an array — tool parameters must be a JSON object
        assertThrows(IllegalArgumentException::class.java) {
            build { tool("t") { parameters("""[1,2,3]""") } }
        }
    }

    @Test
    fun `blank parameters string is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            build { tool("t") { parameters("   ") } }
        }
    }

    @Test
    fun `empty builder emits an empty JSON array`() {
        assertEquals("[]", build { }.replace(" ", ""))
        assertTrue(Json.parseToJsonElement(build { }).jsonArray.isEmpty())
    }

    @Test
    fun `wire-stability constants are pinned`() {
        assertEquals(64, ToolsBuilder.MAX_TOOLS)
        assertEquals(128, ToolsBuilder.MAX_TOOL_NAME_LEN)
        assertEquals("__", ToolsBuilder.RESERVED_PREFIX)
    }

    @Test
    fun `description is omitted from the envelope when not set`() {
        val json = build { tool("noDesc") { parameters(objectSchema) } }
        assertFalse("description key must be absent, not null-valued", json.contains("\"description\""))
    }
}
