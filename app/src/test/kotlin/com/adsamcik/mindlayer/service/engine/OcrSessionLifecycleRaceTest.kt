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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException

/**
 * OCR lifecycle race tests — the "High" Phase 5 audit gap.
 *
 * Drives [OcrSessionManager] + [OcrRecognitionDispatcher] through the
 * same real components production runs (no fakes for the dispatcher
 * itself), using a [CompletableDeferred] to block the mocked
 * [PaddleOcrEngine.recognise] mid-frame so we can stress:
 *
 *  - **FinalizeRace**: `finalize()` must block until in-flight recognise
 *    jobs drain. No `FrameProcessed` / `FieldUpdate` events may
 *    surface AFTER `ResultFinalized` for the same frame.
 *  - **ReaderDeath**: when the SDK closes its read end of the pipe
 *    mid-frame, the writer must detect `IOException`, mark itself
 *    closed, and NOT propagate the failure to subsequent session
 *    methods (`pushFrame`, `close`).
 *  - **BinderDeath / closeAllForUid**: closeAllForUid cancels in-flight
 *    jobs, closes the writer, and removes all sessions for the uid.
 *    Other UIDs' sessions are unaffected.
 *  - **MultiSessionFinalize**: finalizing one session for a uid must
 *    NOT touch the second session — the dispatcher's per-session
 *    state isolation holds under load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrSessionLifecycleRaceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        ioScope.coroutineContext[Job]?.cancel()
    }

    // ── Test harness ────────────────────────────────────────────────────

    private fun limits() = OcrLimits(
        maxConcurrentOcrSessions = 4,
        maxOcrFramesPerMinute = 600,
        maxFramesPerOcrSession = 16,
        maxOcrSessionDurationMs = 60_000L,
        ocrPerFrameDecodeBudgetTokens = 1024,
        ocrSchemaJsonMaxLen = 16 * 1024,
    )

    private fun cfg() = OcrSessionConfig(
        mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        outputSchemaJson = """{"type":"object"}""",
    )

    private fun textLikeFrame(w: Int = 64, h: Int = 64, seed: Int = 0): ByteArray {
        val rng = java.util.Random(seed.toLong())
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val band = (y / 4) % 2
            val base = if (band == 0) 235 else 25
            val jitter = if (band == 0) rng.nextInt(31) - 15 else rng.nextInt(21) - 10
            out[y * w + x] = (base + jitter).coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Blocking engine — recognise() suspends until the supplied gate releases. */
    private fun blockingEngine(release: CompletableDeferred<Unit>) = mockk<PaddleOcrEngine>(relaxed = true).also { eng ->
        coEvery { eng.recognise(any(), any(), any(), any()) } coAnswers {
            release.await()
            OcrEngineOutput(
                lines = listOf(
                    OcrTextLine("blocked-line", confidence = OcrFieldFusion.Confidence.HIGH),
                ),
                backend = "CPU",
                detDurationMs = 1L, recDurationMs = 1L, clsDurationMs = 0L, totalDurationMs = 2L,
            )
        }
    }

    private data class Session(
        val sessionId: String,
        val pipe: Array<ParcelFileDescriptor>,
        val writer: OcrTokenStreamWriter,
    )

    private class Harness(
        val manager: OcrSessionManager,
        val dispatcher: OcrRecognitionDispatcher,
    )

    private fun harness(engine: PaddleOcrEngine): Harness {
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            ioDispatcher = Dispatchers.IO,
            scope = ioScope,
            barcodeDetector = null,
            llmExtractor = NoOpOcrLlmExtractor(),
        )
        val manager = OcrSessionManager(
            engine = engine,
            limits = limits(),
            recognitionDispatcher = dispatcher,
        )
        return Harness(manager, dispatcher)
    }

    private fun openSession(h: Harness, uid: Int): Session {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val writer = OcrTokenStreamWriter(pipe[1])
        val sid = h.manager.createSession(uid, cfg())
        assertTrue(h.manager.attachEventWriter(uid, sid, writer))
        return Session(sid, pipe, writer)
    }

    private fun readEvents(
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
                    val len = try { Integer.reverseBytes(input.readInt()) }
                    catch (_: EOFException) { break }
                    if (len !in 0..(1 shl 20)) break
                    val payload = ByteArray(len)
                    try { input.readFully(payload) } catch (_: EOFException) { break }
                    val text = payload.decodeToString()
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

    // ── 1. Finalize during recognise must block until the engine completes ─

    @Test fun `finalize waits for in-flight recognise before emitting terminal events`() {
        val release = CompletableDeferred<Unit>()
        val engine = blockingEngine(release)
        val h = harness(engine)
        val s = openSession(h, uid = 100)

        val ack = h.manager.pushFrame(
            uid = 100, sessionId = s.sessionId,
            meta = OcrFrameMeta(frameId = 1L, captureTimeMs = 0L),
            yPlane = textLikeFrame(seed = 1), width = 64, height = 64,
        )
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)

        // Launch finalize from a separate coroutine — it must block on
        // `drainActiveJobs(joinAll)` while engine.recognise is parked
        // on the deferred.
        val finalizeJob = runBlocking {
            ioScope.async { h.manager.finalize(100, s.sessionId) }
        }

        // Give the manager a chance to enter the drain loop, then
        // assert finalize is still pending.
        runBlocking {
            val completedEarly = withTimeoutOrNull(300L) { finalizeJob.await(); true }
            assertNull("finalize must NOT complete while engine is blocked", completedEarly)
        }

        // Phase is FINALIZING but NOT yet FINALIZED.
        val midState = h.manager.stateOf(100, s.sessionId)
        assertEquals(OcrSessionState.PHASE_FINALIZING, midState.phase)

        // Release the engine; finalize should now drain + emit terminal events.
        release.complete(Unit)
        runBlocking {
            withTimeout(5_000L) { finalizeJob.await() }
        }
        assertEquals(OcrSessionState.PHASE_FINALIZED, h.manager.stateOf(100, s.sessionId).phase)

        val events = readEvents(s.pipe[0], stopAfter = StreamEventType.DONE)
        val types = events.map { it.type }
        // Frame_processed for frameId=1 must precede ResultFinalized.
        val processedIdx = types.indexOf(StreamEventType.OCR_FRAME_PROCESSED)
        val finalizedIdx = types.indexOf(StreamEventType.OCR_RESULT_FINALIZED)
        val doneIdx = types.indexOf(StreamEventType.DONE)
        assertTrue("expected FRAME_PROCESSED, types=$types", processedIdx >= 0)
        assertTrue("expected RESULT_FINALIZED, types=$types", finalizedIdx >= 0)
        assertTrue("FRAME_PROCESSED must precede RESULT_FINALIZED, types=$types",
            processedIdx < finalizedIdx)
        assertTrue("RESULT_FINALIZED must precede DONE, types=$types",
            finalizedIdx < doneIdx)
    }

    // ── 2. Reader death — pipe read end closes mid-stream ─────────────

    @Test fun `pipe reader death mid-stream is absorbed by writer without breaking session lifecycle`() {
        // Use a sentinel "always-empty" engine — finishes immediately, no
        // blocking. Reader closes its end before the writer can flush the
        // result. Writer must mark itself closed and not throw.
        val engine = mockk<PaddleOcrEngine>(relaxed = true).also { eng ->
            coEvery { eng.recognise(any(), any(), any(), any()) } returns OcrEngineOutput(
                lines = emptyList(),
                backend = "CPU",
                detDurationMs = 0, recDurationMs = 0, clsDurationMs = 0, totalDurationMs = 0,
            )
        }
        val h = harness(engine)
        val s = openSession(h, uid = 101)

        // Close the read end IMMEDIATELY. Subsequent writes from the
        // service should hit EPIPE / EAGAIN; the writer absorbs them
        // and flips its `closed` flag.
        s.pipe[0].close()

        val ack = h.manager.pushFrame(
            uid = 101, sessionId = s.sessionId,
            meta = OcrFrameMeta(frameId = 1L, captureTimeMs = 0L),
            yPlane = textLikeFrame(seed = 2), width = 64, height = 64,
        )
        // pushFrame returns the ACK regardless — recognition + emission
        // is async, and emission failures must NOT propagate to the
        // intake path.
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)

        // Finalize must succeed in finite time. Without the writer's
        // IOException absorption (see OcrTokenStreamWriter.writeFrame
        // catch), the finalize emission would throw and the test would
        // hang here.
        runBlocking {
            withTimeout(5_000L) { h.manager.finalize(101, s.sessionId) }
        }
        assertEquals(OcrSessionState.PHASE_FINALIZED, h.manager.stateOf(101, s.sessionId).phase)

        // close() is idempotent + safe even after pipe peer died.
        runBlocking { h.manager.close(101, s.sessionId) }
    }

    // ── 3. Binder death simulation via closeAllForUid mid-frame ───────

    @Test fun `closeAllForUid mid-frame cancels in-flight recognise and cleans up writer`() {
        val release = CompletableDeferred<Unit>()
        val engine = blockingEngine(release)
        val h = harness(engine)
        val s = openSession(h, uid = 102)

        val ack = h.manager.pushFrame(
            uid = 102, sessionId = s.sessionId,
            meta = OcrFrameMeta(frameId = 1L, captureTimeMs = 0L),
            yPlane = textLikeFrame(seed = 3), width = 64, height = 64,
        )
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)

        // Wait briefly to let the recognise job dispatch onto IO and
        // park on `release.await()`.
        Thread.sleep(50)

        // Simulate binder death — closeAllForUid is what
        // ServiceBinder.BinderObituary.binderDied invokes.
        h.manager.closeAllForUid(uid = 102)

        // Session must be gone.
        assertEquals(0, h.manager.activeSessionCount())

        // The recognise job is cancelled — release() must NOT cause a
        // late event to surface. We complete the deferred to unblock
        // the engine (otherwise the cancelled coroutine leaks a stuck
        // mock invocation), then ensure no exception propagated.
        release.complete(Unit)

        // Pipe is fully drained (writer.close() was invoked by cleanupSession).
        val events = readEvents(s.pipe[0], stopAfter = "__never_appears__", timeoutMs = 2_000L)
        // Any events that surfaced must be the initial ones; no
        // ResultFinalized / DONE because the session was cancelled
        // (cleanupSession does NOT call dispatcher.finalize on cancel).
        val terminalSeen = events.any {
            it.type == StreamEventType.OCR_RESULT_FINALIZED || it.type == StreamEventType.DONE
        }
        // Cancellation MUST NOT emit a fabricated terminal event.
        org.junit.Assert.assertFalse(
            "cancellation path must not emit terminal events, saw $events",
            terminalSeen,
        )
    }

    // ── 4. Multi-session finalize is per-session ──────────────────────

    @Test fun `finalize on one session leaves another session for the same uid untouched`() {
        val release = CompletableDeferred<Unit>()
        val engine = blockingEngine(release)
        val h = harness(engine)

        val a = openSession(h, uid = 103)
        val b = openSession(h, uid = 103)
        assertNotEquals("session ids must differ", a.sessionId, b.sessionId)

        // Push one frame on each session.
        assertEquals(
            OcrFrameAck.STATUS_ACCEPTED,
            h.manager.pushFrame(103, a.sessionId,
                OcrFrameMeta(frameId = 1L, captureTimeMs = 0L),
                textLikeFrame(seed = 4), 64, 64).status,
        )
        assertEquals(
            OcrFrameAck.STATUS_ACCEPTED,
            h.manager.pushFrame(103, b.sessionId,
                OcrFrameMeta(frameId = 1L, captureTimeMs = 0L),
                textLikeFrame(seed = 5), 64, 64).status,
        )

        // Finalize only session A while both engines are still blocked.
        val finalizeA = runBlocking { ioScope.async { h.manager.finalize(103, a.sessionId) } }

        // Confirm finalize A hasn't completed (engine still blocked).
        runBlocking {
            val done = withTimeoutOrNull(200L) { finalizeA.await(); true }
            assertNull("finalize A must block while engine blocks", done)
        }
        // Session B unaffected — still ACTIVE.
        assertEquals(
            "session B must remain ACTIVE while A is finalizing",
            OcrSessionState.PHASE_ACTIVE,
            h.manager.stateOf(103, b.sessionId).phase,
        )

        // Release. A's drain completes; B's drain is also unblocked.
        release.complete(Unit)
        runBlocking { withTimeout(5_000L) { finalizeA.await() } }

        // A is FINALIZED.
        assertEquals(OcrSessionState.PHASE_FINALIZED, h.manager.stateOf(103, a.sessionId).phase)

        // B is still ACTIVE — finalize only acts on its target session.
        // (Its recognise job has now completed because the same release
        // gate unblocked it, but the session manager only flipped A's
        // phase.)
        val bState = h.manager.stateOf(103, b.sessionId).phase
        assertTrue(
            "session B phase should still be ACTIVE (or FINALIZING-on-its-own-terms), was $bState",
            bState < OcrSessionState.PHASE_FINALIZED,
        )

        // Drain A's pipe (terminal events) so the writer closes cleanly.
        val aEvents = readEvents(a.pipe[0], stopAfter = StreamEventType.DONE)
        assertNotNull(aEvents.firstOrNull { it.type == StreamEventType.OCR_RESULT_FINALIZED })

        // Finalize B too and verify no leak.
        runBlocking { h.manager.finalize(103, b.sessionId) }
        val bEvents = readEvents(b.pipe[0], stopAfter = StreamEventType.DONE)
        assertNotNull(bEvents.firstOrNull { it.type == StreamEventType.OCR_RESULT_FINALIZED })
    }
}
