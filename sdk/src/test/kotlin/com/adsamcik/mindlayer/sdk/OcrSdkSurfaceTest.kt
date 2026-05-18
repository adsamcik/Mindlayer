package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [OcrProfile], [OcrEvent], and the helper
 * extension functions on [OcrFrameAck] / [OcrSessionState].
 *
 * No service/binder dependency — these are SDK-side surface tests.
 */
class OcrSdkSurfaceTest {

    // ── OcrProfile ───────────────────────────────────────────────────────

    @Test fun `all 5 profiles are reachable`() {
        assertEquals(5, OcrProfile.all.size)
        assertTrue(OcrProfile.GeneralDocument in OcrProfile.all)
        assertTrue(OcrProfile.Receipt in OcrProfile.all)
        assertTrue(OcrProfile.IdCard in OcrProfile.all)
        assertTrue(OcrProfile.Whiteboard in OcrProfile.all)
        assertTrue(OcrProfile.ScreenCapture in OcrProfile.all)
    }

    @Test fun `each profile has wire-stable mode mapping`() {
        assertEquals(OcrSessionConfig.MODE_GENERAL_DOCUMENT, OcrProfile.GeneralDocument.mode)
        assertEquals(OcrSessionConfig.MODE_RECEIPT, OcrProfile.Receipt.mode)
        assertEquals(OcrSessionConfig.MODE_ID_CARD, OcrProfile.IdCard.mode)
        assertEquals(OcrSessionConfig.MODE_WHITEBOARD, OcrProfile.Whiteboard.mode)
        assertEquals(OcrSessionConfig.MODE_SCREEN_CAPTURE, OcrProfile.ScreenCapture.mode)
    }

    @Test fun `each profile has a non-empty default schema`() {
        for (profile in OcrProfile.all) {
            assertTrue(
                "${profile.displayName} default schema should be non-empty",
                profile.defaultSchema.isNotBlank(),
            )
            // Cheap structural check — schema must look like a JSON object.
            assertTrue(
                "${profile.displayName} schema should start with object brace",
                profile.defaultSchema.trim().startsWith("{"),
            )
        }
    }

    @Test fun `forMode returns the matching profile`() {
        assertEquals(OcrProfile.Receipt, OcrProfile.forMode(OcrSessionConfig.MODE_RECEIPT))
        assertEquals(OcrProfile.IdCard, OcrProfile.forMode(OcrSessionConfig.MODE_ID_CARD))
        assertNull(OcrProfile.forMode(999))
    }

    // ── OcrSessionConfigBuilder ──────────────────────────────────────────

    @Test fun `builder produces config with profile mode`() {
        val cfg = OcrSessionConfigBuilder(OcrProfile.Receipt).build()
        assertEquals(OcrSessionConfig.MODE_RECEIPT, cfg.mode)
        assertEquals(OcrProfile.Receipt.defaultSchema, cfg.outputSchemaJson)
    }

    @Test fun `builder honors overrides`() {
        val builder = OcrSessionConfigBuilder(OcrProfile.GeneralDocument).apply {
            outputSchemaJson = """{"type":"custom"}"""
            languageHints = listOf("en", "de-DE")
            maxFrames = 25
            frameRateLimitFps = 5
            optionsJson = """{"opt":1}"""
        }
        val cfg = builder.build()
        assertEquals("""{"type":"custom"}""", cfg.outputSchemaJson)
        assertEquals(listOf("en", "de-DE"), cfg.languageHints)
        assertEquals(25, cfg.maxFrames)
        assertEquals(5, cfg.frameRateLimitFps)
        assertEquals("""{"opt":1}""", cfg.optionsJson)
    }

    // ── OcrEvent + helpers ───────────────────────────────────────────────

    @Test fun `OcrFrameAck STATUS_ACCEPTED maps to FrameReceived event`() {
        val ack = OcrFrameAck(frameId = 7L, status = OcrFrameAck.STATUS_ACCEPTED)
        val event = ack.toEvent()
        assertTrue(event is OcrEvent.FrameReceived)
        assertEquals(7L, (event as OcrEvent.FrameReceived).frameId)
    }

    @Test fun `OcrFrameAck STATUS_DROPPED_BUSY maps to FrameDroppedBusy event`() {
        val ack = OcrFrameAck(
            frameId = 8L,
            status = OcrFrameAck.STATUS_DROPPED_BUSY,
            retryAfterMs = 250L,
        )
        val event = ack.toEvent()
        assertTrue(event is OcrEvent.FrameDroppedBusy)
        val dropped = event as OcrEvent.FrameDroppedBusy
        assertEquals(8L, dropped.frameId)
        assertEquals(250L, dropped.retryAfterMs)
    }

    @Test fun `OcrFrameAck STATUS_REJECTED_QUALITY maps to FrameRejectedQuality`() {
        val ack = OcrFrameAck(frameId = 9L, status = OcrFrameAck.STATUS_REJECTED_QUALITY)
        val event = ack.toEvent()
        assertTrue(event is OcrEvent.FrameRejectedQuality)
    }

    @Test fun `OcrFrameAck STATUS_REJECTED_FINALIZED maps to null`() {
        val ack = OcrFrameAck(frameId = 10L, status = OcrFrameAck.STATUS_REJECTED_FINALIZED)
        assertNull(ack.toEvent())
    }

    @Test fun `OcrFrameAck status helpers`() {
        val accepted = OcrFrameAck(frameId = 1L, status = OcrFrameAck.STATUS_ACCEPTED)
        assertTrue(accepted.isAccepted())
        assertFalse(accepted.isDroppedBusy())

        val busy = OcrFrameAck(frameId = 2L, status = OcrFrameAck.STATUS_DROPPED_BUSY)
        assertTrue(busy.isDroppedBusy())

        val rejQ = OcrFrameAck(frameId = 3L, status = OcrFrameAck.STATUS_REJECTED_QUALITY)
        assertTrue(rejQ.isRejectedQuality())

        val rejF = OcrFrameAck(frameId = 4L, status = OcrFrameAck.STATUS_REJECTED_FINALIZED)
        assertTrue(rejF.isRejectedFinalized())
    }

    // ── OcrSessionState helpers ──────────────────────────────────────────

    @Test fun `OcrSessionState phase predicates`() {
        val active = state(OcrSessionState.PHASE_ACTIVE)
        assertTrue(active.isActive())
        assertFalse(active.isFinalizing())
        assertFalse(active.isTerminal())

        val finalizing = state(OcrSessionState.PHASE_FINALIZING)
        assertTrue(finalizing.isFinalizing())
        assertFalse(finalizing.isTerminal())

        val finalized = state(OcrSessionState.PHASE_FINALIZED)
        assertTrue(finalized.isFinalized())
        assertTrue(finalized.isTerminal())

        val closed = state(OcrSessionState.PHASE_CLOSED)
        assertTrue(closed.isClosed())
        assertTrue(closed.isTerminal())
    }

    private fun state(phase: Int) = OcrSessionState(
        sessionId = "ocr-1-test",
        phase = phase,
        framesAccepted = 0,
        framesDropped = 0,
        framesRejected = 0,
        pendingQueueDepth = 0,
        streamAttached = false,
        createdAtMs = 0L,
        lastFrameAtMs = 0L,
    )

    // ── Privacy invariants ───────────────────────────────────────────────

    @Test fun `FieldUpdate toString redacts value`() {
        val ev = OcrEvent.FieldUpdate(
            fieldName = "/total",
            topValue = "12.99 USD",
            confidence = "high",
            consecutiveAgreement = 3,
        )
        val s = ev.toString()
        assertFalse("Must not leak topValue", s.contains("12.99"))
        assertTrue(s.contains("<redacted:9>"))
    }

    @Test fun `FieldLocked toString redacts value`() {
        val ev = OcrEvent.FieldLocked("/mrz_line_1", "P<USAJOHN<<DOE<<<<<<<<<<<<<<<<<<<<<<<")
        val s = ev.toString()
        assertFalse("Must not leak topValue", s.contains("JOHN"))
        assertTrue(s.contains("<redacted:"))
    }

    @Test fun `ResultSnapshot toString redacts json`() {
        val ev = OcrEvent.ResultSnapshot("""{"sensitive":"x"}""")
        val s = ev.toString()
        assertFalse(s.contains("sensitive"))
    }

    @Test fun `ResultFinalized toString redacts json`() {
        val ev = OcrEvent.ResultFinalized("""{"final":"answer"}""")
        val s = ev.toString()
        assertFalse(s.contains("final"))
    }
}
