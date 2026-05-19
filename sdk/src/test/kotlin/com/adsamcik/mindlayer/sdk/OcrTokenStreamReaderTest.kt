package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [OcrTokenStreamReader.parseFrame].
 *
 * The frame-level loop in [OcrTokenStreamReader.readStream] requires
 * a real pipe + Android framework PFD; its end-to-end correctness is
 * covered by the existing SDK integration tests once they're
 * augmented for OCR events. This file pins the wire-mapping
 * invariants — each of the 10 OCR_* event types decodes into the
 * expected [OcrEvent] subclass; malformed frames return null without
 * crashing the reader.
 */
class OcrTokenStreamReaderTest {

    private val json = Json { encodeDefaults = true }

    @Test fun `header frame returns null without emission`() {
        val header = StreamHeader(protocol = StreamProtocol.OCR_V1, requestId = "test-session")
        val text = json.encodeToString(StreamHeader.serializer(), header)
        assertNull(OcrTokenStreamReader.parseFrame(text))
    }

    @Test fun `chat-stream header is rejected (returns null)`() {
        val header = StreamHeader(protocol = StreamProtocol.V1, requestId = "test-req")
        val text = json.encodeToString(StreamHeader.serializer(), header)
        assertNull(OcrTokenStreamReader.parseFrame(text))
    }

    @Test fun `malformed JSON returns null`() {
        assertNull(OcrTokenStreamReader.parseFrame("{not valid json"))
        assertNull(OcrTokenStreamReader.parseFrame(""))
    }

    @Test fun `ocr_frame_received maps to FrameReceived`() {
        val event = StreamEvent(
            seq = 1L,
            type = StreamEventType.OCR_FRAME_RECEIVED,
            tsMs = 0L,
            payload = buildJsonObject { put("frameId", 42L); put("queueDepth", 3) },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.FrameReceived)
        assertEquals(42L, (parsed as OcrEvent.FrameReceived).frameId)
    }

    @Test fun `ocr_frame_rejected_quality maps to FrameRejectedQuality with reason`() {
        val event = StreamEvent(
            seq = 2L,
            type = StreamEventType.OCR_FRAME_REJECTED_QUALITY,
            tsMs = 0L,
            payload = buildJsonObject { put("frameId", 7L); put("reason", "blur") },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.FrameRejectedQuality)
        val r = parsed as OcrEvent.FrameRejectedQuality
        assertEquals(7L, r.frameId)
        assertEquals("blur", r.reason)
    }

    @Test fun `ocr_frame_dropped_busy maps to FrameDroppedBusy with retryAfterMs`() {
        val event = StreamEvent(
            seq = 3L,
            type = StreamEventType.OCR_FRAME_DROPPED_BUSY,
            tsMs = 0L,
            payload = buildJsonObject { put("frameId", 8L); put("retryAfterMs", 250L) },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.FrameDroppedBusy)
        val r = parsed as OcrEvent.FrameDroppedBusy
        assertEquals(8L, r.frameId)
        assertEquals(250L, r.retryAfterMs)
    }

    @Test fun `ocr_frame_processing and ocr_frame_processed`() {
        val processing = StreamEvent(
            seq = 4L,
            type = StreamEventType.OCR_FRAME_PROCESSING,
            tsMs = 0L,
            payload = buildJsonObject { put("frameId", 9L) },
        )
        val parsed1 = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), processing))
        assertTrue(parsed1 is OcrEvent.FrameProcessing)

        val processed = StreamEvent(
            seq = 5L,
            type = StreamEventType.OCR_FRAME_PROCESSED,
            tsMs = 0L,
            payload = buildJsonObject { put("frameId", 9L); put("lineCount", 5); put("durationMs", 120L) },
        )
        val parsed2 = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), processed))
        assertTrue(parsed2 is OcrEvent.FrameProcessed)
        assertEquals(5, (parsed2 as OcrEvent.FrameProcessed).lineCount)
    }

    @Test fun `ocr_field_update maps to FieldUpdate`() {
        val event = StreamEvent(
            seq = 6L,
            type = StreamEventType.OCR_FIELD_UPDATE,
            tsMs = 0L,
            payload = buildJsonObject {
                put("fieldName", "/total")
                put("topValue", "12.99")
                put("confidence", "high")
                put("consecutiveAgreement", 2)
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.FieldUpdate)
        val u = parsed as OcrEvent.FieldUpdate
        assertEquals("/total", u.fieldName)
        assertEquals("12.99", u.topValue)
        assertEquals("high", u.confidence)
        assertEquals(2, u.consecutiveAgreement)
    }

    @Test fun `ocr_field_locked maps to FieldLocked`() {
        val event = StreamEvent(
            seq = 7L,
            type = StreamEventType.OCR_FIELD_LOCKED,
            tsMs = 0L,
            payload = buildJsonObject { put("fieldName", "/merchant"); put("topValue", "Cafe X") },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.FieldLocked)
        assertEquals("Cafe X", (parsed as OcrEvent.FieldLocked).topValue)
    }

    @Test fun `ocr_result_snapshot and ocr_result_finalized`() {
        val snap = StreamEvent(
            seq = 8L,
            type = StreamEventType.OCR_RESULT_SNAPSHOT,
            tsMs = 0L,
            payload = buildJsonObject { put("partialJson", """{"total":"12.99"}""") },
        )
        val parsed1 = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), snap))
        assertTrue(parsed1 is OcrEvent.ResultSnapshot)

        val fin = StreamEvent(
            seq = 9L,
            type = StreamEventType.OCR_RESULT_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject { put("fullJson", """{"total":"12.99"}""") },
        )
        val parsed2 = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), fin))
        assertTrue(parsed2 is OcrEvent.ResultFinalized)
    }

    @Test fun `ocr_throttle_hint maps to ThrottleHint`() {
        val event = StreamEvent(
            seq = 10L,
            type = StreamEventType.OCR_THROTTLE_HINT,
            tsMs = 0L,
            payload = buildJsonObject { put("recommendedIntervalMs", 500L) },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.ThrottleHint)
        assertEquals(500L, (parsed as OcrEvent.ThrottleHint).recommendedIntervalMs)
    }

    @Test fun `unknown event type returns null without crash`() {
        val event = StreamEvent(
            seq = 99L,
            type = "made_up_type",
            tsMs = 0L,
            payload = buildJsonObject { put("foo", "bar") },
        )
        assertNull(OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event)))
    }

    @Test fun `event with missing required field returns null`() {
        // ocr_frame_received without frameId.
        val event = StreamEvent(
            seq = 100L,
            type = StreamEventType.OCR_FRAME_RECEIVED,
            tsMs = 0L,
            payload = buildJsonObject { put("queueDepth", 3) },
        )
        assertNull(OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event)))
    }

    @Test fun `DONE and ERROR events return null (terminal handled in readStream loop)`() {
        val done = StreamEvent(
            seq = 200L,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", "success") },
        )
        assertNull(OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), done)))

        val error = StreamEvent(
            seq = 201L,
            type = StreamEventType.ERROR,
            tsMs = 0L,
            payload = buildJsonObject { put("code", "OCR_SCHEMA_INVALID"); put("codeInt", 3007) },
        )
        assertNull(OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), error)))
    }
}
