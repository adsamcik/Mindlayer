package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.service.engine.DeviceTier
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EmbeddingEngine
import com.adsamcik.mindlayer.service.engine.EngineNotReadyException
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.MemoryPressure
import com.adsamcik.mindlayer.service.engine.MemorySnapshot
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngine
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalBand
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the thermal/backend-switch starvation guard (R-3) and the
 * backend-switch restart quiescing race fix (R-6).
 *
 * The full `applyPendingBackendSwitch` flow ends in `Process.killProcess`,
 * so we test the two extracted, deterministic policy points:
 *  - `shouldForceOverdueThermalSwitch` (R-3): when a deferred downshift is
 *    forced.
 *  - `enterForeground` rejecting a new inference while quiescing (R-6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MindlayerMlServiceBackendSwitchTest {

    @After
    fun tearDown() = unmockkAll()

    private fun newService(): MindlayerMlService {
        val service = Robolectric.buildService(MindlayerMlService::class.java).get()
        setField(service, "logRepository", mockk<LogRepository>(relaxed = true))
        mockkObject(MindlayerLog)
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit
        return service
    }

    // ---- R-3: overdue-switch forcing policy --------------------------------

    @Test
    fun `does not force a non-critical deferred switch (R-3)`() {
        val s = newService()
        // HOT (not CRITICAL) deferred well past the deadline must NOT force —
        // we only pre-empt in-flight inference when the device is genuinely
        // too hot.
        assertFalse(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.HOT,
                pendingSince = 1_000L,
                now = 1_000L + 10 * 60_000L,
                deadlineMs = 20_000L,
            ),
        )
    }

    @Test
    fun `does not force before the deadline even under CRITICAL (R-3)`() {
        val s = newService()
        assertFalse(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.CRITICAL,
                pendingSince = 1_000L,
                now = 1_000L + 19_999L,
                deadlineMs = 20_000L,
            ),
        )
    }

    @Test
    fun `forces an overdue CRITICAL deferred switch (R-3)`() {
        val s = newService()
        assertTrue(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.CRITICAL,
                pendingSince = 1_000L,
                now = 1_000L + 20_000L,
                deadlineMs = 20_000L,
            ),
        )
    }

    @Test
    fun `never forces when no switch is pending (pendingSince == 0)`() {
        val s = newService()
        assertFalse(
            s.shouldForceOverdueThermalSwitch(
                band = ThermalBand.CRITICAL,
                pendingSince = 0L,
                now = 10 * 60_000L,
                deadlineMs = 20_000L,
            ),
        )
    }

    // ---- R-6: quiescing rejects new inferences -----------------------------

    @Test
    fun `enterForeground rejects a new inference while quiescing for restart (R-6)`() {
        val s = newService()
        s.setQuiescingForRestartForTest(true)

        // A new inference arriving after the backend-switch restart decision
        // must be rejected (retryable) so it can't race the process restart.
        assertThrows(EngineNotReadyException::class.java) {
            s.enterForeground()
        }
        // The rejected call must NOT have bumped the refcount.
        assertEquals(0, s.activeInferenceCount)
    }

    @Test
    fun `enterForeground proceeds normally when not quiescing (R-6 boundary)`() {
        val s = newService()
        s.setQuiescingForRestartForTest(false)
        // startForeground succeeds under Robolectric; the count increments.
        s.enterForeground()
        assertEquals(1, s.activeInferenceCount)
        s.exitForeground()
        assertEquals(0, s.activeInferenceCount)
    }

    @Test
    fun `idle disconnect grant blocks a racing inference until a client is visible`() {
        val s = newService()
        val binder = mockk<ServiceBinder>(relaxed = true) {
            every { hasVisibleClients() } returns false
        }
        setField(s, "binder", binder)

        assertTrue(s.tryGrantIdleDisconnect())
        assertThrows(EngineNotReadyException::class.java) { s.enterForeground() }

        every { binder.hasVisibleClients() } returns true
        s.onClientVisibilityChanged()
        s.enterForeground()
        assertEquals(1, s.activeInferenceCount)
        s.exitForeground()
    }

    @Test
    fun `final hidden unbind terminates the ml process only after active work drains`() {
        val s = newService()
        val binder = mockk<ServiceBinder>(relaxed = true) {
            every { hasVisibleClients() } returns false
        }
        setField(s, "binder", binder)
        var killCount = 0
        s.idleProcessKiller = { killCount++ }

        s.onBind(null)
        s.enterForeground()
        s.onUnbind(null)
        assertEquals(0, killCount)

        s.exitForeground()
        assertEquals(1, killCount)
    }

    @Test
    fun `hidden bound client releases independently reloadable engines after grace`() {
        val s = newService()
        val binder = mockk<ServiceBinder>(relaxed = true) {
            every { hasVisibleClients() } returns false
        }
        val embeddingCoordinator = mockk<EmbeddingCoordinator>(relaxed = true)
        val ocrSessions = mockk<OcrSessionManager>(relaxed = true)
        val embeddingEngine = mockk<EmbeddingEngine>(relaxed = true)
        val paddleOcrEngine = mockk<PaddleOcrEngine>(relaxed = true)
        coEvery { embeddingCoordinator.awaitAllJobs(any()) } returns true
        setField(s, "binder", binder)
        setField(s, "embeddingCoordinator", embeddingCoordinator)
        setField(s, "ocrSessionManager", ocrSessions)
        setField(s, "embeddingEngine", embeddingEngine)
        setField(s, "paddleOcrEngine", paddleOcrEngine)
        s.auxiliaryIdleReleaseDelayMs = 0L

        s.onBind(null)
        s.onClientVisibilityChanged()

        coVerify(timeout = 1_000) { embeddingCoordinator.awaitAllJobs(any()) }
        coVerify(timeout = 1_000) { ocrSessions.drainForMemoryPressure() }
        coVerify(timeout = 1_000) { embeddingEngine.shutdown() }
        coVerify(timeout = 1_000) { paddleOcrEngine.shutdown() }

        every { binder.hasVisibleClients() } returns true
        s.onClientVisibilityChanged()
        coVerify(timeout = 1_000) { paddleOcrEngine.initialize() }
    }

    @Test
    fun `context resize drains sessions and persists larger restart budget`() {
        val s = newService()
        val engine = mockk<EngineManager>(relaxed = true) {
            every { currentBackend } returns "CPU"
        }
        val orchestrator = mockk<InferenceOrchestrator>(relaxed = true)
        val sessions = mockk<SessionManager>(relaxed = true)
        val memory = mockk<MemoryBudget>(relaxed = true) {
            every { deviceTier } returns DeviceTier(1, 8192, 32_768, 6144)
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 4096,
                totalMb = 6144,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 32_768,
            )
        }
        coEvery { orchestrator.awaitAllJobs(any()) } returns true
        coEvery {
            engine.shutdownAndRestart(
                reason = "context_resize",
                targetBackend = "CPU",
                maxTokens = 8192,
            )
        } returns Unit
        setField(s, "engineManager", engine)
        setField(s, "orchestrator", orchestrator)
        setField(s, "sessionManager", sessions)
        setField(s, "memoryBudget", memory)

        s.requestEngineContextResize(8192)

        coVerify(timeout = 1_000) { orchestrator.awaitAllJobs(60_000L) }
        verify(timeout = 1_000) { sessions.invalidateIdleSessionsForBackendSwitch() }
        coVerify(timeout = 1_000) {
            engine.shutdownAndRestart(
                reason = "context_resize",
                targetBackend = "CPU",
                maxTokens = 8192,
            )
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = MindlayerMlService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
