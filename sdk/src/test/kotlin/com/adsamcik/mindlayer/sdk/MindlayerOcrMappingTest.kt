package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.OcrImageExtractedField
import com.adsamcik.mindlayer.OcrImageLine
import com.adsamcik.mindlayer.OcrImageResult
import com.adsamcik.mindlayer.ServiceCapabilities
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * Pins the canonical one-shot OCR mapping ([Mindlayer.ocr] → [OcrResult]): the
 * v1 superset must carry every field the legacy [OcrImageResult] exposed —
 * per-pass timing, backend, structured `extractionFields`, and the rotated
 * `boundingBoxQuad` / `orientationDegrees` — through [MindlayerImpl]'s
 * `mapOcrImageResult`. Capability gating is covered by
 * [MindlayerOcrCapabilityTest]; the multi-frame path by
 * [MindlayerOcrSessionV1Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerOcrMappingTest {

    private lateinit var context: Context
    private lateinit var mockService: IMindlayerService
    private lateinit var mindlayer: MindlayerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockService = mockk(relaxed = true) {
            every { capabilities } returns ocrImageCaps()
        }
        val connection = mockk<ConnectionManager>(relaxed = true) {
            every { getService() } returns mockService
            every { getContext() } returns context
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected() } returns mockService
            coEvery { awaitConnected(any()) } returns mockService
        }
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        mindlayer = ctor.newInstance(connection, null)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `ocr maps enriched OcrImageResult fields onto canonical OcrResult`() = runTest {
        val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        every { mockService.ocrImage(any(), any()) } returns OcrImageResult(
            lines = listOf(
                OcrImageLine(
                    text = "TOTAL 12.50",
                    confidence = OcrImageLine.CONFIDENCE_HIGH,
                    boundingBox = quad,
                    orientationDegrees = 90,
                ),
            ),
            extractionFields = listOf(
                OcrImageExtractedField(
                    name = "total",
                    value = "12.50",
                    confidence = OcrImageLine.CONFIDENCE_MEDIUM,
                ),
            ),
            extractionJson = """{"total":"12.50"}""",
            backend = "GPU",
            ocrDurationMs = 120L,
            llmDurationMs = 80L,
            totalDurationMs = 200L,
        )

        val result = mindlayer.ocr {
            image(byteArrayOf(1, 2, 3, 4), "image/jpeg")
            extractWithLlm(JsonSchema.parse("""{"type":"object"}"""))
            emitBoundingBoxes()
        }.awaitResult()

        // Per-pass timing + backend (the headline reason ocrAsync->ocr{} is lossless).
        assertEquals(200L, result.metrics.totalDurationMs)
        assertEquals(120L, result.metrics.ocrDurationMs)
        assertEquals(80L, result.metrics.llmDurationMs)
        assertEquals("GPU", result.metrics.backend)

        // Per-line: verbalized confidence → float, rotated quad + orientation preserved.
        assertEquals(1, result.lines.size)
        val line = result.lines.single()
        assertEquals("TOTAL 12.50", line.text)
        assertEquals(1.0f, line.confidence)
        assertEquals(quad.toList(), line.boundingBoxQuad)
        assertEquals(90, line.orientationDegrees)

        // Structured extraction fields preserved with mapped confidence.
        assertEquals(1, result.extractionFields.size)
        val field = result.extractionFields.single()
        assertEquals("total", field.name)
        assertEquals("12.50", field.value)
        assertEquals(0.66f, field.confidence)

        // Extraction JSON parsed into the typed result.
        assertNotNull(result.extractionJson)
        assertEquals("12.50", result.extractionJson!!["total"]!!.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
    }

    @Test
    fun `ocr without extraction leaves extractionFields empty and timing absent`() = runTest {
        every { mockService.ocrImage(any(), any()) } returns OcrImageResult(
            lines = listOf(OcrImageLine(text = "hello", confidence = OcrImageLine.CONFIDENCE_LOW)),
            backend = OcrImageResult.BACKEND_NONE,
            // all durations 0 -> mapped to null (absent)
        )

        val result = mindlayer.ocr {
            image(byteArrayOf(9, 9, 9, 9), "image/png")
        }.awaitResult()

        assertEquals(listOf("hello"), result.lines.map { it.text })
        assertEquals(0.33f, result.lines.single().confidence)
        assertTrue(result.extractionFields.isEmpty())
        assertNull(result.metrics.ocrDurationMs)
        assertNull(result.metrics.llmDurationMs)
        assertNull(result.metrics.backend)
        assertNull(result.extractionJson)
    }

    private fun ocrImageCaps(): ServiceCapabilities = ServiceCapabilities(
        apiVersion = 9,
        supportedFeatures = setOf(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT),
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
}
