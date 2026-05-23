package com.adsamcik.mindlayer.service.engine

import android.os.Binder
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ServiceBinder
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 7 OCR pipeline stress coverage — complements
 * [OcrSessionLifecycleRaceTest] (lifecycle races) with three orthogonal
 * stress dimensions from `docs/ROADMAP.md` § Phase 7:
 *
 *  - **A. SHM pool exhaustion** — pins
 *    [MediaPartYPlaneExtractor.extractY] propagation of
 *    [SharedMemoryPoolExhaustedException] and the typed
 *    `TRANSIENT_RESOURCE_EXHAUSTED` wire mapping emitted by
 *    `ServiceBinder.pushOcrFrame`.
 *  - **B. 1000-frame single-session stress** — drive [OcrSessionManager]
 *    1000 frames and assert no bookkeeping field grows unboundedly.
 *    Conservative ceilings, not equalities.
 *  - **C. Rotation × ROI under stress** — `{0,90,180,270}` ×
 *    `{full,center,corner}`, asserting post-transform dimensions that
 *    actually flow to `engine.recognise()` via a real dispatcher.
 *
 * The SHM→typed-wire contract is covered here so OCR frame intake keeps
 * the same retryable resource-exhaustion semantics as inference media intake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrPipelineStressTest {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        ioScope.coroutineContext[Job]?.cancel()
    }

    // ── Shared helpers ─────────────────────────────────────────────────

    private fun stressLimits() = OcrLimits(
        maxConcurrentOcrSessions = 4,
        // 100k/min ≫ 1000-frame stress so the per-UID token bucket
        // never reject-drops a legitimate frame.
        maxOcrFramesPerMinute = 100_000,
        maxFramesPerOcrSession = 2_000,
        maxOcrSessionDurationMs = 10L * 60L * 1000L,
        ocrPerFrameDecodeBudgetTokens = 1024,
        ocrSchemaJsonMaxLen = 16 * 1024,
    )

    private fun cfg() = OcrSessionConfig(
        mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        outputSchemaJson = """{"type":"object"}""",
    )

    /**
     * Synth Y-plane with horizontal-band high-contrast pattern that
     * passes [OcrFrameQualityPresort]. Seed governs jitter so dHash
     * differs between frames (avoids duplicate-rejection at scale).
     */
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

    /** Dispatcher mock that drains submitted jobs synchronously so
     * [OcrSessionManager.OcrSession.activeJobs] does not accumulate. */
    private fun fastDrainingDispatcher(): OcrRecognitionDispatcher = mockk(relaxed = true) {
        every { registerSession(any(), any()) } just Runs
        every {
            submit(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            // Pre-completed Job → activeJobs.invokeOnCompletion fires
            // immediately, mimicking instant recognise.
            Job().also { it.complete() }
        }
        every { finalizeAsync(any(), any()) } answers { Job().also { it.complete() } }
        coEvery { finalize(any(), any()) } just Runs
        every { closeSession(any()) } just Runs
    }

    private fun managerWith(dispatcher: OcrRecognitionDispatcher): OcrSessionManager =
        OcrSessionManager(limits = stressLimits(), recognitionDispatcher = dispatcher)

    private fun serviceBinderForOcrTest(
        ocr: OcrSessionManager,
        pool: SharedMemoryPool,
    ): ServiceBinder {
        var nowMs = 0L
        return ServiceBinder(
            service = mockk<MindlayerMlService>(relaxed = true),
            engineManager = mockk(relaxed = true),
            orchestrator = mockk(relaxed = true),
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk(relaxed = true),
            memoryBudget = mockk(relaxed = true),
            context = mockk(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("com.test.ocr", "deadbeef", "OCR Test") },
            allowlistStore = mockk<AllowlistStore>(relaxed = true) {
                every { isDenied(any(), any()) } returns false
                every { isAllowed(any(), any()) } returns true
            },
            rateLimiter = RateLimiter(
                maxRequestsPerMinute = 60_000,
                maxConcurrent = 1_000,
                timeSource = {
                    nowMs += 1_000L
                    nowMs
                },
            ),
            ocrSessionManager = ocr,
            sharedMemoryPool = pool,
        )
    }

    /** Reflectively pull the internal `OcrSession` so we can introspect
     * the live bookkeeping fields that have no public accessor. */
    private fun internalSession(
        mgr: OcrSessionManager,
        sessionId: String,
    ): OcrSessionManager.OcrSession {
        val field = OcrSessionManager::class.java.getDeclaredField("sessions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(mgr) as Map<String, OcrSessionManager.OcrSession>
        return map[sessionId] ?: error("OcrSession $sessionId not found in manager")
    }

    // ══════════════════════════════════════════════════════════════════
    // A. SHM pool exhaustion translation
    // ══════════════════════════════════════════════════════════════════

    /**
     * Characterisation: [MediaPartYPlaneExtractor.extractY] does NOT
     * swallow [SharedMemoryPoolExhaustedException] thrown by
     * [SharedMemoryPool.stageImage] — it propagates as-is, preserving
     * the typed `retryAfterMs`. This is the precondition the binder
     * needs in order to map it to `TRANSIENT_RESOURCE_EXHAUSTED`.
     */
    @Test fun `MediaPartYPlaneExtractor propagates SharedMemoryPoolExhaustedException with retry hint preserved`() {
        val exhausted = SharedMemoryPoolExhaustedException(
            reason = "global_count_cap",
            currentCount = 8,
            currentBytes = 32L * 1024 * 1024,
            retryAfterMs = 250L,
        )
        val pool = mockk<SharedMemoryPool>()
        every { pool.stageImage(any(), any()) } throws exhausted

        val pipe = android.os.ParcelFileDescriptor.createPipe()
        try {
            val frame = com.adsamcik.mindlayer.MediaPart(
                requestId = "ocr-shm-exhaustion",
                kind = com.adsamcik.mindlayer.MediaPart.KIND_IMAGE,
                mimeType = "image/jpeg",
                source = pipe[0],
                isSharedMemory = false,
                payloadBytes = 1024L,
                width = 64,
                height = 64,
                pixelFormat = 0,
                rowStride = 64,
            )
            val thrown = runCatching {
                MediaPartYPlaneExtractor.extractY(frame, pool, "ocr:1000:sid:1")
            }.exceptionOrNull()
            assertTrue(
                "expected SharedMemoryPoolExhaustedException to propagate, got $thrown",
                thrown is SharedMemoryPoolExhaustedException,
            )
            val e = thrown as SharedMemoryPoolExhaustedException
            // retryAfterMs preserved → binder can surface it as the
            // typed wire retry hint.
            assertEquals(250L, e.retryAfterMs)
            assertTrue(
                "exception message must carry retryAfterMs hint, was '${e.message}'",
                e.message?.contains("retryAfterMs=250") == true,
            )
        } finally {
            runCatching { pipe[1].close() }
            runCatching { pipe[0].close() }
        }
    }

    /**
     * Regression: `ServiceBinder.pushOcrFrame` must not downgrade SHM pool
     * exhaustion to a quality rejection. The SDK needs the same typed,
     * retryable wire code that sibling inference paths emit.
     */
    @Test
    fun `pushOcrFrame translates SHM pool exhaustion to typed TRANSIENT_RESOURCE_EXHAUSTED`() {
        val uid = 7_001
        val sessionId = "ocr-shm-exhausted-session"
        val exhausted = SharedMemoryPoolExhaustedException(
            reason = "global_count_cap",
            currentCount = 8,
            currentBytes = 32L * 1024 * 1024,
            retryAfterMs = 250L,
        )
        val pool = mockk<SharedMemoryPool>()
        every { pool.stageImage(any(), any()) } throws exhausted

        val ocr = mockk<OcrSessionManager>(relaxed = true)
        every { ocr.isOwner(uid, sessionId) } returns true
        every { ocr.rejectFrame(any(), any(), any()) } returns OcrFrameAck(
            frameId = 1L,
            status = OcrFrameAck.STATUS_REJECTED_QUALITY,
        )

        val binder = serviceBinderForOcrTest(ocr, pool)
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns uid

        val pipe = android.os.ParcelFileDescriptor.createPipe()
        try {
            val frame = MediaPart(
                requestId = "ocr-shm-exhaustion",
                kind = MediaPart.KIND_IMAGE,
                mimeType = "image/jpeg",
                source = pipe[0],
                isSharedMemory = false,
                payloadBytes = 1024L,
                width = 64,
                height = 64,
                pixelFormat = 0,
                rowStride = 64,
            )
            val thrown = assertThrows(SecurityException::class.java) {
                binder.pushOcrFrame(
                    sessionId,
                    frame,
                    OcrFrameMeta(frameId = 1L, captureTimeMs = 0L),
                )
            }
            assertEquals(
                MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
                MindlayerErrorCode.codeFromWireMessage(thrown.message),
            )
            val body = MindlayerErrorCode.messageFromWireMessage(thrown.message)
            assertTrue(
                "typed wire body must preserve retryAfterMs hint, was: $body",
                body?.contains("retryAfterMs=250") == true,
            )
            verify(exactly = 0) { ocr.rejectFrame(any(), any(), any()) }
        } finally {
            runCatching { pipe[1].close() }
            runCatching { pipe[0].close() }
            unmockkStatic(Binder::class)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // B. 1000-frame single-session stress
    // ══════════════════════════════════════════════════════════════════

    @Test fun `1000 frames in a single session keep bookkeeping bounded`() {
        val dispatcher = fastDrainingDispatcher()
        val mgr = managerWith(dispatcher)
        val sid = mgr.createSession(uid = 7001, cfg())

        // pushFrame requires an attached writer when a dispatcher is
        // wired (else it returns REJECTED_STREAM_NOT_ATTACHED).
        val writer = mockk<OcrTokenStreamWriter>(relaxed = true)
        assertTrue(mgr.attachEventWriter(7001, sid, writer))

        val totalFrames = 1000
        var accepted = 0
        var nonAcceptedAcks = 0
        for (i in 1..totalFrames) {
            val ack = mgr.pushFrame(
                uid = 7001,
                sessionId = sid,
                meta = OcrFrameMeta(frameId = i.toLong(), captureTimeMs = 0L),
                yPlane = textLikeFrame(seed = i),
                width = 64,
                height = 64,
            )
            when (ack.status) {
                OcrFrameAck.STATUS_ACCEPTED -> accepted++
                else -> nonAcceptedAcks++
            }
        }

        // Conservative bound: presort may reject borderline frames.
        assertTrue(
            "expected ≥950 of $totalFrames frames accepted, got accepted=$accepted, other=$nonAcceptedAcks",
            accepted >= 950,
        )

        // Healthy pre-finalize phase (maxFramesPerOcrSession=2000).
        val state = mgr.stateOf(7001, sid)
        assertTrue(
            "session must stay below FINALIZING (phase=${state.phase})",
            state.phase < OcrSessionState.PHASE_FINALIZING,
        )
        assertEquals(accepted, state.framesAccepted)

        // ── Bookkeeping-leak guards (ceilings, not equalities) ─────────
        verify(exactly = 1) { dispatcher.registerSession(any(), any()) }
        verify(atLeast = accepted, atMost = accepted + 4) {
            dispatcher.submit(any(), any(), any(), any(), any(), any(), any(), any())
        }

        // Internal per-session bookkeeping: active-jobs set must drain
        // (fastDrainingDispatcher pre-completes every Job).
        val internal = internalSession(mgr, sid)
        assertTrue(
            "activeJobs must drain, size=${internal.activeJobs.size} after $totalFrames frames",
            internal.activeJobs.size <= 8,
        )
        // framesDropped / framesRejected should not silently scale with
        // total frame count — caps catch a future "rejection-leak" bug
        // where a per-frame reject path mis-counts.
        assertTrue(
            "framesDropped should not scale with volume, was ${state.framesDropped}",
            state.framesDropped <= 16,
        )

        // Cleanup
        runBlocking { mgr.close(7001, sid) }
    }

    // ══════════════════════════════════════════════════════════════════
    // C. Rotation × ROI under stress
    // ══════════════════════════════════════════════════════════════════

    /**
     * Drive 50 frames per rotation through `pushFrame`; verify the
     * engine receives the post-rotation dimensions. For 90/270 the
     * width/height swap. Uses a real [OcrRecognitionDispatcher] with
     * a mocked [PaddleOcrEngine] so the dimension capture is real,
     * not a mock-arg assertion against a fake transformer.
     */
    @Test fun `rotation under stress dispatches post-rotation dimensions to the engine`() {
        val capturedDims = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val engine = mockk<PaddleOcrEngine>(relaxed = true) {
            coEvery { recognise(any(), any(), any(), any()) } answers {
                val w = secondArg<Int>()
                val h = thirdArg<Int>()
                capturedDims.add(w to h)
                OcrEngineOutput(
                    lines = emptyList(),
                    backend = "CPU",
                    detDurationMs = 0L,
                    recDurationMs = 0L,
                    clsDurationMs = 0L,
                    totalDurationMs = 0L,
                )
            }
        }
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            ioDispatcher = Dispatchers.IO,
            scope = ioScope,
            barcodeDetector = null,
            llmExtractor = NoOpOcrLlmExtractor(),
        )
        val mgr = OcrSessionManager(
            engine = engine,
            limits = stressLimits(),
            recognitionDispatcher = dispatcher,
        )

        val perRotation = 50
        val rotations = listOf(0, 90, 180, 270)
        val expectedByRotation = mutableMapOf<Int, Pair<Int, Int>>()
        var frameCounter = 0L
        val baseW = 80
        val baseH = 48

        for (rot in rotations) {
            val uid = 7100 + rot
            val sid = mgr.createSession(uid, cfg())
            val writer = mockk<OcrTokenStreamWriter>(relaxed = true)
            assertTrue(mgr.attachEventWriter(uid, sid, writer))

            // Expected post-rotation dims: rectangular base so the
            // 90/270 swap is visible.
            expectedByRotation[rot] = when (rot) {
                0, 180 -> baseW to baseH
                90, 270 -> baseH to baseW
                else -> baseW to baseH
            }

            for (i in 1..perRotation) {
                frameCounter++
                val ack = mgr.pushFrame(
                    uid = uid,
                    sessionId = sid,
                    meta = OcrFrameMeta(
                        frameId = frameCounter,
                        captureTimeMs = 0L,
                        rotationDegrees = rot,
                    ),
                    yPlane = textLikeFrame(w = baseW, h = baseH, seed = (frameCounter * 31).toInt()),
                    width = baseW,
                    height = baseH,
                )
                assertTrue(
                    "frame $i rot=$rot expected ACCEPTED, got ${ack.status}",
                    ack.status == OcrFrameAck.STATUS_ACCEPTED ||
                        ack.status == OcrFrameAck.STATUS_REJECTED_QUALITY,
                )
            }
            runBlocking {
                withTimeout(10_000L) { mgr.finalize(uid, sid) }
                mgr.close(uid, sid)
            }
        }

        // Engine must have seen the swapped dims for 90/270 and the
        // straight dims for 0/180. Use frequency assertions — presort
        // may reject the occasional jitter-borderline frame, but the
        // dim mix must dominate per rotation.
        val byDim = capturedDims.toList().groupingBy { it }.eachCount()
        for (rot in rotations) {
            val (expW, expH) = expectedByRotation.getValue(rot)
            val count = byDim[expW to expH] ?: 0
            assertTrue(
                "rotation=$rot: expected ≥1 engine call with dims=${expW}x${expH}, " +
                    "captured=$byDim",
                count >= 1,
            )
        }
        // Sanity: each rotation pair MUST be represented (engine saw
        // both portrait and landscape orientations).
        val portraitDims = capturedDims.count { it == (baseH to baseW) }
        val landscapeDims = capturedDims.count { it == (baseW to baseH) }
        assertTrue(
            "expected both orientations represented, portrait=$portraitDims landscape=$landscapeDims",
            portraitDims >= 1 && landscapeDims >= 1,
        )
    }

    /**
     * ROI under stress — 50 frames each for three distinct ROIs:
     * full-frame (regionJson=null), centered crop, and corner crop.
     * Verifies the engine receives the post-crop dimensions.
     */
    @Test fun `ROI under stress dispatches post-crop dimensions to the engine`() {
        val capturedDims = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val engine = mockk<PaddleOcrEngine>(relaxed = true) {
            coEvery { recognise(any(), any(), any(), any()) } answers {
                capturedDims.add(secondArg<Int>() to thirdArg<Int>())
                OcrEngineOutput(
                    lines = emptyList(),
                    backend = "CPU",
                    detDurationMs = 0L,
                    recDurationMs = 0L,
                    clsDurationMs = 0L,
                    totalDurationMs = 0L,
                )
            }
        }
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            ioDispatcher = Dispatchers.IO,
            scope = ioScope,
            barcodeDetector = null,
            llmExtractor = NoOpOcrLlmExtractor(),
        )
        val mgr = OcrSessionManager(
            engine = engine,
            limits = stressLimits(),
            recognitionDispatcher = dispatcher,
        )

        // 96×64 base so center / corner crops yield distinct expected
        // dimensions vs full-frame.
        val baseW = 96
        val baseH = 64
        data class Roi(val name: String, val json: String?, val expW: Int, val expH: Int)
        val rois = listOf(
            Roi("full",   null,                                            baseW, baseH),
            // Center 50% × 50% → 48×32.
            Roi("center", """{"x":0.25,"y":0.25,"w":0.5,"h":0.5}""",       48, 32),
            // Corner 25% × 50% (top-left) → 24×32.
            Roi("corner", """{"x":0.0,"y":0.0,"w":0.25,"h":0.5}""",        24, 32),
        )

        val perRoi = 50
        var frameCounter = 0L
        for ((index, roi) in rois.withIndex()) {
            val uid = 7200 + index
            val sid = mgr.createSession(uid, cfg())
            val writer = mockk<OcrTokenStreamWriter>(relaxed = true)
            assertTrue(mgr.attachEventWriter(uid, sid, writer))

            for (i in 1..perRoi) {
                frameCounter++
                val ack = mgr.pushFrame(
                    uid = uid,
                    sessionId = sid,
                    meta = OcrFrameMeta(
                        frameId = frameCounter,
                        captureTimeMs = 0L,
                        rotationDegrees = 0,
                        regionJson = roi.json,
                    ),
                    yPlane = textLikeFrame(w = baseW, h = baseH, seed = (frameCounter * 37).toInt()),
                    width = baseW,
                    height = baseH,
                )
                // Either ACCEPTED (most) or REJECTED_QUALITY (dHash
                // duplicate near boundary) — both are legitimate
                // service-side outcomes; the test asserts the dim
                // mix downstream, not per-frame acceptance.
                assertTrue(
                    "roi=${roi.name} frame $i unexpected ack status=${ack.status}",
                    ack.status == OcrFrameAck.STATUS_ACCEPTED ||
                        ack.status == OcrFrameAck.STATUS_REJECTED_QUALITY,
                )
            }
            runBlocking {
                withTimeout(10_000L) { mgr.finalize(uid, sid) }
                mgr.close(uid, sid)
            }
        }

        val byDim = capturedDims.toList().groupingBy { it }.eachCount()
        for (roi in rois) {
            val key = roi.expW to roi.expH
            val count = byDim[key] ?: 0
            assertTrue(
                "roi=${roi.name}: expected ≥1 engine call with dims=${roi.expW}x${roi.expH}, " +
                    "captured=$byDim",
                count >= 1,
            )
        }
        // All three distinct dimension footprints must show up — proves
        // the rotation/ROI pipeline preserves per-frame metadata under
        // load instead of caching the first frame's transform.
        val distinct = rois.count { (byDim[it.expW to it.expH] ?: 0) >= 1 }
        assertEquals("all three ROI dims must surface in engine input", 3, distinct)
    }
}
