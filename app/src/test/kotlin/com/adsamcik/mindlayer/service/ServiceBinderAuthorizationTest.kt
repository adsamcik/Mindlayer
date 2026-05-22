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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderAuthorizationTest {
    private lateinit var service: MindlayerMlService
    private lateinit var allowlist: AllowlistStore
    private lateinit var rateLimiter: RateLimiter
    private lateinit var binder: ServiceBinder

    @Before fun setUp() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 24_681
        service = mockk(relaxed = true) {
            every { sessionManager } returns mockk<SessionManager>(relaxed = true)
            every { packageName } returns "com.adsamcik.mindlayer.service"
            every { createdAtMs } returns 1_000L
        }
        allowlist = mockk(relaxed = true) {
            every { isDenied(any(), any()) } returns false
            every { isAllowed(any(), any()) } returns false
        }
        rateLimiter = mockk(relaxed = true) {
            every { tryAcquire(any(), any()) } returns false
            every { tryAcquireRejection(any()) } returns true
            every { tryAcquirePing(any()) } returns true
        }
        binder = ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = mockk<InferenceOrchestrator>(relaxed = true),
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true) {
                every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true))
            },
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("first.time", "sig", "First Time") },
            allowlistStore = allowlist,
            rateLimiter = rateLimiter,
        )
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `first time UID records pending before main rate limit is consumed`() {
        assertThrows(SecurityException::class.java) { binder.getStatus() }

        verify { allowlist.isAllowed("first.time", "sig") }
        verify { rateLimiter.tryAcquireRejection(24_681) }
        verify { allowlist.recordPending("first.time", "sig", "First Time") }
        verify(exactly = 0) { rateLimiter.tryAcquire(24_681, any()) }
    }

    @Test fun `ping uses ping-specific throttle without allowlist gate`() {
        every { rateLimiter.tryAcquirePing(24_681) } returns false

        assertThrows(SecurityException::class.java) { binder.ping() }

        verify { rateLimiter.tryAcquirePing(24_681) }
        verify(exactly = 0) { allowlist.isAllowed(any(), any()) }
    }
}
