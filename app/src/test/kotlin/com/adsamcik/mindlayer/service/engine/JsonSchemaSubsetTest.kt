package com.adsamcik.mindlayer.service.engine

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F-037: native subset JSON-Schema validator regression coverage.
 * Replaces the previous shallow validator that only checked top-level
 * `required` + primitive `type`. Spec subset documented inline on
 * [StructuredOutputHelper.SubsetSchemaValidator].
 */
class JsonSchemaSubsetTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        StructuredOutputHelper.SubsetSchemaValidator.resetCacheForTest()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun assertValid(json: String, schema: JsonObject) {
        val r = StructuredOutputHelper.validateJsonOutput(json, schema)
        assertTrue("Expected Valid for $json against $schema, got $r", r is ValidationResult.Valid)
    }

    private fun assertInvalid(json: String, schema: JsonObject) {
        val r = StructuredOutputHelper.validateJsonOutput(json, schema)
        assertTrue("Expected Invalid for $json against $schema, got $r", r is ValidationResult.Invalid)
    }

    // ── type ─────────────────────────────────────────────────────────────

    @Test fun `type object accepts object`() =
        assertValid("""{"a":1}""", buildJsonObject { put("type", "object") })

    @Test fun `type object rejects array`() =
        assertInvalid("""[1,2]""", buildJsonObject { put("type", "object") })

    @Test fun `type integer accepts integer`() =
        assertValid("""42""", buildJsonObject { put("type", "integer") })

    @Test fun `type integer rejects fractional number`() =
        assertInvalid("""1.5""", buildJsonObject { put("type", "integer") })

    @Test fun `type number accepts both integer and float`() {
        val s = buildJsonObject { put("type", "number") }
        assertValid("1", s)
        assertValid("1.5", s)
    }

    @Test fun `type boolean rejects string`() =
        assertInvalid("\"true\"", buildJsonObject { put("type", "boolean") })

    @Test fun `type null accepts null`() =
        assertValid("null", buildJsonObject { put("type", "null") })

    @Test fun `type null rejects zero`() =
        assertInvalid("0", buildJsonObject { put("type", "null") })

    // ── required ────────────────────────────────────────────────────────

    @Test fun `required field present passes`() =
        assertValid("""{"name":"x"}""", buildJsonObject {
            put("type", "object")
            putJsonArray("required") { add(JsonPrimitive("name")) }
        })

    @Test fun `required field missing fails`() =
        assertInvalid("""{"other":"x"}""", buildJsonObject {
            put("type", "object")
            putJsonArray("required") { add(JsonPrimitive("name")) }
        })

    // ── enum ────────────────────────────────────────────────────────────

    @Test fun `enum accepts member`() =
        assertValid("\"a\"", buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }
        })

    @Test fun `enum rejects non-member`() =
        assertInvalid("\"c\"", buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }
        })

    // ── pattern ─────────────────────────────────────────────────────────

    @Test fun `pattern accepts matching string`() =
        assertValid("\"ABC\"", buildJsonObject {
            put("type", "string")
            put("pattern", "^[A-Z]+$")
        })

    @Test fun `pattern rejects non-matching string`() =
        assertInvalid("\"abc\"", buildJsonObject {
            put("type", "string")
            put("pattern", "^[A-Z]+$")
        })

    // ── minLength / maxLength ───────────────────────────────────────────

    @Test fun `minLength rejects short string`() =
        assertInvalid("\"a\"", buildJsonObject {
            put("type", "string")
            put("minLength", 3)
        })

    @Test fun `maxLength rejects long string`() =
        assertInvalid("\"abcdef\"", buildJsonObject {
            put("type", "string")
            put("maxLength", 3)
        })

    // ── minimum / maximum ───────────────────────────────────────────────

    @Test fun `minimum rejects below`() =
        assertInvalid("0", buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
        })

    @Test fun `maximum rejects above`() =
        assertInvalid("11", buildJsonObject {
            put("type", "integer")
            put("maximum", 10)
        })

    @Test fun `minimum and maximum accept inclusive bounds`() {
        val s = buildJsonObject {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", 10)
        }
        assertValid("0", s)
        assertValid("10", s)
        assertValid("5", s)
    }

    // ── properties / additionalProperties ───────────────────────────────

    @Test fun `properties recurse into nested object`() =
        assertInvalid(
            """{"address":{"city":"Prague"}}""",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("address") {
                        put("type", "object")
                        putJsonArray("required") { add(JsonPrimitive("zip")) }
                    }
                }
            },
        )

    @Test fun `additionalProperties false rejects unknown key`() =
        assertInvalid(
            """{"a":1,"b":2}""",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("a") { put("type", "integer") } }
                put("additionalProperties", false)
            },
        )

    @Test fun `additionalProperties true accepts unknown key`() =
        assertValid(
            """{"a":1,"b":2}""",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("a") { put("type", "integer") } }
                put("additionalProperties", true)
            },
        )

    @Test fun `additionalProperties schema validates unknown values`() =
        assertInvalid(
            """{"a":1,"b":"not-an-int"}""",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("a") { put("type", "integer") } }
                putJsonObject("additionalProperties") { put("type", "integer") }
            },
        )

    // ── items ───────────────────────────────────────────────────────────

    @Test fun `items rejects non-conforming array element`() =
        assertInvalid(
            """[1,"two",3]""",
            buildJsonObject {
                put("type", "array")
                putJsonObject("items") { put("type", "integer") }
            },
        )

    @Test fun `items accepts uniformly-typed array`() =
        assertValid(
            """[1,2,3]""",
            buildJsonObject {
                put("type", "array")
                putJsonObject("items") { put("type", "integer") }
            },
        )

    // ── parse error ─────────────────────────────────────────────────────

    @Test fun `non-json string returns Invalid`() =
        assertInvalid("not json", buildJsonObject { put("type", "object") })

    // ── pattern cache ───────────────────────────────────────────────────

    @Test
    fun `pattern regex compile cache reused across calls`() {
        StructuredOutputHelper.SubsetSchemaValidator.resetCacheForTest()
        val schema = buildJsonObject {
            put("type", "string")
            put("pattern", "^[A-Z]{1,3}$")
        }
        repeat(50) {
            StructuredOutputHelper.validateJsonOutput("\"AB\"", schema)
        }
        // Single distinct pattern → exactly 1 cache entry regardless of
        // call count.
        assertTrue(
            "expected cache size 1, got ${StructuredOutputHelper.SubsetSchemaValidator.cacheSizeForTest()}",
            StructuredOutputHelper.SubsetSchemaValidator.cacheSizeForTest() == 1,
        )
    }
}
