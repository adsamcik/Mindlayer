@file:Suppress("DEPRECATION") // Existing tests deliberately exercise the
// ocrSession() deprecated alias to prove the rename hasn't broken pre-v0.10
// callers. New tests should use ocrRealtime() / ocrAsync() instead.

package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end SDK → AIDL wiring test for the v0.8 multi-frame OCR API.
 *
 * Mocks ``IMindlayerService`` at the binder boundary; exercises the
 * full SDK pipeline:
 *
 *  - ``Mindlayer.ocrLimits()`` reads through the cached binder.
 *  - ``Mindlayer.ocrSession(profile, configure) { }`` builds the
 *    correct ``OcrSessionConfig`` and calls ``createOcrSession``.
 *  - ``OcrSession.pushFrame(meta)`` round-trips through
 *    ``pushOcrFrame(sessionId, real MediaPart, meta)`` and returns
 *    the service's ``OcrFrameAck``.
 *  - ``OcrSession.state()`` round-trips through
 *    ``getOcrSessionState``.
 *  - ``OcrSession.finalize()`` calls ``finalizeOcrSession``.
 *  - ``OcrSession.close()`` calls ``closeOcrSession`` and is
 *    idempotent.
 *  - All five ``OcrProfile`` presets translate to the expected
 *    ``OcrSessionConfig.MODE_*`` on the binder call.
 *  - Capability fallback: an old service throwing
 *    ``NoSuchMethodError`` on ``ocrLimits`` returns
 *    ``OcrLimits.zeroBaseline()``.
 *  - ``use { }`` closure semantics close the session even when the
 *    body throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerOcrIntegrationTest {

    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: Mindlayer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockService = mockk(relaxed = true) {
            every { capabilities } returns ocrCaps()
            every { ocrLimits } returns OcrLimits(
                maxConcurrentOcrSessions = 2,
                maxOcrFramesPerMinute = 60,
                maxFramesPerOcrSession = 30,
                maxOcrSessionDurationMs = 60_000L,
                ocrPerFrameDecodeBudgetTokens = 512,
                ocrSchemaJsonMaxLen = 8 * 1024,
            )
            every { createOcrSession(any()) } returns "ocr-1000-fake"
            every { pushOcrFrame(any(), any(), any()) } answers {
                val frameMeta = thirdArg<OcrFrameMeta>()
                OcrFrameAck(
                    frameId = frameMeta.frameId,
                    status = OcrFrameAck.STATUS_ACCEPTED,
                    queueDepth = 1,
                )
            }
            every { getOcrSessionState(any()) } answers {
                OcrSessionState(
                    sessionId = firstArg(),
                    phase = OcrSessionState.PHASE_ACTIVE,
                    framesAccepted = 3,
                    framesDropped = 0,
                    framesRejected = 0,
                    pendingQueueDepth = 0,
                    streamAttached = false,
                    createdAtMs = 0L,
                    lastFrameAtMs = 0L,
                )
            }
        }

        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected(any()) } returns mockService
            coEvery { awaitConnected() } returns mockService
        }

        mindlayer = buildMindlayer(mockConnection, null)
    }

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore?): Mindlayer {
        val ctor = Mindlayer::class.java.getDeclaredConstructor(
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

    // ── ocrLimits ────────────────────────────────────────────────────────

    @Test fun `ocrLimits returns service-advertised values`() = runBlocking {
        val limits = mindlayer.ocrLimits()
        assertEquals(2, limits.maxConcurrentOcrSessions)
        assertEquals(60, limits.maxOcrFramesPerMinute)
        assertEquals(30, limits.maxFramesPerOcrSession)
    }

    @Test fun `ocrLimits falls back to zeroBaseline on NoSuchMethodError`() = runBlocking {
        every { mockService.capabilities } returns ocrCaps()
        every { mockService.ocrLimits } throws NoSuchMethodError("not present on old service")
        val limits = mindlayer.ocrLimits()
        assertEquals(OcrLimits.zeroBaseline(), limits)
    }

    @Test fun `ocrLimits falls back to zeroBaseline on AbstractMethodError`() = runBlocking {
        every { mockService.capabilities } returns ocrCaps()
        every { mockService.ocrLimits } throws AbstractMethodError("not implemented on old service")
        val limits = mindlayer.ocrLimits()
        assertEquals(OcrLimits.zeroBaseline(), limits)
    }

    // ── ocrSession DSL ───────────────────────────────────────────────────

    @Test fun `ocrSession builds with profile defaults`() = runBlocking {
        val configSlot = slot<OcrSessionConfig>()
        every { mockService.createOcrSession(capture(configSlot)) } returns "ocr-1-x"

        val session = mindlayer.ocrSession(OcrProfile.Receipt)
        assertEquals("ocr-1-x", session.sessionId)
        assertEquals(OcrSessionConfig.MODE_RECEIPT, configSlot.captured.mode)
        assertEquals(OcrProfile.Receipt.defaultSchema, configSlot.captured.outputSchemaJson)
    }

    @Test fun `ocrSession honors builder overrides`() = runBlocking {
        val configSlot = slot<OcrSessionConfig>()
        every { mockService.createOcrSession(capture(configSlot)) } returns "ocr-1-y"

        mindlayer.ocrSession(OcrProfile.IdCard) {
            languageHints = listOf("en", "de-DE")
            maxFrames = 15
            frameRateLimitFps = 3
            optionsJson = """{"strict_mrz":true}"""
        }
        assertEquals(OcrSessionConfig.MODE_ID_CARD, configSlot.captured.mode)
        assertEquals(listOf("en", "de-DE"), configSlot.captured.languageHints)
        assertEquals(15, configSlot.captured.maxFrames)
        assertEquals(3, configSlot.captured.frameRateLimitFps)
        assertEquals("""{"strict_mrz":true}""", configSlot.captured.optionsJson)
    }

    @Test fun `each of 5 profiles maps to its wire-stable mode`() = runBlocking {
        for (profile in OcrProfile.all) {
            val slot = slot<OcrSessionConfig>()
            every { mockService.createOcrSession(capture(slot)) } returns "ocr-1-${profile.mode}"
            mindlayer.ocrSession(profile)
            assertEquals(
                "Profile ${profile.displayName} should map to mode ${profile.mode}",
                profile.mode,
                slot.captured.mode,
            )
        }
    }

    // ── OcrSession lifecycle ─────────────────────────────────────────────

    @Test fun `pushFrame round-trips through AIDL`() = runBlocking {
        val session = mindlayer.ocrSession(OcrProfile.GeneralDocument)
        val meta = OcrFrameMeta(frameId = 7L, captureTimeMs = 0L)
        val ack = session.pushFrame(meta, ByteArray(64 * 64) { 127.toByte() }, 64, 64)
        assertEquals(7L, ack.frameId)
        assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)
    }

    @Test fun `OCR AIDL wire-prefixed errors become MindlayerException`() = runBlocking {
        val session = mindlayer.ocrSession(OcrProfile.GeneralDocument)
        every { mockService.pushOcrFrame(any(), any(), any()) } throws SecurityException(
            MindlayerErrorCode.wireMessage(MindlayerErrorCode.OCR_SESSION_FINALIZED, "finalized"),
        )

        val ex = assertThrows(MindlayerException::class.java) {
            runBlocking {
                session.pushFrame(
                    OcrFrameMeta(frameId = 9L, captureTimeMs = 0L),
                    ByteArray(64 * 64) { 127.toByte() },
                    64,
                    64,
                )
            }
        }
        assertEquals(MindlayerErrorCode.OCR_SESSION_FINALIZED, ex.code)
        assertEquals("ocr-1000-fake", ex.sessionId)
    }

    @Test fun `state round-trips through AIDL`() = runBlocking {
        val session = mindlayer.ocrSession(OcrProfile.GeneralDocument)
        val state = session.state()
        assertEquals(OcrSessionState.PHASE_ACTIVE, state.phase)
        assertEquals(3, state.framesAccepted)
    }

    @Test fun `finalize round-trips through AIDL`() = runBlocking {
        val session = mindlayer.ocrSession(OcrProfile.GeneralDocument)
        session.finalize()
        verify(exactly = 1) { mockService.finalizeOcrSession(any()) }
    }

    @Test fun `close calls closeOcrSession exactly once`() = runBlocking {
        val session = mindlayer.ocrSession(OcrProfile.GeneralDocument)
        session.close()
        session.close()
        verify(exactly = 1) { mockService.closeOcrSession(any()) }
    }

    @Test fun `pushFrame on closed session throws`() {
        runBlocking {
            val session = mindlayer.ocrSession(OcrProfile.GeneralDocument)
            session.close()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { session.pushFrame(OcrFrameMeta(frameId = 1L, captureTimeMs = 0L), ByteArray(64 * 64) { 127.toByte() }, 64, 64) }
            }
        }
    }

    @Test fun `use closure closes the session even when body throws`() {
        runBlocking {
            val session = mindlayer.ocrSession(OcrProfile.GeneralDocument)
            val ex = assertThrows(RuntimeException::class.java) {
                session.use {
                    throw RuntimeException("oops")
                }
            }
            assertEquals("oops", ex.message)
            verify(exactly = 1) { mockService.closeOcrSession(any()) }
        }
    }

    // ── End-to-end happy path ────────────────────────────────────────────

    @Test fun `full session lifecycle e2e`() = runBlocking {
        mindlayer.ocrSession(OcrProfile.Receipt) {
            languageHints = listOf("en")
            maxFrames = 10
        }.use { session ->
            assertNotNull(session.sessionId)
            val ack1 = session.pushFrame(OcrFrameMeta(frameId = 1L, captureTimeMs = 0L), ByteArray(64 * 64) { 127.toByte() }, 64, 64)
            assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack1.status)
            val ack2 = session.pushFrame(OcrFrameMeta(frameId = 2L, captureTimeMs = 100L), ByteArray(64 * 64) { 128.toByte() }, 64, 64)
            assertEquals(2L, ack2.frameId)
            val state = session.state()
            assertEquals(OcrSessionState.PHASE_ACTIVE, state.phase)
            session.finalize()
        }
        verify(exactly = 1) { mockService.createOcrSession(any()) }
        verify(atLeast = 2) { mockService.pushOcrFrame(any(), any(), any()) }
        verify(exactly = 1) { mockService.finalizeOcrSession(any()) }
        verify(exactly = 1) { mockService.closeOcrSession(any()) }
    }
}
