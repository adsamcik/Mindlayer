package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import app.cash.turbine.test
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wire-level tests for the v0.9 page-boundary events
 * ([StreamEventType.OCR_PAGE_STARTED] / [StreamEventType.OCR_PAGE_FINALIZED])
 * added in PR 1.
 *
 * Mirror of [OcrTokenStreamReaderTest]'s style — pure-JVM (Robolectric for
 * `ParcelFileDescriptor` only), parses both via [OcrTokenStreamReader.parseFrame]
 * (single-frame) and [OcrTokenStreamReader.readStream] (pipe round-trip).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class OcrTokenStreamReaderPageEventsTest {

    private val json = Json { encodeDefaults = true }

    @Test fun `ocr_page_started maps to PageStarted with both fields`() {
        val event = StreamEvent(
            seq = 1L,
            type = StreamEventType.OCR_PAGE_STARTED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 2)
                put("triggerFrameId", 17L)
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageStarted)
        val started = parsed as OcrEvent.PageStarted
        assertEquals(2, started.pageIndex)
        assertEquals(17L, started.triggerFrameId)
    }

    @Test fun `ocr_page_started with missing triggerFrameId defaults to 0`() {
        val event = StreamEvent(
            seq = 2L,
            type = StreamEventType.OCR_PAGE_STARTED,
            tsMs = 0L,
            payload = buildJsonObject { put("pageIndex", 0) },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageStarted)
        assertEquals(0L, (parsed as OcrEvent.PageStarted).triggerFrameId)
    }

    @Test fun `ocr_page_started with missing pageIndex is dropped (returns null)`() {
        val event = StreamEvent(
            seq = 3L,
            type = StreamEventType.OCR_PAGE_STARTED,
            tsMs = 0L,
            payload = buildJsonObject { put("triggerFrameId", 4L) },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertNull(parsed)
    }

    @Test fun `ocr_page_finalized maps to PageFinalized with lines, lineCount, framesContributed`() {
        val event = StreamEvent(
            seq = 10L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 1)
                put("lineCount", 3)
                put("framesContributed", 12)
                putJsonArray("lines") {
                    add(buildJsonObject { put("text", "Item A"); put("confidence", "high") })
                    add(buildJsonObject { put("text", "Item B"); put("confidence", "medium") })
                    add(buildJsonObject {
                        put("text", "Item C")
                        put("confidence", "low")
                        putJsonArray("bbox") { repeat(8) { add(JsonPrimitive(0f)) } }
                    })
                }
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageFinalized)
        val finalized = parsed as OcrEvent.PageFinalized
        assertEquals(1, finalized.pageIndex)
        assertEquals(3, finalized.lineCount)
        assertEquals(12, finalized.framesContributed)
        assertEquals(listOf("Item A", "Item B", "Item C"), finalized.lines)
        assertNull("Without fullJson on wire, SDK surfaces null", finalized.fullJson)
    }

    @Test fun `ocr_page_finalized with fullJson re-serialises the JsonObject as a string`() {
        val event = StreamEvent(
            seq = 11L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 0)
                put("lineCount", 1)
                put("framesContributed", 5)
                putJsonArray("lines") {
                    add(buildJsonObject { put("text", "Total: 12.99") })
                }
                putJsonObject("fullJson") {
                    put("total", "12.99")
                    put("currency", "USD")
                }
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageFinalized)
        val finalized = parsed as OcrEvent.PageFinalized
        assertNotNull(finalized.fullJson)
        // The SDK should keep the structured JSON intact (re-encoded as a
        // string — the contract is just "a valid JSON object string").
        val reparsed = Json.parseToJsonElement(finalized.fullJson!!)
        assertEquals(
            "USD",
            (reparsed as kotlinx.serialization.json.JsonObject)["currency"]
                ?.let { (it as JsonPrimitive).content },
        )
    }

    @Test fun `ocr_page_finalized with missing lines surfaces empty list (not null, not error)`() {
        val event = StreamEvent(
            seq = 12L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 4)
                put("framesContributed", 2)
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageFinalized)
        val finalized = parsed as OcrEvent.PageFinalized
        assertEquals(emptyList<String>(), finalized.lines)
        assertEquals(0, finalized.lineCount)
        assertEquals(2, finalized.framesContributed)
        assertNull(finalized.fullJson)
    }

    @Test fun `ocr_page_finalized skips line entries without text but keeps the event`() {
        val event = StreamEvent(
            seq = 13L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 0)
                put("framesContributed", 3)
                putJsonArray("lines") {
                    add(buildJsonObject { put("text", "Good") })
                    add(buildJsonObject { put("confidence", "high") /* no text */ })
                    add(JsonPrimitive("not-an-object")) // non-object line entry
                    add(buildJsonObject { put("text", "Also good") })
                }
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageFinalized)
        val finalized = parsed as OcrEvent.PageFinalized
        assertEquals(listOf("Good", "Also good"), finalized.lines)
    }

    @Test fun `ocr_page_finalized with non-object fullJson falls back to null (no crash)`() {
        val event = StreamEvent(
            seq = 14L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 0)
                put("framesContributed", 1)
                putJsonArray("lines") { add(buildJsonObject { put("text", "Hi") }) }
                put("fullJson", "stringified-not-object") // wrong shape
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageFinalized)
        assertNull((parsed as OcrEvent.PageFinalized).fullJson)
    }

    @Test fun `ocr_page_finalized with missing pageIndex is dropped (returns null)`() {
        val event = StreamEvent(
            seq = 15L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("framesContributed", 1)
                putJsonArray("lines") { add(buildJsonObject { put("text", "Orphan") }) }
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertNull(parsed)
    }

    @Test fun `ocr_page_finalized with unknown extra fields is ignored (not crashed)`() {
        val event = StreamEvent(
            seq = 16L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 0)
                put("framesContributed", 1)
                put("unknownFutureField", "ignored")
                putJsonObject("anotherFutureBlock") { put("nested", true) }
                putJsonArray("lines") { add(buildJsonObject { put("text", "OK") }) }
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageFinalized)
    }

    @Test fun `lineCount falls back to lines size when absent`() {
        val event = StreamEvent(
            seq = 17L,
            type = StreamEventType.OCR_PAGE_FINALIZED,
            tsMs = 0L,
            payload = buildJsonObject {
                put("pageIndex", 0)
                put("framesContributed", 4)
                putJsonArray("lines") {
                    add(buildJsonObject { put("text", "L1") })
                    add(buildJsonObject { put("text", "L2") })
                }
            },
        )
        val parsed = OcrTokenStreamReader.parseFrame(json.encodeToString(StreamEvent.serializer(), event))
        assertTrue(parsed is OcrEvent.PageFinalized)
        assertEquals(2, (parsed as OcrEvent.PageFinalized).lineCount)
    }

    @Test fun `mixed stream surfaces page events in order alongside frame events`() = runTest {
        val frames = arrayOf<Any>(
            StreamHeader(protocol = StreamProtocol.OCR_V1, requestId = "test-mixed"),
            StreamEvent(
                seq = 1L,
                type = StreamEventType.OCR_PAGE_STARTED,
                tsMs = 0L,
                payload = buildJsonObject { put("pageIndex", 0); put("triggerFrameId", 0L) },
            ),
            StreamEvent(
                seq = 2L,
                type = StreamEventType.OCR_FRAME_PROCESSING,
                tsMs = 0L,
                payload = buildJsonObject { put("frameId", 1L) },
            ),
            StreamEvent(
                seq = 3L,
                type = StreamEventType.OCR_FRAME_PROCESSED,
                tsMs = 0L,
                payload = buildJsonObject { put("frameId", 1L); put("lineCount", 4) },
            ),
            StreamEvent(
                seq = 4L,
                type = StreamEventType.OCR_PAGE_FINALIZED,
                tsMs = 0L,
                payload = buildJsonObject {
                    put("pageIndex", 0)
                    put("framesContributed", 1)
                    putJsonArray("lines") {
                        add(buildJsonObject { put("text", "Page 1 line 1") })
                    }
                },
            ),
            StreamEvent(
                seq = 5L,
                type = StreamEventType.OCR_PAGE_STARTED,
                tsMs = 0L,
                payload = buildJsonObject { put("pageIndex", 1); put("triggerFrameId", 7L) },
            ),
            StreamEvent(
                seq = 6L,
                type = StreamEventType.DONE,
                tsMs = 0L,
                payload = buildJsonObject {},
            ),
        )
        val pipe = framedPipe(*frames)
        OcrTokenStreamReader.readStream(pipe, UnconfinedTestDispatcher()).test {
            val e1 = awaitItem(); assertTrue("e1 PageStarted, got $e1", e1 is OcrEvent.PageStarted)
            assertEquals(0, (e1 as OcrEvent.PageStarted).pageIndex)
            val e2 = awaitItem(); assertTrue("e2 FrameProcessing, got $e2", e2 is OcrEvent.FrameProcessing)
            val e3 = awaitItem(); assertTrue("e3 FrameProcessed, got $e3", e3 is OcrEvent.FrameProcessed)
            val e4 = awaitItem(); assertTrue("e4 PageFinalized, got $e4", e4 is OcrEvent.PageFinalized)
            assertEquals(listOf("Page 1 line 1"), (e4 as OcrEvent.PageFinalized).lines)
            val e5 = awaitItem(); assertTrue("e5 PageStarted, got $e5", e5 is OcrEvent.PageStarted)
            assertEquals(1, (e5 as OcrEvent.PageStarted).pageIndex)
            assertEquals(7L, e5.triggerFrameId)
            awaitComplete()
        }
    }

    @Test fun `PageFinalized toString redacts line text and fullJson`() {
        val finalized = OcrEvent.PageFinalized(
            pageIndex = 2,
            lines = listOf("Sensitive PII line 1", "Sensitive PII line 2"),
            fullJson = """{"total":"42.00"}""",
            lineCount = 2,
            framesContributed = 6,
        )
        val str = finalized.toString()
        assertTrue("toString must include pageIndex", str.contains("page=2"))
        assertTrue("toString must include lineCount", str.contains("lineCount=2"))
        assertTrue("toString must include framesContributed", str.contains("framesContributed=6"))
        assertTrue("toString must NOT include raw text", !str.contains("Sensitive PII"))
        assertTrue("toString must NOT include fullJson contents", !str.contains("42.00"))
    }

    @Test fun `PageStarted equals and hashCode follow data class semantics`() {
        val a = OcrEvent.PageStarted(pageIndex = 3, triggerFrameId = 9L)
        val b = OcrEvent.PageStarted(pageIndex = 3, triggerFrameId = 9L)
        val c = OcrEvent.PageStarted(pageIndex = 4, triggerFrameId = 9L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test fun `PageFinalized equals compares lines list by content`() {
        val a = OcrEvent.PageFinalized(0, listOf("x", "y"), null, 2, 1)
        val b = OcrEvent.PageFinalized(0, listOf("x", "y"), null, 2, 1)
        val c = OcrEvent.PageFinalized(0, listOf("x", "z"), null, 2, 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    private fun framedPipe(vararg frames: Any): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
            frames.forEach { frame ->
                val text = when (frame) {
                    is StreamHeader -> json.encodeToString(StreamHeader.serializer(), frame)
                    is StreamEvent -> json.encodeToString(StreamEvent.serializer(), frame)
                    else -> error("Unsupported frame $frame")
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
                out.write(bytes)
            }
        }
        return pipe[0]
    }
}
