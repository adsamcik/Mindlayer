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

    private companion object {
        const val UID = 24_680
    }
}
