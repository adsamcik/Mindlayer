package com.adsamcik.mindlayer.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── StreamEventType constants ────────────────────────────────────────

    @Test
    fun `StreamEventType START is correct`() {
        assertEquals("start", StreamEventType.START)
    }

    @Test
    fun `StreamEventType TOKEN_DELTA is correct`() {
        assertEquals("token_delta", StreamEventType.TOKEN_DELTA)
    }

    @Test
    fun `StreamEventType TOOL_CALL is correct`() {
        assertEquals("tool_call", StreamEventType.TOOL_CALL)
    }

    @Test
    fun `StreamEventType TOOL_RESULT is correct`() {
        assertEquals("tool_result", StreamEventType.TOOL_RESULT)
    }

    @Test
    fun `StreamEventType METRICS is correct`() {
        assertEquals("metrics", StreamEventType.METRICS)
    }

    @Test
    fun `StreamEventType ERROR is correct`() {
        assertEquals("error", StreamEventType.ERROR)
    }

    @Test
    fun `StreamEventType DONE is correct`() {
        assertEquals("done", StreamEventType.DONE)
    }

    // ── StreamEvent serialization roundtrips ─────────────────────────────

    @Test
    fun `StreamEvent roundtrip with START type`() {
        val event = StreamEvent(
            seq = 1L,
            type = StreamEventType.START,
            tsMs = 1700000000000L,
            payload = buildJsonObject { put("model", "gemma") },
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent roundtrip with TOKEN_DELTA type`() {
        val event = StreamEvent(
            seq = 2L,
            type = StreamEventType.TOKEN_DELTA,
            tsMs = 1700000000001L,
            payload = buildJsonObject { put("text", "Hello") },
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent roundtrip with TOOL_CALL type`() {
        val event = StreamEvent(
            seq = 3L,
            type = StreamEventType.TOOL_CALL,
            tsMs = 1700000000002L,
            payload = buildJsonObject {
                put("name", "search")
                put("args", "{\"query\":\"test\"}")
            },
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent roundtrip with TOOL_RESULT type`() {
        val event = StreamEvent(
            seq = 4L,
            type = StreamEventType.TOOL_RESULT,
            tsMs = 1700000000003L,
            payload = buildJsonObject { put("result", "found 3 items") },
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent roundtrip with METRICS type`() {
        val event = StreamEvent(
            seq = 5L,
            type = StreamEventType.METRICS,
            tsMs = 1700000000004L,
            payload = buildJsonObject {
                put("tokens_per_sec", 42.5)
                put("total_tokens", 128)
            },
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent roundtrip with ERROR type`() {
        val event = StreamEvent(
            seq = 6L,
            type = StreamEventType.ERROR,
            tsMs = 1700000000005L,
            payload = buildJsonObject { put("message", "OOM") },
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent roundtrip with DONE type`() {
        val event = StreamEvent(
            seq = 7L,
            type = StreamEventType.DONE,
            tsMs = 1700000000006L,
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent with empty payload serializes correctly`() {
        val event = StreamEvent(seq = 0L, type = "test", tsMs = 0L)
        assertEquals(JsonObject(emptyMap()), event.payload)

        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
        assertEquals(JsonObject(emptyMap()), decoded.payload)
    }

    @Test
    fun `StreamEvent with complex nested payload roundtrips`() {
        val nestedPayload = buildJsonObject {
            put("outer", buildJsonObject {
                put("inner", buildJsonObject {
                    put("deep_value", 99)
                })
                put("list_hint", "three items")
            })
            put("flag", true)
            put("count", 7)
        }
        val event = StreamEvent(seq = 10L, type = "complex", tsMs = 12345L, payload = nestedPayload)
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString(StreamEvent.serializer(), encoded)
        assertEquals(event, decoded)
    }

    @Test
    fun `StreamEvent deserialization ignores unknown keys`() {
        val rawJson = """
            {"seq":1,"type":"start","tsMs":100,"payload":{},"unknownField":"surprise"}
        """.trimIndent()
        val decoded = json.decodeFromString(StreamEvent.serializer(), rawJson)
        assertEquals(1L, decoded.seq)
        assertEquals("start", decoded.type)
        assertEquals(100L, decoded.tsMs)
    }

    @Test
    fun `StreamEvent payload defaults to empty object when omitted in JSON`() {
        val rawJson = """{"seq":1,"type":"done","tsMs":999}"""
        val decoded = json.decodeFromString(StreamEvent.serializer(), rawJson)
        assertEquals(JsonObject(emptyMap()), decoded.payload)
    }

    // ── StreamHeader ─────────────────────────────────────────────────────

    @Test
    fun `StreamHeader has default protocol version`() {
        val header = StreamHeader(requestId = "req-1")
        assertEquals("mindlayer.stream.v1", header.protocol)
    }

    @Test
    fun `StreamHeader serialization roundtrip with default protocol`() {
        val header = StreamHeader(requestId = "abc-123")
        val encoded = json.encodeToString(StreamHeader.serializer(), header)
        val decoded = json.decodeFromString(StreamHeader.serializer(), encoded)
        assertEquals(header, decoded)
        assertEquals("mindlayer.stream.v1", decoded.protocol)
        assertEquals("abc-123", decoded.requestId)
    }

    @Test
    fun `StreamHeader serialization roundtrip with custom protocol`() {
        val header = StreamHeader(protocol = "mindlayer.stream.v2", requestId = "req-42")
        val encoded = json.encodeToString(StreamHeader.serializer(), header)
        val decoded = json.decodeFromString(StreamHeader.serializer(), encoded)
        assertEquals("mindlayer.stream.v2", decoded.protocol)
        assertEquals("req-42", decoded.requestId)
    }

    @Test
    fun `StreamHeader deserialization ignores unknown keys`() {
        val rawJson = """{"protocol":"mindlayer.stream.v1","requestId":"x","extra":true}"""
        val decoded = json.decodeFromString(StreamHeader.serializer(), rawJson)
        assertNotNull(decoded)
        assertEquals("x", decoded.requestId)
    }

    @Test
    fun `StreamHeader JSON contains expected values in encoded string`() {
        // protocol has a default, so use encodeDefaults to ensure it appears
        val jsonWithDefaults = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val header = StreamHeader(requestId = "check-keys")
        val encoded = jsonWithDefaults.encodeToString(StreamHeader.serializer(), header)
        assertTrue("Encoded JSON should contain the protocol value", encoded.contains("mindlayer.stream.v1"))
        assertTrue("Encoded JSON should contain the requestId value", encoded.contains("check-keys"))
    }
}
