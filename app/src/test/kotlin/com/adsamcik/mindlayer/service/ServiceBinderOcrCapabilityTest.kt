package com.adsamcik.mindlayer.service

import android.os.Binder
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for the Phase 2 #5 ([p2-feature-flip](todo)) OCR
 * capability advertisement.
 *
 * Verifies:
 *  - `FEATURE_OCR_BARCODE_ANCHOR` is always advertised
 *    (wire-shape capability, no engine dependency).
 *  - `FEATURE_OCR_BOUNDING_BOXES` is always advertised
 *    (wire-shape capability, no engine dependency).
 *  - `FEATURE_OCR_SESSION` is ADVERTISED ONLY when
 *    [OcrSessionManager.isEngineReady] returns `true`.
 *  - When the engine is not ready, the flag is absent — capability-aware
 *    SDKs degrade gracefully without calling `createOcrSession` and
 *    hitting a runtime error on a device with no model bundle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderOcrCapabilityTest {

    private lateinit var ocrManager: OcrSessionManager

    @Before fun setUp() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 42
        ocrManager = mockk(relaxed = true)
        every { ocrManager.isProductionReady } returns true
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `FEATURE_OCR_BARCODE_ANCHOR is always advertised`() {
        every { ocrManager.isEngineReady() } returns false
        val caps = newBinder(ocrManager).getCapabilities()
        assertTrue(
            "FEATURE_OCR_BARCODE_ANCHOR must be advertised even without engine",
            caps.supports(ServiceCapabilities.FEATURE_OCR_BARCODE_ANCHOR),
        )
    }

    @Test fun `FEATURE_OCR_BOUNDING_BOXES is always advertised`() {
        every { ocrManager.isEngineReady() } returns false
        val caps = newBinder(ocrManager).getCapabilities()
        assertTrue(
            "FEATURE_OCR_BOUNDING_BOXES must be advertised even without engine",
            caps.supports(ServiceCapabilities.FEATURE_OCR_BOUNDING_BOXES),
        )
    }

    @Test fun `FEATURE_OCR_SESSION is advertised only when engine ready`() {
        every { ocrManager.isEngineReady() } returns true
        val withEngine = newBinder(ocrManager).getCapabilities()
        assertTrue(
            "engine ready -> FEATURE_OCR_SESSION must be advertised",
            withEngine.supports(ServiceCapabilities.FEATURE_OCR_SESSION),
        )

        every { ocrManager.isEngineReady() } returns false
        val withoutEngine = newBinder(ocrManager).getCapabilities()
        assertFalse(
            "engine NOT ready -> FEATURE_OCR_SESSION must NOT be advertised",
            withoutEngine.supports(ServiceCapabilities.FEATURE_OCR_SESSION),
        )
    }

    @Test fun `FEATURE_OCR_IMAGE_ONESHOT advertised iff engine ready and production`() {
        every { ocrManager.isEngineReady() } returns true
        val withEngine = newBinder(ocrManager).getCapabilities()
        assertTrue(
            "engine ready + production ready -> FEATURE_OCR_IMAGE_ONESHOT must be advertised",
            withEngine.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT),
        )

        every { ocrManager.isEngineReady() } returns false
        val withoutEngine = newBinder(ocrManager).getCapabilities()
        assertFalse(
            "engine NOT ready -> FEATURE_OCR_IMAGE_ONESHOT must NOT be advertised",
            withoutEngine.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT),
        )
    }

    @Test fun `OCR session and one-shot flip together`() {
        // The two flags share a single gate (engine ready + production ready),
        // so they must always advertise as a pair. Capability-aware SDKs can
        // therefore rely on either flag's presence to imply the other.
        every { ocrManager.isEngineReady() } returns true
        val ready = newBinder(ocrManager).getCapabilities()
        assertTrue(ready.supports(ServiceCapabilities.FEATURE_OCR_SESSION))
        assertTrue(ready.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT))

        every { ocrManager.isEngineReady() } returns false
        val dark = newBinder(ocrManager).getCapabilities()
        assertFalse(dark.supports(ServiceCapabilities.FEATURE_OCR_SESSION))
        assertFalse(dark.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT))
    }

    @Test fun `OCR flag flip is independent of FEATURE_EMBEDDINGS`() {
        every { ocrManager.isEngineReady() } returns true
        val caps = newBinder(ocrManager).getCapabilities()
        assertTrue(caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION))
        // FEATURE_EMBEDDINGS is opted out separately — verify both can
        // be on / off independently.
        assertFalse(
            "no embedding coordinator -> FEATURE_EMBEDDINGS must be off",
            caps.supports(ServiceCapabilities.FEATURE_EMBEDDINGS),
        )
    }

    private fun newBinder(ocr: OcrSessionManager): ServiceBinder {
        val service = mockk<MindlayerMlService>(relaxed = true)
        every { service.sessionManager } returns mockk<SessionManager>(relaxed = true)
        every { service.packageName } returns "com.adsamcik.mindlayer"
        val rateLimiter = mockk<RateLimiter>(relaxed = true)
        every { rateLimiter.tryAcquire(any(), any()) } returns true
        every { rateLimiter.tryAcquireRejected(any()) } returns true
        every { rateLimiter.tryAcquireRejection(any()) } returns true
        val allow = mockk<AllowlistStore>(relaxed = true)
        every { allow.isDenied(any(), any()) } returns false
        every { allow.isAllowed(any(), any()) } returns true
        return ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = mockk<InferenceOrchestrator>(relaxed = true),
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true) {
                every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true))
            },
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("pkg", "sig", "Pkg") },
            allowlistStore = allow,
            rateLimiter = rateLimiter,
            embeddingCoordinator = mockk<EmbeddingCoordinator>(relaxed = true) {
                every { defaultModelOrNull() } returns null
            },
            ocrSessionManager = ocr,
        )
    }
}
