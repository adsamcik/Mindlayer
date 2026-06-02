package com.adsamcik.mindlayer.sdk

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [Mindlayer.chatDeferred]: capability gating + H-D2 FD-cleanup
 * invariant. The SDK passes [MediaPart.source] PFDs to the binder; once the
 * call returns (or throws) the SDK side must close its handle to those
 * PFDs — the service side will have dup'd them at parcel deserialization.
 * Leaking the source dups exhausts the per-process FD table on repeated
 * deferred submits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerDeferredTest {

    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: MindlayerImpl

    /** Caps that advertise FEATURE_DEFERRED_INFERENCE. */
    private val deferredCaps = ServiceCapabilities(
        apiVersion = 7,
        supportedFeatures = setOf(
            ServiceCapabilities.FEATURE_DEFERRED_INFERENCE,
        ),
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

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        mockService = mockk(relaxed = true)
        every { mockService.capabilities } returns deferredCaps

        mockConnection = mockk(relaxed = true)
        every { mockConnection.state } returns MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { mockConnection.awaitConnected() } returns mockService

        mindlayer = buildMindlayer(mockConnection)
    }

    @After
    fun tearDown() = unmockkAll()

    // ── capability gate ────────────────────────────────────────────────────

    @Test
    fun `chatDeferred throws NOT_SUPPORTED when capability missing`() = runTest {
        val capsNoDeferred = deferredCaps.copy(supportedFeatures = emptySet())
        every { mockService.capabilities } returns capsNoDeferred

        try {
            mindlayer.chatDeferred(sessionId = "s1", text = "hi")
            fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            assertEquals(MindlayerErrorCode.NOT_SUPPORTED, e.code)
        }
    }

    // ── H-D2 FD cleanup on successful inferDeferred ────────────────────────

    @Test
    fun `chatDeferred closes media source PFDs on success`() = runTest {
        val img = makeMediaPart(MediaPart.KIND_IMAGE)
        val aud = makeMediaPart(MediaPart.KIND_AUDIO)
        every {
            mockService.inferDeferred(any(), any())
        } returns DeferredHandle(requestId = "ignored-handle-uses-rebound", expiresAtMs = 1L)

        mindlayer.chatDeferred(sessionId = "s1", text = "hi", media = listOf(img, aud))

        // The SDK passes a copy()'d MediaPart through the AIDL parcel. The
        // ORIGINAL `img.source` / `aud.source` remain with the caller and the
        // SDK is responsible for closing them once the call has returned —
        // otherwise repeated chatDeferred submits leak one FD pair each.
        assertFalse(
            "image source PFD should be closed by chatDeferred() on success",
            img.source.fileDescriptor.valid(),
        )
        assertFalse(
            "audio source PFD should be closed by chatDeferred() on success",
            aud.source.fileDescriptor.valid(),
        )
    }

    // ── H-D2 FD cleanup on RemoteException ─────────────────────────────────

    @Test
    fun `chatDeferred closes media source PFDs when service throws RemoteException`() = runTest {
        val img = makeMediaPart(MediaPart.KIND_IMAGE)
        val aud = makeMediaPart(MediaPart.KIND_AUDIO)
        every { mockService.inferDeferred(any(), any()) } throws RemoteException("transport down")

        try {
            mindlayer.chatDeferred(sessionId = "s1", text = "hi", media = listOf(img, aud))
            fail("expected exception")
        } catch (_: Throwable) {
            // expected
        }

        assertFalse(
            "image source PFD should be closed by chatDeferred() on failure",
            img.source.fileDescriptor.valid(),
        )
        assertFalse(
            "audio source PFD should be closed by chatDeferred() on failure",
            aud.source.fileDescriptor.valid(),
        )
    }

    // ── requestId rebind ──────────────────────────────────────────────────

    @Test
    fun `chatDeferred rebinds media requestId to the generated requestId`() = runTest {
        val img = makeMediaPart(MediaPart.KIND_IMAGE, requestId = "caller-supplied-ignored")
        val metaSlot = slot<RequestMeta>()
        val partsSlot = slot<List<MediaPart>>()
        every {
            mockService.inferDeferred(capture(metaSlot), capture(partsSlot))
        } returns DeferredHandle(requestId = "doesnt-matter", expiresAtMs = 1L)

        mindlayer.chatDeferred(sessionId = "s1", text = "hi", media = listOf(img))

        val rebound = partsSlot.captured.single()
        assertEquals(
            "MediaPart.requestId must be rewritten to the freshly minted requestId",
            metaSlot.captured.requestId,
            rebound.requestId,
        )
        assertNotNull(metaSlot.captured.requestId)
        assertTrue(metaSlot.captured.requestId.isNotBlank())
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun makeMediaPart(
        kind: Int,
        requestId: String = "placeholder-req-id",
    ): MediaPart {
        // Real PFDs so .valid() reflects whether the SDK closed them.
        val pipe = ParcelFileDescriptor.createPipe()
        // Close the read end immediately — we only need the write end as the
        // `source` to track close-state. Leaving the read end open keeps the
        // pipe alive; closing the write side is the assertion target.
        try { pipe[0].close() } catch (_: Exception) {}
        return MediaPart(
            requestId = requestId,
            kind = kind,
            mimeType = if (kind == MediaPart.KIND_IMAGE) "image/jpeg" else "audio/wav",
            source = pipe[1],
            isSharedMemory = false,
            payloadBytes = 16L,
            width = if (kind == MediaPart.KIND_IMAGE) 1 else 0,
            height = if (kind == MediaPart.KIND_IMAGE) 1 else 0,
        )
    }

    private fun buildMindlayer(conn: ConnectionManager): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }
}
