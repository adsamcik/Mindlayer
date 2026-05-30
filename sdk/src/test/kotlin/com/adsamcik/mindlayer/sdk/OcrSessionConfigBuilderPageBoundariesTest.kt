package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that the v0.9 [PageBoundariesBuilder] block on
 * [OcrSessionConfigBuilder] is serialised into ``OcrSessionConfig.optionsJson``
 * with the JSON envelope that
 * [com.adsamcik.mindlayer.service.engine.PageBoundariesConfig.parse]
 * reads on the service side, and that it correctly merges with
 * caller-supplied raw `optionsJson` (caller-supplied keys win).
 */
class OcrSessionConfigBuilderPageBoundariesTest {

    @Test fun `default builder has no pageBoundaries block in optionsJson`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt)
        val cfg = builder.buildForTest()
        // Either optionsJson is null, OR it is a JSON object without a
        // "pageBoundaries" key. Both are acceptable "off by default".
        val optionsJson = cfg.optionsJson
        if (optionsJson != null) {
            val obj = Json.parseToJsonElement(optionsJson).jsonObject
            assertFalse(
                "Default builder must NOT inject a pageBoundaries block",
                obj.containsKey("pageBoundaries"),
            )
        }
    }

    @Test fun `empty pageBoundaries block sets enabled=true and all knob defaults`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            pageBoundaries {}
        }
        val obj = builder.pageBoundariesEnvelope()
        assertEquals(true, obj["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(0.3f, obj["jaccardThreshold"]!!.jsonPrimitive.float, 1e-6f)
        assertEquals(0.5f, obj["spatialThreshold"]!!.jsonPrimitive.float, 1e-6f)
        assertEquals(2.0f, obj["gyroThreshold"]!!.jsonPrimitive.float, 1e-6f)
        assertEquals(3, obj["stabilityFrames"]!!.jsonPrimitive.int)
        assertEquals(false, obj["llmExtractPerPage"]?.jsonPrimitive?.boolean)
        assertEquals(true, obj["llmExtractFinal"]?.jsonPrimitive?.boolean)
    }

    @Test fun `customized knobs serialise into the envelope`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            pageBoundaries {
                enabled = true
                jaccardThreshold = 0.42f
                spatialThreshold = 1.1f
                gyroThreshold = 4.5f
                stabilityFrames = 7
                llmExtractPerPage = true
                llmExtractFinal = false
            }
        }
        val obj = builder.pageBoundariesEnvelope()
        assertEquals(true, obj["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(0.42f, obj["jaccardThreshold"]!!.jsonPrimitive.float, 1e-6f)
        assertEquals(1.1f, obj["spatialThreshold"]!!.jsonPrimitive.float, 1e-4f)
        assertEquals(4.5f, obj["gyroThreshold"]!!.jsonPrimitive.float, 1e-4f)
        assertEquals(7, obj["stabilityFrames"]!!.jsonPrimitive.int)
        assertEquals(true, obj["llmExtractPerPage"]?.jsonPrimitive?.boolean)
        assertEquals(false, obj["llmExtractFinal"]?.jsonPrimitive?.boolean)
    }

    @Test fun `pageBoundaries block can disable itself with enabled=false`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            pageBoundaries { enabled = false }
        }
        val obj = builder.pageBoundariesEnvelope()
        assertEquals(false, obj["enabled"]?.jsonPrimitive?.boolean)
        // Still serialised — the explicit `enabled=false` signal is
        // intentional (caller wants the server to KNOW they considered it).
    }

    @Test fun `caller-supplied optionsJson with non-pageBoundaries keys coexists after merge`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            optionsJson = """{"foo":"bar","nested":{"baz":1}}"""
            pageBoundaries {}
        }
        val cfg = builder.buildForTest()
        val obj = Json.parseToJsonElement(cfg.optionsJson!!).jsonObject
        assertNotNull("pageBoundaries block injected", obj["pageBoundaries"])
        assertEquals("bar", obj["foo"]?.jsonPrimitive?.content)
        assertNotNull("nested caller-supplied object preserved", obj["nested"])
    }

    @Test fun `caller-supplied pageBoundaries in optionsJson wins on collision`() {
        // Caller already supplied a pageBoundaries block (e.g. wired from
        // a remote config). The DSL block is overridden because that's
        // the convention everywhere else in the SDK: explicit caller
        // input is authoritative.
        val callerJson = """{"pageBoundaries":{"enabled":false,"jaccardThreshold":0.99}}"""
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            optionsJson = callerJson
            pageBoundaries {
                enabled = true
                jaccardThreshold = 0.1f
            }
        }
        val cfg = builder.buildForTest()
        val outer = Json.parseToJsonElement(cfg.optionsJson!!).jsonObject
        val pb = outer["pageBoundaries"]!!.jsonObject
        assertEquals(false, pb["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(0.99f, pb["jaccardThreshold"]!!.jsonPrimitive.float, 1e-4f)
    }

    @Test fun `no pageBoundaries call and no optionsJson yields null optionsJson`() {
        val cfg = OcrSessionConfigBuilder(OcrProfile.Receipt).buildForTest()
        assertNull(cfg.optionsJson)
    }

    @Test fun `pageBoundaries call but caller optionsJson is blank still produces valid envelope`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            optionsJson = ""
            pageBoundaries { enabled = true }
        }
        val cfg = builder.buildForTest()
        // Blank optionsJson is treated as absent — the resulting JSON
        // contains the pageBoundaries block only.
        val obj = Json.parseToJsonElement(cfg.optionsJson!!).jsonObject
        assertNotNull(obj["pageBoundaries"])
    }

    @Test fun `malformed caller-supplied optionsJson is gracefully dropped (pageBoundaries still emitted)`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            optionsJson = "{this is not valid JSON"
            pageBoundaries { enabled = true }
        }
        val cfg = builder.buildForTest()
        // A broken caller envelope must not silently disable the
        // page-boundary feature the caller explicitly opted into.
        val obj = Json.parseToJsonElement(cfg.optionsJson!!).jsonObject
        assertNotNull("pageBoundaries block must still be present", obj["pageBoundaries"])
        assertEquals(true, obj["pageBoundaries"]!!.jsonObject["enabled"]?.jsonPrimitive?.boolean)
    }

    @Test fun `last pageBoundaries call wins (idempotent overwrite)`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.Receipt).apply {
            pageBoundaries { jaccardThreshold = 0.9f }
            pageBoundaries { jaccardThreshold = 0.1f } // overrides
        }
        val obj = builder.pageBoundariesEnvelope()
        assertEquals(0.1f, obj["jaccardThreshold"]!!.jsonPrimitive.float, 1e-6f)
    }

    // ---- helpers -----------------------------------------------------

    /**
     * Build the config via the internal builder entry point (mirrors what
     * `Mindlayer.ocrRealtime(profile, configure)` does). `internal`
     * visibility means same-module tests can call it directly.
     */
    private fun OcrSessionConfigBuilder.buildForTest() = build()

    private fun OcrSessionConfigBuilder.pageBoundariesEnvelope(): JsonObject {
        val cfg = buildForTest()
        val outer = Json.parseToJsonElement(cfg.optionsJson!!).jsonObject
        return outer["pageBoundaries"]!!.jsonObject
    }
}
