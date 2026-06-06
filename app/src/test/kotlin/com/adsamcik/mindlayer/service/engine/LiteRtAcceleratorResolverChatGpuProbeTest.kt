package com.adsamcik.mindlayer.service.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for the chat hardware-GPU gate added to [LiteRtAcceleratorResolver].
 *
 * On emulators (software SwiftShader GL) LiteRT-LM's GPU `nativeCreateEngine`
 * SIGSEGVs un-catchably, so the chat default/unknown branches must select CPU
 * when no hardware GPU is present. Real devices and explicit `GPU` requests
 * keep GPU. OCR/embeddings are intentionally unaffected (their GPU compile
 * failures are catchable and already fall back at runtime).
 */
class LiteRtAcceleratorResolverChatGpuProbeTest {

    @Before fun setUp() { LiteRtAcceleratorResolver.resetForTesting() }
    @After fun tearDown() { LiteRtAcceleratorResolver.resetForTesting() }

    @Test fun `chat default selects CPU when no hardware GPU`() {
        LiteRtAcceleratorResolver.setHardwareGpuForTesting(present = false)
        val decision = LiteRtAcceleratorResolver.resolveBackend(requested = null, featureName = "chat")
        assertEquals("CPU", decision.backend)
        assertEquals("DEFAULT_CPU_NO_HARDWARE_GPU", decision.reason)
        assertEquals("GPU" to "skipped_no_hardware_gpu", decision.attempted.first())
        assertEquals("CPU" to "selected", decision.attempted.last())
    }

    @Test fun `chat default selects GPU when hardware GPU present`() {
        LiteRtAcceleratorResolver.setHardwareGpuForTesting(present = true)
        val decision = LiteRtAcceleratorResolver.resolveBackend(requested = null, featureName = "chat")
        assertEquals("GPU", decision.backend)
        assertEquals("DEFAULT_GPU_THEN_CPU_CHAIN", decision.reason)
    }

    @Test fun `chat explicit GPU is honoured even without hardware GPU`() {
        // An explicit request is a deliberate caller choice (e.g. on-device GPU
        // testing) and must not be silently downgraded.
        LiteRtAcceleratorResolver.setHardwareGpuForTesting(present = false)
        val decision = LiteRtAcceleratorResolver.resolveBackend("GPU", "chat")
        assertEquals("GPU", decision.backend)
        assertEquals("REQUESTED_GPU", decision.reason)
    }

    @Test fun `chat explicit CPU is honoured regardless of probe`() {
        LiteRtAcceleratorResolver.setHardwareGpuForTesting(present = true)
        val decision = LiteRtAcceleratorResolver.resolveBackend("CPU", "chat")
        assertEquals("CPU", decision.backend)
        assertEquals("REQUESTED_CPU", decision.reason)
    }

    @Test fun `chat unknown backend selects CPU when no hardware GPU`() {
        LiteRtAcceleratorResolver.setHardwareGpuForTesting(present = false)
        val decision = LiteRtAcceleratorResolver.resolveBackend("TPU", "chat")
        assertEquals("CPU", decision.backend)
        assertEquals("UNKNOWN_REQUESTED_BACKEND_CPU_NO_HARDWARE_GPU", decision.reason)
    }

    @Test fun `chat unknown backend selects GPU when hardware GPU present`() {
        LiteRtAcceleratorResolver.setHardwareGpuForTesting(present = true)
        val decision = LiteRtAcceleratorResolver.resolveBackend("TPU", "chat")
        assertEquals("GPU", decision.backend)
        assertEquals("UNKNOWN_REQUESTED_BACKEND_GPU_FALLBACK", decision.reason)
    }

    @Test fun `gate is chat-scoped — OCR default keeps GPU without hardware GPU`() {
        // OCR's GPU compile failure is catchable and already falls back to CPU
        // at runtime, so it must not be downgraded by the chat hardware-GPU gate.
        LiteRtAcceleratorResolver.setHardwareGpuForTesting(present = false)
        val decision = LiteRtAcceleratorResolver.resolveBackend(requested = null, featureName = "ocr")
        assertEquals("GPU", decision.backend)
        assertTrue(
            "OCR default reason should be unchanged, was ${decision.reason}",
            decision.reason == "DEFAULT_GPU_THEN_CPU_CHAIN",
        )
    }
}
