package com.adsamcik.mindlayer.service.engine

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StructuredOutputHelperTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── Helper to build a typical schema ─────────────────────────────────

    private fun buildTestSchema(
        vararg properties: Pair<String, String>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, type) in properties) {
                putJsonObject(name) { put("type", type) }
            }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  parseConfig
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `parseConfig returns valid config for tool_routing strategy`() {
        val input = """
        {
          "structured_output": {
            "schema": { "type": "object", "properties": { "name": { "type": "string" } } },
            "strategy": "tool_routing"
          }
        }
        """.trimIndent()
        val config = StructuredOutputHelper.parseConfig(input)
        assertNotNull(config)
        assertEquals(StructuredOutputStrategy.TOOL_ROUTING, config!!.strategy)
        assertEquals(3, config.maxRetries) // default
    }

    @Test
    fun `parseConfig returns valid config for prompt_and_validate strategy`() {
        val input = """
        {
          "structured_output": {
            "schema": { "type": "object", "properties": {} },
            "strategy": "prompt_and_validate"
          }
        }
        """.trimIndent()
        val config = StructuredOutputHelper.parseConfig(input)
        assertNotNull(config)
        assertEquals(StructuredOutputStrategy.PROMPT_AND_VALIDATE, config!!.strategy)
    }

    @Test
    fun `parseConfig defaults to prompt_and_validate when strategy omitted`() {
        val input = """
        {
          "structured_output": {
            "schema": { "type": "object", "properties": {} }
          }
        }
        """.trimIndent()
        val config = StructuredOutputHelper.parseConfig(input)
        assertNotNull(config)
        assertEquals(StructuredOutputStrategy.PROMPT_AND_VALIDATE, config!!.strategy)
    }

    @Test
    fun `parseConfig reads custom max_retries`() {
        val input = """
        {
          "structured_output": {
            "schema": { "type": "object", "properties": {} },
            "max_retries": 5
          }
        }
        """.trimIndent()
        val config = StructuredOutputHelper.parseConfig(input)
        assertNotNull(config)
        assertEquals(5, config!!.maxRetries)
    }

    @Test
    fun `parseConfig returns null for null input`() {
        assertNull(StructuredOutputHelper.parseConfig(null))
    }

    @Test
    fun `parseConfig returns null for blank input`() {
        assertNull(StructuredOutputHelper.parseConfig(""))
        assertNull(StructuredOutputHelper.parseConfig("   "))
    }

    @Test
    fun `parseConfig returns null when structured_output key missing`() {
        val input = """{ "other_key": 42 }"""
        assertNull(StructuredOutputHelper.parseConfig(input))
    }

    @Test
    fun `parseConfig returns null when schema missing`() {
        val input = """
        {
          "structured_output": {
            "strategy": "tool_routing"
          }
        }
        """.trimIndent()
        assertNull(StructuredOutputHelper.parseConfig(input))
    }

    @Test
    fun `parseConfig returns null for malformed JSON`() {
        assertNull(StructuredOutputHelper.parseConfig("{not valid json"))
    }

    @Test
    fun `parseConfig returns null when schema is not an object`() {
        val input = """
        {
          "structured_output": {
            "schema": "not_an_object"
          }
        }
        """.trimIndent()
        assertNull(StructuredOutputHelper.parseConfig(input))
    }

    @Test
    fun `parseConfig treats unknown strategy as prompt_and_validate`() {
        val input = """
        {
          "structured_output": {
            "schema": { "type": "object", "properties": {} },
            "strategy": "unknown_future_strategy"
          }
        }
        """.trimIndent()
        val config = StructuredOutputHelper.parseConfig(input)
        assertNotNull(config)
        assertEquals(StructuredOutputStrategy.PROMPT_AND_VALIDATE, config!!.strategy)
    }

    @Test
    fun `parseConfig strategy matching is case-insensitive`() {
        val input = """
        {
          "structured_output": {
            "schema": { "type": "object", "properties": {} },
            "strategy": "TOOL_ROUTING"
          }
        }
        """.trimIndent()
        val config = StructuredOutputHelper.parseConfig(input)
        assertNotNull(config)
        assertEquals(StructuredOutputStrategy.TOOL_ROUTING, config!!.strategy)
    }

    // ════════════════════════════════════════════════════════════════════
    //  validateJsonOutput
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `validate valid JSON with all required fields`() {
        val schema = buildTestSchema(
            "name" to "string",
            "age" to "integer",
            required = listOf("name", "age"),
        )
        val result = StructuredOutputHelper.validateJsonOutput(
            """{"name":"Alice","age":30}""",
            schema,
        )
        assertTrue(result is ValidationResult.Valid)
        assertEquals("""{"name":"Alice","age":30}""", (result as ValidationResult.Valid).json)
    }

    @Test
    fun `validate missing required field returns Invalid`() {
        val schema = buildTestSchema(
            "name" to "string",
            "age" to "integer",
            required = listOf("name", "age"),
        )
        val result = StructuredOutputHelper.validateJsonOutput("""{"name":"Alice"}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors
        assertTrue(errors.any { it.contains("age") })
    }

    @Test
    fun `validate wrong type for string field`() {
        val schema = buildTestSchema("name" to "string", required = listOf("name"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"name":123}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("string") })
    }

    @Test
    fun `validate wrong type for number field`() {
        val schema = buildTestSchema("score" to "number", required = listOf("score"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"score":"high"}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("number") })
    }

    @Test
    fun `validate wrong type for integer field`() {
        val schema = buildTestSchema("count" to "integer", required = listOf("count"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"count":"five"}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("integer") })
    }

    @Test
    fun `validate wrong type for boolean field`() {
        val schema = buildTestSchema("active" to "boolean", required = listOf("active"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"active":"yes"}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("boolean") })
    }

    @Test
    fun `validate wrong type for array field`() {
        val schema = buildTestSchema("tags" to "array", required = listOf("tags"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"tags":"not-array"}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("array") })
    }

    @Test
    fun `validate wrong type for object field`() {
        val schema = buildTestSchema("meta" to "object", required = listOf("meta"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"meta":"flat"}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("object") })
    }

    @Test
    fun `validate not valid JSON returns Invalid`() {
        val schema = buildTestSchema("x" to "string")
        val result = StructuredOutputHelper.validateJsonOutput("this is not json", schema)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.any { it.contains("Not valid JSON") })
    }

    @Test
    fun `validate parse error does not include model output snippet`() {
        val schema = buildTestSchema("answer" to "string", required = listOf("answer"))
        val secretOutput = "not json but includes patient name Adam Smith"

        val result = StructuredOutputHelper.validateJsonOutput(secretOutput, schema)

        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors.joinToString("\n")
        assertFalse(errors.contains("Adam Smith"))
        assertFalse(errors.contains("patient name"))
        assertTrue(errors.contains("Not valid JSON"))
    }

    @Test
    fun `validate JSON array when object expected returns Invalid`() {
        val schema = buildTestSchema("x" to "string")
        val result = StructuredOutputHelper.validateJsonOutput("""[1,2,3]""", schema)
        assertTrue(result is ValidationResult.Invalid)
        // F-037: the native subset validator emits "expected object, got array".
        // The previous shallow validator emitted "Expected JSON object …".
        // Either form should satisfy the spirit of this regression.
        val errors = (result as ValidationResult.Invalid).errors.joinToString(" | ")
        assertTrue(
            "Expected an error mentioning object/array mismatch, got: $errors",
            errors.contains("expected object", ignoreCase = true) ||
                errors.contains("array", ignoreCase = true),
        )
    }

    @Test
    fun `validate extra fields not in schema are accepted (lenient)`() {
        val schema = buildTestSchema("name" to "string", required = listOf("name"))
        val result = StructuredOutputHelper.validateJsonOutput(
            """{"name":"Alice","bonus":"extra"}""",
            schema,
        )
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate empty required array passes`() {
        val schema = buildTestSchema("name" to "string", required = emptyList())
        val result = StructuredOutputHelper.validateJsonOutput("""{}""", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate schema without required array passes`() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("opt") { put("type", "string") }
            }
        }
        val result = StructuredOutputHelper.validateJsonOutput("""{}""", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate strips markdown fences and validates cleaned JSON`() {
        val schema = buildTestSchema("name" to "string", required = listOf("name"))
        val wrapped = "```\n{\"name\":\"Alice\"}\n```"
        val result = StructuredOutputHelper.validateJsonOutput(wrapped, schema)
        assertTrue(result is ValidationResult.Valid)
        assertEquals("""{"name":"Alice"}""", (result as ValidationResult.Valid).json)
    }

    @Test
    fun `validate strips markdown fences with language tag`() {
        val schema = buildTestSchema("name" to "string", required = listOf("name"))
        val wrapped = "```json\n{\"name\":\"Bob\"}\n```"
        val result = StructuredOutputHelper.validateJsonOutput(wrapped, schema)
        assertTrue(result is ValidationResult.Valid)
        assertEquals("""{"name":"Bob"}""", (result as ValidationResult.Valid).json)
    }

    @Test
    fun `validate nested markdown fences - outer stripped`() {
        val schema = buildTestSchema("code" to "string", required = listOf("code"))
        // The inner fences are part of the value, outer fences are stripped
        val wrapped = "```\n{\"code\":\"example\"}\n```"
        val result = StructuredOutputHelper.validateJsonOutput(wrapped, schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate correct type number accepts integer values`() {
        val schema = buildTestSchema("score" to "number", required = listOf("score"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"score":42}""", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate correct type number accepts decimal values`() {
        val schema = buildTestSchema("score" to "number", required = listOf("score"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"score":3.14}""", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate correct type boolean true`() {
        val schema = buildTestSchema("flag" to "boolean", required = listOf("flag"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"flag":true}""", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate correct type array`() {
        val schema = buildTestSchema("items" to "array", required = listOf("items"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"items":[1,2,3]}""", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate correct type object`() {
        val schema = buildTestSchema("meta" to "object", required = listOf("meta"))
        val result = StructuredOutputHelper.validateJsonOutput("""{"meta":{"k":"v"}}""", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate multiple missing required fields reports all errors`() {
        val schema = buildTestSchema(
            "a" to "string",
            "b" to "string",
            "c" to "string",
            required = listOf("a", "b", "c"),
        )
        val result = StructuredOutputHelper.validateJsonOutput("""{}""", schema)
        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors
        assertEquals(3, errors.size)
    }

    @Test
    fun `validate multiple type errors reports all errors`() {
        val schema = buildTestSchema(
            "name" to "string",
            "age" to "integer",
            required = listOf("name", "age"),
        )
        val result = StructuredOutputHelper.validateJsonOutput(
            """{"name":123,"age":"old"}""",
            schema,
        )
        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors
        assertEquals(2, errors.size)
    }

    @Test
    fun `validate whitespace-wrapped JSON is trimmed before parsing`() {
        val schema = buildTestSchema("x" to "string", required = listOf("x"))
        val result = StructuredOutputHelper.validateJsonOutput("   {\"x\":\"ok\"}   ", schema)
        assertTrue(result is ValidationResult.Valid)
    }

    // ════════════════════════════════════════════════════════════════════
    //  buildSchemaPromptSuffix
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `buildSchemaPromptSuffix contains schema text`() {
        val schema = buildTestSchema("name" to "string")
        val config = StructuredOutputConfig(
            schema = schema,
            strategy = StructuredOutputStrategy.PROMPT_AND_VALIDATE,
        )
        val suffix = StructuredOutputHelper.buildSchemaPromptSuffix(config)
        assertTrue(suffix.contains(schema.toString()))
    }

    @Test
    fun `buildSchemaPromptSuffix contains MUST respond instruction`() {
        val schema = buildTestSchema("x" to "string")
        val config = StructuredOutputConfig(
            schema = schema,
            strategy = StructuredOutputStrategy.PROMPT_AND_VALIDATE,
        )
        val suffix = StructuredOutputHelper.buildSchemaPromptSuffix(config)
        assertTrue(suffix.contains("MUST respond with valid JSON"))
    }

    @Test
    fun `buildSchemaPromptSuffix contains no-markdown instruction`() {
        val schema = buildTestSchema("x" to "string")
        val config = StructuredOutputConfig(
            schema = schema,
            strategy = StructuredOutputStrategy.PROMPT_AND_VALIDATE,
        )
        val suffix = StructuredOutputHelper.buildSchemaPromptSuffix(config)
        assertTrue(suffix.contains("Do NOT wrap"))
        assertTrue(suffix.contains("markdown"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  buildRetryPrompt
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `buildRetryPrompt contains error messages`() {
        val schema = buildTestSchema("name" to "string")
        val errors = listOf("Missing required field: name", "Field 'age': expected integer")
        val prompt = StructuredOutputHelper.buildRetryPrompt(errors, schema)
        assertTrue(prompt.contains("Missing required field: name"))
        assertTrue(prompt.contains("Field 'age': expected integer"))
    }

    @Test
    fun `buildRetryPrompt contains schema`() {
        val schema = buildTestSchema("name" to "string")
        val prompt = StructuredOutputHelper.buildRetryPrompt(listOf("err"), schema)
        assertTrue(prompt.contains(schema.toString()))
    }

    @Test
    fun `buildRetryPrompt contains previous response was not valid`() {
        val schema = buildTestSchema("x" to "string")
        val prompt = StructuredOutputHelper.buildRetryPrompt(listOf("err"), schema)
        assertTrue(prompt.contains("previous response was not valid"))
    }

    @Test
    fun `buildRetryPrompt formats each error as bullet`() {
        val schema = buildTestSchema()
        val errors = listOf("err1", "err2", "err3")
        val prompt = StructuredOutputHelper.buildRetryPrompt(errors, schema)
        assertTrue(prompt.contains("  - err1"))
        assertTrue(prompt.contains("  - err2"))
        assertTrue(prompt.contains("  - err3"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  extractStructuredResult
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `extractStructuredResult finds __structured_output tool`() {
        val calls = listOf(
            "other_tool" to """{"a":1}""",
            "__structured_output" to """{"name":"Alice"}""",
        )
        val result = StructuredOutputHelper.extractStructuredResult(calls)
        assertEquals("""{"name":"Alice"}""", result)
    }

    @Test
    fun `extractStructuredResult returns null when tool not present`() {
        val calls = listOf(
            "search" to """{"q":"hello"}""",
            "calculator" to """{"expr":"2+2"}""",
        )
        assertNull(StructuredOutputHelper.extractStructuredResult(calls))
    }

    @Test
    fun `extractStructuredResult returns null for empty list`() {
        assertNull(StructuredOutputHelper.extractStructuredResult(emptyList()))
    }

    @Test
    fun `extractStructuredResult returns first match when duplicates exist`() {
        val calls = listOf(
            "__structured_output" to """{"first":true}""",
            "__structured_output" to """{"second":true}""",
        )
        val result = StructuredOutputHelper.extractStructuredResult(calls)
        assertEquals("""{"first":true}""", result)
    }

    @Test
    fun `extractStructuredResult is case-sensitive on tool name`() {
        val calls = listOf(
            "__Structured_Output" to """{"x":1}""",
        )
        assertNull(StructuredOutputHelper.extractStructuredResult(calls))
    }

    // ════════════════════════════════════════════════════════════════════
    //  TOOL_NAME constant
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `TOOL_NAME constant is correct`() {
        assertEquals("__structured_output", StructuredOutputHelper.TOOL_NAME)
    }
}
