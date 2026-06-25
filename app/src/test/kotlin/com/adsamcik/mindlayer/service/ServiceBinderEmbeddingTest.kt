package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.Process
import com.adsamcik.mindlayer.EmbeddingBatchResult
import com.adsamcik.mindlayer.EmbeddingBatchTransfer
import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingResult
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.VectorBlobHandle
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EmbeddingModelInfo
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderEmbeddingTest {
    private lateinit var binder: ServiceBinder
    private lateinit var rateLimiter: RateLimiter
    private lateinit var coordinator: EmbeddingCoordinator

    @Before fun setUp() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 42
        val service = mockk<MindlayerMlService>(relaxed = true)
        every { service.sessionManager } returns mockk<SessionManager>(relaxed = true)
        every { service.packageName } returns "com.adsamcik.mindlayer"
        rateLimiter = mockk(relaxed = true)
        every { rateLimiter.tryAcquire(any(), any()) } returns true
        every { rateLimiter.tryAcquireRejected(any()) } returns true
        every { rateLimiter.tryAcquireRejection(any()) } returns true
        // S-4: synchronous embed/ocr calls now acquire a concurrency slot.
        every { rateLimiter.beginInference(any()) } returns true
        val allow = mockk<AllowlistStore>(relaxed = true)
        every { allow.isDenied(any(), any()) } returns false
        every { allow.isAllowed(any(), any()) } returns true
        coordinator = mockk(relaxed = true)
        val model = EmbeddingModelInfo("m", "M", "/m", "/t", 1, 768, listOf(768, 256), 2048, null)
        every { coordinator.defaultModelOrNull() } returns model
        // FEATURE_EMBEDDINGS in getCapabilities() now requires both a model
        // AND the production-readiness flag. Tests that exercise the "feature
        // on" path stub this to true; tests that exercise the gated-off path
        // leave it at the default (false).
        every { coordinator.isProductionReady } returns true
        // The new IpcInputValidator wiring in ServiceBinder reads batch
        // caps from the coordinator. `mockk(relaxed = true)` returns 0 for
        // primitive properties, so a batch of N requests would be rejected
        // as "too large" before reaching the AIDL method body. Stub the
        // production defaults so the validator passes.
        every { coordinator.maxBatchInline } returns 64
        every { coordinator.maxBatchShm } returns 4096
        every { coordinator.maxBatchTotal } returns 4096
        every { coordinator.maxInputBytes } returns 512L * 1024L
        coEvery { coordinator.embed(any(), any(), any()) } returns EmbeddingResult(vector = floatArrayOf(), dim = 0, modelId = "m", tokenCount = 0, truncated = false, backend = "CPU", durationMs = 0)
        coEvery { coordinator.embedBatch(any(), any(), any()) } returns EmbeddingBatchResult(results = emptyList(), totalDurationMs = 0, backend = "CPU")
        coEvery { coordinator.embedBatchDeferred(any(), any()) } returns com.adsamcik.mindlayer.DeferredHandle(requestId = "d", expiresAtMs = 1)
        coEvery { coordinator.fetchEmbeddingBatchResult(any(), any()) } returns VectorBlobHandle(status = 0)
        coEvery { coordinator.cancelEmbeddingBatch(any(), any()) } returns 0
        coEvery { coordinator.acknowledgeEmbeddingBatchResult(any(), any()) } returns true
        every { coordinator.cancelEmbed(any(), any()) } returns 0
        binder = ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = mockk<InferenceOrchestrator>(relaxed = true),
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true) { every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true)) },
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("pkg", "sig", "Pkg") },
            allowlistStore = allow,
            rateLimiter = rateLimiter,
            embeddingCoordinator = coordinator,
        )
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `embedding methods charge designed costs`() {
        binder.embed(EmbeddingRequest(text = "x"))
        binder.embedBatch(List(4) { EmbeddingRequest(text = "x") })
        binder.embedBatchDeferred(listOf(EmbeddingRequest(text = "x")))
        binder.fetchEmbeddingBatchResult("id")
        binder.cancelEmbeddingBatch("id")
        binder.acknowledgeEmbeddingBatchResult("id")
        binder.cancelEmbed("id")
        verify { rateLimiter.tryAcquire(42, 1.0) }
        verify { rateLimiter.tryAcquire(42, 2.0) }
        verify { rateLimiter.tryAcquire(42, 0.5) }
        verify(exactly = 4) { rateLimiter.tryAcquire(42, 0.1) }
    }

    @Test fun `embedding result requestIds are validated at binder boundary`() {
        val bad = "bad/../id"

        assertThrows(SecurityException::class.java) { binder.fetchEmbeddingBatchResult(bad) }
        assertThrows(SecurityException::class.java) { binder.cancelEmbeddingBatch(bad) }
        assertThrows(SecurityException::class.java) { binder.acknowledgeEmbeddingBatchResult(bad) }
        assertThrows(SecurityException::class.java) { binder.cancelEmbed(bad) }

        io.mockk.coVerify(exactly = 0) { coordinator.fetchEmbeddingBatchResult(any(), any()) }
        io.mockk.coVerify(exactly = 0) { coordinator.cancelEmbeddingBatch(any(), any()) }
        io.mockk.coVerify(exactly = 0) { coordinator.acknowledgeEmbeddingBatchResult(any(), any()) }
        verify(exactly = 0) { coordinator.cancelEmbed(any(), any()) }
    }

    @Test fun `embed acquires and releases a concurrency slot (S-4)`() {
        binder.embed(EmbeddingRequest(text = "x"))
        verify { rateLimiter.beginInference(42) }
        verify { rateLimiter.endInference(42) }
    }

    @Test fun `embed rejects with CONCURRENT_LIMIT when the slot is unavailable (S-4)`() {
        every { rateLimiter.beginInference(42) } returns false
        val ex = assertThrows(SecurityException::class.java) {
            binder.embed(EmbeddingRequest(text = "x"))
        }
        assertTrue(
            "should surface the typed concurrent-limit error",
            (ex.message ?: "").contains("CONCURRENT_LIMIT") ||
                (ex.message ?: "").contains("Concurrent inference limit"),
        )
        // The heavy coordinator call must NOT run when the slot is refused.
        io.mockk.coVerify(exactly = 0) { coordinator.embed(any(), any(), any()) }
    }

    @Test fun `self uid bypasses external rate limit`() {
        every { Binder.getCallingUid() } returns Process.myUid()
        binder.embed(EmbeddingRequest(text = "x"))
        verify(exactly = 0) { rateLimiter.tryAcquire(Process.myUid(), any()) }
    }

    @Test fun `capabilities advertise embeddings only with model`() {
        val yes = binder.getCapabilities()
        assertTrue(yes.supports(ServiceCapabilities.FEATURE_EMBEDDINGS))
        every { coordinator.defaultModelOrNull() } returns null
        val no = binder.getCapabilities()
        assertFalse(no.supports(ServiceCapabilities.FEATURE_EMBEDDINGS))
        assertEquals(0, no.maxEmbeddingBatchInline)
    }

    @Test fun `capabilities omit embeddings when backend is not production ready`() {
        // With a real model installed but the production-readiness flag
        // off (Phase A scaffold state), FEATURE_EMBEDDINGS must not be
        // advertised and the numeric caps must all be zero — otherwise
        // capability-aware SDKs would call into the stub backend.
        every { coordinator.isProductionReady } returns false
        val caps = binder.getCapabilities()
        assertFalse(caps.supports(ServiceCapabilities.FEATURE_EMBEDDINGS))
        assertEquals(0, caps.maxEmbeddingBatchInline)
        assertEquals(0, caps.maxEmbeddingBatchShm)
        assertEquals(0, caps.maxEmbeddingBatchTotal)
        assertEquals(0L, caps.maxEmbeddingInputBytes)
        assertEquals(emptyList<String>(), caps.embeddingModelIds)
        assertEquals(emptyList<Int>(), caps.embeddingDims)
    }

    @Test
    @Config(sdk = [26])
    fun `embedBatchShm rejects API 26 with NOT_SUPPORTED before coordinator call`() {
        val ex = assertThrows(SecurityException::class.java) {
            binder.embedBatchShm(listOf(EmbeddingRequest(text = "x")))
        }
        assertEquals(
            com.adsamcik.mindlayer.shared.MindlayerErrorCode.NOT_SUPPORTED,
            com.adsamcik.mindlayer.shared.MindlayerErrorCode.codeFromWireMessage(ex.message),
        )
        io.mockk.coVerify(exactly = 0) { coordinator.embedBatchShm(any(), any(), any()) }
    }
}
