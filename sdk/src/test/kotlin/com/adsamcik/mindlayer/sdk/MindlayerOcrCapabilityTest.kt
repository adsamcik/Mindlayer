package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import android.util.Log
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerOcrCapabilityTest {
    private lateinit var mockService: IMindlayerService
    private lateinit var mindlayer: Mindlayer

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockService = mockk(relaxed = true) {
            every { capabilities } returns capsWithoutOcr()
        }
        val connection = mockk<ConnectionManager>(relaxed = true) {
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            every { getService() } returns mockService
            coEvery { awaitConnected() } returns mockService
            coEvery { awaitConnected(any()) } returns mockService
        }
        mindlayer = buildMindlayer(connection)
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `ocrRealtime requires advertised OCR feature`() = runTest {
        assertFeatureNotSupported { mindlayer.ocrRealtime(OcrProfile.Receipt) }
        verify(exactly = 0) { mockService.createOcrSession(any()) }
    }

    @Suppress("DEPRECATION")
    @Test fun `deprecated ocrSession alias delegates to ocrRealtime`() = runTest {
        // The @Deprecated alias must still gate on the OCR capability —
        // verifies the rename hasn't broken pre-v0.10 callers.
        assertFeatureNotSupported { mindlayer.ocrSession(OcrProfile.Receipt) }
        verify(exactly = 0) { mockService.createOcrSession(any()) }
    }

    @Test fun `ocrAsync requires advertised OCR-image feature`() = runTest {
        assertFeatureNotSupported {
            mindlayer.ocrAsync(byteArrayOf(0x01), "image/jpeg")
        }
        verify(exactly = 0) { mockService.ocrImage(any(), any()) }
    }

    @Suppress("DEPRECATION")
    @Test fun `deprecated ocrImage alias delegates to ocrAsync`() = runTest {
        assertFeatureNotSupported {
            mindlayer.ocrImage(byteArrayOf(0x01), "image/jpeg")
        }
        verify(exactly = 0) { mockService.ocrImage(any(), any()) }
    }

    @Test fun `ocrLimits requires advertised OCR feature`() = runTest {
        assertFeatureNotSupported { mindlayer.ocrLimits() }
        verify(exactly = 0) { mockService.ocrLimits }
    }

    @Test fun `pushOcrFrame requires advertised OCR feature`() = runTest {
        val pipe = ParcelFileDescriptor.createPipe()
        val part = MediaPart(
            requestId = "ocr-frame-test",
            kind = MediaPart.KIND_IMAGE,
            mimeType = null,
            source = pipe[0],
            isSharedMemory = true,
            payloadBytes = 1,
            width = 1,
            height = 1,
            pixelFormat = 1,
            rowStride = 1,
        )
        pipe[1].close()

        assertFeatureNotSupported {
            mindlayer.pushOcrFrame("ocr-1", part, OcrFrameMeta(frameId = 1L, captureTimeMs = 0L))
        }
        pipe[0].close()
        verify(exactly = 0) { mockService.pushOcrFrame(any(), any(), any()) }
    }

    @Test fun `state finalize and stream require advertised OCR feature`() = runTest {
        assertFeatureNotSupported { mindlayer.getOcrSessionState("ocr-1") }
        assertFeatureNotSupported { mindlayer.finalizeOcrSession("ocr-1") }

        val pipe = ParcelFileDescriptor.createPipe()
        assertFeatureNotSupported { mindlayer.attachOcrEventStream("ocr-1", pipe[1]) }
        pipe[0].close()
        pipe[1].close()

        verify(exactly = 0) { mockService.getOcrSessionState(any()) }
        verify(exactly = 0) { mockService.finalizeOcrSession(any()) }
        verify(exactly = 0) { mockService.streamOcrEvents(any(), any()) }
    }

    private suspend fun assertFeatureNotSupported(block: suspend () -> Unit) {
        try {
            block()
            fail("expected MindlayerException")
        } catch (ex: MindlayerException) {
            assertEquals(MindlayerErrorCode.FEATURE_NOT_SUPPORTED, ex.code)
        }
    }

    private fun capsWithoutOcr(): ServiceCapabilities = ServiceCapabilities(
        apiVersion = 8,
        supportedFeatures = setOf(ServiceCapabilities.FEATURE_EMBEDDINGS),
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

    private fun buildMindlayer(conn: ConnectionManager): Mindlayer {
        val ctor = Mindlayer::class.java.getDeclaredConstructor(ConnectionManager::class.java, HistoryStore::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }
}
