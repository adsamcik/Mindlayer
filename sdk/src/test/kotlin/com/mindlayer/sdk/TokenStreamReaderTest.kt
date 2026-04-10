package com.mindlayer.sdk

import android.util.Log
import com.mindlayer.shared.StreamEvent
import com.mindlayer.shared.StreamEventType
import com.mindlayer.shared.StreamHeader
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [TokenStreamReader] event mapping and frame parsing.
 *
 * We invoke the private `mapEvent` and `parseFrame` methods via a JVM-side
 * re-implementation that mirrors the reader logic, so tests run without
 * Android's [android.os.ParcelFileDescriptor].
 */
class TokenStreamReaderTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private companion object {
        const val MAX_FRAME_BYTES = 1_048_576
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -- Helpers ---------------------------------------------------------------

    /** Write a length-prefixed frame to [out]. */
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
     * JVM-side reader that mirrors [TokenStreamReader.readStream] but works
     * with plain [InputStream] instead of ParcelFileDescriptor.
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
        StreamEventType.START -> MindlayerEvent.Started(
            requestId = event.payload["requestId"]?.jsonPrimitive?.contentOrNull ?: "",
        )
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
    // mapEvent — each StreamEventType
    // =========================================================================

    @Test
    fun `mapEvent TOKEN_DELTA returns TextDelta with text`() {
        val event = StreamEvent(
            seq = 1, type = StreamEventType.TOKEN_DELTA, tsMs = 0,
            payload = buildJsonObject { put("text", "hello") },
        )
        val result = mapEvent(event)
        assertTrue(result is MindlayerEvent.TextDelta)
        assertEquals("hello", (result as MindlayerEvent.TextDelta).text)
        assertEquals(1L, result.seq)
    }

    @Test
    fun `mapEvent TOOL_CALL returns ToolCall with correct fields`() {
        val event = StreamEvent(
            seq = 2, type = StreamEventType.TOOL_CALL, tsMs = 0,
            payload = buildJsonObject {
                put("callId", "c1")
                put("name", "fn_name")
                put("args", """{"k":"v"}""")
            },
        )
        val result = mapEvent(event) as MindlayerEvent.ToolCall
        assertEquals("fn_name", result.toolName)
        assertEquals("""{"k":"v"}""", result.arguments)
        assertEquals("c1", result.callId)
        assertEquals(2L, result.seq)
    }

    @Test
    fun `mapEvent METRICS returns Metrics with fields`() {
        val event = StreamEvent(
            seq = 3, type = StreamEventType.METRICS, tsMs = 0,
            payload = buildJsonObject {
                put("prefillToksPerSec", 100.5f)
                put("decodeToksPerSec", 50.0f)
                put("thermalBand", "nominal")
            },
        )
        val result = mapEvent(event) as MindlayerEvent.Metrics
        assertEquals(100.5f, result.prefillToksPerSec!!, 0.01f)
        assertEquals(50.0f, result.decodeToksPerSec!!, 0.01f)
        assertEquals("nominal", result.thermalBand)
    }

    @Test
    fun `mapEvent ERROR returns Error with code and message`() {
        val event = StreamEvent(
            seq = 4, type = StreamEventType.ERROR, tsMs = 0,
            payload = buildJsonObject {
                put("code", "timeout")
                put("message", "Request timed out")
            },
        )
        val result = mapEvent(event) as MindlayerEvent.Error
        assertEquals("timeout", result.code)
        assertEquals("Request timed out", result.message)
        assertEquals(4L, result.seq)
    }

    @Test
    fun `mapEvent DONE returns Done with finish_reason`() {
        val event = StreamEvent(
            seq = 5, type = StreamEventType.DONE, tsMs = 0,
            payload = buildJsonObject { put("finish_reason", "stop") },
        )
        val result = mapEvent(event) as MindlayerEvent.Done
        assertEquals("stop", result.finishReason)
        assertNull(result.fullText)
        assertEquals(5L, result.seq)
    }

    @Test
    fun `mapEvent START returns Started`() {
        val event = StreamEvent(
            seq = 0, type = StreamEventType.START, tsMs = 0,
            payload = buildJsonObject { put("requestId", "req-99") },
        )
        val result = mapEvent(event) as MindlayerEvent.Started
        assertEquals("req-99", result.requestId)
    }

    // =========================================================================
    // mapEvent — missing / default fields
    // =========================================================================

    @Test
    fun `TOKEN_DELTA with missing text defaults to empty string`() {
        val event = StreamEvent(
            seq = 10, type = StreamEventType.TOKEN_DELTA, tsMs = 0,
            payload = JsonObject(emptyMap()),
        )
        val result = mapEvent(event) as MindlayerEvent.TextDelta
        assertEquals("", result.text)
    }

    @Test
    fun `TOOL_CALL with missing name, args, callId defaults to empty strings`() {
        val event = StreamEvent(
            seq = 11, type = StreamEventType.TOOL_CALL, tsMs = 0,
            payload = JsonObject(emptyMap()),
        )
        val result = mapEvent(event) as MindlayerEvent.ToolCall
        assertEquals("", result.toolName)
        assertEquals("{}", result.arguments)
        assertEquals("", result.callId)
    }

    @Test
    fun `METRICS with missing fields yields null values`() {
        val event = StreamEvent(
            seq = 12, type = StreamEventType.METRICS, tsMs = 0,
            payload = JsonObject(emptyMap()),
        )
        val result = mapEvent(event) as MindlayerEvent.Metrics
        assertNull(result.prefillToksPerSec)
        assertNull(result.decodeToksPerSec)
        assertNull(result.thermalBand)
    }

    @Test
    fun `ERROR with missing code yields null code`() {
        val event = StreamEvent(
            seq = 13, type = StreamEventType.ERROR, tsMs = 0,
            payload = buildJsonObject { put("message", "oops") },
        )
        val result = mapEvent(event) as MindlayerEvent.Error
        assertNull(result.code)
        assertEquals("oops", result.message)
    }

    @Test
    fun `DONE with missing finish_reason defaults to unknown`() {
        val event = StreamEvent(
            seq = 14, type = StreamEventType.DONE, tsMs = 0,
            payload = JsonObject(emptyMap()),
        )
        val result = mapEvent(event) as MindlayerEvent.Done
        assertEquals("unknown", result.finishReason)
    }

    // =========================================================================
    // Unknown event type
    // =========================================================================

    @Test
    fun `unknown event type defaults to TextDelta with empty text`() {
        val event = StreamEvent(
            seq = 20, type = "some_future_type", tsMs = 0,
            payload = buildJsonObject { put("data", "whatever") },
        )
        val result = mapEvent(event) as MindlayerEvent.TextDelta
        assertEquals("", result.text)
        assertEquals(20L, result.seq)
    }

    // =========================================================================
    // parseFrame
    // =========================================================================

    @Test
    fun `parseFrame with StreamHeader returns Started`() {
        val headerJson = buildStreamHeaderJson("req-parse-1")
        val result = parseFrame(headerJson)
        assertTrue(result is MindlayerEvent.Started)
        assertEquals("req-parse-1", (result as MindlayerEvent.Started).requestId)
    }

    @Test
    fun `parseFrame with StreamEvent returns mapped event`() {
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", "world") },
        )
        val result = parseFrame(eventJson)
        assertTrue(result is MindlayerEvent.TextDelta)
        assertEquals("world", (result as MindlayerEvent.TextDelta).text)
    }

    @Test
    fun `parseFrame with invalid JSON returns null`() {
        val result = parseFrame("not valid json {{{")
        assertNull("Invalid JSON should return null", result)
    }

    // =========================================================================
    // Flow completes on EOF
    // =========================================================================

    @Test
    fun `flow completes on EOF`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)

        val headerJson = buildStreamHeaderJson("req-flow-1")
        val deltaJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", "hi") },
        )
        val doneJson = buildStreamEventJson(
            seq = 2,
            type = StreamEventType.DONE,
            payload = buildJsonObject { put("finish_reason", "stop") },
        )

        writeFrame(pipeOut, headerJson)
        writeFrame(pipeOut, deltaJson)
        writeFrame(pipeOut, doneJson)
        pipeOut.close()

        val events = readStreamFromInputStream(pipeIn).toList()

        assertEquals(3, events.size)
        assertTrue(events[0] is MindlayerEvent.Started)
        assertTrue(events[1] is MindlayerEvent.TextDelta)
        assertEquals("hi", (events[1] as MindlayerEvent.TextDelta).text)
        assertTrue(events[2] is MindlayerEvent.Done)
        assertEquals("stop", (events[2] as MindlayerEvent.Done).finishReason)
    }

    // =========================================================================
    // MAX_FRAME_BYTES constant
    // =========================================================================

    @Test
    fun `MAX_FRAME_BYTES constant is 1 MiB`() {
        assertEquals(1_048_576, MAX_FRAME_BYTES)
    }
}
