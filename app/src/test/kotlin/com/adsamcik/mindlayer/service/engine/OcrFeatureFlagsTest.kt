package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ServiceBinder
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the [OcrFeatureFlags.IS_PRODUCTION_READY] gate at two layers:
 *
 *  1. **Constant + manager wiring** — the committed flag is `true` as of v0.9
 *     (production promotion), and the [OcrSessionManager] default constructor
 *     arg honours that flag.
 *  2. **Binder advertisement** — [ServiceBinder.getCapabilities]
 *     advertises [ServiceCapabilities.FEATURE_OCR_SESSION] (and
 *     [ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT]) **only**
 *     when BOTH `isProductionReady == true` AND `isEngineReady() == true`.
 *
 * Complements [com.adsamcik.mindlayer.service.ServiceBinderOcrCapabilityTest],
 * which covers the engine-ready half of the gate with the production
 * flag forced on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrFeatureFlagsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dir = context.filesDir
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "paddleocr-ppocrv5-mobile-det.tflite").writeBytes(byteArrayOf(1))
        File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").writeBytes(byteArrayOf(2))
        File(dir, "paddleocr-ppocrv5-mobile-dict.txt").writeText("A")
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 42
    }

    @After fun tearDown() = unmockkAll()

    // ── Layer 1: constant + OcrSessionManager wiring ─────────────────────

    @Test fun defaultOcrProductionFlagIsTrue() {
        assertTrue(
            "v0.9 production promotion: IS_PRODUCTION_READY must remain true",
            OcrFeatureFlags.IS_PRODUCTION_READY,
        )
        assertTrue(
            "OcrSessionManager default ctor must mirror OcrFeatureFlags.IS_PRODUCTION_READY",
            OcrSessionManager().isProductionReady,
        )
    }

    @Test fun constructorInjectionControlsProductionGate() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = PaddleOcrEngine(context, backendFactory = { backend })
        engine.initialize()

        val disabled = OcrSessionManager(engine = engine, isProductionReady = false)
        val enabled = OcrSessionManager(engine = engine, isProductionReady = true)

        assertTrue(disabled.isEngineReady())
        assertFalse(disabled.isProductionReady)
        assertTrue(enabled.isEngineReady())
        assertTrue(enabled.isProductionReady)
    }

    // ── Layer 2: ServiceBinder.getCapabilities() truth table ─────────────

    @Test fun `FEATURE_OCR_SESSION absent when isProductionReady is false even with engine ready`() {
        val caps = newBinder(productionReady = false, engineReady = true).getCapabilities()
        assertFalse(
            "production-not-ready -> FEATURE_OCR_SESSION must NOT be advertised",
            caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION),
        )
        assertFalse(
            "production-not-ready -> FEATURE_OCR_IMAGE_ONESHOT must NOT be advertised",
            caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT),
        )
    }

    @Test fun `FEATURE_OCR_SESSION present when both gates true`() {
        val caps = newBinder(productionReady = true, engineReady = true).getCapabilities()
        assertTrue(
            "production-ready + engine-ready -> FEATURE_OCR_SESSION must be advertised",
            caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION),
        )
        assertTrue(
            "production-ready + engine-ready -> FEATURE_OCR_IMAGE_ONESHOT must be advertised",
            caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT),
        )
    }

    @Test fun `FEATURE_OCR_SESSION absent when engine not ready even with production flag set`() {
        val caps = newBinder(productionReady = true, engineReady = false).getCapabilities()
        assertFalse(
            "engine-not-ready -> FEATURE_OCR_SESSION must NOT be advertised",
            caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION),
        )
        assertFalse(
            "engine-not-ready -> FEATURE_OCR_IMAGE_ONESHOT must NOT be advertised",
            caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT),
        )
    }

    @Test fun `both gates false then FEATURE_OCR_SESSION absent`() {
        val caps = newBinder(productionReady = false, engineReady = false).getCapabilities()
        assertFalse(caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION))
        assertFalse(caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT))
    }

    @Test fun `live ServiceBinder respects committed IS_PRODUCTION_READY default`() {
        // The default OcrSessionManager pulls isProductionReady from
        // OcrFeatureFlags.IS_PRODUCTION_READY. With the committed value
        // (true as of v0.9) FEATURE_OCR_SESSION + FEATURE_OCR_IMAGE_ONESHOT
        // must appear when the engine is also ready.
        val ocr = mockk<OcrSessionManager>(relaxed = true)
        every { ocr.isProductionReady } returns OcrFeatureFlags.IS_PRODUCTION_READY
        every { ocr.isEngineReady() } returns true
        val caps = newBinderFromMock(ocr).getCapabilities()
        assertEquals(true, caps.supports(ServiceCapabilities.FEATURE_OCR_SESSION))
        assertEquals(true, caps.supports(ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT))
    }

    private fun newBinder(productionReady: Boolean, engineReady: Boolean): ServiceBinder {
        val ocr = mockk<OcrSessionManager>(relaxed = true)
        every { ocr.isProductionReady } returns productionReady
        every { ocr.isEngineReady() } returns engineReady
        return newBinderFromMock(ocr)
    }

    private fun newBinderFromMock(ocr: OcrSessionManager): ServiceBinder {
        val service = mockk<MindlayerMlService>(relaxed = true)
        every { service.sessionManager } returns mockk<SessionManager>(relaxed = true)
        every { service.packageName } returns "com.adsamcik.mindlayer.service"
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
