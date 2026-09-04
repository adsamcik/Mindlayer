package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrewarmContextPolicyTest {
    private val normalSnapshot = MemorySnapshot(
        availableMb = 6_000,
        totalMb = 12_000,
        lowMemory = false,
        pressure = MemoryPressure.NORMAL,
        recommendedMaxTokens = 32_768,
    )

    @Test
    fun `76 MiB KV budget maps to 8192 tokens`() {
        assertEquals(8_192, PrewarmContextPolicy.tokensForKvCacheBudget(76))
    }

    @Test
    fun `automatic prewarm uses bounded budget instead of tier maximum`() {
        val tier = DeviceTier(1, 32_768, 131_072, 8_192)

        assertEquals(
            8_192,
            PrewarmContextPolicy.automaticContextTokens(tier, normalSnapshot),
        )
    }

    @Test
    fun `automatic prewarm is disabled at 4 GiB and below`() {
        val tier = DeviceTier(1, 8_192, 32_768, 4_096)

        assertNull(PrewarmContextPolicy.automaticContextTokens(tier, normalSnapshot))
    }

    @Test
    fun `automatic prewarm is disabled outside normal pressure`() {
        val tier = DeviceTier(1, 16_384, 65_536, 6_144)
        val warning = normalSnapshot.copy(pressure = MemoryPressure.WARNING)

        assertNull(PrewarmContextPolicy.automaticContextTokens(tier, warning))
    }

    @Test
    fun `explicit request remains available on low RAM and respects live ceiling`() {
        val tier = DeviceTier(1, 8_192, 32_768, 4_096)
        val emergency = normalSnapshot.copy(
            pressure = MemoryPressure.EMERGENCY,
            recommendedMaxTokens = 2_048,
        )

        assertEquals(
            2_048,
            PrewarmContextPolicy.effectiveRequestedContextTokens(8_192, tier, emergency),
        )
    }
}
