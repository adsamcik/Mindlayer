package com.adsamcik.mindlayer.service

import android.os.Binder
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the DEBUG-only `autoAcceptGate` escape hatch in
 * [ServiceBinder.authorizeCall]: when enabled it lets an identified, not-denied
 * but unconsented caller through (bypassing the CONSENT_REQUIRED rejection),
 * while still honoring identity verification, explicit user denials, and the
 * main rate limit. The gate is injected directly here (variant-agnostic); the
 * production wiring of the debug seam is exercised by the debug source set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderAutoAcceptTest {
    private lateinit var service: MindlayerMlService
    private lateinit var allowlist: AllowlistStore
    private lateinit var rateLimiter: RateLimiter

    @Before fun setUp() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 24_681
        service = mockk(relaxed = true) {
            every { sessionManager } returns mockk<SessionManager>(relaxed = true)
            every { packageName } returns "com.adsamcik.mindlayer"
            every { createdAtMs } returns 1_000L
        }
        allowlist = mockk(relaxed = true) {
            every { isDenied(any(), any()) } returns false
            every { isAllowed(any(), any()) } returns false
        }
        rateLimiter = mockk(relaxed = true) {
            every { tryAcquire(any(), any()) } returns true
            every { tryAcquireRejected(any()) } returns true
            every { tryAcquireRejection(any()) } returns true
            every { tryAcquirePing(any()) } returns true
        }
    }

    @After fun tearDown() = unmockkAll()

    private fun binder(
        autoAccept: Boolean,
        verifier: (android.content.Context, Int) -> CallerIdentity? =
            { _, _ -> CallerIdentity("first.time", "sig", "First Time") },
    ): ServiceBinder = ServiceBinder(
        service = service,
        engineManager = mockk<EngineManager>(relaxed = true),
        orchestrator = mockk<InferenceOrchestrator>(relaxed = true),
        diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
        thermalMonitor = mockk<ThermalMonitor>(relaxed = true) {
            every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true))
        },
        memoryBudget = mockk<MemoryBudget>(relaxed = true),
        callerVerifier = verifier,
        allowlistStore = allowlist,
        rateLimiter = rateLimiter,
        autoAcceptGate = { autoAccept },
    )

    @Test fun `auto-accept bypasses CONSENT_REQUIRED and reaches the main rate limit`() {
        // Rate limit blocks here, which proves the gate passed identity +
        // allowlist + consent and reached the *accept* path (RATE_LIMITED,
        // never CONSENT_REQUIRED).
        every { rateLimiter.tryAcquire(any(), any()) } returns false

        val ex = assertThrows(SecurityException::class.java) { binder(autoAccept = true).getStatus() }

        assertTrue("expected rate-limit, got: ${ex.message}", ex.message!!.contains("Rate limit exceeded"))
        assertTrue("must not be consent error: ${ex.message}", !ex.message!!.contains("consent"))
        verify { rateLimiter.tryAcquire(24_681, any()) }
    }

    @Test fun `auto-accept off still rejects an unconsented caller with CONSENT_REQUIRED`() {
        val ex = assertThrows(SecurityException::class.java) { binder(autoAccept = false).getStatus() }

        assertTrue("expected consent error, got: ${ex.message}", ex.message!!.contains("requires user consent"))
        verify(exactly = 0) { rateLimiter.tryAcquire(24_681, any()) }
    }

    @Test fun `auto-accept still honors an explicit user denial`() {
        every { allowlist.isDenied("first.time", "sig") } returns true

        val ex = assertThrows(SecurityException::class.java) { binder(autoAccept = true).getStatus() }

        assertTrue("expected denied error, got: ${ex.message}", ex.message!!.contains("denied by user"))
        // Denied callers are rejected before the allowlist/consent check, so
        // the main rate limit is never consulted.
        verify(exactly = 0) { rateLimiter.tryAcquire(24_681, any()) }
    }

    @Test fun `auto-accept still requires a verifiable caller identity`() {
        val ex = assertThrows(SecurityException::class.java) {
            binder(autoAccept = true, verifier = { _, _ -> null }).getStatus()
        }

        assertTrue("expected identity error, got: ${ex.message}", ex.message!!.contains("identity could not be verified"))
        verify(exactly = 0) { allowlist.isAllowed(any(), any()) }
        verify(exactly = 0) { rateLimiter.tryAcquire(24_681, any()) }
    }
}
