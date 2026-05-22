package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [OcrSessionManager] under Robolectric so the manager's
 * use of [com.adsamcik.mindlayer.service.logging.MindlayerLog] (which
 * routes through ``android.util.Log``) does not throw
 * ``Method i in android.util.Log not mocked``.
 *
 * Covers:
 *  - session creation + per-UID concurrent limit
 *  - frame intake (pushFrame and pushFrameMetadataOnly variants)
 *  - phase transitions (ACTIVE → FINALIZING → CLOSED)
 *  - monotonicity rejection
 *  - rate-limit token bucket
 *  - service-side presort routing
 *  - idle + max-duration sweeper
 *  - ownership semantics for cross-UID isolation
 *  - close + closeAllForUid idempotence
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrSessionManagerTest {

    private fun limits(
        maxSessions: Int = 2,
        maxFramesPerMinute: Int = 10,
        maxFramesPerSession: Int = 5,
        maxDurationMs: Long = 60_000L,
    ) = OcrLimits(
        maxConcurrentOcrSessions = maxSessions,
        maxOcrFramesPerMinute = maxFramesPerMinute,
        maxFramesPerOcrSession = maxFramesPerSession,
        maxOcrSessionDurationMs = maxDurationMs,
        ocrPerFrameDecodeBudgetTokens = 1024,
        ocrSchemaJsonMaxLen = 16 * 1024,
    )

    private fun config(mode: Int = OcrSessionConfig.MODE_GENERAL_DOCUMENT) = OcrSessionConfig(
        mode = mode,
        outputSchemaJson = """{"type":"object"}""",
    )

    private fun meta(
        frameId: Long,
        hint: Int = OcrFrameMeta.QUALITY_GOOD,
        rotationDegrees: Int = 0,
        regionJson: String? = null,
    ) = OcrFrameMeta(
        frameId = frameId,
        captureTimeMs = 0L,
        rotationDegrees = rotationDegrees,
        regionJson = regionJson,
        qualityHint = hint,
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

    private fun mgr(
        lim: OcrLimits = limits(),
        clockNow: () -> Long = { 0L },
        idleTimeoutMs: Long = OcrSessionManager.DEFAULT_IDLE_TIMEOUT_MS,
        recognitionDispatcher: OcrRecognitionDispatcher? = null,
    ) = OcrSessionManager(
        limits = lim,
        clock = clockNow,
        idleTimeoutMs = idleTimeoutMs,
        recognitionDispatcher = recognitionDispatcher,
    )

    private fun dispatcherMock(): OcrRecognitionDispatcher =
        mockk(relaxed = true) {
            every { registerSession(any(), any()) } just Runs
            every { submit(any(), any(), any(), any(), any(), any(), any(), any()) } returns Job()
            every { finalizeAsync(any(), any()) } returns Job()
            coEvery { finalize(any(), any()) } just Runs
            every { closeSession(any()) } just Runs
        }

    // ── createSession ─────────────────────────────────────────────────────

    @Test fun `createSession returns unique id`() {
        val m = mgr()
        val id1 = m.createSession(uid = 100, config())
        val id2 = m.createSession(uid = 100, config())
        assertNotEquals(id1, id2)
        assertEquals(2, m.activeSessionCount())
    }

    @Test fun `createSession id encodes uid`() {
        val m = mgr()
        val id = m.createSession(uid = 42, config())
        assertTrue("Session id should start with ocr-42-", id.startsWith("ocr-42-"))
    }

    @Test fun `createSession rejects unknown mode`() {
        val m = mgr()
        assertThrows(IllegalArgumentException::class.java) {
            m.createSession(100, OcrSessionConfig(mode = 999, outputSchemaJson = "{}"))
        }
    }

    @Test fun `createSession enforces per-UID concurrent session limit`() {
        val m = mgr(lim = limits(maxSessions = 2))
        m.createSession(100, config())
        m.createSession(100, config())
        assertThrows(IllegalStateException::class.java) {
            m.createSession(100, config())
        }
    }

    @Test fun `concurrent limit is per-UID`() {
        val m = mgr(lim = limits(maxSessions = 1))
        m.createSession(100, config())
        m.createSession(200, config())
        assertEquals(2, m.activeSessionCount())
    }

    // ── pushFrame (full presort) ──────────────────────────────────────────

    @Test fun `pushFrame ACCEPTED on first good frame`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        val ack = m.pushFrame(100, sid, meta(1L), textLikeFrame(), 64, 64)
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)
        assertEquals(1L, ack.frameId)
    }

    @Test fun `pushFrame REJECTED_QUALITY when presort fails`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        val blackFrame = ByteArray(64 * 64) { 0 }
        val ack = m.pushFrame(100, sid, meta(1L), blackFrame, 64, 64)
        assertEquals(OcrFrameAck.STATUS_REJECTED_QUALITY, ack.status)
    }

    @Test fun `pushFrame REJECTED_QUALITY when bad dimensions`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        val ack = m.pushFrame(100, sid, meta(1L), ByteArray(50), 10, 10)
        assertEquals(OcrFrameAck.STATUS_REJECTED_QUALITY, ack.status)
    }

    @Test fun `pushFrame REJECTED_QUALITY on duplicate dHash`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        val frame = textLikeFrame(seed = 7)
        m.pushFrame(100, sid, meta(1L), frame, 64, 64)
        val ack = m.pushFrame(100, sid, meta(2L), frame, 64, 64)
        assertEquals(OcrFrameAck.STATUS_REJECTED_QUALITY, ack.status)
    }

    @Test fun `pushFrame REJECTED_QUALITY on non-monotonic frameId`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        m.pushFrame(100, sid, meta(5L), textLikeFrame(seed = 1), 64, 64)
        val ack = m.pushFrame(100, sid, meta(3L), textLikeFrame(seed = 2), 64, 64)
        assertEquals(OcrFrameAck.STATUS_REJECTED_QUALITY, ack.status)
    }

    @Test fun `pushFrame counters update`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64)
        m.pushFrame(100, sid, meta(2L), ByteArray(64 * 64) { 0 }, 64, 64) // black -> reject
        val state = m.stateOf(100, sid)
        assertEquals(1, state.framesAccepted)
        assertEquals(1, state.framesRejected)
    }

    @Test fun `pushFrame auto-finalizes at maxFrames`() {
        val m = mgr(lim = limits(maxFramesPerSession = 2))
        val sid = m.createSession(100, config())
        m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64)
        m.pushFrame(100, sid, meta(2L), textLikeFrame(seed = 2), 64, 64)
        val state = m.stateOf(100, sid)
        assertEquals(OcrSessionState.PHASE_FINALIZING, state.phase)
    }

    @Test fun `pushFrame auto-finalize schedules terminal event after maxFrames`() {
        val dispatcher = dispatcherMock()
        val m = mgr(lim = limits(maxFramesPerSession = 1), recognitionDispatcher = dispatcher)
        val sid = m.createSession(100, config())
        assertTrue(m.attachEventWriter(100, sid, mockk<OcrTokenStreamWriter>(relaxed = true)))
        val ack = m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64)

        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)
        verify(exactly = 1) {
            dispatcher.submit(sid, 1L, any(), 64, 64, any(), any(), any())
            dispatcher.finalizeAsync(sid, any())
        }
    }

    @Test fun `pushFrame rejects when recognition stream is not attached`() {
        val dispatcher = dispatcherMock()
        val m = mgr(recognitionDispatcher = dispatcher)
        val sid = m.createSession(100, config())

        val ack = m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64)

        assertEquals(OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED, ack.status)
        verify(exactly = 0) { dispatcher.submit(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `applyFrameMetadata rotates before applying normalized ROI crop`() {
        val frame = byteArrayOf(
            1, 2, 3,
            4, 5, 6,
        )
        val transformed = mgr().applyFrameMetadata(
            meta = meta(
                frameId = 1L,
                rotationDegrees = 90,
                regionJson = """{"x":0.0,"y":0.0,"w":0.5,"h":1.0}""",
            ),
            yPlane = frame,
            width = 3,
            height = 2,
        )

        assertEquals(1, transformed.width)
        assertEquals(3, transformed.height)
        assertEquals(listOf(4.toByte(), 5.toByte(), 6.toByte()), transformed.yPlane.toList())
    }

    @Test fun `finalize schedules terminal event once`() {
        val dispatcher = dispatcherMock()
        val m = mgr(recognitionDispatcher = dispatcher)
        val sid = m.createSession(100, config())

        runBlocking {
            m.finalize(100, sid)
            m.finalize(100, sid)
        }

        assertEquals(OcrSessionState.PHASE_FINALIZED, m.stateOf(100, sid).phase)
        coVerify(exactly = 1) { dispatcher.finalize(sid, null) }
    }

    @Test fun `pushFrame after finalize returns REJECTED_FINALIZED`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        runBlocking { m.finalize(100, sid) }
        val ack = m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64)
        assertEquals(OcrFrameAck.STATUS_REJECTED_FINALIZED, ack.status)
    }

    // ── Rate-limit token bucket ──────────────────────────────────────────

    @Test fun `pushFrame DROPPED_BUSY when rate limit exceeded`() {
        var ts = 1000L
        val m = mgr(lim = limits(maxFramesPerMinute = 2), clockNow = { ts })
        val sid = m.createSession(100, config())
        m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64) // ok
        ts = 1100L
        m.pushFrame(100, sid, meta(2L), textLikeFrame(seed = 2), 64, 64) // ok
        ts = 1200L
        val ack = m.pushFrame(100, sid, meta(3L), textLikeFrame(seed = 3), 64, 64)
        assertEquals(OcrFrameAck.STATUS_DROPPED_BUSY, ack.status)
        assertTrue(ack.retryAfterMs > 0L)
    }

    @Test fun `rate-limit window slides after 60 seconds`() {
        var ts = 1000L
        val m = mgr(lim = limits(maxFramesPerMinute = 1), clockNow = { ts })
        val sid = m.createSession(100, config())
        m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64)
        ts = 70_000L // 70 seconds later
        val ack = m.pushFrame(100, sid, meta(2L), textLikeFrame(seed = 2), 64, 64)
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)
    }

    // ── pushFrameMetadataOnly ────────────────────────────────────────────

    @Test fun `pushFrameMetadataOnly accepts without pixel data`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        val ack = m.pushFrameMetadataOnly(100, sid, meta(1L))
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)
    }

    @Test fun `pushFrameMetadataOnly enforces monotonicity`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        m.pushFrameMetadataOnly(100, sid, meta(5L))
        val ack = m.pushFrameMetadataOnly(100, sid, meta(3L))
        assertEquals(OcrFrameAck.STATUS_REJECTED_QUALITY, ack.status)
    }

    @Test fun `pushFrameMetadataOnly enforces rate limit`() {
        var ts = 1000L
        val m = mgr(lim = limits(maxFramesPerMinute = 1), clockNow = { ts })
        val sid = m.createSession(100, config())
        m.pushFrameMetadataOnly(100, sid, meta(1L))
        ts = 1100L
        val ack = m.pushFrameMetadataOnly(100, sid, meta(2L))
        assertEquals(OcrFrameAck.STATUS_DROPPED_BUSY, ack.status)
    }

    // ── Ownership semantics ──────────────────────────────────────────────

    @Test fun `cross-UID access throws not-found`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        assertThrows(IllegalStateException::class.java) {
            m.stateOf(200, sid)
        }
    }

    @Test fun `isOwner returns true for owner false for stranger`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        assertTrue(m.isOwner(100, sid))
        assertFalse(m.isOwner(200, sid))
        assertFalse(m.isOwner(100, "non-existent"))
    }

    // ── close / closeAllForUid ───────────────────────────────────────────

    @Test fun `close removes session`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        runBlocking { m.close(100, sid) }
        assertEquals(0, m.activeSessionCount())
        assertFalse(m.isOwner(100, sid))
    }

    @Test fun `close is idempotent`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        runBlocking { m.close(100, sid) }
        runBlocking { m.close(100, sid) }
    }

    @Test fun `close by wrong uid throws`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        assertThrows(IllegalStateException::class.java) {
            runBlocking { m.close(200, sid) }
        }
    }

    @Test fun `closeAllForUid drops only the uid's sessions`() {
        val m = mgr()
        m.createSession(100, config())
        m.createSession(100, config())
        m.createSession(200, config())
        m.closeAllForUid(100)
        assertEquals(1, m.activeSessionCount())
    }

    // ── Idle + expiry sweeper ────────────────────────────────────────────

    @Test fun `idle sessions are reaped on next createSession`() {
        var ts = 0L
        val m = mgr(clockNow = { ts }, idleTimeoutMs = 1_000L)
        val sid = m.createSession(100, config())
        ts = 2_000L
        m.createSession(100, config())
        assertFalse(m.isOwner(100, sid))
    }

    @Test fun `max-duration sessions are reaped on next createSession`() {
        var ts = 0L
        val m = mgr(lim = limits(maxDurationMs = 5_000L), clockNow = { ts })
        val sid = m.createSession(100, config())
        ts = 6_000L
        m.createSession(100, config())
        assertFalse(m.isOwner(100, sid))
    }

    // ── stateOf snapshot ─────────────────────────────────────────────────

    @Test fun `stateOf returns wire-stable snapshot`() {
        val m = mgr()
        val sid = m.createSession(100, config())
        m.pushFrame(100, sid, meta(1L), textLikeFrame(seed = 1), 64, 64)
        val state = m.stateOf(100, sid)
        assertEquals(sid, state.sessionId)
        assertEquals(OcrSessionState.PHASE_ACTIVE, state.phase)
        assertEquals(1, state.framesAccepted)
        assertFalse(state.streamAttached)
    }

    // ── getLimits ────────────────────────────────────────────────────────

    @Test fun `getLimits returns configured limits`() {
        val expected = limits(maxSessions = 3, maxFramesPerSession = 100)
        val m = mgr(lim = expected)
        assertEquals(expected, m.getLimits())
    }

    @Test fun `default limits are non-zero`() {
        val m = mgr()
        val lim = m.getLimits()
        assertTrue(lim.maxConcurrentOcrSessions > 0)
        assertTrue(lim.maxOcrFramesPerMinute > 0)
        assertTrue(lim.maxFramesPerOcrSession > 0)
        assertTrue(lim.maxOcrSessionDurationMs > 0)
    }
}
