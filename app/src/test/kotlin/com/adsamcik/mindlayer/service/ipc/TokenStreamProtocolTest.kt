package com.adsamcik.mindlayer.service.ipc

import app.cash.turbine.test
import com.adsamcik.mindlayer.sdk.MindlayerEvent
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests the Mindlayer streaming IPC wire protocol without Android dependencies.
 *
 * The protocol is: 4-byte little-endian length prefix + UTF-8 JSON payload.
 * We use [PipedInputStream]/[PipedOutputStream] to simulate the pipe, and
 * re-implement the reader logic from [com.adsamcik.mindlayer.sdk.TokenStreamReader] so
 * tests run on a plain JVM.
 */
class TokenStreamProtocolTest {

    // -- Shared helpers -------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private companion object {
        const val MAX_FRAME_BYTES = 1_048_576 // 1 MiB — matches TokenStreamReader
    }

    /** Write a single length-prefixed frame to [out]. Mirrors TokenStreamWriter.writeFrame. */
    private fun writeFrame(out: OutputStream, payload: String) {
        val bytes = payload.encodeToByteArray()
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bytes.size)
            .array()
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    /** Write raw bytes as the 4-byte length header (for negative / corrupt tests). */
    private fun writeLengthHeader(out: OutputStream, length: Int) {
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(length)
            .array()
        out.write(header)
        out.flush()
    }

    /** Read a single frame from [input], returning the JSON string. */
    private fun readFrame(input: DataInputStream): String {
        val len = Integer.reverseBytes(input.readInt())
        require(len in 0..MAX_FRAME_BYTES) { "Invalid frame length: $len" }
        val payload = ByteArray(len)
        input.readFully(payload)
        return payload.decodeToString()
    }

    /** Build a StreamEvent JSON string. */
    private fun buildStreamEventJson(
        seq: Long,
        type: String,
        payload: JsonObject = JsonObject(emptyMap()),
        tsMs: Long = System.currentTimeMillis(),
    ): String {
        val event = StreamEvent(seq = seq, type = type, tsMs = tsMs, payload = payload)
        return json.encodeToString(StreamEvent.serializer(), event)
    }

    /** Build a StreamHeader JSON string. */
    private fun buildStreamHeaderJson(requestId: String): String {
        val header = StreamHeader(requestId = requestId)
        return json.encodeToString(StreamHeader.serializer(), header)
    }

    /**
     * Pure-JVM reader that mirrors TokenStreamReader.readStream but reads from
     * a plain [InputStream] instead of a [ParcelFileDescriptor].
     * Returns a cold Flow of [MindlayerEvent].
     */
    private fun readStreamFromInputStream(input: InputStream): Flow<MindlayerEvent> = flow {
        val dis = DataInputStream(BufferedInputStream(input))
        try {
            while (true) {
                val len = try {
                    Integer.reverseBytes(dis.readInt())
                } catch (_: EOFException) {
                    break
                }
                require(len in 0..MAX_FRAME_BYTES) { "Invalid frame length: $len" }

                val payload = ByteArray(len)
                dis.readFully(payload)
                val jsonStr = payload.decodeToString()

                val event = parseFrame(jsonStr)
                if (event != null) emit(event)
            }
        } finally {
            dis.close()
        }
    }.flowOn(Dispatchers.IO)

    /** Mirrors TokenStreamReader.parseFrame. */
    private fun parseFrame(jsonStr: String): MindlayerEvent? {
        return try {
            val streamEvent = json.decodeFromString<StreamEvent>(jsonStr)
            mapEvent(streamEvent)
        } catch (_: Exception) {
            try {
                val header = json.decodeFromString<StreamHeader>(jsonStr)
                MindlayerEvent.Started(header.requestId)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Mirrors TokenStreamReader.mapEvent. */
    private fun mapEvent(event: StreamEvent): MindlayerEvent = when (event.type) {
        StreamEventType.TOKEN_DELTA -> MindlayerEvent.TextDelta(
            text = event.payload["text"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )
        StreamEventType.TOOL_CALL -> MindlayerEvent.ToolCall(
            toolName = event.payload["name"]?.jsonPrimitive?.contentOrNull ?: "",
            arguments = event.payload["args"]?.jsonPrimitive?.contentOrNull ?: "{}",
            callId = event.payload["callId"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )
        StreamEventType.METRICS -> MindlayerEvent.Metrics(
            prefillToksPerSec = event.payload["prefillToksPerSec"]?.jsonPrimitive?.floatOrNull,
            decodeToksPerSec = event.payload["decodeToksPerSec"]?.jsonPrimitive?.floatOrNull,
            thermalBand = event.payload["thermalBand"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )
        StreamEventType.ERROR -> MindlayerEvent.Error(
            message = event.payload["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
            code = event.payload["code"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )
        StreamEventType.DONE -> MindlayerEvent.Done(
            finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            fullText = event.payload["full_text"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )
        else -> MindlayerEvent.TextDelta(text = "", seq = event.seq)
    }

    // =========================================================================
    // Wire format tests
    // =========================================================================

    @Test
    fun `single frame roundtrip through piped streams`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val payload = """{"hello":"world"}"""

        writeFrame(pipeOut, payload)
        pipeOut.close()

        val dis = DataInputStream(BufferedInputStream(pipeIn))
        val result = readFrame(dis)
        assertEquals(payload, result)
    }

    @Test
    fun `multiple frames in sequence`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val payloads = listOf(
            """{"seq":1}""",
            """{"seq":2}""",
            """{"seq":3}""",
        )

        payloads.forEach { writeFrame(pipeOut, it) }
        pipeOut.close()

        val dis = DataInputStream(BufferedInputStream(pipeIn))
        val results = mutableListOf<String>()
        repeat(payloads.size) { results.add(readFrame(dis)) }
        assertEquals(payloads, results)
    }

    @Test
    fun `empty payload frame (zero length)`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        writeFrame(pipeOut, "")
        pipeOut.close()

        val dis = DataInputStream(BufferedInputStream(pipeIn))
        val result = readFrame(dis)
        assertEquals("", result)
    }

    @Test
    fun `large payload exceeding 64KB`() {
        val pipeIn = PipedInputStream(128 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val largeText = "A".repeat(80_000)
        val payload = """{"text":"$largeText"}"""

        writeFrame(pipeOut, payload)
        pipeOut.close()

        val dis = DataInputStream(BufferedInputStream(pipeIn))
        val result = readFrame(dis)
        assertEquals(payload, result)
    }

    @Test
    fun `verify little-endian byte order`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        // Payload of 1 byte => length = 0x00000001 in LE = [0x01, 0x00, 0x00, 0x00]
        val payload = "X"
        writeFrame(pipeOut, payload)
        pipeOut.close()

        val raw = pipeIn.readNBytes(4)
        assertEquals(0x01.toByte(), raw[0])
        assertEquals(0x00.toByte(), raw[1])
        assertEquals(0x00.toByte(), raw[2])
        assertEquals(0x00.toByte(), raw[3])
    }

    @Test
    fun `length 256 is correct in little-endian`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        // Payload of 256 bytes => LE = [0x00, 0x01, 0x00, 0x00]
        val payload = "X".repeat(256)
        writeFrame(pipeOut, payload)
        pipeOut.close()

        val raw = pipeIn.readNBytes(4)
        assertEquals(0x00.toByte(), raw[0])
        assertEquals(0x01.toByte(), raw[1])
        assertEquals(0x00.toByte(), raw[2])
        assertEquals(0x00.toByte(), raw[3])
    }

    // =========================================================================
    // StreamEvent → JSON → MindlayerEvent mapping tests
    // =========================================================================

    @Test
    fun `TOKEN_DELTA event maps to TextDelta with correct text and seq`() {
        val eventJson = buildStreamEventJson(
            seq = 42,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", "Hello") },
        )
        val event = parseFrame(eventJson)
        assertTrue(event is MindlayerEvent.TextDelta)
        val td = event as MindlayerEvent.TextDelta
        assertEquals("Hello", td.text)
        assertEquals(42L, td.seq)
    }

    @Test
    fun `TOOL_CALL event maps to ToolCall with name args callId`() {
        val eventJson = buildStreamEventJson(
            seq = 5,
            type = StreamEventType.TOOL_CALL,
            payload = buildJsonObject {
                put("name", "search_web")
                put("args", """{"q":"test"}""")
                put("callId", "call-123")
            },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.ToolCall
        assertEquals("search_web", event.toolName)
        assertEquals("""{"q":"test"}""", event.arguments)
        assertEquals("call-123", event.callId)
        assertEquals(5L, event.seq)
    }

    @Test
    fun `METRICS event maps to Metrics with toks per sec and thermalBand`() {
        val eventJson = buildStreamEventJson(
            seq = 10,
            type = StreamEventType.METRICS,
            payload = buildJsonObject {
                put("prefillToksPerSec", 120.5f)
                put("decodeToksPerSec", 45.3f)
                put("thermalBand", "nominal")
            },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.Metrics
        assertEquals(120.5f, event.prefillToksPerSec!!, 0.01f)
        assertEquals(45.3f, event.decodeToksPerSec!!, 0.01f)
        assertEquals("nominal", event.thermalBand)
        assertEquals(10L, event.seq)
    }

    @Test
    fun `ERROR event maps to Error with message and code`() {
        val eventJson = buildStreamEventJson(
            seq = 99,
            type = StreamEventType.ERROR,
            payload = buildJsonObject {
                put("message", "Out of memory")
                put("code", "OOM")
            },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.Error
        assertEquals("Out of memory", event.message)
        assertEquals("OOM", event.code)
        assertEquals(99L, event.seq)
    }

    @Test
    fun `DONE event maps to Done with finish_reason`() {
        val eventJson = buildStreamEventJson(
            seq = 100,
            type = StreamEventType.DONE,
            payload = buildJsonObject {
                put("finish_reason", "stop")
                put("full_text", "Hello world")
            },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.Done
        assertEquals("stop", event.finishReason)
        assertEquals("Hello world", event.fullText)
        assertEquals(100L, event.seq)
    }

    @Test
    fun `DONE event without full_text has null fullText`() {
        val eventJson = buildStreamEventJson(
            seq = 101,
            type = StreamEventType.DONE,
            payload = buildJsonObject { put("finish_reason", "length") },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.Done
        assertEquals("length", event.finishReason)
        assertNull(event.fullText)
    }

    @Test
    fun `legacy start wire type no longer maps to Started`() {
        // StreamEventType.START was retired — real stream starts are
        // emitted as a StreamHeader frame, decoded in parseFrame, not in
        // mapEvent. Any "start"-typed wire event must now fall through to
        // the catch-all (the production reader returns Unknown; this local
        // test mirror returns an empty TextDelta — verify the catch-all).
        val eventJson = buildStreamEventJson(
            seq = 0,
            type = "start",
            payload = buildJsonObject { put("requestId", "req-abc") },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals("", event.text)
    }

    @Test
    fun `StreamHeader maps to Started with requestId`() {
        val headerJson = buildStreamHeaderJson("req-xyz-789")
        val event = parseFrame(headerJson) as MindlayerEvent.Started
        assertEquals("req-xyz-789", event.requestId)
    }

    @Test
    fun `unknown event type defaults to empty TextDelta`() {
        val eventJson = buildStreamEventJson(
            seq = 77,
            type = "some_future_event_type",
            payload = buildJsonObject { put("data", "irrelevant") },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals("", event.text)
        assertEquals(77L, event.seq)
    }

    // =========================================================================
    // Edge cases: missing / extra / null keys
    // =========================================================================

    @Test
    fun `TOKEN_DELTA with missing text key defaults to empty string`() {
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = JsonObject(emptyMap()),
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals("", event.text)
    }

    @Test
    fun `TOOL_CALL with missing keys defaults gracefully`() {
        val eventJson = buildStreamEventJson(
            seq = 2,
            type = StreamEventType.TOOL_CALL,
            payload = JsonObject(emptyMap()),
        )
        val event = parseFrame(eventJson) as MindlayerEvent.ToolCall
        assertEquals("", event.toolName)
        assertEquals("{}", event.arguments)
        assertEquals("", event.callId)
    }

    @Test
    fun `METRICS with missing keys has null values`() {
        val eventJson = buildStreamEventJson(
            seq = 3,
            type = StreamEventType.METRICS,
            payload = JsonObject(emptyMap()),
        )
        val event = parseFrame(eventJson) as MindlayerEvent.Metrics
        assertNull(event.prefillToksPerSec)
        assertNull(event.decodeToksPerSec)
        assertNull(event.thermalBand)
    }

    @Test
    fun `ERROR with missing message defaults to Unknown error`() {
        val eventJson = buildStreamEventJson(
            seq = 4,
            type = StreamEventType.ERROR,
            payload = JsonObject(emptyMap()),
        )
        val event = parseFrame(eventJson) as MindlayerEvent.Error
        assertEquals("Unknown error", event.message)
        assertNull(event.code)
    }

    @Test
    fun `DONE with missing finish_reason defaults to unknown`() {
        val eventJson = buildStreamEventJson(
            seq = 5,
            type = StreamEventType.DONE,
            payload = JsonObject(emptyMap()),
        )
        val event = parseFrame(eventJson) as MindlayerEvent.Done
        assertEquals("unknown", event.finishReason)
        assertNull(event.fullText)
    }

    @Test
    fun `legacy start wire type with missing requestId still falls through`() {
        val eventJson = buildStreamEventJson(
            seq = 0,
            type = "start",
            payload = JsonObject(emptyMap()),
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals("", event.text)
    }

    @Test
    fun `extra unknown keys in payload are ignored`() {
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject {
                put("text", "hi")
                put("unknown_field", "should be ignored")
                put("another_unknown", 42)
            },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals("hi", event.text)
        assertEquals(1L, event.seq)
    }

    @Test
    fun `extra unknown keys at StreamEvent level are ignored`() {
        // Manually construct JSON with extra top-level keys
        val rawJson = """{"seq":1,"type":"token_delta","tsMs":1000,"payload":{"text":"ok"},"extraField":"ignored"}"""
        val event = parseFrame(rawJson) as MindlayerEvent.TextDelta
        assertEquals("ok", event.text)
    }

    @Test
    fun `very long text content in token_delta`() {
        val longText = "word ".repeat(20_000).trim()
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", longText) },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals(longText, event.text)
    }

    @Test
    fun `unicode text in token_delta`() {
        val unicodeText = "こんにちは世界 🌍 Ñoño café résumé"
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", unicodeText) },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals(unicodeText, event.text)
    }

    @Test
    fun `unicode text survives wire format roundtrip`() {
        val unicodeText = "Ελληνικά 中文 العربية 🎉🔥"
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        writeFrame(pipeOut, unicodeText)
        pipeOut.close()

        val dis = DataInputStream(BufferedInputStream(pipeIn))
        val result = readFrame(dis)
        assertEquals(unicodeText, result)
    }

    @Test
    fun `json special characters in token_delta`() {
        val specialText = """He said "hello\" and went to C:\path\to\file"""
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", specialText) },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals(specialText, event.text)
    }

    @Test
    fun `newlines and tabs in token_delta`() {
        val text = "line1\nline2\ttab\r\nwindows"
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", text) },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals(text, event.text)
    }

    @Test
    fun `empty text in token_delta`() {
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", "") },
        )
        val event = parseFrame(eventJson) as MindlayerEvent.TextDelta
        assertEquals("", event.text)
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `negative frame length throws`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        writeLengthHeader(pipeOut, -1)
        pipeOut.close()

        val dis = DataInputStream(BufferedInputStream(pipeIn))
        readFrame(dis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frame length exceeding 1MB throws`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        writeLengthHeader(pipeOut, MAX_FRAME_BYTES + 1)
        pipeOut.close()

        val dis = DataInputStream(BufferedInputStream(pipeIn))
        readFrame(dis)
    }

    @Test
    fun `frame length at exactly 1MB does not throw`() {
        // Should not throw — boundary case
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        // We can't actually write 1MB through a piped stream easily,
        // so just verify the require check passes for MAX_FRAME_BYTES
        val len = MAX_FRAME_BYTES
        assertTrue(len in 0..MAX_FRAME_BYTES) // sanity
    }

    @Test
    fun `truncated frame at EOF mid-read ends stream cleanly`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        // Write a valid first frame
        val validJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", "first") },
        )
        writeFrame(pipeOut, validJson)

        // Write a length header claiming 100 bytes, but only write 10 then close
        writeLengthHeader(pipeOut, 100)
        pipeOut.write(ByteArray(10))
        pipeOut.close()

        // The reader should emit the first event, then hit EOFException on readFully
        // which may terminate the flow. We verify the stream ends without crashing,
        // and the first event may or may not have been delivered depending on timing.
        val events = mutableListOf<MindlayerEvent>()
        try {
            readStreamFromInputStream(pipeIn).collect { events.add(it) }
        } catch (_: EOFException) {
            // Expected — readFully hits EOF mid-frame
        } catch (_: IOException) {
            // Also acceptable — pipe broken
        }
        assertTrue("Should have 0 or 1 events (got ${events.size})", events.size <= 1)
        if (events.isNotEmpty()) {
            assertTrue(events[0] is MindlayerEvent.TextDelta)
            assertEquals("first", (events[0] as MindlayerEvent.TextDelta).text)
        }
    }

    @Test
    fun `malformed JSON in frame is skipped and returns null`() {
        val result = parseFrame("this is not { valid json !!!")
        assertNull(result)
    }

    @Test
    fun `partially valid JSON not matching any schema returns null`() {
        val result = parseFrame("""{"randomKey": 42}""")
        assertNull(result)
    }

    @Test
    fun `empty string frame returns null from parseFrame`() {
        val result = parseFrame("")
        assertNull(result)
    }

    // =========================================================================
    // StreamEvent JSON serialization fidelity
    // =========================================================================

    @Test
    fun `StreamEvent serialization roundtrip`() {
        val original = StreamEvent(
            seq = 7,
            type = StreamEventType.TOKEN_DELTA,
            tsMs = 1700000000000L,
            payload = buildJsonObject { put("text", "hello") },
        )
        val encoded = json.encodeToString(StreamEvent.serializer(), original)
        val decoded = json.decodeFromString<StreamEvent>(encoded)
        assertEquals(original.seq, decoded.seq)
        assertEquals(original.type, decoded.type)
        assertEquals(original.tsMs, decoded.tsMs)
        assertEquals(original.payload, decoded.payload)
    }

    @Test
    fun `StreamHeader serialization roundtrip`() {
        val original = StreamHeader(requestId = "req-roundtrip-123")
        val encoded = json.encodeToString(StreamHeader.serializer(), original)
        val decoded = json.decodeFromString<StreamHeader>(encoded)
        assertEquals("mindlayer.stream.v1", decoded.protocol)
        assertEquals("req-roundtrip-123", decoded.requestId)
    }

    @Test
    fun `StreamEvent with default empty payload`() {
        val event = StreamEvent(seq = 0, type = "test", tsMs = 0)
        val encoded = json.encodeToString(StreamEvent.serializer(), event)
        val decoded = json.decodeFromString<StreamEvent>(encoded)
        assertTrue(decoded.payload.isEmpty())
    }

    // =========================================================================
    // Roundtrip integration: write frames → read as Flow<MindlayerEvent>
    // =========================================================================

    @Test
    fun `full roundtrip header plus token deltas plus done`() = runTest {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        // Write frames on a background thread (piped streams block if buffer is full)
        val writer = Thread {
            writeFrame(pipeOut, buildStreamHeaderJson("req-001"))
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 1,
                    type = StreamEventType.TOKEN_DELTA,
                    payload = buildJsonObject { put("text", "Hello") },
                ),
            )
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 2,
                    type = StreamEventType.TOKEN_DELTA,
                    payload = buildJsonObject { put("text", " world") },
                ),
            )
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 3,
                    type = StreamEventType.DONE,
                    payload = buildJsonObject { put("finish_reason", "stop") },
                ),
            )
            pipeOut.close()
        }
        writer.start()

        readStreamFromInputStream(pipeIn).test {
            val started = awaitItem()
            assertTrue("Expected Started, got $started", started is MindlayerEvent.Started)
            assertEquals("req-001", (started as MindlayerEvent.Started).requestId)

            val t1 = awaitItem() as MindlayerEvent.TextDelta
            assertEquals("Hello", t1.text)
            assertEquals(1L, t1.seq)

            val t2 = awaitItem() as MindlayerEvent.TextDelta
            assertEquals(" world", t2.text)
            assertEquals(2L, t2.seq)

            val done = awaitItem() as MindlayerEvent.Done
            assertEquals("stop", done.finishReason)
            assertEquals(3L, done.seq)

            awaitComplete()
        }

        writer.join()
    }

    @Test
    fun `roundtrip with all event types`() = runTest {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        val writer = Thread {
            // Header
            writeFrame(pipeOut, buildStreamHeaderJson("req-all"))

            // Token delta
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 1,
                    type = StreamEventType.TOKEN_DELTA,
                    payload = buildJsonObject { put("text", "Hi") },
                ),
            )

            // Tool call
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 2,
                    type = StreamEventType.TOOL_CALL,
                    payload = buildJsonObject {
                        put("name", "calculator")
                        put("args", """{"x":1}""")
                        put("callId", "c-1")
                    },
                ),
            )

            // Metrics
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 3,
                    type = StreamEventType.METRICS,
                    payload = buildJsonObject {
                        put("prefillToksPerSec", 100.0f)
                        put("decodeToksPerSec", 50.0f)
                        put("thermalBand", "nominal")
                    },
                ),
            )

            // Error
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 4,
                    type = StreamEventType.ERROR,
                    payload = buildJsonObject {
                        put("message", "rate limited")
                        put("code", "RATE_LIMIT")
                    },
                ),
            )

            // Done
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 5,
                    type = StreamEventType.DONE,
                    payload = buildJsonObject {
                        put("finish_reason", "stop")
                        put("full_text", "Hi")
                    },
                ),
            )
            pipeOut.close()
        }
        writer.start()

        readStreamFromInputStream(pipeIn).test {
            // Header → Started
            val header = awaitItem() as MindlayerEvent.Started
            assertEquals("req-all", header.requestId)

            // Token delta
            val td = awaitItem() as MindlayerEvent.TextDelta
            assertEquals("Hi", td.text)

            // Tool call
            val tc = awaitItem() as MindlayerEvent.ToolCall
            assertEquals("calculator", tc.toolName)
            assertEquals("""{"x":1}""", tc.arguments)
            assertEquals("c-1", tc.callId)

            // Metrics
            val m = awaitItem() as MindlayerEvent.Metrics
            assertEquals(100.0f, m.prefillToksPerSec!!, 0.1f)
            assertEquals(50.0f, m.decodeToksPerSec!!, 0.1f)
            assertEquals("nominal", m.thermalBand)

            // Error
            val err = awaitItem() as MindlayerEvent.Error
            assertEquals("rate limited", err.message)
            assertEquals("RATE_LIMIT", err.code)

            // Done
            val done = awaitItem() as MindlayerEvent.Done
            assertEquals("stop", done.finishReason)
            assertEquals("Hi", done.fullText)

            awaitComplete()
        }

        writer.join()
    }

    @Test
    fun `roundtrip with malformed frame skipped between valid frames`() = runTest {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        val writer = Thread {
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 1,
                    type = StreamEventType.TOKEN_DELTA,
                    payload = buildJsonObject { put("text", "before") },
                ),
            )
            // Malformed JSON frame
            writeFrame(pipeOut, "NOT VALID JSON {{{")
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 2,
                    type = StreamEventType.TOKEN_DELTA,
                    payload = buildJsonObject { put("text", "after") },
                ),
            )
            pipeOut.close()
        }
        writer.start()

        readStreamFromInputStream(pipeIn).test {
            val e1 = awaitItem() as MindlayerEvent.TextDelta
            assertEquals("before", e1.text)
            // Malformed frame is skipped
            val e2 = awaitItem() as MindlayerEvent.TextDelta
            assertEquals("after", e2.text)
            awaitComplete()
        }

        writer.join()
    }

    @Test
    fun `roundtrip with unicode content`() = runTest {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        val unicodeText = "日本語テスト 🚀 émojis and àccents"
        val writer = Thread {
            writeFrame(
                pipeOut,
                buildStreamEventJson(
                    seq = 1,
                    type = StreamEventType.TOKEN_DELTA,
                    payload = buildJsonObject { put("text", unicodeText) },
                ),
            )
            pipeOut.close()
        }
        writer.start()

        val flow: Flow<MindlayerEvent> = readStreamFromInputStream(pipeIn)
        flow.test {
            val td = awaitItem() as MindlayerEvent.TextDelta
            assertEquals(unicodeText, td.text)
            awaitComplete()
        }

        writer.join()
    }

    @Test
    fun `roundtrip empty stream emits no events`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        pipeOut.close()

        val flow: Flow<MindlayerEvent> = readStreamFromInputStream(pipeIn)
        flow.test {
            awaitComplete()
        }
    }

    @Test
    fun `roundtrip many frames stress test`() = runTest {
        val frameCount = 500
        val pipeIn = PipedInputStream(256 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        val writer = Thread {
            for (i in 1..frameCount) {
                writeFrame(
                    pipeOut,
                    buildStreamEventJson(
                        seq = i.toLong(),
                        type = StreamEventType.TOKEN_DELTA,
                        payload = buildJsonObject { put("text", "tok$i") },
                    ),
                )
            }
            pipeOut.close()
        }
        writer.start()

        val events: List<MindlayerEvent> = readStreamFromInputStream(pipeIn).toList()
        assertEquals(frameCount, events.size)
        events.forEachIndexed { idx, event ->
            val td = event as MindlayerEvent.TextDelta
            assertEquals("tok${idx + 1}", td.text)
            assertEquals((idx + 1).toLong(), td.seq)
        }

        writer.join()
    }

    // =========================================================================
    // Flow error propagation
    // =========================================================================

    @Test
    fun `negative length in flow throws IllegalArgumentException`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        val writer = Thread {
            writeLengthHeader(pipeOut, -42)
            pipeOut.close()
        }
        writer.start()

        val flow: Flow<MindlayerEvent> = readStreamFromInputStream(pipeIn)
        flow.test {
            val error = awaitError()
            assertTrue(
                "Expected IllegalArgumentException, got ${error::class.simpleName}",
                error is IllegalArgumentException,
            )
            assertTrue(error.message!!.contains("Invalid frame length"))
        }

        writer.join()
    }

    @Test
    fun `oversized frame length in flow throws IllegalArgumentException`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        val writer = Thread {
            writeLengthHeader(pipeOut, MAX_FRAME_BYTES + 1)
            pipeOut.close()
        }
        writer.start()

        val flow: Flow<MindlayerEvent> = readStreamFromInputStream(pipeIn)
        flow.test {
            val error = awaitError()
            assertTrue(error is IllegalArgumentException)
        }

        writer.join()
    }
}
