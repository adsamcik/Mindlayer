package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.service.engine.DeviceTier
import com.adsamcik.mindlayer.service.engine.EmbeddingEngine
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.MemorySnapshot
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngine
import com.adsamcik.mindlayer.service.engine.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Locks in PR #99 single-flight EMERGENCY behaviour and current partial-failure
 * handling in `applyMemoryPressure`. See ROADMAP Phase 7. Only PaddleOCR unload
 * is wrapped in try/catch today; drain and embedding-unload failures propagate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerMlServiceConcurrentEmergencyTest {
    private lateinit var service: MindlayerMlService
    private lateinit var ocrSessionManager: OcrSessionManager
    private lateinit var paddleOcrEngine: PaddleOcrEngine
    private lateinit var embeddingEngine: EmbeddingEngine
    private lateinit var sessionManager: SessionManager
    private lateinit var engineManager: EngineManager
    private lateinit var orchestrator: InferenceOrchestrator

    @Before fun setUp() {
        service = Robolectric.buildService(MindlayerMlService::class.java).get()
        ocrSessionManager = mockk(relaxed = true)
        paddleOcrEngine = mockk(relaxed = true)
        embeddingEngine = mockk(relaxed = true)
        engineManager = mockk(relaxed = true)
        orchestrator = mockk(relaxed = true)
        val memoryBudget = mockk<MemoryBudget>(relaxed = true) {
            every { deviceTier } returns DeviceTier(
                maxSessions = 2,
                defaultMaxTokens = 4096,
                maxMaxTokens = 4096,
                deviceRamMb = 8_192L,
            )
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 4096L,
                totalMb = 8192L,
                lowMemory = false,
                pressure = MemoryPressure.EMERGENCY,
                recommendedMaxTokens = 2048,
            )
        }
        sessionManager = SessionManager(mockk(relaxed = true), engineManager, memoryBudget)
        coEvery { engineManager.shutdownIfIdle(any()) } returns true
        ReflectionHelpers.setField(service, "ocrSessionManager", ocrSessionManager)
        ReflectionHelpers.setField(service, "paddleOcrEngine", paddleOcrEngine)
        ReflectionHelpers.setField(service, "embeddingEngine", embeddingEngine)
        ReflectionHelpers.setField(service, "sessionManager", sessionManager)
        ReflectionHelpers.setField(service, "engineManager", engineManager)
        ReflectionHelpers.setField(service, "orchestrator", orchestrator)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun eightConcurrentEmergencyEmissionsDrainOnce() = runTest {
        // Pin the first drain inside the mutex so the other 7 launches see
        // `tryLock()` fail. Without this gate, runTest's serial dispatcher
        // would let each launch finish before the next started.
        val drainGate = CompletableDeferred<Unit>()
        coEvery { ocrSessionManager.drainForMemoryPressure() } coAnswers { drainGate.await() }

        val jobs = List(8) { launch { service.applyMemoryPressure(MemoryPressure.EMERGENCY) } }
        runCurrent()
        coVerify(exactly = 1) { ocrSessionManager.drainForMemoryPressure() }

        drainGate.complete(Unit)
        advanceUntilIdle()
        jobs.forEach { it.join() }

        coVerify(exactly = 1) { ocrSessionManager.drainForMemoryPressure() }
        coVerify(exactly = 1) { paddleOcrEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { embeddingEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { engineManager.shutdownIfIdle(any()) }
    }

    @Test fun paddleOcrUnloadFailureDoesNotStopEmbeddingUnloadOrEngineShutdown() = runTest {
        coEvery { paddleOcrEngine.unloadForMemoryPressure() } throws
            RuntimeException("paddle oom")

        service.applyMemoryPressure(MemoryPressure.EMERGENCY)

        coVerify(exactly = 1) { ocrSessionManager.drainForMemoryPressure() }
        coVerify(exactly = 1) { paddleOcrEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { embeddingEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { engineManager.shutdownIfIdle(any()) }
    }

    @Test fun ocrDrainFailurePropagatesAndReleasesSingleFlightMutex() = runTest {
        coEvery { ocrSessionManager.drainForMemoryPressure() } throws
            RuntimeException("drain failed")

        val first = runCatching {
            service.applyMemoryPressure(MemoryPressure.EMERGENCY)
        }
        assertTrue("first call should surface drain failure", first.isFailure)

        // Mutex must be released in `finally` so a follow-up pass can run.
        coEvery { ocrSessionManager.drainForMemoryPressure() } returns Unit
        service.applyMemoryPressure(MemoryPressure.EMERGENCY)

        coVerify(exactly = 2) { ocrSessionManager.drainForMemoryPressure() }
        coVerify(exactly = 1) { paddleOcrEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { embeddingEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { engineManager.shutdownIfIdle(any()) }
    }

    @Test fun embeddingUnloadFailurePropagatesAndReleasesSingleFlightMutex() = runTest {
        coEvery { embeddingEngine.unloadForMemoryPressure() } throws
            RuntimeException("embedding oom")

        val first = runCatching {
            service.applyMemoryPressure(MemoryPressure.EMERGENCY)
        }
        assertTrue("first call should surface embedding failure", first.isFailure)

        coEvery { embeddingEngine.unloadForMemoryPressure() } returns Unit
        service.applyMemoryPressure(MemoryPressure.EMERGENCY)

        coVerify(exactly = 2) { ocrSessionManager.drainForMemoryPressure() }
        coVerify(exactly = 2) { paddleOcrEngine.unloadForMemoryPressure() }
        coVerify(exactly = 2) { embeddingEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { engineManager.shutdownIfIdle(any()) }
    }
}
