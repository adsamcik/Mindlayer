package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * Wire-format tests for v1.1 Gemma 4 thinking mode. The reader must:
 *
 *  - Accept `mindlayer.stream.v3` headers and surface the [InferenceEvent.Started]
 *    event identically to v1/v2 (callers see no difference at the surface).
 *  - Decode `THOUGHT_DELTA` frames into [InferenceEvent.ThoughtDelta] with
 *    the wire `seq` preserved.
 *  - Expand `THOUGHT_DELTA_BATCH` into per-fragment [InferenceEvent.ThoughtDelta]
 *    emissions with synthesised contiguous seq values ending at the
 *    envelope's seq (mirrors `TOKEN_DELTA_BATCH` behaviour for answer
 *    tokens).
 *  - Keep the `thoughtDeltas()` / `answerOnly()` Flow operators returning
 *    the right slices on a mixed thought + answer stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenStreamReaderThinkingTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `v3 header is accepted and surfaces as Started`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V3, requestId = "rt")
        val pfd = writeFrames(json.encodeToString(StreamHeader.serializer(), header))
        val events = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).toList()
        // EOF after the header (no DONE) — but the header itself must
        // appear as Started without a PROTOCOL_MISMATCH error.
        assertTrue("expected at least one event", events.isNotEmpty())
        val started = events.first()
        assertTrue("expected Started event but got $started", started is InferenceEvent.Started)
        assertEquals("rt", (started as InferenceEvent.Started).requestId)
        // Any subsequent event (if present) must not be a PROTOCOL_MISMATCH error.
        events.drop(1).forEach { e ->
            assertTrue(
                "v3 stream must not raise PROTOCOL_MISMATCH",
                (e as? InferenceEvent.Error)?.code != "PROTOCOL_MISMATCH",
            )
        }
    }

    @Test
    fun `single THOUGHT_DELTA frame decodes to ThoughtDelta`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V3, requestId = "rt")
        val thought = StreamEvent(
            seq = 4L,
            type = StreamEventType.THOUGHT_DELTA,
            tsMs = 0L,
            payload = buildJsonObject { put("text", JsonPrimitive("Let me think about this...")) },
        )
        val done = StreamEvent(
            seq = 5L,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", JsonPrimitive("stop")) },
        )
        val pfd = writeFrames(
            json.encodeToString(StreamHeader.serializer(), header),
            json.encodeToString(StreamEvent.serializer(), thought),
            json.encodeToString(StreamEvent.serializer(), done),
        )

        val events = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).toList()
        val deltas = events.filterIsInstance<InferenceEvent.ThoughtDelta>()
        assertEquals(1, deltas.size)
        assertEquals("Let me think about this...", deltas[0].text)
        assertEquals(4L, deltas[0].seq)
    }

    @Test
    fun `THOUGHT_DELTA_BATCH expands to per-fragment ThoughtDelta in order`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V3, requestId = "rt")
        val batch = StreamEvent(
            seq = 12L,
            type = StreamEventType.THOUGHT_DELTA_BATCH,
            tsMs = 0L,
            payload = buildJsonObject {
                put(
                    "texts",
                    JsonArray(
                        listOf(
                            JsonPrimitive("first "),
                            JsonPrimitive("second "),
                            JsonPrimitive("third"),
                        )
                    )
                )
            },
        )
        val done = StreamEvent(
            seq = 13L,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", JsonPrimitive("stop")) },
        )
        val pfd = writeFrames(
            json.encodeToString(StreamHeader.serializer(), header),
            json.encodeToString(StreamEvent.serializer(), batch),
            json.encodeToString(StreamEvent.serializer(), done),
        )

        val events = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).toList()
        val thoughts = events.filterIsInstance<InferenceEvent.ThoughtDelta>()
        assertEquals(3, thoughts.size)
        assertEquals(listOf("first ", "second ", "third"), thoughts.map { it.text })
        // seq counts backward from the envelope: last entry gets envelope seq.
        assertEquals(listOf(10L, 11L, 12L), thoughts.map { it.seq })
    }

    @Test
    fun `empty THOUGHT_DELTA_BATCH yields no thought events`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V3, requestId = "rt")
        val batch = StreamEvent(
            seq = 7L,
            type = StreamEventType.THOUGHT_DELTA_BATCH,
            tsMs = 0L,
            payload = buildJsonObject { put("texts", JsonArray(emptyList())) },
        )
        val done = StreamEvent(
            seq = 8L,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", JsonPrimitive("stop")) },
        )
        val pfd = writeFrames(
            json.encodeToString(StreamHeader.serializer(), header),
            json.encodeToString(StreamEvent.serializer(), batch),
            json.encodeToString(StreamEvent.serializer(), done),
        )

        val events = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).toList()
        assertTrue(events.none { it is InferenceEvent.ThoughtDelta })
        assertTrue(events.any { it is InferenceEvent.Done })
    }

    @Test
    fun `thoughtDeltas operator returns only ThoughtDelta text`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V3, requestId = "rt")
        val thought = StreamEvent(
            seq = 1L,
            type = StreamEventType.THOUGHT_DELTA,
            tsMs = 0L,
            payload = buildJsonObject { put("text", JsonPrimitive("reasoning")) },
        )
        val answer = StreamEvent(
            seq = 2L,
            type = StreamEventType.TOKEN_DELTA,
            tsMs = 0L,
            payload = buildJsonObject { put("text", JsonPrimitive("answer")) },
        )
        val done = StreamEvent(
            seq = 3L,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", JsonPrimitive("stop")) },
        )
        val pfd = writeFrames(
            json.encodeToString(StreamHeader.serializer(), header),
            json.encodeToString(StreamEvent.serializer(), thought),
            json.encodeToString(StreamEvent.serializer(), answer),
            json.encodeToString(StreamEvent.serializer(), done),
        )

        val thoughts = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).thoughtDeltas().toList()
        assertEquals(listOf("reasoning"), thoughts)
    }

    @Test
    fun `answerOnly operator filters ThoughtDelta out of mixed stream`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V3, requestId = "rt")
        val thought = StreamEvent(
            seq = 1L,
            type = StreamEventType.THOUGHT_DELTA,
            tsMs = 0L,
            payload = buildJsonObject { put("text", JsonPrimitive("reasoning")) },
        )
        val answer = StreamEvent(
            seq = 2L,
            type = StreamEventType.TOKEN_DELTA,
            tsMs = 0L,
            payload = buildJsonObject { put("text", JsonPrimitive("answer")) },
        )
        val done = StreamEvent(
            seq = 3L,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", JsonPrimitive("stop")) },
        )
        val pfd = writeFrames(
            json.encodeToString(StreamHeader.serializer(), header),
            json.encodeToString(StreamEvent.serializer(), thought),
            json.encodeToString(StreamEvent.serializer(), answer),
            json.encodeToString(StreamEvent.serializer(), done),
        )

        val events = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).answerOnly().toList()
        assertTrue(
            "answerOnly() must not surface ThoughtDelta",
            events.none { it is InferenceEvent.ThoughtDelta },
        )
        assertEquals(
            listOf("answer"),
            events.filterIsInstance<InferenceEvent.TextDelta>().map { it.text },
        )
    }

    /**
     * Build a temp file containing the supplied JSON frames in
     * length-prefixed LE u32 format and return a read-end PFD over it.
     */
    private fun writeFrames(vararg frames: String): android.os.ParcelFileDescriptor {
        val tmp = Files.createTempFile("thinking-stream", ".bin").toFile()
        FileOutputStream(tmp).use { fos ->
            val dos = DataOutputStream(fos)
            for (frame in frames) {
                val bytes = frame.encodeToByteArray()
                dos.writeInt(Integer.reverseBytes(bytes.size))
                dos.write(bytes)
            }
            dos.flush()
        }
        return android.os.ParcelFileDescriptor.open(
            tmp,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }
}
