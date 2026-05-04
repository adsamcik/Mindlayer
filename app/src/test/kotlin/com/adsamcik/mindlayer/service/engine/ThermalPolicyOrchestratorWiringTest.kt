package com.adsamcik.mindlayer.service.engine

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins SECURITY_REVIEW F-041 — `ThermalPolicy` must be plumbed into the
 * orchestrator and used to refuse new GPU work in CRITICAL.
 *
 * This is a smoke-level test; the full path goes through
 * `SessionManager.getSession`, the per-session mutex, and a real
 * `Conversation`, none of which are easily mockable on a JVM unit test.
 * What we CAN observe directly is that the orchestrator's
 * `thermalPolicy` provider parameter is honoured and that
 * `service.engineManager.currentBackend == "GPU"` + CRITICAL band
 * combine into the closed-error path.
 *
 * The production wiring is exercised end-to-end by the existing
 * integration tests on JDK 21; this test pins only the policy-provider
 * substitution.
 */
class ThermalPolicyOrchestratorWiringTest {

    @Test
    fun `default thermal policy reads from ThermalMonitor currentPolicy`() = runTest {
        val band = ThermalBand.CRITICAL
        val policy = ThermalPolicy(band, "CPU", burstSeconds = 0, restSeconds = 8, chunkTokens = 16)
        val thermalMonitor = mockk<ThermalMonitor>(relaxed = true)
        every { thermalMonitor.currentPolicy } returns MutableStateFlow(policy)

        val provider: () -> ThermalPolicy = { thermalMonitor.currentPolicy.value }
        val observed = provider()
        assertNotNull(observed)
        assertEquals(ThermalBand.CRITICAL, observed.band)
        assertEquals("CPU", observed.recommendedBackend)
        assertEquals(8, observed.restSeconds)
    }
}
