package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.service.engine.EmbeddingEngine
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngine
import com.adsamcik.mindlayer.service.engine.SessionManager
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
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
        sessionManager = mockk(relaxed = true)
        engineManager = mockk(relaxed = true)
        orchestrator = mockk(relaxed = true)
        every { sessionManager.hasActiveStreaming() } returns false
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
            sessionManager.applyMemoryPressure(MemoryPressure.EMERGENCY)
            engineManager.shutdownIfIdle(any())
            sessionManager.invalidateIdleSessionsForBackendSwitch()
        }
    }
}
