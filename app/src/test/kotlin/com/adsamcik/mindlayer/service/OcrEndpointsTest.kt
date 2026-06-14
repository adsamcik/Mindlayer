package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [OcrEndpoints] — the post-auth OCR session delegate extracted
 * from `ServiceBinder`.
 *
 * Focus: the error-code mapping (concurrent-limit vs. schema-invalid) and the
 * ownership gate that every per-session endpoint enforces. The frame-ingest
 * path (`pushOcrFrame`) needs a real `MediaPart` + `SharedMemoryPool` and is
 * covered by the `ServiceBinderOcr*` and instrumented OCR suites instead.
 *
 * `typedException` is supplied as a test double that carries the wire code so
 * assertions can pin the exact [MindlayerErrorCode] each branch maps to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrEndpointsTest {

    private class TypedTestException(val code: Int, message: String) : RuntimeException(message)

    private lateinit var manager: OcrSessionManager
    private lateinit var endpoints: OcrEndpoints

    private fun goodConfig() = OcrSessionConfig(
        mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        outputSchemaJson = """{"type":"object","properties":{"x":{"type":"string"}}}""",
    )

    @Before
    fun setUp() {
        manager = mockk(relaxed = true)
        endpoints = OcrEndpoints(
            ocrSessionManager = manager,
            sharedMemoryPool = null,
            typedException = { code, message -> TypedTestException(code, message) },
        )
    }

    // ── createOcrSession ─────────────────────────────────────────────────

    @Test
    fun `createOcrSession returns the manager-issued id`() {
        every { manager.createSession(any(), any()) } returns "ocr-abcdef12"
        assertEquals("ocr-abcdef12", endpoints.createOcrSession(goodConfig()))
    }

    @Test
    fun `createOcrSession maps a concurrent-limit failure to CONCURRENT_LIMIT`() {
        every { manager.createSession(any(), any()) } throws
            IllegalStateException("OCR concurrent session limit reached")

        val ex = assertThrows(TypedTestException::class.java) {
            endpoints.createOcrSession(goodConfig())
        }
        assertEquals(MindlayerErrorCode.CONCURRENT_LIMIT, ex.code)
    }

    @Test
    fun `createOcrSession maps any other IllegalStateException to OCR_SCHEMA_INVALID`() {
        every { manager.createSession(any(), any()) } throws
            IllegalStateException("schema rejected")

        val ex = assertThrows(TypedTestException::class.java) {
            endpoints.createOcrSession(goodConfig())
        }
        assertEquals(MindlayerErrorCode.OCR_SCHEMA_INVALID, ex.code)
    }

    // ── getOcrSessionState ───────────────────────────────────────────────

    @Test
    fun `getOcrSessionState rejects a non-owner with SESSION_NOT_FOUND_OR_NOT_OWNED`() {
        every { manager.isOwner(any(), any()) } returns false

        val ex = assertThrows(TypedTestException::class.java) {
            endpoints.getOcrSessionState("ocr-abcdef12")
        }
        assertEquals(MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED, ex.code)
    }

    @Test
    fun `getOcrSessionState returns the manager state for an owner`() {
        every { manager.isOwner(any(), any()) } returns true
        val state = OcrSessionState(
            sessionId = "ocr-abcdef12",
            phase = OcrSessionState.PHASE_ACTIVE,
            framesAccepted = 3,
            framesDropped = 0,
            framesRejected = 0,
            pendingQueueDepth = 1,
            streamAttached = true,
            createdAtMs = 1_700_000_000_000L,
            lastFrameAtMs = 1_700_000_005_000L,
        )
        every { manager.stateOf(any(), any()) } returns state

        assertEquals(state, endpoints.getOcrSessionState("ocr-abcdef12"))
    }

    @Test
    fun `getOcrSessionState maps a stateOf race to SESSION_NOT_FOUND_OR_NOT_OWNED`() {
        every { manager.isOwner(any(), any()) } returns true
        every { manager.stateOf(any(), any()) } throws IllegalStateException("closed mid-call")

        val ex = assertThrows(TypedTestException::class.java) {
            endpoints.getOcrSessionState("ocr-abcdef12")
        }
        assertEquals(MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED, ex.code)
    }

    // ── finalizeOcrSession ───────────────────────────────────────────────

    @Test
    fun `finalizeOcrSession rejects a non-owner`() {
        every { manager.isOwner(any(), any()) } returns false

        val ex = assertThrows(TypedTestException::class.java) {
            endpoints.finalizeOcrSession("ocr-abcdef12")
        }
        assertEquals(MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED, ex.code)
    }

    @Test
    fun `finalizeOcrSession delegates to the manager for an owner`() {
        every { manager.isOwner(any(), any()) } returns true

        endpoints.finalizeOcrSession("ocr-abcdef12")

        coVerify(exactly = 1) { manager.finalize(any(), "ocr-abcdef12") }
    }

    // ── closeOcrSession ──────────────────────────────────────────────────

    @Test
    fun `closeOcrSession swallows an IllegalStateException (idempotent close)`() {
        coEvery { manager.close(any(), any()) } throws IllegalStateException("already closed / not owned")
        // Must not throw — close-of-unowned is indistinguishable from close-of-closed.
        endpoints.closeOcrSession("ocr-abcdef12")
        coVerify(exactly = 1) { manager.close(any(), "ocr-abcdef12") }
    }

    // ── getOcrLimits ─────────────────────────────────────────────────────

    @Test
    fun `getOcrLimits delegates to the manager`() {
        val limits = OcrLimits.zeroBaseline()
        every { manager.getLimits() } returns limits
        assertEquals(limits, endpoints.getOcrLimits())
    }
}
