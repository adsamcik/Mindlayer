package com.adsamcik.mindlayer.service.integration

import android.util.Log
import com.adsamcik.mindlayer.sdk.MindlayerEvent
import com.adsamcik.mindlayer.service.engine.StructuredOutputHelper
import com.adsamcik.mindlayer.service.engine.ToolCallBridge
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Integration tests for the tool-calling pipeline: ToolCallBridge ↔ pipe
 * events ↔ submitResult → conversation resumed.
 *
 * Uses real [ToolCallBridge] and simulated pipe I/O (PipedStreams) to verify
 * the full round-trip without Android framework dependencies.
 */
class ToolCallPipelineTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var bridge: ToolCallBridge

    @Before
    fun setUp() {
        bridge = ToolCallBridge()
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- Pipe helpers -------------------------------------------------------

    /** Write a length-prefixed frame mimicking TokenStreamWriter.writeFrame. */
    private fun writeFrame(out: java.io.OutputStream, payload: String) {
        val bytes = payload.encodeToByteArray()
        val header = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bytes.size)
            .array()
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    /** Read a single length-prefixed frame from [input]. */
    private fun readFrame(input: DataInputStream): String {
        val len = Integer.reverseBytes(input.readInt())
        require(len in 0..1_048_576) { "Invalid frame length: $len" }
        val payload = ByteArray(len)
        input.readFully(payload)
        return payload.decodeToString()
    }

    /** Read all frames until EOF, returning decoded JSON strings. */
    private fun readAllFrames(input: InputStream): List<String> {
        val dis = DataInputStream(BufferedInputStream(input))
        val frames = mutableListOf<String>()
        try {
            while (true) {
                frames.add(readFrame(dis))
            }
        } catch (_: EOFException) {
            // end of pipe
        }
        return frames
    }

    /** Parse a frame string into a [StreamEvent], or null if it's a header. */
    private fun parseStreamEvent(frameJson: String): StreamEvent? {
        return try {
            json.decodeFromString<StreamEvent>(frameJson)
        } catch (_: Exception) {
            null
        }
    }

    /** Parse a frame string as a [StreamHeader], or null. */
    private fun parseStreamHeader(frameJson: String): StreamHeader? {
        return try {
            json.decodeFromString<StreamHeader>(frameJson)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Simulate the orchestrator's tool-call pipe write: register tool calls
     * on the bridge, write tool_call events to the output stream, and return
     * the pending calls for later result submission.
     */
    private fun emitToolCallsToStream(
        requestId: String,
        toolCalls: List<Pair<String, String>>,
        out: java.io.OutputStream,
        startSeq: Long = 1L,
    ): List<ToolCallBridge.PendingToolCall> {
        val pending = bridge.registerPendingToolCalls(requestId, toolCalls)
        var seq = startSeq
        for (call in pending) {
            val event = StreamEvent(
                seq = seq,
                type = StreamEventType.TOOL_CALL,
                tsMs = System.currentTimeMillis(),
                payload = kotlinx.serialization.json.buildJsonObject {
                    put("callId", kotlinx.serialization.json.JsonPrimitive(call.callId))
                    put("name", kotlinx.serialization.json.JsonPrimitive(call.toolName))
                    put("args", kotlinx.serialization.json.JsonPrimitive(call.arguments))
                },
            )
            writeFrame(out, json.encodeToString(StreamEvent.serializer(), event))
            seq++
        }
        return pending
    }

    private fun submitResult(
        requestId: String,
        pending: List<ToolCallBridge.PendingToolCall>,
        toolName: String,
        resultJson: String,
        occurrence: Int = 0,
    ) {
        val call = pending.filter { it.toolName == toolName }[occurrence]
        bridge.submitResult(requestId, call.callId, toolName, resultJson)
    }

    /** Write a DONE event to the pipe. */
    private fun writeDoneEvent(
        out: java.io.OutputStream,
        seq: Long,
        finishReason: String = "stop",
    ) {
        val event = StreamEvent(
            seq = seq,
            type = StreamEventType.DONE,
            tsMs = System.currentTimeMillis(),
            payload = kotlinx.serialization.json.buildJsonObject {
                put("finish_reason", kotlinx.serialization.json.JsonPrimitive(finishReason))
            },
        )
        writeFrame(out, json.encodeToString(StreamEvent.serializer(), event))
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    fun `singleToolCall_fullRoundtrip`() = runTest {
        val requestId = "req-single"
        val pipeIn = PipedInputStream(4096)
        val pipeOut = PipedOutputStream(pipeIn)

        // --- Orchestrator side: emit tool_call event ---
        val pending = emitToolCallsToStream(
            requestId,
            listOf("get_weather" to """{"city":"Prague"}"""),
            pipeOut,
        )
        assertEquals(1, pending.size)

        // --- Client side: read tool_call from pipe ---
        val toolCallFrame = readFrame(DataInputStream(BufferedInputStream(pipeIn)))
        val toolCallEvent = parseStreamEvent(toolCallFrame)!!
        assertEquals(StreamEventType.TOOL_CALL, toolCallEvent.type)
        assertEquals("get_weather", toolCallEvent.payload["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("""{"city":"Prague"}""", toolCallEvent.payload["args"]?.jsonPrimitive?.contentOrNull)
        val callId = toolCallEvent.payload["callId"]?.jsonPrimitive?.contentOrNull!!
        assertEquals(pending[0].callId, callId)

        // --- Client side: submit result via bridge ---
        val awaitJob = async {
            bridge.awaitResults(requestId, timeoutMs = 5_000L)
        }
        submitResult(requestId, pending, "get_weather", """{"temp":22,"unit":"C"}""")

        val results = awaitJob.await()
        assertEquals(1, results.size)
        assertEquals("get_weather", results[0].first)
        assertEquals("""{"temp":22,"unit":"C"}""", results[0].second)

        // --- Orchestrator writes done ---
        writeDoneEvent(pipeOut, 2L)
        pipeOut.close()

        val doneFrame = readFrame(DataInputStream(BufferedInputStream(pipeIn)))
        val doneEvent = parseStreamEvent(doneFrame)!!
        assertEquals(StreamEventType.DONE, doneEvent.type)
        assertEquals("stop", doneEvent.payload["finish_reason"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `multipleToolCalls_allResolved`() = runTest {
        val requestId = "req-multi"
        val pipeIn = PipedInputStream(4096)
        val pipeOut = PipedOutputStream(pipeIn)

        val pending = emitToolCallsToStream(
            requestId,
            listOf(
                "get_weather" to """{"city":"Prague"}""",
                "get_time" to """{"tz":"CET"}""",
            ),
            pipeOut,
        )
        assertEquals(2, pending.size)

        // Read both tool_call events from pipe
        val dis = DataInputStream(BufferedInputStream(pipeIn))
        val tc1 = parseStreamEvent(readFrame(dis))!!
        val tc2 = parseStreamEvent(readFrame(dis))!!

        assertEquals(StreamEventType.TOOL_CALL, tc1.type)
        assertEquals("get_weather", tc1.payload["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(StreamEventType.TOOL_CALL, tc2.type)
        assertEquals("get_time", tc2.payload["name"]?.jsonPrimitive?.contentOrNull)

        // Submit both results from a separate coroutine (simulating AIDL)
        val awaitJob = async {
            bridge.awaitResults(requestId, timeoutMs = 5_000L)
        }

        launch {
            submitResult(requestId, pending, "get_weather", """{"temp":18}""")
            submitResult(requestId, pending, "get_time", """{"time":"14:00"}""")
        }

        val results = awaitJob.await()
        assertEquals(2, results.size)
        assertEquals("get_weather" to """{"temp":18}""", results[0])
        assertEquals("get_time" to """{"time":"14:00"}""", results[1])

        // Done event
        writeDoneEvent(pipeOut, 3L)
        pipeOut.close()

        val doneEvent = parseStreamEvent(readFrame(dis))!!
        assertEquals(StreamEventType.DONE, doneEvent.type)
    }

    @Test
    fun `toolCall_timeout_producesError`() = runTest {
        val requestId = "req-timeout"

        // Register tool calls but never submit results
        bridge.registerPendingToolCalls(requestId, listOf("slow_tool" to """{"x":1}"""))

        try {
            bridge.awaitResults(requestId, timeoutMs = 100L)
            fail("Expected TimeoutCancellationException")
        } catch (e: TimeoutCancellationException) {
            // Expected: bridge timed out waiting for results
        }

        // After timeout, pending state is cleaned up
        try {
            bridge.awaitResults(requestId)
            fail("Expected IllegalStateException after timeout cleanup")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains(requestId))
        }
    }

    @Test
    fun `toolCall_cancelDuringWait`() = runTest {
        val requestId = "req-cancel"

        val calls = bridge.registerPendingToolCalls(
            requestId,
            listOf("tool_a" to """{"a":1}""", "tool_b" to """{"b":2}"""),
        )

        // Start awaiting in a separate coroutine — yield to let it start
        val awaitJob = async {
            try {
                bridge.awaitResults(requestId, timeoutMs = 10_000L)
            } catch (e: CancellationException) {
                throw e
            }
        }
        // Ensure the async coroutine has started and is suspended on awaitResults
        yield()

        // Cancel the inference (as the orchestrator would)
        bridge.cancel(requestId)

        // Verify pending calls are cleaned up
        assertTrue(calls[0].resultDeferred.isCancelled)
        assertTrue(calls[1].resultDeferred.isCancelled)

        // awaitJob should fail since deferred was cancelled
        try {
            awaitJob.await()
            fail("Expected exception from cancelled await")
        } catch (_: CancellationException) {
            // Expected — deferred was cancelled
        }

        // Verify no pending state remains
        try {
            bridge.awaitResults(requestId)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains(requestId))
        }
    }

    @Test
    fun `structuredOutput_toolRouting`() = runTest {
        val requestId = "req-structured"
        val pipeIn = PipedInputStream(4096)
        val pipeOut = PipedOutputStream(pipeIn)

        // Simulate the orchestrator receiving a __structured_output tool call
        // from the model. In TOOL_ROUTING mode, this should be extracted as
        // structured JSON content, NOT forwarded as a tool_call event to the
        // client.

        val structuredArgs = """{"name":"Alice","age":30,"city":"Prague"}"""
        val toolCalls = listOf(
            StructuredOutputHelper.TOOL_NAME to structuredArgs,
            "real_tool" to """{"q":"test"}""",
        )

        // extractStructuredResult should intercept __structured_output
        val structuredResult = StructuredOutputHelper.extractStructuredResult(toolCalls)
        assertEquals(structuredArgs, structuredResult)

        // Filter out the structured output tool, only real tools go to bridge
        val realToolCalls = toolCalls.filter { it.first != StructuredOutputHelper.TOOL_NAME }
        assertEquals(1, realToolCalls.size)
        assertEquals("real_tool", realToolCalls[0].first)

        // Write the structured result as a token_delta (how orchestrator does it)
        val deltaEvent = StreamEvent(
            seq = 1L,
            type = StreamEventType.TOKEN_DELTA,
            tsMs = System.currentTimeMillis(),
            payload = kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(structuredResult))
            },
        )
        writeFrame(pipeOut, json.encodeToString(StreamEvent.serializer(), deltaEvent))

        // Forward real tool calls to bridge + pipe
        val pending = emitToolCallsToStream(requestId, realToolCalls, pipeOut, startSeq = 2L)

        // Read from pipe: first should be token_delta with structured JSON
        val dis = DataInputStream(BufferedInputStream(pipeIn))
        val frame1 = parseStreamEvent(readFrame(dis))!!
        assertEquals(StreamEventType.TOKEN_DELTA, frame1.type)
        assertEquals(structuredArgs, frame1.payload["text"]?.jsonPrimitive?.contentOrNull)

        // Second should be tool_call for the real tool
        val frame2 = parseStreamEvent(readFrame(dis))!!
        assertEquals(StreamEventType.TOOL_CALL, frame2.type)
        assertEquals("real_tool", frame2.payload["name"]?.jsonPrimitive?.contentOrNull)

        // Submit the real tool result and verify
        val awaitJob = async { bridge.awaitResults(requestId, timeoutMs = 5_000L) }
        submitResult(requestId, pending, "real_tool", """{"result":"ok"}""")
        val results = awaitJob.await()
        assertEquals(1, results.size)
        assertEquals("real_tool" to """{"result":"ok"}""", results[0])

        // Done
        writeDoneEvent(pipeOut, 3L)
        pipeOut.close()

        val doneFrame = parseStreamEvent(readFrame(dis))!!
        assertEquals(StreamEventType.DONE, doneFrame.type)
    }

    // =========================================================================
    // Edge-case tests
    // =========================================================================

    @Test
    fun `toolResult_duplicateSubmission_ignored`() = runTest {
        val requestId = "req-dup"

        val pending = bridge.registerPendingToolCalls(requestId, listOf("get_weather" to """{"city":"Prague"}"""))

        val awaitJob = async {
            bridge.awaitResults(requestId, timeoutMs = 5_000L)
        }

        // First submit — should complete the deferred
        submitResult(requestId, pending, "get_weather", """{"temp":22}""")
        val results = awaitJob.await()
        assertEquals(1, results.size)
        assertEquals("get_weather" to """{"temp":22}""", results[0])

        // Second submit — requestId already removed by awaitResults, should be silently ignored
        submitResult(requestId, pending, "get_weather", """{"temp":99}""")
        // No crash = pass
    }

    @Test
    fun `toolResult_afterCancellation_ignored`() = runTest {
        val requestId = "req-post-cancel"

        val pending = bridge.registerPendingToolCalls(
            requestId,
            listOf("tool_a" to """{"a":1}"""),
        )

        // Cancel before any results are submitted
        bridge.cancel(requestId)

        // Submit after cancellation — should be silently ignored, no crash
        submitResult(requestId, pending, "tool_a", """{"result":"late"}""")
        // No crash = pass
    }

    @Test
    fun `toolResult_unknownRequestId_ignored`() = runTest {
        // Submit for a request that was never registered
        bridge.submitResult("req-nonexistent", "call-1", "some_tool", """{"x":1}""")
        // No crash = pass. Bridge logs a warning.
    }

    @Test
    fun `toolResult_wrongToolName_ignored`() = runTest {
        val requestId = "req-wrong-name"

        val pending = bridge.registerPendingToolCalls(requestId, listOf("get_weather" to """{"city":"Prague"}"""))

        // Submit with wrong tool name — no matching pending call
        bridge.submitResult(requestId, pending[0].callId, "wrong_tool_name", """{"result":"oops"}""")

        // The pending call for get_weather should still be incomplete
        // Verify by cancelling (clean up) — if it had been completed, cancel would be a no-op
        bridge.cancel(requestId)
        // No crash = pass. Bridge logs a warning about no matching tool.
    }
}
