package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.IBinder
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderLifecycleCleanupTest {
    private lateinit var binder: ServiceBinder
    private lateinit var orchestrator: InferenceOrchestrator
    private lateinit var ocr: OcrSessionManager
    private lateinit var embedding: EmbeddingCoordinator

    @Before fun setUp() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns UID
        val service = mockk<MindlayerMlService>(relaxed = true)
        every { service.sessionManager } returns mockk<SessionManager>(relaxed = true)
        every { service.packageName } returns "com.adsamcik.mindlayer.service"
        val allow = mockk<AllowlistStore>(relaxed = true) {
            every { isDenied(any(), any()) } returns false
            every { isAllowed(any(), any()) } returns true
        }
        val rateLimiter = mockk<RateLimiter>(relaxed = true) {
            every { tryAcquire(any(), any()) } returns true
            every { tryAcquireRejected(any()) } returns true
            every { tryAcquireRejection(any()) } returns true
        }
        val sessionManager = mockk<SessionManager>(relaxed = true) {
            every { activeRequestIdsOwnedBy(any()) } returns emptyList()
            every { closeAllOwnedBy(any()) } returns emptyList()
        }
        orchestrator = InferenceOrchestrator(
            service = service,
            sessionManager = sessionManager,
            sharedMemoryPool = mockk<SharedMemoryPool>(relaxed = true),
        )
        ocr = mockk(relaxed = true) {
            every { closeAllForUid(any()) } just Runs
        }
        embedding = mockk(relaxed = true) {
            every { cancelAllForUid(any()) } just Runs
        }
        binder = ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = orchestrator,
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true) {
                every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true))
            },
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("pkg", "sig", "Pkg") },
            allowlistStore = allow,
            rateLimiter = rateLimiter,
            ocrSessionManager = ocr,
            embeddingCoordinator = embedding,
        )
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `binder death closes OCR sessions and cancels embedding jobs for uid`() {
        val death = CapturingSlot<IBinder.DeathRecipient>()
        val token = mockk<IBinder>(relaxed = true) {
            every { interfaceDescriptor } returns "android.os.IBinder"
            every { linkToDeath(capture(death), 0) } just Runs
        }

        binder.registerClient(token)
        death.captured.binderDied()

        verify(exactly = 1) { ocr.closeAllForUid(UID) }
        verify(exactly = 1) { embedding.cancelAllForUid(UID) }
    }

    @Test fun `re-registering the same stable token is idempotent (R-19a)`() {
        // The canonical SDK reconnect path uses one stable liveness Binder for
        // the ConnectionManager's lifetime. Re-registering it must NOT link a
        // second DeathRecipient (which would accumulate toward the per-UID cap
        // and, on death, fire teardown twice).
        val token = mockk<IBinder>(relaxed = true) {
            every { interfaceDescriptor } returns "android.os.IBinder"
            every { linkToDeath(any(), 0) } just Runs
        }

        binder.registerClient(token)
        binder.registerClient(token)
        binder.registerClient(token)

        // Only the first registration links a recipient; the rest short-circuit.
        verify(exactly = 1) { token.linkToDeath(any(), 0) }
    }

    @Test fun `re-registering a different token keeps the prior registration independent (R-19a)`() {
        // Same-UID registrations are INDEPENDENT (see ServiceBinderTest's
        // "same uid registrations retain independent death cleanup"): a new
        // token becomes the current registration that owns NEW sessions, but
        // it must NOT unlink the prior recipient or tear down the prior
        // registration's sessions — each registration is cleaned up only by
        // its own binder death. Only the same-token reconnect is idempotent.
        val first = mockk<IBinder>(relaxed = true) {
            every { interfaceDescriptor } returns "android.os.IBinder"
            every { linkToDeath(any(), 0) } just Runs
        }
        val second = mockk<IBinder>(relaxed = true) {
            every { interfaceDescriptor } returns "android.os.IBinder"
            every { linkToDeath(any(), 0) } just Runs
        }

        binder.registerClient(first)
        binder.registerClient(second)

        // The prior recipient stays linked so the first client's own death
        // still cleans up its own sessions.
        verify(exactly = 0) { first.unlinkToDeath(any(), 0) }
    }

    private companion object {
        const val UID = 24_680
    }
}
