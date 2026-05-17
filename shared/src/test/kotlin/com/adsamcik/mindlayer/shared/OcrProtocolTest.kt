package com.adsamcik.mindlayer.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-stability tests for the v0.8 multi-frame OCR additions to the
 * stream protocol + error code registry.
 *
 * If any of these tests fail, you have introduced a wire break — those
 * string constants and integer codes are pinned to ship.
 */
class OcrProtocolTest {

    // ── StreamEventType ───────────────────────────────────────────────────

    @Test fun `OCR_FRAME_RECEIVED string is wire-stable`() {
        assertEquals("ocr_frame_received", StreamEventType.OCR_FRAME_RECEIVED)
    }

    @Test fun `OCR_FRAME_REJECTED_QUALITY string is wire-stable`() {
        assertEquals("ocr_frame_rejected_quality", StreamEventType.OCR_FRAME_REJECTED_QUALITY)
    }

    @Test fun `OCR_FRAME_DROPPED_BUSY string is wire-stable`() {
        assertEquals("ocr_frame_dropped_busy", StreamEventType.OCR_FRAME_DROPPED_BUSY)
    }

    @Test fun `OCR_FRAME_PROCESSING string is wire-stable`() {
        assertEquals("ocr_frame_processing", StreamEventType.OCR_FRAME_PROCESSING)
    }

    @Test fun `OCR_FRAME_PROCESSED string is wire-stable`() {
        assertEquals("ocr_frame_processed", StreamEventType.OCR_FRAME_PROCESSED)
    }

    @Test fun `OCR_FIELD_UPDATE string is wire-stable`() {
        assertEquals("ocr_field_update", StreamEventType.OCR_FIELD_UPDATE)
    }

    @Test fun `OCR_FIELD_LOCKED string is wire-stable`() {
        assertEquals("ocr_field_locked", StreamEventType.OCR_FIELD_LOCKED)
    }

    @Test fun `OCR_RESULT_SNAPSHOT string is wire-stable`() {
        assertEquals("ocr_result_snapshot", StreamEventType.OCR_RESULT_SNAPSHOT)
    }

    @Test fun `OCR_RESULT_FINALIZED string is wire-stable`() {
        assertEquals("ocr_result_finalized", StreamEventType.OCR_RESULT_FINALIZED)
    }

    @Test fun `OCR_THROTTLE_HINT string is wire-stable`() {
        assertEquals("ocr_throttle_hint", StreamEventType.OCR_THROTTLE_HINT)
    }

    // ── StreamProtocol ────────────────────────────────────────────────────

    @Test fun `OCR_V1 protocol identifier is wire-stable`() {
        assertEquals("mindlayer.stream.ocr.v1", StreamProtocol.OCR_V1)
    }

    @Test fun `OCR_V1 is in OCR_SUPPORTED`() {
        assertTrue(StreamProtocol.OCR_V1 in StreamProtocol.OCR_SUPPORTED)
    }

    @Test fun `OCR_V1 is NOT in chat-stream SUPPORTED set`() {
        // Disjoint protocols: chat-stream readers must never accept OCR frames.
        assertFalse(StreamProtocol.OCR_V1 in StreamProtocol.SUPPORTED)
    }

    @Test fun `chat V1 V2 are NOT in OCR_SUPPORTED`() {
        // Mirror invariant in the other direction.
        assertFalse(StreamProtocol.V1 in StreamProtocol.OCR_SUPPORTED)
        assertFalse(StreamProtocol.V2 in StreamProtocol.OCR_SUPPORTED)
    }

    // ── MindlayerErrorCode integer assignments ────────────────────────────

    @Test fun `OCR_IDLE_TIMEOUT is 2004`() {
        assertEquals(2004, MindlayerErrorCode.OCR_IDLE_TIMEOUT)
    }

    @Test fun `OCR_MAX_DURATION is 2005`() {
        assertEquals(2005, MindlayerErrorCode.OCR_MAX_DURATION)
    }

    @Test fun `OCR_SCHEMA_INVALID is 3007`() {
        assertEquals(3007, MindlayerErrorCode.OCR_SCHEMA_INVALID)
    }

    @Test fun `OCR_SESSION_FINALIZED is 3008`() {
        assertEquals(3008, MindlayerErrorCode.OCR_SESSION_FINALIZED)
    }

    @Test fun `FRAME_DROPPED_BUSY is 5015`() {
        assertEquals(5015, MindlayerErrorCode.FRAME_DROPPED_BUSY)
    }

    @Test fun `FRAME_REJECTED_QUALITY is 5016`() {
        assertEquals(5016, MindlayerErrorCode.FRAME_REJECTED_QUALITY)
    }

    // ── nameOf round-trips ────────────────────────────────────────────────

    @Test fun `nameOf returns symbolic for new codes`() {
        assertEquals("OCR_IDLE_TIMEOUT", MindlayerErrorCode.nameOf(2004))
        assertEquals("OCR_MAX_DURATION", MindlayerErrorCode.nameOf(2005))
        assertEquals("OCR_SCHEMA_INVALID", MindlayerErrorCode.nameOf(3007))
        assertEquals("OCR_SESSION_FINALIZED", MindlayerErrorCode.nameOf(3008))
        assertEquals("FRAME_DROPPED_BUSY", MindlayerErrorCode.nameOf(5015))
        assertEquals("FRAME_REJECTED_QUALITY", MindlayerErrorCode.nameOf(5016))
    }

    // ── categoryOf mappings ───────────────────────────────────────────────

    @Test fun `OCR session-lifecycle codes map to SESSION category`() {
        assertEquals(MindlayerErrorCode.Category.SESSION, MindlayerErrorCode.categoryOf(2004))
        assertEquals(MindlayerErrorCode.Category.SESSION, MindlayerErrorCode.categoryOf(2005))
    }

    @Test fun `OCR validation codes map to VALIDATION category`() {
        assertEquals(MindlayerErrorCode.Category.VALIDATION, MindlayerErrorCode.categoryOf(3007))
        assertEquals(MindlayerErrorCode.Category.VALIDATION, MindlayerErrorCode.categoryOf(3008))
    }

    @Test fun `OCR resource codes map to RESOURCE category`() {
        assertEquals(MindlayerErrorCode.Category.RESOURCE, MindlayerErrorCode.categoryOf(5015))
        assertEquals(MindlayerErrorCode.Category.RESOURCE, MindlayerErrorCode.categoryOf(5016))
    }

    // ── Wire round-trip via wireMessage / codeFromWireMessage ─────────────

    @Test fun `wireMessage roundtrip preserves OCR codes`() {
        val codes = listOf(2004, 2005, 3007, 3008, 5015, 5016)
        for (code in codes) {
            val wire = MindlayerErrorCode.wireMessage(code, "test")
            val decoded = MindlayerErrorCode.codeFromWireMessage(wire)
            assertEquals(code, decoded)
        }
    }

    @Test fun `wireMessage preserves human-readable message`() {
        val wire = MindlayerErrorCode.wireMessage(3007, "schemaJson too long: 17000 > 16384")
        assertEquals("schemaJson too long: 17000 > 16384", MindlayerErrorCode.messageFromWireMessage(wire))
    }

    // ── Anti-enumeration invariant ────────────────────────────────────────

    @Test fun `no separate code for not-owned-OCR-session leaks UID information`() {
        // SESSION_NOT_FOUND_OR_NOT_OWNED (2001) is reused for OCR ownership
        // failures. Splitting these would leak cross-UID session existence.
        // This test pins the convention.
        val notFoundName = MindlayerErrorCode.nameOf(2001)
        assertEquals("SESSION_NOT_FOUND_OR_NOT_OWNED", notFoundName)
    }
}
