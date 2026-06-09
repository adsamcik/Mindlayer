package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focused unit test for the canonical builder-based OCR session entry point
 * [Mindlayer.ocrSession] (the `ocrSession { }` DSL returning
 * [OcrHandle.MultiFrame]).
 *
 * Mocks `IMindlayerService` at the binder boundary and proves the canonical
 * surface bridges onto the existing working multi-frame OCR plumbing:
 *
 *  - the [OcrSessionRequest.Builder] fields map onto the wire
 *    [OcrSessionConfig] exactly as the realtime path does;
 *  - [OcrHandle.MultiFrame.pushFrame] round-trips an [ImageInput] through
 *    `pushOcrFrame`;
 *  - [OcrHandle.MultiFrame.finalize] attaches an event pipe, drains the
 *    session, and maps the terminal `OCR_RESULT_FINALIZED` frame into a typed
 *    [OcrResult] (lines + fullJson, extractionJson only when requested);
 *  - [OcrHandle.MultiFrame.closeAsync] / `close()` call `closeOcrSession`.
 *
 * The deprecated `ocrSession(profile, configure)` / `ocrRealtime` overloads are
 * covered by [MindlayerOcrIntegrationTest]; this file pins the additive v1
 * builder behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerOcrSessionV1Test {

    private val json = Json { encodeDefaults = true }

    private lateinit var context: Context
    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: MindlayerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockService = mockk(relaxed = true) {
            every { capabilities } returns ocrCaps()
            every { createOcrSession(any()) } returns "ocr-v1-fake"
            every { pushOcrFrame(any(), any(), any()) } answers {
                val frameMeta = thirdArg<OcrFrameMeta>()
                OcrFrameAck(
                    frameId = frameMeta.frameId,
                    status = OcrFrameAck.STATUS_ACCEPTED,
                    queueDepth = 1,
                )
            }
        }

        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { getContext() } returns context
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected(any()) } returns mockService
            coEvery { awaitConnected() } returns mockService
        }

        mindlayer = buildMindlayer(mockConnection, null)
    }

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore?): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, historyStore)
    }

    private fun ocrCaps(): ServiceCapabilities = ServiceCapabilities(
        apiVersion = 8,
        supportedFeatures = setOf(ServiceCapabilities.FEATURE_OCR_SESSION),
        pipeProtocol = "mindlayer.stream.v1",
        maxFrameBytes = 1_048_576,
        maxToolRounds = 25,
        maxToolArgsLen = 64 * 1024,
        maxRequestsPerMinute = 60,
        maxConcurrentInferences = 4,
        maxConcurrentSessions = 3,
        maxSessionExpirationMs = 90L * 24 * 60 * 60 * 1000,
        maxMediaPartsPerRequest = 2,
        maxTotalMediaBytesPerRequest = 200L * 1024 * 1024,
    )

    // ── Builder → OcrSessionConfig mapping ───────────────────────────────

    @Test fun `canonical ocrSession maps builder fields onto the wire config`() = runBlocking {
        val configSlot = slot<OcrSessionConfig>()
        every { mockService.createOcrSession(capture(configSlot)) } returns "ocr-v1-map"

        val handle = mindlayer.ocrSession {
            profile(OcrProfile.Receipt)
            languageHints(listOf("en", "de-DE"))
            maxFrames(15)
            frameRateLimit(3)
        }

        assertEquals("ocr-v1-map", handle.sessionId)
        assertEquals(OcrSessionConfig.MODE_RECEIPT, configSlot.captured.mode)
        assertEquals(OcrProfile.Receipt.defaultSchema, configSlot.captured.outputSchemaJson)
        assertEquals(listOf("en", "de-DE"), configSlot.captured.languageHints)
        assertEquals(15, configSlot.captured.maxFrames)
        assertEquals(3, configSlot.captured.frameRateLimitFps)
    }

    @Test fun `extraction schema overrides the profile output schema`() = runBlocking {
        val configSlot = slot<OcrSessionConfig>()
        every { mockService.createOcrSession(capture(configSlot)) } returns "ocr-v1-extract"

        val schema = JsonSchema(buildJsonObject { put("type", "object") })
        mindlayer.ocrSession {
            profile(OcrProfile.GeneralDocument)
            extractWithLlm(schema)
        }

        assertEquals(schema.json.toString(), configSlot.captured.outputSchemaJson)
    }

    // ── pushFrame ────────────────────────────────────────────────────────

    @Test fun `pushFrame round-trips an ImageInput through AIDL`() = runBlocking {
        val handle = mindlayer.ocrSession { profile(OcrProfile.GeneralDocument) }
        val meta = OcrFrameMeta(frameId = 11L, captureTimeMs = 0L)
        val ack = handle.pushFrame(meta, ImageInput.Bytes(ByteArray(256) { 0x7F }, "image/jpeg"))
        assertEquals(11L, ack.frameId)
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)
        verify(exactly = 1) { mockService.pushOcrFrame(any(), any(), any()) }
    }

    // ── finalize ─────────────────────────────────────────────────────────

    @Test fun `finalize maps the finalized stream into a typed OcrResult`() = runBlocking {
        every { mockService.streamOcrEvents(any(), any()) } answers {
            writeFramesTo(
                secondArg(),
                StreamHeader(protocol = StreamProtocol.OCR_V1, requestId = "ocr-v1-fake"),
                finalizedEvent("""{"lines":["Hello","World"]}"""),
                doneEvent(),
            )
        }

        val handle = mindlayer.ocrSession { profile(OcrProfile.GeneralDocument) }
        val result = handle.finalize()

        assertEquals(listOf("Hello", "World"), result.lines.map { it.text })
        assertEquals(
            listOf("Hello", "World"),
            result.fullJson["lines"]!!.let { arr ->
                (arr as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
            },
        )
        assertNull("No extraction requested → extractionJson must be null", result.extractionJson)
        assertEquals(Metrics.EMPTY, result.metrics)
        verify(exactly = 1) { mockService.finalizeOcrSession(any()) }
    }

    @Test fun `finalize surfaces extractionJson when extraction was requested`() = runBlocking {
        every { mockService.streamOcrEvents(any(), any()) } answers {
            writeFramesTo(
                secondArg(),
                StreamHeader(protocol = StreamProtocol.OCR_V1, requestId = "ocr-v1-fake"),
                finalizedEvent("""{"merchant":"Cafe X","total":"12.99"}"""),
                doneEvent(),
            )
        }

        val handle = mindlayer.ocrSession {
            profile(OcrProfile.Receipt)
            extractWithLlm(JsonSchema(buildJsonObject { put("type", "object") }))
        }
        val result = handle.finalize()

        assertNotNull("Extraction requested → extractionJson must be present", result.extractionJson)
        assertEquals("Cafe X", result.extractionJson!!["merchant"]!!.jsonPrimitive.content)
        assertTrue("No top-level lines[] → lines best-effort empty", result.lines.isEmpty())
    }

    // ── close ────────────────────────────────────────────────────────────

    @Test fun `closeAsync calls closeOcrSession`() = runBlocking {
        val handle = mindlayer.ocrSession { profile(OcrProfile.GeneralDocument) }
        handle.closeAsync()
        verify(exactly = 1) { mockService.closeOcrSession(any()) }
    }

    @Test fun `close calls closeOcrSession`() = runBlocking {
        val handle = mindlayer.ocrSession { profile(OcrProfile.GeneralDocument) }
        handle.close()
        verify(exactly = 1) { mockService.closeOcrSession(any()) }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun finalizedEvent(fullJson: String): StreamEvent = StreamEvent(
        seq = 1L,
        type = StreamEventType.OCR_RESULT_FINALIZED,
        tsMs = 0L,
        payload = buildJsonObject { put("fullJson", fullJson) },
    )

    private fun doneEvent(): StreamEvent = StreamEvent(
        seq = 2L,
        type = StreamEventType.DONE,
        tsMs = 0L,
        payload = buildJsonObject { put("finish_reason", "success") },
    )

    private fun writeFramesTo(pfd: ParcelFileDescriptor, vararg frames: Any) {
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
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
    }
}
