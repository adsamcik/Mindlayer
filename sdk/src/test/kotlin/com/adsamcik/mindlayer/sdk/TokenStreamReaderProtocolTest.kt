package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Tests for v02-pipe-proto-validate: [TokenStreamReader] must reject any
 * pipe whose [StreamHeader] does not advertise the expected
 * `mindlayer.stream.v1` protocol identifier. The receiver emits a
 * `PROTOCOL_MISMATCH` error frame instead of silently misinterpreting later
 * frames as the wire format may have changed.
 *
 * Robolectric is required because [android.os.ParcelFileDescriptor.open]
 * is unimplemented in plain JUnit Android stubs. We avoid creating a real
 * pipe (which needs the Android emulator) by writing the wire bytes to a
 * temp file and opening it READ_ONLY — the reader treats its input as a
 * stream of length-prefixed frames so a file-backed FD is wire-equivalent
 * for unit-test purposes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenStreamReaderProtocolTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `mindlayer-stream-v1 header is accepted as Started`() = runTest {
        val frames = listOf(
            wireFrame(json.encodeToString(StreamHeader.serializer(), StreamHeader(requestId = "r1"))),
            wireFrame(json.encodeToString(StreamEvent.serializer(), doneEvent("r1"))),
        )
        val pfd = pfdFor(frames)
        val events = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).toList()

        assertTrue(
            "expected Started + Done, got $events",
            events.size == 2 &&
                events[0] is InferenceEvent.Started &&
                events[1] is InferenceEvent.Done,
        )
    }

    @Test
    fun `unexpected pipe protocol yields PROTOCOL_MISMATCH error frame`() = runTest {
        // v4 is not in StreamProtocol.SUPPORTED, so the reader rejects it.
        // (v3 became a supported protocol when Gemma 4 thinking-mode
        // support landed — see docs/THINKING.md.)
        val futureHeader = StreamHeader(protocol = "mindlayer.stream.v4", requestId = "r1")
        val frames = listOf(
            wireFrame(json.encodeToString(StreamHeader.serializer(), futureHeader)),
        )
        val pfd = pfdFor(frames)

        val first = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).first()
        assertTrue("expected Error, got $first", first is InferenceEvent.Error)
        val err = first as InferenceEvent.Error
        assertEquals("PROTOCOL_MISMATCH", err.code)
        assertTrue(
            "message should mention the unsupported protocol, got '${err.message}'",
            err.message.contains("mindlayer.stream.v4"),
        )
    }

    @Test
    fun `mindlayer-stream-v2 header is accepted as Started`() = runTest {
        // v0.5 token-batching adds v2 to StreamProtocol.SUPPORTED.
        val v2Header = StreamHeader(protocol = "mindlayer.stream.v2", requestId = "r2")
        val frames = listOf(
            wireFrame(json.encodeToString(StreamHeader.serializer(), v2Header)),
            wireFrame(json.encodeToString(StreamEvent.serializer(), doneEvent("r2"))),
        )
        val pfd = pfdFor(frames)
        val events = TokenStreamReader.readStream(pfd, UnconfinedTestDispatcher()).toList()

        assertTrue(
            "expected Started + Done on v2, got $events",
            events.size == 2 &&
                events[0] is InferenceEvent.Started &&
                events[1] is InferenceEvent.Done,
        )
    }

    // ---- Helpers ----------------------------------------------------------

    private fun doneEvent(requestId: String): StreamEvent = StreamEvent(
        seq = 1L,
        type = StreamEventType.DONE,
        tsMs = 0L,
        payload = buildJsonObject {
            put("finish_reason", JsonPrimitive("stop"))
        },
    )

    /** Encode a JSON payload into the on-wire frame layout: 4-byte LE u32 length + bytes. */
    private fun wireFrame(payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        val out = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        // Wire format is little-endian; DataOutputStream is big-endian — reverse.
        dos.writeInt(Integer.reverseBytes(len))
        dos.write(bytes)
        return out.toByteArray()
    }

    /**
     * Build a real OS file containing the wire bytes and return a
     * [android.os.ParcelFileDescriptor] opened READ_ONLY against it. The
     * reader treats its input as a stream of length-prefixed frames so a
     * file-backed FD is wire-equivalent to a real pipe for unit-test purposes.
     */
    private fun pfdFor(frames: List<ByteArray>): android.os.ParcelFileDescriptor {
        val tmp: File = Files.createTempFile("mindlayer-stream-test", ".bin").toFile()
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
