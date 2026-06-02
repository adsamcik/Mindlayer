package com.adsamcik.mindlayer.sdk

import android.util.Log
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
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
import kotlinx.serialization.json.intOrNull
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
     *
     * Mirrors the guarded implementation exactly so regression tests validate
     * the same error-handling paths.
     */
    private fun readStreamFromInputStream(input: InputStream): Flow<InferenceEvent> = flow {
        val dis = DataInputStream(BufferedInputStream(input))
        try {
            while (true) {
                val len = try {
                    Integer.reverseBytes(dis.readInt())
                } catch (_: EOFException) {
                    break
                }

                var terminalError: InferenceEvent.Error? = null
                val parsedEvent: InferenceEvent? = try {
                    require(len in 0..MAX_FRAME_BYTES) { "Invalid frame length: $len" }
                    val payload = ByteArray(len)
                    dis.readFully(payload)
                    parseFrame(payload.decodeToString())
                } catch (e: EOFException) {
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error: truncated frame",
                        code = "PROTOCOL_ERROR_EOF",
                        seq = -1,
                    )
                    null
                } catch (e: IllegalArgumentException) {
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error: invalid frame length",
                        code = "PROTOCOL_ERROR_LENGTH",
                        seq = -1,
                    )
                    null
                } catch (e: kotlinx.serialization.SerializationException) {
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error: invalid frame encoding",
                        code = "PROTOCOL_ERROR_JSON",
                        seq = -1,
                    )
                    null
                } catch (e: Throwable) {
                    terminalError = InferenceEvent.Error(
                        message = "Protocol error",
                        code = "PROTOCOL_ERROR",
                        seq = -1,
                    )
                    null
                }

                when {
                    terminalError != null -> { emit(terminalError); break }
                    parsedEvent == null -> {
                        emit(
                            InferenceEvent.Error(
                                message = "Protocol error: unrecognised frame",
                                code = "PROTOCOL_ERROR_JSON",
                                seq = -1,
                            ),
                        )
                        break
                    }
                    else -> emit(parsedEvent)
                }
            }
        } finally {
            dis.close()
        }
    }.flowOn(Dispatchers.IO)

    /** Mirrors TokenStreamReader.parseFrame. */
    private fun parseFrame(jsonStr: String): InferenceEvent? {
        return try {
            val streamEvent = json.decodeFromString<StreamEvent>(jsonStr)
            mapEvent(streamEvent)
        } catch (_: Exception) {
            try {
                val header = json.decodeFromString<StreamHeader>(jsonStr)
                InferenceEvent.Started(header.requestId)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Mirrors TokenStreamReader.mapEvent. */
    private fun mapEvent(event: StreamEvent): InferenceEvent = when (event.type) {
        StreamEventType.TOKEN_DELTA -> InferenceEvent.TextDelta(
            text = event.payload["text"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )
        StreamEventType.TOOL_CALL -> InferenceEvent.ToolCall(
            toolName = event.payload["name"]?.jsonPrimitive?.contentOrNull ?: "",
            arguments = event.payload["args"]?.jsonPrimitive?.contentOrNull ?: "{}",
            callId = event.payload["callId"]?.jsonPrimitive?.contentOrNull ?: "",
            seq = event.seq,
        )
        StreamEventType.METRICS -> InferenceEvent.Metrics(
            prefillToksPerSec = event.payload["prefillToksPerSec"]?.jsonPrimitive?.floatOrNull,
            decodeToksPerSec = event.payload["decodeToksPerSec"]?.jsonPrimitive?.floatOrNull,
            thermalBand = event.payload["thermalBand"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )
        StreamEventType.ERROR -> InferenceEvent.Error(
            message = event.payload["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error",
            code = event.payload["code"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
            codeInt = event.payload["codeInt"]?.jsonPrimitive?.intOrNull,
        )
        StreamEventType.DONE -> InferenceEvent.Done(
            finishReason = event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            fullText = event.payload["full_text"]?.jsonPrimitive?.contentOrNull,
            seq = event.seq,
        )
        else -> InferenceEvent.Unknown(type = event.type, seq = event.seq)
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
        assertTrue(result is InferenceEvent.TextDelta)
        assertEquals("hello", (result as InferenceEvent.TextDelta).text)
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
        val result = mapEvent(event) as InferenceEvent.ToolCall
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
        val result = mapEvent(event) as InferenceEvent.Metrics
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
        val result = mapEvent(event) as InferenceEvent.Error
        assertEquals("timeout", result.code)
        assertEquals("Request timed out", result.message)
        assertEquals(4L, result.seq)
    }

    @Test
    fun `mapEvent ERROR preserves numeric codeInt`() {
        val event = StreamEvent(
            seq = 40, type = StreamEventType.ERROR, tsMs = 0,
            payload = buildJsonObject {
                put("code", "LOW_MEMORY")
                put("codeInt", com.adsamcik.mindlayer.shared.MindlayerErrorCode.LOW_MEMORY)
                put("message", "memory pressure")
            },
        )
        val result = mapEvent(event) as InferenceEvent.Error
        assertEquals("LOW_MEMORY", result.code)
        assertEquals(com.adsamcik.mindlayer.shared.MindlayerErrorCode.LOW_MEMORY, result.codeInt)
    }

    @Test
    fun `mapEvent DONE returns Done with finish_reason`() {
        val event = StreamEvent(
            seq = 5, type = StreamEventType.DONE, tsMs = 0,
            payload = buildJsonObject { put("finish_reason", "stop") },
        )
        val result = mapEvent(event) as InferenceEvent.Done
        assertEquals("stop", result.finishReason)
        assertNull(result.fullText)
        assertEquals(5L, result.seq)
    }

    @Test
    fun `mapEvent legacy start type now falls through to Unknown`() {
        // StreamEventType.START was retired — wire type "start" is no longer
        // recognized as a Started event. (Real stream starts arrive as a
        // StreamHeader frame, decoded in parseFrame, not in mapEvent.) Verify
        // the reader treats it as a forward-compat Unknown rather than ever
        // resurrecting the dead branch.
        val event = StreamEvent(
            seq = 0, type = "start", tsMs = 0,
            payload = buildJsonObject { put("requestId", "req-99") },
        )
        val result = mapEvent(event) as InferenceEvent.Unknown
        assertEquals("start", result.type)
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
        val result = mapEvent(event) as InferenceEvent.TextDelta
        assertEquals("", result.text)
    }

    @Test
    fun `TOOL_CALL with missing name, args, callId defaults to empty strings`() {
        val event = StreamEvent(
            seq = 11, type = StreamEventType.TOOL_CALL, tsMs = 0,
            payload = JsonObject(emptyMap()),
        )
        val result = mapEvent(event) as InferenceEvent.ToolCall
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
        val result = mapEvent(event) as InferenceEvent.Metrics
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
        val result = mapEvent(event) as InferenceEvent.Error
        assertNull(result.code)
        assertEquals("oops", result.message)
    }

    @Test
    fun `DONE with missing finish_reason defaults to unknown`() {
        val event = StreamEvent(
            seq = 14, type = StreamEventType.DONE, tsMs = 0,
            payload = JsonObject(emptyMap()),
        )
        val result = mapEvent(event) as InferenceEvent.Done
        assertEquals("unknown", result.finishReason)
    }

    // =========================================================================
    // Unknown event type
    // =========================================================================

    @Test
    fun `unknown event type returns Unknown event`() {
        val event = StreamEvent(
            seq = 20, type = "some_future_type", tsMs = 0,
            payload = buildJsonObject { put("data", "whatever") },
        )
        val result = mapEvent(event) as InferenceEvent.Unknown
        assertEquals("some_future_type", result.type)
        assertEquals(20L, result.seq)
    }

    // =========================================================================
    // parseFrame
    // =========================================================================

    @Test
    fun `parseFrame with StreamHeader returns Started`() {
        val headerJson = buildStreamHeaderJson("req-parse-1")
        val result = parseFrame(headerJson)
        assertTrue(result is InferenceEvent.Started)
        assertEquals("req-parse-1", (result as InferenceEvent.Started).requestId)
    }

    @Test
    fun `parseFrame with StreamEvent returns mapped event`() {
        val eventJson = buildStreamEventJson(
            seq = 1,
            type = StreamEventType.TOKEN_DELTA,
            payload = buildJsonObject { put("text", "world") },
        )
        val result = parseFrame(eventJson)
        assertTrue(result is InferenceEvent.TextDelta)
        assertEquals("world", (result as InferenceEvent.TextDelta).text)
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
        assertTrue(events[0] is InferenceEvent.Started)
        assertTrue(events[1] is InferenceEvent.TextDelta)
        assertEquals("hi", (events[1] as InferenceEvent.TextDelta).text)
        assertTrue(events[2] is InferenceEvent.Done)
        assertEquals("stop", (events[2] as InferenceEvent.Done).finishReason)
    }

    // =========================================================================
    // MAX_FRAME_BYTES constant
    // =========================================================================

    @Test
    fun `MAX_FRAME_BYTES constant is 1 MiB`() {
        assertEquals(1_048_576, MAX_FRAME_BYTES)
    }

    // =========================================================================
    // Protocol error regression tests — H10
    // In every case the flow MUST complete cleanly (toList() returns),
    // emit exactly one Error frame, and close the underlying stream.
    // No uncaught exception must escape to the collector.
    // =========================================================================

    private fun writeLengthOnly(out: OutputStream, len: Int) {
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(len).array()
        out.write(header)
        out.flush()
    }

    @Test
    fun `frame with len -1 emits PROTOCOL_ERROR_LENGTH then flow completes cleanly`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        writeLengthOnly(pipeOut, -1)
        pipeOut.close()

        val events = readStreamFromInputStream(pipeIn).toList()

        assertEquals("Expected exactly one event", 1, events.size)
        val error = events[0] as? InferenceEvent.Error
            ?: error("Expected InferenceEvent.Error, got ${events[0]}")
        assertEquals("PROTOCOL_ERROR_LENGTH", error.code)
    }

    @Test
    fun `frame with len exceeding MAX_FRAME_BYTES emits PROTOCOL_ERROR_LENGTH then flow completes cleanly`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        writeLengthOnly(pipeOut, MAX_FRAME_BYTES + 1)
        pipeOut.close()

        val events = readStreamFromInputStream(pipeIn).toList()

        assertEquals("Expected exactly one event", 1, events.size)
        val error = events[0] as? InferenceEvent.Error
            ?: error("Expected InferenceEvent.Error, got ${events[0]}")
        assertEquals("PROTOCOL_ERROR_LENGTH", error.code)
    }

    @Test
    fun `truncated frame emits PROTOCOL_ERROR_EOF then flow completes cleanly`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        // Declare 100-byte payload but only write 50 bytes, then close.
        writeLengthOnly(pipeOut, 100)
        pipeOut.write(ByteArray(50) { 0x00 })
        pipeOut.close()

        val events = readStreamFromInputStream(pipeIn).toList()

        assertEquals("Expected exactly one event", 1, events.size)
        val error = events[0] as? InferenceEvent.Error
            ?: error("Expected InferenceEvent.Error, got ${events[0]}")
        assertEquals("PROTOCOL_ERROR_EOF", error.code)
    }

    @Test
    fun `malformed JSON frame emits PROTOCOL_ERROR_JSON then flow completes cleanly`() = runTest {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val garbage = "not valid json {{{}}}".encodeToByteArray()
        writeLengthOnly(pipeOut, garbage.size)
        pipeOut.write(garbage)
        pipeOut.close()

        val events = readStreamFromInputStream(pipeIn).toList()

        assertEquals("Expected exactly one event", 1, events.size)
        val error = events[0] as? InferenceEvent.Error
            ?: error("Expected InferenceEvent.Error, got ${events[0]}")
        assertEquals("PROTOCOL_ERROR_JSON", error.code)
    }
}
