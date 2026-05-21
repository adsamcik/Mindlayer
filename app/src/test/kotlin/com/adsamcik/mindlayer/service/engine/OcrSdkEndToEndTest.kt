package com.adsamcik.mindlayer.service.engine

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException

/**
 * End-to-end test for the Phase 4 PR #2 wire:
 *
 *   FakeEngine -> OcrRecognitionDispatcher -> OcrTokenStreamWriter ->
 *   PFD pipe -> SDK-shape DataInputStream -> decoded StreamEvent
 *
 * Bypasses the binder (which requires a real Android service context
 * and is exercised by the `MindlayerOcrIntegrationTest` mock in `:sdk`
 * and by `EngineCoexistenceInstrumentedTest`) but drives the full
 * recognition + finalize lifecycle through the SAME components
 * production runs:
 *
 *  - `OcrSessionManager` (per-session mutex + active-job tracking +
 *    drain on finalize, PR #2's new contract);
 *  - `OcrRecognitionDispatcher` (writer-mutex guarded emissions);
 *  - `OcrTokenStreamWriter` (4-byte LE-u32 framing on a real PFD).
 *
 * The PaddleOcrEngine is mocked because it owns native LiteRT-LM
 * resources we cannot bring up under Robolectric. That mirrors what
 * an instrumented test would do with `FakePaddleOcrLiteRtRunner`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrSdkEndToEndTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        ioScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun limits() = OcrLimits(
        maxConcurrentOcrSessions = 2,
        maxOcrFramesPerMinute = 60,
        maxFramesPerOcrSession = 8,
        maxOcrSessionDurationMs = 60_000L,
        ocrPerFrameDecodeBudgetTokens = 1024,
        ocrSchemaJsonMaxLen = 16 * 1024,
    )

    private fun config() = OcrSessionConfig(
        mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        outputSchemaJson = """{"type":"object"}""",
    )

    private fun textLikeFrame(w: Int = 64, h: Int = 64, seed: Int = 0): ByteArray {
        val rng = java.util.Random(seed.toLong())
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rowBand = (y / 4) % 2
                val base = if (rowBand == 0) 235 else 25
                val jitter = if (rowBand == 0) rng.nextInt(31) - 15 else rng.nextInt(21) - 10
                out[y * w + x] = (base + jitter).coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Read length-prefixed StreamEvent frames until EOF or `stopAfter` matches an event type. */
    private fun readEventsUntil(
        readEnd: ParcelFileDescriptor,
        stopAfter: String,
        timeoutMs: Long = 5_000L,
    ): List<StreamEvent> = runBlocking {
        withTimeout(timeoutMs) {
            val events = mutableListOf<StreamEvent>()
            DataInputStream(
                BufferedInputStream(
                    ParcelFileDescriptor.AutoCloseInputStream(readEnd),
                ),
            ).use { input ->
                while (true) {
                    val len = try {
                        Integer.reverseBytes(input.readInt())
                    } catch (_: EOFException) {
                        break
                    }
                    if (len !in 0..(1 shl 20)) break
                    val payload = ByteArray(len)
                    try {
                        input.readFully(payload)
                    } catch (_: EOFException) {
                        break
                    }
                    val text = payload.decodeToString()
                    // First frame is the header (different shape from StreamEvent).
                    // Skip anything that doesn't parse as StreamEvent.
                    val event = try {
                        json.decodeFromString(StreamEvent.serializer(), text)
                    } catch (_: Throwable) {
                        continue
                    }
                    events += event
                    if (event.type == stopAfter) break
                }
            }
            events
        }
    }

    private fun fakeOcrOutput(text: String): OcrEngineOutput = OcrEngineOutput(
        lines = listOf(
            OcrTextLine(
                text = text,
                confidence = OcrFieldFusion.Confidence.HIGH,
            ),
        ),
        backend = "CPU",
        detDurationMs = 1L,
        recDurationMs = 1L,
        clsDurationMs = 0L,
        totalDurationMs = 2L,
    )

    private data class Harness(
        val manager: OcrSessionManager,
        val dispatcher: OcrRecognitionDispatcher,
        val pipe: Array<ParcelFileDescriptor>,
        val writer: OcrTokenStreamWriter,
    )

    private fun buildHarness(recognise: (yPlane: ByteArray, w: Int, h: Int) -> OcrEngineOutput): Harness {
        val engine = mockk<PaddleOcrEngine>(relaxed = true)
        coEvery { engine.recognise(any(), any(), any(), any()) } answers {
            val y = firstArg<ByteArray>()
            val w = secondArg<Int>()
            val h = thirdArg<Int>()
            recognise(y, w, h)
        }
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            ioDispatcher = Dispatchers.IO,
            scope = ioScope,
        )
        val manager = OcrSessionManager(
            engine = engine,
            limits = limits(),
            recognitionDispatcher = dispatcher,
        )
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val writer = OcrTokenStreamWriter(pipe[1])
        return Harness(manager, dispatcher, pipe, writer)
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test fun `pushFrame yields FrameProcessing + FrameProcessed events on attached writer`() {
        val h = buildHarness { _, _, _ -> fakeOcrOutput("hello") }
        val sid = h.manager.createSession(uid = 100, config = config())
        assertTrue(h.manager.attachEventWriter(uid = 100, sessionId = sid, writer = h.writer))

        val ack = h.manager.pushFrame(
            uid = 100,
            sessionId = sid,
            meta = OcrFrameMeta(frameId = 1L, captureTimeMs = 0L),
            yPlane = textLikeFrame(seed = 1),
            width = 64,
            height = 64,
        )
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)

        runBlocking { h.manager.finalize(100, sid) }

        val events = readEventsUntil(h.pipe[0], stopAfter = StreamEventType.DONE)
        val types = events.map { it.type }
        assertTrue(
            "expected FRAME_PROCESSING+FRAME_PROCESSED before RESULT_FINALIZED, saw $types",
            types.containsAll(
                listOf(
                    StreamEventType.OCR_FRAME_PROCESSING,
                    StreamEventType.OCR_FRAME_PROCESSED,
                    StreamEventType.OCR_RESULT_FINALIZED,
                    StreamEventType.DONE,
                ),
            ),
        )
        val processingIdx = types.indexOf(StreamEventType.OCR_FRAME_PROCESSING)
        val processedIdx = types.indexOf(StreamEventType.OCR_FRAME_PROCESSED)
        val finalizedIdx = types.indexOf(StreamEventType.OCR_RESULT_FINALIZED)
        val doneIdx = types.indexOf(StreamEventType.DONE)
        assertTrue("ordering: $types", processingIdx < processedIdx)
        assertTrue("ordering: $types", processedIdx < finalizedIdx)
        assertTrue("ordering: $types", finalizedIdx < doneIdx)
    }

    @Test fun `finalize transitions phase to FINALIZED and rejects later frames`() {
        val h = buildHarness { _, _, _ -> fakeOcrOutput("ok") }
        val sid = h.manager.createSession(uid = 100, config = config())
        assertTrue(h.manager.attachEventWriter(100, sid, h.writer))

        h.manager.pushFrame(100, sid, OcrFrameMeta(frameId = 1L, captureTimeMs = 0L), textLikeFrame(seed = 7), 64, 64)
        runBlocking { h.manager.finalize(100, sid) }

        val state = h.manager.stateOf(100, sid)
        assertEquals(OcrSessionState.PHASE_FINALIZED, state.phase)

        val rejected = h.manager.pushFrame(
            100,
            sid,
            OcrFrameMeta(frameId = 2L, captureTimeMs = 1L),
            textLikeFrame(seed = 8),
            64,
            64,
        )
        assertEquals(OcrFrameAck.STATUS_REJECTED_FINALIZED, rejected.status)

        // Drain the pipe so the harness's writer closes cleanly.
        val events = readEventsUntil(h.pipe[0], stopAfter = StreamEventType.DONE)
        assertNotNull(events.firstOrNull { it.type == StreamEventType.OCR_RESULT_FINALIZED })
    }

    @Test fun `close after recognition tears down the writer cleanly`() {
        val h = buildHarness { _, _, _ -> fakeOcrOutput("bye") }
        val sid = h.manager.createSession(uid = 100, config = config())
        assertTrue(h.manager.attachEventWriter(100, sid, h.writer))

        h.manager.pushFrame(100, sid, OcrFrameMeta(frameId = 1L, captureTimeMs = 0L), textLikeFrame(seed = 11), 64, 64)
        runBlocking { h.manager.close(100, sid) }

        // Close drains + cancels the per-session writer, so the pipe
        // reaches EOF in finite time. If anything had wedged the writer,
        // `readEventsUntil` would hit its 2s timeout and fail.
        val events = readEventsUntil(h.pipe[0], stopAfter = "__sentinel__", timeoutMs = 2_000L)
        assertTrue(
            "unexpected events after close: ${events.map { it.type }}",
            events.all {
                it.type == StreamEventType.OCR_FRAME_PROCESSING ||
                    it.type == StreamEventType.OCR_FRAME_PROCESSED ||
                    it.type == StreamEventType.OCR_FIELD_UPDATE
            },
        )
    }
}
