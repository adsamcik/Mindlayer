package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import kotlinx.coroutines.flow.toList
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
 * Wire-format tests for v05-token-batching. The reader must:
 *
 *  - Accept `mindlayer.stream.v2` headers (v0.5 protocol).
 *  - Expand a single `TOKEN_DELTA_BATCH` envelope into N per-token
 *    `MindlayerEvent.TextDelta` emissions in order, with synthesised
 *    contiguous seq values ending at the envelope's seq.
 *  - Tolerate empty `texts` arrays (no events emitted).
 *  - Continue to handle v1 streams identically (regression check).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenStreamReaderBatchTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `TOKEN_DELTA_BATCH expands to per-token TextDelta in order`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V2, requestId = "rb")
        val batch = StreamEvent(
            seq = 12L, // last token's seq
            type = StreamEventType.TOKEN_DELTA_BATCH,
            tsMs = 0L,
            payload = buildJsonObject {
                put(
                    "texts",
                    JsonArray(
                        listOf(
                            JsonPrimitive("hello"),
                            JsonPrimitive(" "),
                            JsonPrimitive("world"),
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
        val frames = listOf(
            wireFrame(json.encodeToString(StreamHeader.serializer(), header)),
            wireFrame(json.encodeToString(StreamEvent.serializer(), batch)),
            wireFrame(json.encodeToString(StreamEvent.serializer(), done)),
        )
        val pfd = pfdFor(frames)
        val events = TokenStreamReader.readStream(pfd).toList()

        // Expect: Started + 3 TextDelta + Done
        assertEquals("event count", 5, events.size)
        assertTrue(events[0] is MindlayerEvent.Started)
        val deltas = events.drop(1).take(3).map { it as MindlayerEvent.TextDelta }
        assertEquals(listOf("hello", " ", "world"), deltas.map { it.text })
        // Synthesised seqs: last = envelope seq (12), prior count backward.
        assertEquals(listOf(10L, 11L, 12L), deltas.map { it.seq })
        assertTrue(events[4] is MindlayerEvent.Done)
    }

    @Test
    fun `empty TOKEN_DELTA_BATCH yields no events`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V2, requestId = "rb")
        val emptyBatch = StreamEvent(
            seq = 0L,
            type = StreamEventType.TOKEN_DELTA_BATCH,
            tsMs = 0L,
            payload = buildJsonObject {
                put("texts", JsonArray(emptyList()))
            },
        )
        val done = StreamEvent(
            seq = 1L,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", JsonPrimitive("stop")) },
        )
        val frames = listOf(
            wireFrame(json.encodeToString(StreamHeader.serializer(), header)),
            wireFrame(json.encodeToString(StreamEvent.serializer(), emptyBatch)),
            wireFrame(json.encodeToString(StreamEvent.serializer(), done)),
        )
        val pfd = pfdFor(frames)
        val events = TokenStreamReader.readStream(pfd).toList()

        // Started + Done; no TextDelta from empty batch.
        assertEquals(2, events.size)
        assertTrue(events[0] is MindlayerEvent.Started)
        assertTrue(events[1] is MindlayerEvent.Done)
    }

    @Test
    fun `TOKEN_DELTA_BATCH with one element behaves like a single TextDelta`() = runTest {
        val header = StreamHeader(protocol = StreamProtocol.V2, requestId = "rb")
        val batch = StreamEvent(
            seq = 5L,
            type = StreamEventType.TOKEN_DELTA_BATCH,
            tsMs = 0L,
            payload = buildJsonObject {
                put("texts", JsonArray(listOf(JsonPrimitive("solo"))))
            },
        )
        val frames = listOf(
            wireFrame(json.encodeToString(StreamHeader.serializer(), header)),
            wireFrame(json.encodeToString(StreamEvent.serializer(), batch)),
        )
        val pfd = pfdFor(frames)
        val events = TokenStreamReader.readStream(pfd).toList()

        assertEquals(2, events.size) // Started + 1 TextDelta
        val delta = events[1] as MindlayerEvent.TextDelta
        assertEquals("solo", delta.text)
        assertEquals(5L, delta.seq)
    }

    // ---- Helpers ----------------------------------------------------------

    private fun wireFrame(payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        val out = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(Integer.reverseBytes(len))
        dos.write(bytes)
        return out.toByteArray()
    }

    private fun pfdFor(frames: List<ByteArray>): android.os.ParcelFileDescriptor {
        val tmp: File = Files.createTempFile("mindlayer-batch-test", ".bin").toFile()
        tmp.deleteOnExit()
        FileOutputStream(tmp).use { fos ->
            for (frame in frames) fos.write(frame)
        }
        return android.os.ParcelFileDescriptor.open(
            tmp,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }
}
