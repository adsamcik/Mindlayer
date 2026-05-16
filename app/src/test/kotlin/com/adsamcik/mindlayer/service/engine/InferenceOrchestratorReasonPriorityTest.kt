package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.util.Log
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.TokenStreamWriter
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import com.google.ai.edge.litertlm.Engine
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * M-E2: regression coverage for [InferenceOrchestrator.primeTypedCancellationReason].
 *
 * When EMERGENCY memory pressure and ALLOWLIST_REVOKED race for the
 * same in-flight inference, the user-initiated revoke must always win:
 * a revoked caller has lost the right to run inference, so the cancel
 * has to surface as `allowlist_revoked` even if a racing EMERGENCY
 * sweep would otherwise stamp it as `low_memory`. The merge logic is
 * driven by [InferenceOrchestrator.cancellationReasonPriority]; this
 * test pins that priority order so it cannot regress silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceOrchestratorReasonPriorityTest {

    private lateinit var orchestrator: InferenceOrchestrator

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkObject(MindlayerLog)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { MindlayerLog.d(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit

        val engine = mockk<Engine>(relaxed = true)
        val engineManager = mockk<EngineManager>(relaxed = true) {
            every { requireEngine() } returns engine
            every { currentBackend } returns "CPU"
            every { isInitialized } returns true
        }
        val tier = DeviceTier(
            maxSessions = 6,
            defaultMaxTokens = 4096,
            maxMaxTokens = 8192,
            deviceRamMb = 16 * 1024L,
        )
        val memoryBudget = mockk<MemoryBudget>(relaxed = true) {
            every { deviceTier } returns tier
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 4000L,
                totalMb = 16 * 1024L,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 8192,
            )
        }
        val sharedMemoryPool = mockk<SharedMemoryPool>(relaxed = true)
        val service = mockk<MindlayerMlService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val sessionManager = SessionManager(context, engineManager, memoryBudget)

        orchestrator = InferenceOrchestrator(
            service, sessionManager, sharedMemoryPool,
            writerFactory = { _ ->
                TokenStreamWriter.forTesting(ByteArrayOutputStream(), writeTimeoutMs = 60_000L)
            },
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        unmockkAll()
    }

    @Test
    fun `revoke wins against subsequent low_memory race`() {
        val key = "100:req-1"
        val first = orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.ALLOWLIST_REVOKED,
        )
        val second = orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.LOW_MEMORY,
        )
        // Higher-priority reason already installed → second call must
        // NOT overwrite; the stored reason stays as ALLOWLIST_REVOKED.
        assertEquals(MindlayerErrorCode.ALLOWLIST_REVOKED, first)
        assertEquals(MindlayerErrorCode.ALLOWLIST_REVOKED, second)
    }

    @Test
    fun `revoke wins when low_memory was installed first`() {
        val key = "100:req-2"
        orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.LOW_MEMORY,
        )
        val merged = orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.ALLOWLIST_REVOKED,
        )
        // Lower-priority reason was first; higher-priority revoke must
        // overwrite — without this, the cancellation surfaces as a
        // generic `low_memory` and the SDK cannot distinguish revoke
        // from pressure.
        assertEquals(MindlayerErrorCode.ALLOWLIST_REVOKED, merged)
    }

    @Test
    fun `thermal vs memory_pressure stable when equal priority`() {
        val key = "100:req-3"
        orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.LOW_MEMORY,
        )
        val merged = orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.MEMORY_PRESSURE,
        )
        // Equal-priority merge keeps the existing reason, preventing
        // ping-pong overwrites between sibling pressure sources.
        assertEquals(MindlayerErrorCode.LOW_MEMORY, merged)
    }

    @Test
    fun `session_evicted beats thermal_critical`() {
        val key = "100:req-4"
        orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.THERMAL_CRITICAL,
        )
        val merged = orchestrator.primeTypedCancellationReason(
            key, MindlayerErrorCode.SESSION_EVICTED,
        )
        // SESSION_EVICTED (operator/quota decision) has higher priority
        // than thermal pressure, but still below ALLOWLIST_REVOKED.
        assertEquals(MindlayerErrorCode.SESSION_EVICTED, merged)
    }
}
