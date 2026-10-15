package com.adsamcik.mindlayer.sdk

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrImageResult
import com.adsamcik.mindlayer.ServiceCapabilities
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerOcrDeadlineTest {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private lateinit var service: IMindlayerService
    private lateinit var mindlayer: MindlayerImpl

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        service = mockk(relaxed = true) {
            every { capabilities } returns ServiceCapabilities(
                apiVersion = 9,
                supportedFeatures = setOf(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT),
                pipeProtocol = "mindlayer.stream.v1",
                maxFrameBytes = 1_048_576,
                maxToolRounds = 25,
                maxToolArgsLen = 65_536,
                maxRequestsPerMinute = 60,
                maxConcurrentInferences = 4,
                maxConcurrentSessions = 3,
                maxSessionExpirationMs = 86_400_000,
                maxMediaPartsPerRequest = 2,
                maxTotalMediaBytesPerRequest = 209_715_200,
            )
        }
        val connection = mockk<ConnectionManager>(relaxed = true) {
            every { getService() } returns service
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected() } returns service
            coEvery { awaitConnected(any()) } returns service
        }
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java, HistoryStore::class.java,
        ).apply { isAccessible = true }
        mindlayer = ctor.newInstance(connection, null)
        val source = mockk<ParcelFileDescriptor> {
            every { close() } answers { closed.countDown() }
        }
        mockkObject(MediaTransfer)
        every { MediaTransfer.ocrEncodedImagePart(any(), any(), any(), any()) } returns MediaPart(
            requestId = "ocr-deadline-test", kind = MediaPart.KIND_IMAGE,
            mimeType = "image/png", source = source, isSharedMemory = false, payloadBytes = 4,
        )
        every { service.ocrImage(any(), any()) } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release OCR Binder call" }
            OcrImageResult(lines = emptyList(), backend = OcrImageResult.BACKEND_NONE)
        }
    }

    @After fun tearDown() {
        release.countDown()
        mindlayer.disconnect()
        unmockkAll()
    }

    @Test fun `deadline expires while Binder is blocked and media closes after it returns`() = runTest {
        val call = async {
            runCatching { mindlayer.ocr { image(byteArrayOf(1, 2, 3, 4), "image/png"); deadline(1.minutes) } }
        }
        runCurrent()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            advanceTimeBy(60_001)
            runCurrent()
            assertTrue("Caller must finish before Binder is released", call.isCompleted)
            assertTrue(call.await().exceptionOrNull() is TimeoutCancellationException)
            assertEquals(1L, closed.count)
        } finally {
            release.countDown()
        }
        assertTrue("SDK releases its staged descriptor after Binder returns", closed.await(10, TimeUnit.SECONDS))
    }

    @Test fun `caller cancellation returns before Binder and preserves eventual cleanup`() = runTest {
        val call = async { mindlayer.ocr { image(byteArrayOf(1, 2, 3, 4), "image/png") } }
        runCurrent()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            call.cancel()
            runCurrent()
            assertTrue(call.isCompleted)
            assertEquals(1L, closed.count)
        } finally {
            release.countDown()
        }
        assertTrue(closed.await(10, TimeUnit.SECONDS))
    }

    @Test fun `deadline includes image loading before Binder dispatch`() = runTest {
        val callerThread = Thread.currentThread()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context> { every { contentResolver } returns resolver }
        val uri = Uri.parse("content://mindlayer-test/image")
        every { resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read(): Int {
                assertFalse("Image IO must leave the caller thread", Thread.currentThread() === callerThread)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                throw CancellationException("Image provider released after timeout")
            }
            override fun close() { closed.countDown() }
        }
        every { resolver.getType(uri) } returns "image/png"
        val call = async {
            runCatching { mindlayer.ocr { image(uri, context); deadline(1.minutes) } }
        }
        runCurrent()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            advanceTimeBy(60_001)
            runCurrent()
            assertTrue(call.isCompleted)
            assertTrue(call.await().exceptionOrNull() is TimeoutCancellationException)
            verify(exactly = 0) { service.ocrImage(any(), any()) }
        } finally {
            release.countDown()
        }
        assertTrue(closed.await(10, TimeUnit.SECONDS))
    }

    @Test fun `infinite deadline preserves successful result`() = runTest {
        release.countDown()
        val handle = mindlayer.ocr {
            image(byteArrayOf(1, 2, 3, 4), "image/png")
            deadline(Duration.INFINITE)
        }
        assertTrue(handle.awaitResult().lines.isEmpty())
        assertEquals(0L, closed.count)
    }

    @Test fun `deadline must be positive`() {
        for (duration in listOf(Duration.ZERO, (-1).seconds, -Duration.INFINITE)) {
            assertThrows(IllegalArgumentException::class.java) { OcrRequest.Builder().deadline(duration) }
        }
    }
}
