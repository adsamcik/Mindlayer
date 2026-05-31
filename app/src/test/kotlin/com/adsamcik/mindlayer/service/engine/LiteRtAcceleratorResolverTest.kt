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

    @Test fun embeddingsDowngradeUnsupportedNpuToGpu() {
        // Mirrors OCR/Chat policy: when NPU is requested but unavailable
        // (API < 31 / SoC not allowlisted / native libs missing) the
        // resolver now offers GPU as the next attempt instead of dropping
        // straight to CPU. The runtime catches GPU compile failure and
        // falls back to CPU on its own (LiteRtEmbeddingBackend).
        LiteRtAcceleratorResolver.setEnvironmentForTesting(apiLevel = 30, socModel = "sm8450", libs = listOf("libQnnHtp.so"))
        val decision = LiteRtAcceleratorResolver.resolveBackend("NPU", "embeddings")
        assertEquals("GPU", decision.backend)
        assertTrue(decision.reason.startsWith("REQUESTED_NPU_UNSUPPORTED_GPU_FALLBACK_"))
        assertEquals("NPU" to "NPU_API_BELOW_31", decision.attempted.first())
        assertEquals("GPU" to "selected", decision.attempted.last())
    }

    @Test fun embeddingsDefaultsToGpuWhenNoNpu() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(apiLevel = 33, socModel = "exynos9999", libs = emptyList())
        val decision = LiteRtAcceleratorResolver.resolveBackend(null, "embeddings")
        assertEquals("GPU", decision.backend)
        assertTrue(decision.reason.startsWith("DEFAULT_GPU_NPU_UNSUPPORTED_"))
    }

    @Test fun embeddingsUnknownRequestFallsBackToGpu() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("DSP", "embeddings")
        assertEquals("GPU", decision.backend)
        assertEquals("UNKNOWN_REQUESTED_BACKEND_GPU_FALLBACK", decision.reason)
    }

    @Test fun embeddingsAllowAllowlistedNpuWithQnnLibrary() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(apiLevel = 33, socModel = "sm8450", libs = listOf("libLiteRtQnnAccelerator.so"))
        val decision = LiteRtAcceleratorResolver.resolveBackend(null, "embeddings")
        assertEquals("NPU", decision.backend)
        assertEquals("DEFAULT_NPU_SUPPORTED", decision.reason)
    }

    @Test fun ocrKeepsExplicitCpuRequest() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("CPU", "ocr")
        assertEquals("CPU", decision.backend)
        assertEquals("REQUESTED_CPU", decision.reason)
        assertEquals(listOf("CPU" to "selected"), decision.attempted)
    }

    @Test fun ocrKeepsExplicitGpuRequest() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("GPU", "ocr")
        assertEquals("GPU", decision.backend)
        assertEquals("REQUESTED_GPU", decision.reason)
        assertEquals(listOf("GPU" to "selected"), decision.attempted)
    }

    @Test fun ocrDefaultsToGpuWhenNoExplicitBackend() {
        val decision = LiteRtAcceleratorResolver.resolveBackend(null, "ocr")
        assertEquals("GPU", decision.backend)
        assertEquals("DEFAULT_GPU_THEN_CPU_CHAIN", decision.reason)
        assertEquals(listOf("GPU" to "selected"), decision.attempted)
    }

    @Test fun ocrUnknownRequestFallsBackToGpu() {
        val decision = LiteRtAcceleratorResolver.resolveBackend("DSP", "ocr")
        assertEquals("GPU", decision.backend)
        assertEquals("UNKNOWN_REQUESTED_BACKEND_GPU_FALLBACK", decision.reason)
        assertEquals(listOf("GPU" to "selected"), decision.attempted)
    }

    @Test fun ocrExplicitNpuOnAllowlistedSocHonoured() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(
            apiLevel = 33,
            socModel = "sm8450",
            libs = listOf("libLiteRtQnnAccelerator.so"),
        )
        val decision = LiteRtAcceleratorResolver.resolveBackend("NPU", "ocr")
        assertEquals("NPU", decision.backend)
        assertEquals("REQUESTED_NPU_SUPPORTED", decision.reason)
        assertEquals("NPU" to "NPU_SUPPORTED", decision.attempted.last())
    }

    @Test fun ocrExplicitNpuFallsBackToGpuWhenSocUnknown() {
        LiteRtAcceleratorResolver.setEnvironmentForTesting(
            apiLevel = 33,
            socModel = "exynos9999",
            libs = emptyList(),
        )
        val decision = LiteRtAcceleratorResolver.resolveBackend("NPU", "ocr")
        assertEquals("GPU", decision.backend)
        assertTrue(decision.reason.startsWith("REQUESTED_NPU_UNSUPPORTED_GPU_FALLBACK_"))
        assertEquals("NPU" to "NPU_SOC_NOT_ALLOWLISTED", decision.attempted.first())
        assertEquals("GPU" to "selected", decision.attempted.last())
    }
}
