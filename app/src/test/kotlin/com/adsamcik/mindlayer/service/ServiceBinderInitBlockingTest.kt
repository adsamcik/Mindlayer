package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.EngineState
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderInitBlockingTest {
    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `cold createSession suspends until engine reports Ready`() = runBlocking {
        val ready = CompletableDeferred<EngineState>()
        val orchestrator = mockOrchestrator()
        every { orchestrator.createSession(any(), any()) } returns "s1"
        val engineManager = mockEngineManager(ready)
        val binder = binder(engineManager, orchestrator)

        val call = async(Dispatchers.IO) { binder.createSession(SessionConfig(maxTokens = 2048)) }
        delay(100)
        assertFalse("createSession must wait for Ready", call.isCompleted)

        ready.complete(EngineState.Ready)

        assertEquals("s1", call.await())
        verify { orchestrator.createSession(any(), any()) }
    }

    @Test
    fun `cold createSession waiters receive typed init failure`() = runBlocking {
        val failed = CompletableDeferred<EngineState>()
        val orchestrator = mockOrchestrator()
        val engineManager = mockEngineManager(failed)
        val binder = binder(engineManager, orchestrator)

        val calls = List(3) {
            async(Dispatchers.IO) {
                runCatching { binder.createSession(SessionConfig(maxTokens = 2048)) }
                    .exceptionOrNull() as SecurityException
            }
        }
        delay(100)
        assertTrue(calls.none { it.isCompleted })

        failed.complete(EngineState.Failed(InitFailure.ModelMissing))

        calls.forEach { call ->
            assertEquals(
                MindlayerErrorCode.MODEL_MISSING,
                MindlayerErrorCode.codeFromWireMessage(call.await().message),
            )
        }
        verify(exactly = 0) { orchestrator.createSession(any(), any()) }
    }

    private fun binder(
        engineManager: EngineManager,
        orchestrator: InferenceOrchestrator,
    ): ServiceBinder {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns Process.myUid()
        val service = mockk<MindlayerMlService>(relaxed = true) {
            every { createdAtMs } returns 1_000L
        }
        return ServiceBinder(
            service = service,
            engineManager = engineManager,
            orchestrator = orchestrator,
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true),
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("test", "sig", "Test") },
            allowlistStore = mockk<AllowlistStore>(relaxed = true),
            rateLimiter = RateLimiter(maxRequestsPerMinute = 100_000, maxConcurrent = 1_000),
        )
    }

    private fun mockEngineManager(state: CompletableDeferred<EngineState>): EngineManager =
        mockk(relaxed = true) {
            every { isInitialized } returns false
            coEvery { awaitReady() } coAnswers { state.await() }
            coEvery { initialize(any(), any()) } returns mockk(relaxed = true)
        }

    private fun mockOrchestrator(): InferenceOrchestrator =
        mockk(relaxed = true) {
            every { closeAllOwnedBy(any()) } returns emptyList()
            every { getSessionOwner(any()) } returns null
        }
}
