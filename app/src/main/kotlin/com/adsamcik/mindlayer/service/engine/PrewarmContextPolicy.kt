package com.adsamcik.mindlayer.service.engine

/**
 * Converts a bounded KV-cache memory allowance into the context used for
 * speculative LLM prewarming.
 *
 * The default is intentionally based on a memory amount, not the device
 * tier's maximum context. The repository's conservative empirical estimate is
 * 9.5 KiB of KV cache per configured token, so 76 MiB maps exactly to 8,192
 * tokens. That covers the largest audited first-party workload while avoiding
 * a speculative 32K-131K allocation on higher-memory devices.
 */
internal object PrewarmContextPolicy {
    const val DEFAULT_KV_CACHE_BUDGET_MIB: Int = 76
    const val ESTIMATED_KV_BYTES_PER_TOKEN: Long = 9_728L
    const val MIN_CONTEXT_TOKENS: Int = 128
    const val MAX_AIDL_CONTEXT_TOKENS: Int = 32_768

    private const val LOW_RAM_DEVICE_MIB: Long = 4L * 1024L
    private const val TOKEN_ALIGNMENT: Int = 128

    /**
     * Context for the legacy, no-budget prewarm APIs, or `null` when retaining
     * a speculative engine is inappropriate for the current device state.
     */
    fun automaticContextTokens(
        tier: DeviceTier,
        snapshot: MemorySnapshot,
        kvCacheBudgetMiB: Int = DEFAULT_KV_CACHE_BUDGET_MIB,
    ): Int? {
        if (tier.deviceRamMb <= LOW_RAM_DEVICE_MIB) return null
        if (snapshot.pressure != MemoryPressure.NORMAL) return null

        val runtimeCeiling = runtimeContextCeiling(tier, snapshot)
        val budgetTokens = tokensForKvCacheBudget(kvCacheBudgetMiB)
        return budgetTokens.coerceAtMost(runtimeCeiling)
            .takeIf { it >= MIN_CONTEXT_TOKENS }
    }

    /** Clamp an app-supplied context request to the current safe runtime cap. */
    fun effectiveRequestedContextTokens(
        requestedTokens: Int,
        tier: DeviceTier,
        snapshot: MemorySnapshot,
    ): Int = requestedTokens.coerceIn(
        MIN_CONTEXT_TOKENS,
        runtimeContextCeiling(tier, snapshot).coerceAtLeast(MIN_CONTEXT_TOKENS),
    )

    fun tokensForKvCacheBudget(kvCacheBudgetMiB: Int): Int {
        require(kvCacheBudgetMiB > 0) { "kvCacheBudgetMiB must be positive" }
        val rawTokens = kvCacheBudgetMiB.toLong() * 1024L * 1024L /
            ESTIMATED_KV_BYTES_PER_TOKEN
        return (rawTokens / TOKEN_ALIGNMENT * TOKEN_ALIGNMENT)
            .coerceAtLeast(MIN_CONTEXT_TOKENS.toLong())
            .coerceAtMost(MAX_AIDL_CONTEXT_TOKENS.toLong())
            .toInt()
    }

    private fun runtimeContextCeiling(
        tier: DeviceTier,
        snapshot: MemorySnapshot,
    ): Int = snapshot.recommendedMaxTokens.coerceAtMost(tier.maxMaxTokens)
}
