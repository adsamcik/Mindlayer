package com.adsamcik.mindlayer.service

import android.os.Binder
import com.adsamcik.mindlayer.HealthCheck
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
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Robolectric tests for the Phase 3 #8 `ping()` endpoint contract.
 *
 * Engine-state -> wire-int mapping is covered by the dedicated
 * `HealthCheckEngineStateMapperTest`. MockK on the JVM cannot
 * intercept final `val` properties on final classes (the shape of
 * every engine-state holder) — so we test the mapping pure-function
 * in isolation and the binder contract here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderPingTest {

    private lateinit var rateLimiter: RateLimiter
    private lateinit var allow: AllowlistStore
    private lateinit var binder: ServiceBinder

    @Before fun setUp() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 42

        val service = mockk<MindlayerMlService>(relaxed = true)
        every { service.sessionManager } returns mockk<SessionManager>(relaxed = true)
        every { service.packageName } returns "com.adsamcik.mindlayer.service"

        rateLimiter = mockk(relaxed = true)
        every { rateLimiter.tryAcquire(any(), any()) } returns true
        every { rateLimiter.tryAcquireRejected(any()) } returns true
        every { rateLimiter.tryAcquireRejection(any()) } returns true
        every { rateLimiter.tryAcquirePing(any()) } returns true

        allow = mockk(relaxed = true)
        every { allow.isDenied(any(), any()) } returns false
        every { allow.isAllowed(any(), any()) } returns true

        binder = ServiceBinder(
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
            ocrSessionManager = mockk<OcrSessionManager>(relaxed = true) {
                every { isEngineReady() } returns false
            },
        )
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `ping returns non-null HealthCheck`() {
        val health = binder.ping()
        assertNotNull(health)
    }

    @Test fun `ping does NOT charge the rate limiter`() {
        binder.ping()
        verify(exactly = 0) { rateLimiter.tryAcquire(any(), any()) }
        verify(exactly = 0) { rateLimiter.tryAcquireRejected(any()) }
        verify(exactly = 0) { rateLimiter.tryAcquireRejection(any()) }
    }

    @Test fun `ping uses the ping-specific throttle for external callers`() {
        binder.ping()
        verify(exactly = 1) { rateLimiter.tryAcquirePing(any()) }
    }

    @Test fun `ping consults the allowlist to choose full vs coarse response`() {
        // v0.10: ping() now consults the allowlist to decide between the
        // full response (self-UID or allowlisted) and the coarse
        // pre-consent response. This caller is allowlisted, so the
        // allowlist IS consulted and the full ping path runs.
        binder.ping()
        verify { allow.isAllowed(any(), any()) }
        verify(exactly = 1) { rateLimiter.tryAcquirePing(any()) }
    }

    @Test fun `apiVersion matches binder CURRENT_API_VERSION`() {
        val health = binder.ping()
        assertEquals(binder.getCapabilities().apiVersion, health.apiVersion)
    }

    @Test fun `extensionsJson is null in v1`() {
        val health = binder.ping()
        assertNull(health.extensionsJson)
    }

    @Test fun `serverTimestampMs is positive`() {
        val health = binder.ping()
        assertTrue("timestamp ${health.serverTimestampMs} should be > 0", health.serverTimestampMs > 0)
    }

    @Test fun `serviceUptimeMs is non-negative`() {
        val health = binder.ping()
        assertTrue(
            "uptime ${health.serviceUptimeMs} should be non-negative",
            health.serviceUptimeMs >= 0,
        )
    }

    @Test fun `FEATURE_HEALTH_CHECK is advertised in capabilities`() {
        val caps = binder.getCapabilities()
        assertTrue(
            "FEATURE_HEALTH_CHECK must be advertised",
            caps.supports(ServiceCapabilities.FEATURE_HEALTH_CHECK),
        )
    }

    @Test fun `FEATURE_AUDIO_INPUT is advertised in capabilities`() {
        // Audio plumbing is unconditional today (the bundled Gemma 4 model
        // always handles audio); this test pins that the capability string
        // is actually exposed via getCapabilities() so SDK callers can
        // gate on it. Co-located with the FEATURE_HEALTH_CHECK assertion
        // above because both ride on the same getCapabilities() probe.
        val caps = binder.getCapabilities()
        assertTrue(
            "FEATURE_AUDIO_INPUT must be advertised — Gemma 4 audio support " +
                "is documented in docs/engine/AUDIO.md and gated on this capability flag",
            caps.supports(ServiceCapabilities.FEATURE_AUDIO_INPUT),
        )
    }

    @Test fun `engine-state fields default to IDLE when service fields uninitialized`() {
        val health = binder.ping()
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, health.embeddingEngineState)
        assertEquals(HealthCheck.ENGINE_STATE_IDLE, health.ocrEngineState)
    }
}
