package com.adsamcik.mindlayer.service.ipc

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM tests for [OcrTokenStreamWriter] (sibling of
 * [TokenStreamWriter] for the v0.8 OCR_V1 protocol).
 *
 * Verifies:
 *  - Frame framing (LE u32 length + UTF-8 JSON payload).
 *  - Each of the 10 OCR_* event types serializes correctly.
 *  - Header emits with the `mindlayer.stream.ocr.v1` protocol id.
 *  - Sequence numbers monotonically increase.
 *  - Close is idempotent.
 *  - MAX_FRAME_BYTES is wire-stable at 1 MiB.
 */
class OcrTokenStreamWriterTest {

    @Before
    fun setUp() {
        // The broken-pipe path logs via MindlayerLog -> android.util.Log,
        // which is unmocked in pure-JVM tests. Stub it so the IOException
        // branch can exercise the FD-release logic without an NPE.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test fun `MAX_FRAME_BYTES is 1 MiB`() {
        assertEquals(1_048_576, OcrTokenStreamWriter.MAX_FRAME_BYTES)
    }

    /** OutputStream that throws IOException on write to simulate a dead reader. */
    private class WriteThrowing : java.io.OutputStream() {
        @Volatile var closed = false
            private set
        override fun write(b: Int) { throw java.io.IOException("broken pipe") }
        override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("broken pipe") }
        override fun close() { closed = true }
    }

    @Test fun `broken-pipe write releases the fd instead of leaking it (R-12)`() {
        val out = WriteThrowing()
        val writer = OcrTokenStreamWriter.forTesting(out)
        // writeHeader -> writeFrame hits the IOException path. OCR writes are
        // advisory so this must NOT throw, but it MUST release the fd.
        writer.writeHeader()
        assertTrue("broken-pipe OCR write must release the fd, not leak it", out.closed)
        // A later close() from the session manager must stay safe/idempotent.
        writer.close()
        assertTrue(out.closed)
    }

    @Test fun `writeHeader emits header frame with OCR_V1 protocol`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeHeader()
        writer.close()
        val frames = readAllFrames(out.toByteArray())
        assertEquals(1, frames.size)
        assertTrue("First frame should carry the OCR_V1 protocol id", frames[0].contains("mindlayer.stream.ocr.v1"))
    }

    @Test fun `writeFrameReceived emits ocr_frame_received with frameId + queueDepth`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFrameReceived(frameId = 42L, queueDepth = 3)
        writer.close()
        val frames = readAllFrames(out.toByteArray())
        // 2 frames: header (auto-emitted) + event.
        assertEquals(2, frames.size)
        val event = frames[1]
        assertTrue(event.contains("ocr_frame_received"))
        assertTrue(event.contains("\"frameId\":42"))
        assertTrue(event.contains("\"queueDepth\":3"))
    }

    @Test fun `writeFrameRejectedQuality includes reason and optional score`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFrameRejectedQuality(frameId = 7L, reason = "blur", score = 95.5f)
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue(event.contains("ocr_frame_rejected_quality"))
        assertTrue(event.contains("\"reason\":\"blur\""))
        assertTrue(event.contains("\"score\":95.5"))
    }

    @Test fun `writeFrameDroppedBusy includes retryAfterMs`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFrameDroppedBusy(frameId = 8L, retryAfterMs = 250L)
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue(event.contains("ocr_frame_dropped_busy"))
        assertTrue(event.contains("\"retryAfterMs\":250"))
    }

    @Test fun `writeFieldUpdate emits OCR_FIELD_UPDATE`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFieldUpdate(
            fieldName = "/total",
            topValue = "12.99",
            confidence = "high",
            consecutiveAgreement = 2,
        )
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue(event.contains("ocr_field_update"))
        assertTrue(event.contains("\"fieldName\":\"/total\""))
        assertTrue(event.contains("\"topValue\":\"12.99\""))
        assertTrue(event.contains("\"confidence\":\"high\""))
        assertTrue(event.contains("\"consecutiveAgreement\":2"))
    }

    @Test fun `writeFieldLocked emits OCR_FIELD_LOCKED`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFieldLocked("/merchant", "Cafe X")
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue(event.contains("ocr_field_locked"))
        assertTrue(event.contains("\"fieldName\":\"/merchant\""))
        assertTrue(event.contains("\"topValue\":\"Cafe X\""))
    }

    @Test fun `writeResultSnapshot and writeResultFinalized emit expected types`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeResultSnapshot("""{"total":"12.99"}""")
        writer.writeResultFinalized("""{"total":"12.99","tax":"1.04"}""")
        writer.close()
        val frames = readAllFrames(out.toByteArray())
        // Header + snapshot + finalized = 3 frames.
        assertEquals(3, frames.size)
        assertTrue(frames[1].contains("ocr_result_snapshot"))
        assertTrue(frames[2].contains("ocr_result_finalized"))
    }

    @Test fun `writeFrameProcessing and writeFrameProcessed emit expected types`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFrameProcessing(frameId = 1L)
        writer.writeFrameProcessed(frameId = 1L, lineCount = 5, durationMs = 120L)
        writer.close()
        val frames = readAllFrames(out.toByteArray())
        assertEquals(3, frames.size)
        assertTrue(frames[1].contains("ocr_frame_processing"))
        assertTrue(frames[2].contains("ocr_frame_processed"))
        assertTrue(frames[2].contains("\"lineCount\":5"))
        assertTrue(frames[2].contains("\"durationMs\":120"))
    }

    @Test fun `writeThrottleHint emits expected type`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeThrottleHint(recommendedIntervalMs = 500L)
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue(event.contains("ocr_throttle_hint"))
        assertTrue(event.contains("\"recommendedIntervalMs\":500"))
    }

    @Test fun `writeDone and writeError emit terminal types`() {
        val out1 = ByteArrayOutputStream()
        OcrTokenStreamWriter.forTesting(out1).apply { writeDone("success"); close() }
        val out2 = ByteArrayOutputStream()
        OcrTokenStreamWriter.forTesting(out2).apply { writeError(3007, "schema invalid"); close() }
        assertTrue(readAllFrames(out1.toByteArray())[1].contains("\"type\":\"done\""))
        val errFrame = readAllFrames(out2.toByteArray())[1]
        assertTrue(errFrame.contains("\"type\":\"error\""))
        assertTrue(errFrame.contains("OCR_SCHEMA_INVALID"))
        assertTrue(errFrame.contains("\"codeInt\":3007"))
    }

    @Test fun `sequence numbers monotonically increase across events`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFrameReceived(1L, 0)
        writer.writeFrameReceived(2L, 1)
        writer.writeFrameReceived(3L, 2)
        writer.close()
        val seqs = readAllFrames(out.toByteArray())
            .drop(1) // skip header
            .map { frame -> Regex("\"seq\":(\\d+)").find(frame)?.groupValues?.get(1)?.toLong() ?: -1 }
        assertEquals(listOf(1L, 2L, 3L), seqs)
    }

    @Test fun `close is idempotent`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeHeader()
        writer.close()
        writer.close() // no-op
        writer.writeHeader() // no-op
        // No exception thrown.
    }

    @Test fun `event written before header auto-emits header first`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFrameReceived(frameId = 99L, queueDepth = 0)
        writer.close()
        val frames = readAllFrames(out.toByteArray())
        assertEquals(2, frames.size)
        assertTrue("First frame should be the header", frames[0].contains("mindlayer.stream.ocr.v1"))
        assertTrue("Second frame should be the event", frames[1].contains("ocr_frame_received"))
    }

    @Test fun `writeFieldUpdate with bbox emits 8-element array`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val bbox = floatArrayOf(0.1f, 0.2f, 0.3f, 0.2f, 0.3f, 0.4f, 0.1f, 0.4f)
        writer.writeFieldUpdate(
            fieldName = "/total",
            topValue = "12.99",
            confidence = "high",
            consecutiveAgreement = 1,
            boundingBox = bbox,
        )
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue(event.contains("ocr_field_update"))
        assertTrue(event.contains("\"bbox\":[0.1,0.2,0.3,0.2,0.3,0.4,0.1,0.4]"))
    }

    @Test fun `writeFieldUpdate without bbox omits the bbox key`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFieldUpdate(
            fieldName = "/total",
            topValue = "12.99",
            confidence = "high",
            consecutiveAgreement = 1,
        )
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue("bbox key must be absent when null", !event.contains("\"bbox\""))
    }

    @Test fun `writeFieldLocked with bbox emits 8-element array`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        val bbox = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f)
        writer.writeFieldLocked("/merchant", "Cafe X", boundingBox = bbox)
        writer.close()
        val event = readAllFrames(out.toByteArray())[1]
        assertTrue(event.contains("ocr_field_locked"))
        assertTrue(event.contains("\"bbox\":[0.0,0.0,1.0,0.0,1.0,1.0,0.0,1.0]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writeFieldUpdate rejects wrong-sized bbox`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFieldUpdate(
            fieldName = "/total",
            topValue = "x",
            confidence = "low",
            consecutiveAgreement = 1,
            boundingBox = floatArrayOf(0f, 0f, 1f, 1f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writeFieldLocked rejects wrong-sized bbox`() {
        val out = ByteArrayOutputStream()
        val writer = OcrTokenStreamWriter.forTesting(out)
        writer.writeFieldLocked("/x", "v", boundingBox = floatArrayOf(0f, 0f, 1f))
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun readAllFrames(bytes: ByteArray): List<String> {
        val frames = mutableListOf<String>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) {
            val len = buf.int
            if (buf.remaining() < len) break
            val payload = ByteArray(len)
            buf.get(payload)
            frames += payload.decodeToString()
        }
        return frames
    }
}
