package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.service.engine.EmbeddingEngine
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.DeviceTier
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.MemorySnapshot
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngine
import com.adsamcik.mindlayer.service.engine.SessionManager
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerMlServiceMemoryPressureOrderingTest {
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

    @Test fun emergencyPressureDrainsOcrBeforeUnloadingModelsAndChat() = runTest {
        service.applyMemoryPressure(MemoryPressure.EMERGENCY)

        coVerifyOrder {
            ocrSessionManager.drainForMemoryPressure()
            paddleOcrEngine.unloadForMemoryPressure()
            embeddingEngine.unloadForMemoryPressure()
            engineManager.shutdownIfIdle(any())
        }
    }

    @Test fun emergencyPressureTimesOutBlockedOcrDrainAndContinuesUnload() = runTest {
        coEvery { ocrSessionManager.drainForMemoryPressure() } coAnswers { awaitCancellation() }

        service.applyMemoryPressure(MemoryPressure.EMERGENCY)

        coVerify(exactly = 1) { ocrSessionManager.drainForMemoryPressure() }
        verify(exactly = 1) { ocrSessionManager.cancelAllForMemoryPressure() }
        coVerify(exactly = 1) { paddleOcrEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { embeddingEngine.unloadForMemoryPressure() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun concurrentEmergencyPressureIsSingleFlight() = runTest {
        coEvery { ocrSessionManager.drainForMemoryPressure() } coAnswers { awaitCancellation() }

        val first = launch { service.applyMemoryPressure(MemoryPressure.EMERGENCY) }
        runCurrent()
        service.applyMemoryPressure(MemoryPressure.EMERGENCY)
        advanceTimeBy(2_000L)
        runCurrent()
        first.join()

        coVerify(exactly = 1) { ocrSessionManager.drainForMemoryPressure() }
        coVerify(exactly = 1) { paddleOcrEngine.unloadForMemoryPressure() }
        coVerify(exactly = 1) { embeddingEngine.unloadForMemoryPressure() }
    }
}
