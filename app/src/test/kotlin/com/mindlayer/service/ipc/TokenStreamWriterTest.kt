package com.mindlayer.service.ipc

import android.util.Log
import com.mindlayer.shared.StreamEvent
import com.mindlayer.shared.StreamEventType
import com.mindlayer.shared.StreamHeader
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for [TokenStreamWriter] using piped streams.
 *
 * Each test writes via the writer, then reads back raw frames from the
 * pipe and deserialises to verify correctness.
 */
class TokenStreamWriterTest {

    private val json = Json { ignoreUnknownKeys = true }

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

    // -- Frame reading helper --------------------------------------------------

    /** Read one length-prefixed frame from a DataInputStream. */
    private fun readFrame(dis: DataInputStream): String {
        val len = Integer.reverseBytes(dis.readInt())
        val payload = ByteArray(len)
        dis.readFully(payload)
        return payload.decodeToString()
    }

    /** Collect all frames from a closed pipe. */
    private fun readAllFrames(pipeIn: PipedInputStream): List<String> {
        val dis = DataInputStream(pipeIn)
        val frames = mutableListOf<String>()
        try {
            while (true) {
                frames.add(readFrame(dis))
            }
        } catch (_: Exception) {
            // EOF or pipe closed
        }
        return frames
    }

    // =========================================================================
    // writeHeader
    // =========================================================================

    @Test
    fun `writeHeader produces valid StreamHeader JSON`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)

        writer.writeHeader("req-header-1")
        writer.close()

        val frames = readAllFrames(pipeIn)
        assertEquals(1, frames.size)

        val header = json.decodeFromString<StreamHeader>(frames[0])
        assertEquals("req-header-1", header.requestId)
        assertEquals("mindlayer.stream.v1", header.protocol)
    }

    // =========================================================================
    // writeTokenDelta
    // =========================================================================

    @Test
    fun `writeTokenDelta produces StreamEvent with type token_delta and text`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)

        writer.writeTokenDelta(seq = 1, text = "Hello")
        writer.close()

        val frames = readAllFrames(pipeIn)
        assertEquals(1, frames.size)

        val event = json.decodeFromString<StreamEvent>(frames[0])
        assertEquals(StreamEventType.TOKEN_DELTA, event.type)
        assertEquals(1L, event.seq)
        assertEquals("Hello", event.payload["text"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // writeToolCall
    // =========================================================================

    @Test
    fun `writeToolCall produces StreamEvent with callId, name, args`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)

        writer.writeToolCall(seq = 2, callId = "call-42", name = "get_weather", argsJson = """{"city":"NYC"}""")
        writer.close()

        val frames = readAllFrames(pipeIn)
        assertEquals(1, frames.size)

        val event = json.decodeFromString<StreamEvent>(frames[0])
        assertEquals(StreamEventType.TOOL_CALL, event.type)
        assertEquals(2L, event.seq)
        assertEquals("call-42", event.payload["callId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("get_weather", event.payload["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("""{"city":"NYC"}""", event.payload["args"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // writeMetrics
    // =========================================================================

    @Test
    fun `writeMetrics produces StreamEvent with metrics payload`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)

        val metrics = buildJsonObject {
            put("prefillToksPerSec", 120.5f)
            put("decodeToksPerSec", 42.0f)
            put("thermalBand", "nominal")
        }
        writer.writeMetrics(seq = 3, metrics = metrics)
        writer.close()

        val frames = readAllFrames(pipeIn)
        assertEquals(1, frames.size)

        val event = json.decodeFromString<StreamEvent>(frames[0])
        assertEquals(StreamEventType.METRICS, event.type)
        assertEquals(3L, event.seq)
        assertNotNull(event.payload["prefillToksPerSec"])
        assertNotNull(event.payload["decodeToksPerSec"])
        assertEquals("nominal", event.payload["thermalBand"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // writeDone
    // =========================================================================

    @Test
    fun `writeDone produces StreamEvent with finish_reason`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)

        writer.writeDone(seq = 4, finishReason = "stop")
        writer.close()

        val frames = readAllFrames(pipeIn)
        assertEquals(1, frames.size)

        val event = json.decodeFromString<StreamEvent>(frames[0])
        assertEquals(StreamEventType.DONE, event.type)
        assertEquals(4L, event.seq)
        assertEquals("stop", event.payload["finish_reason"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // writeError
    // =========================================================================

    @Test
    fun `writeError produces StreamEvent with code and message`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)

        writer.writeError(seq = 5, code = "rate_limit", message = "Too many requests")
        writer.close()

        val frames = readAllFrames(pipeIn)
        assertEquals(1, frames.size)

        val event = json.decodeFromString<StreamEvent>(frames[0])
        assertEquals(StreamEventType.ERROR, event.type)
        assertEquals(5L, event.seq)
        assertEquals("rate_limit", event.payload["code"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Too many requests", event.payload["message"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // close()
    // =========================================================================

    @Test
    fun `close flushes and closes stream`() {
        val baos = ByteArrayOutputStream()
        val writer = TokenStreamWriter.forTesting(baos)

        writer.writeTokenDelta(seq = 1, text = "Hi")
        writer.close()

        // After close the buffer should contain at least one frame
        assertTrue("Output should not be empty after write+close", baos.size() > 0)
    }

    // =========================================================================
    // closeWithError()
    // =========================================================================

    @Test
    fun `closeWithError writes error event then closes`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)

        writer.closeWithError(seq = 10, message = "Something broke")

        val frames = readAllFrames(pipeIn)
        assertEquals(1, frames.size)

        val event = json.decodeFromString<StreamEvent>(frames[0])
        assertEquals(StreamEventType.ERROR, event.type)
        assertEquals("internal_error", event.payload["code"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Something broke", event.payload["message"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // Writing after close → no crash
    // =========================================================================

    @Test
    fun `writing after close does not crash`() {
        val baos = ByteArrayOutputStream()
        val writer = TokenStreamWriter.forTesting(baos)

        writer.close()
        val sizeAfterClose = baos.size()

        // These should be silently ignored (closed flag)
        writer.writeTokenDelta(seq = 99, text = "ignored")
        writer.writeHeader("ignored")
        writer.writeDone(seq = 100, finishReason = "stop")

        assertEquals("No additional bytes should be written after close", sizeAfterClose, baos.size())
    }

    // =========================================================================
    // Double close → no crash
    // =========================================================================

    @Test
    fun `double close does not crash`() {
        val baos = ByteArrayOutputStream()
        val writer = TokenStreamWriter.forTesting(baos)

        writer.close()
        writer.close() // Should be a no-op
    }

    // =========================================================================
    // forTesting() factory
    // =========================================================================

    @Test
    fun `forTesting factory creates writer with plain OutputStream`() {
        val baos = ByteArrayOutputStream()
        val writer = TokenStreamWriter.forTesting(baos)

        writer.writeTokenDelta(seq = 1, text = "test")
        writer.close()

        assertTrue("forTesting writer should produce output", baos.size() > 0)

        // Verify the frame is readable
        val dis = DataInputStream(baos.toByteArray().inputStream())
        val frameJson = readFrame(dis)
        val event = json.decodeFromString<StreamEvent>(frameJson)
        assertEquals("test", event.payload["text"]?.jsonPrimitive?.contentOrNull)
    }

    // =========================================================================
    // IPC hardening — max frame size
    // =========================================================================

    @Test(expected = IllegalStateException::class)
    fun `writeTokenDelta rejects payload exceeding MAX_FRAME_BYTES`() {
        val baos = ByteArrayOutputStream()
        val writer = TokenStreamWriter.forTesting(baos)
        // 1 MiB cap → generate a string well above it.
        val huge = "x".repeat(1_048_576 + 64)
        writer.writeTokenDelta(seq = 1, text = huge)
    }

    // =========================================================================
    // IPC hardening — IOException becomes CancellationException
    // =========================================================================

    @Test
    fun `writeFrame re-raises pipe IOException as CancellationException`() {
        val failing = object : java.io.OutputStream() {
            override fun write(b: Int) { throw java.io.IOException("broken pipe") }
            override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("broken pipe") }
        }
        val writer = TokenStreamWriter.forTesting(failing)
        try {
            writer.writeTokenDelta(seq = 1, text = "hello")
            org.junit.Assert.fail("Expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertTrue(
                "Cause should be the original IOException",
                e.cause is java.io.IOException,
            )
        }
    }

    @Test
    fun `closeWithError swallows pipe failure and still closes`() {
        val failing = object : java.io.OutputStream() {
            override fun write(b: Int) { throw java.io.IOException("broken pipe") }
            override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("broken pipe") }
        }
        val writer = TokenStreamWriter.forTesting(failing)
        // Must not throw — closeWithError is terminal, best-effort.
        writer.closeWithError(seq = 0, message = "gone")
    }
}
