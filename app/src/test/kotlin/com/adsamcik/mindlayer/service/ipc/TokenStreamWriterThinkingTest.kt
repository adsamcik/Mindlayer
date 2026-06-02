package com.adsamcik.mindlayer.service.ipc

import android.util.Log
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Wire-format tests for v1.1 Gemma 4 thinking-mode emissions out of
 * [TokenStreamWriter]. Verifies that:
 *
 *  - [TokenStreamWriter.enableThinking] flips the negotiated protocol on
 *    [TokenStreamWriter.writeHeader] to `mindlayer.stream.v3`.
 *  - [TokenStreamWriter.writeThoughtDelta] is rejected when called on a
 *    writer that did not opt into thinking mode (defensive guard against
 *    the orchestrator calling the wrong overload).
 *  - Without batching, each thought fragment becomes one `THOUGHT_DELTA`
 *    frame on the wire.
 *  - With batching enabled, multiple thought fragments coalesce into a
 *    single `THOUGHT_DELTA_BATCH` frame on a terminal event (DONE).
 *  - Mixed answer + thought streams preserve the model's emission order.
 */
class TokenStreamWriterThinkingTest {

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

    private fun readFrame(dis: DataInputStream): String {
        val len = Integer.reverseBytes(dis.readInt())
        val payload = ByteArray(len)
        dis.readFully(payload)
        return payload.decodeToString()
    }

    @Test
    fun `enableThinking negotiates v3 protocol on header`() {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)
        writer.enableThinking()
        runBlocking { writer.writeHeader("req-thinking-1") }
        writer.close()

        val dis = DataInputStream(pipeIn)
        val header = json.decodeFromString(StreamHeader.serializer(), readFrame(dis))
        assertEquals(StreamProtocol.V3, header.protocol)
        assertEquals("req-thinking-1", header.requestId)
    }

    @Test
    fun `enableThinking takes precedence over enableBatching for header`() {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)
        writer.enableBatching()
        writer.enableThinking()
        runBlocking { writer.writeHeader("req-thinking-2") }
        writer.close()

        val dis = DataInputStream(pipeIn)
        val header = json.decodeFromString(StreamHeader.serializer(), readFrame(dis))
        // v3 is the right negotiation when both flags are on: v3 carries
        // batched thought + answer frames AND the new THOUGHT_DELTA types.
        assertEquals(StreamProtocol.V3, header.protocol)
    }

    @Test
    fun `writeThoughtDelta without enableThinking throws`() {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)
        runBlocking { writer.writeHeader("req-guard") }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { writer.writeThoughtDelta(1L, "should fail") }
        }
        writer.close()
    }

    @Test
    fun `unbatched writeThoughtDelta emits one THOUGHT_DELTA frame per call`() {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)
        writer.enableThinking()
        runBlocking {
            writer.writeHeader("req-thinking-3")
            writer.writeThoughtDelta(1L, "Step 1.")
            writer.writeThoughtDelta(2L, "Step 2.")
            writer.writeDone(3L, "stop")
        }
        writer.close()

        val dis = DataInputStream(pipeIn)
        // Skip header
        readFrame(dis)
        val first = json.decodeFromString(StreamEvent.serializer(), readFrame(dis))
        val second = json.decodeFromString(StreamEvent.serializer(), readFrame(dis))
        val done = json.decodeFromString(StreamEvent.serializer(), readFrame(dis))

        assertEquals(StreamEventType.THOUGHT_DELTA, first.type)
        assertEquals("Step 1.", first.payload["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1L, first.seq)

        assertEquals(StreamEventType.THOUGHT_DELTA, second.type)
        assertEquals("Step 2.", second.payload["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2L, second.seq)

        assertEquals(StreamEventType.DONE, done.type)
    }

    @Test
    fun `batched writeThoughtDelta coalesces into THOUGHT_DELTA_BATCH on DONE`() {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)
        writer.enableBatching()
        writer.enableThinking()
        runBlocking {
            writer.writeHeader("req-thinking-4")
            writer.writeThoughtDelta(1L, "a")
            writer.writeThoughtDelta(2L, "b")
            writer.writeThoughtDelta(3L, "c")
            writer.writeDone(4L, "stop")
        }
        writer.close()

        val dis = DataInputStream(pipeIn)
        readFrame(dis) // header
        val batch = json.decodeFromString(StreamEvent.serializer(), readFrame(dis))
        val done = json.decodeFromString(StreamEvent.serializer(), readFrame(dis))

        assertEquals(StreamEventType.THOUGHT_DELTA_BATCH, batch.type)
        // Envelope seq is the LAST entry's seq.
        assertEquals(3L, batch.seq)
        val texts: JsonArray = batch.payload["texts"]!!.jsonArray
        assertEquals(3, texts.size)
        assertEquals("a", texts[0].jsonPrimitive.contentOrNull)
        assertEquals("b", texts[1].jsonPrimitive.contentOrNull)
        assertEquals("c", texts[2].jsonPrimitive.contentOrNull)

        assertEquals(StreamEventType.DONE, done.type)
    }

    @Test
    fun `mixed thought and answer batching preserves emission order`() {
        // The writer must drain the OTHER buffer when one type's chunk
        // arrives, so the consumer sees thoughts and answer in the
        // order the model produced them.
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val writer = TokenStreamWriter.forTesting(pipeOut)
        writer.enableBatching()
        writer.enableThinking()
        runBlocking {
            writer.writeHeader("req-thinking-5")
            writer.writeThoughtDelta(1L, "T1")
            writer.writeThoughtDelta(2L, "T2")
            writer.writeTokenDelta(3L, "A1") // forces thought drain
            writer.writeTokenDelta(4L, "A2")
            writer.writeThoughtDelta(5L, "T3") // forces answer drain
            writer.writeDone(6L, "stop")
        }
        writer.close()

        val dis = DataInputStream(pipeIn)
        readFrame(dis) // header

        val events = mutableListOf<StreamEvent>()
        while (true) {
            val raw = try { readFrame(dis) } catch (_: Exception) { break }
            if (raw.isEmpty()) break
            events += json.decodeFromString(StreamEvent.serializer(), raw)
            if (events.last().type == StreamEventType.DONE) break
        }

        // Order should be: THOUGHT_BATCH(T1,T2), TOKEN_BATCH(A1,A2), THOUGHT_BATCH(T3), DONE
        assertTrue("expected at least 4 events", events.size >= 4)
        assertEquals(StreamEventType.THOUGHT_DELTA_BATCH, events[0].type)
        assertEquals(
            listOf("T1", "T2"),
            events[0].payload["texts"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
        )
        assertEquals(StreamEventType.TOKEN_DELTA_BATCH, events[1].type)
        assertEquals(
            listOf("A1", "A2"),
            events[1].payload["texts"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
        )
        assertEquals(StreamEventType.THOUGHT_DELTA_BATCH, events[2].type)
        assertEquals(
            listOf("T3"),
            events[2].payload["texts"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
        )
        assertEquals(StreamEventType.DONE, events.last().type)
    }
}
