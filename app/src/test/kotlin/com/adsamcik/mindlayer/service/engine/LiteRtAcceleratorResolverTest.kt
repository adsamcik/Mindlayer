package com.adsamcik.mindlayer.service.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtAcceleratorResolverTest {
    @After fun tearDown() { LiteRtAcceleratorResolver.resetForTesting() }

    @Test fun embeddingsKeepGpuRequest() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("GPU", "embeddings")
        assertEquals("GPU", decision.backend)
        assertEquals("REQUESTED_GPU", decision.reason)
        assertEquals(listOf("GPU" to "selected"), decision.attempted)
    }

    @Test fun embeddingsDowngradeUnsupportedNpuToCpu() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(apiLevel = 30, socModel = "sm8450", libs = listOf("libQnnHtp.so"))
        val decision = LiteRtAcceleratorResolver.resolveBackend("NPU", "embeddings")
        assertEquals("CPU", decision.backend)
        assertTrue(decision.reason.contains("NPU_API_BELOW_31"))
        assertEquals("NPU" to "NPU_API_BELOW_31", decision.attempted.first())
        assertEquals("CPU" to "selected", decision.attempted.last())
    }

    @Test fun embeddingsAllowAllowlistedNpuWithQnnLibrary() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(apiLevel = 33, socModel = "sm8450", libs = listOf("libLiteRtQnnAccelerator.so"))
        val decision = LiteRtAcceleratorResolver.resolveBackend(null, "embeddings")
        assertEquals("NPU", decision.backend)
        assertEquals("DEFAULT_NPU_SUPPORTED", decision.reason)
    }

    @Test fun ocrIsCpuLockedEvenWhenGpuRequested() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("GPU", "ocr")
        assertEquals("CPU", decision.backend)
        assertEquals("OCR_CPU_LOCK_UNTIL_COEXISTENCE_VALIDATED", decision.reason)
        assertEquals(listOf("GPU" to "OCR_CPU_LOCK_UNTIL_COEXISTENCE_VALIDATED", "CPU" to "selected"), decision.attempted)
    }
}
