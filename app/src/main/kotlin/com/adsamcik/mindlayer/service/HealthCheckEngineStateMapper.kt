package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.HealthCheck

/**
 * Pure-JVM translation from a sealed-class engine state instance
 * (any of `EngineState`, `EmbeddingEngineState`, `PaddleOcrEngineState`)
 * to the wire-stable [HealthCheck.ENGINE_STATE_*] integer enum used
 * by [com.adsamcik.mindlayer.HealthCheck] — Phase 3 #8.
 *
 * # Why an external object?
 *
 * MockK on the JVM (Robolectric tests) cannot intercept `final val`
 * properties on `final class`-es, which is the shape of every
 * engine-state holder in this repo. Extracting the mapping into a
 * pure function lets unit tests pin the four state-name -> wire-int
 * mappings without needing to mock an `EngineManager` /
 * `EmbeddingEngine` / `PaddleOcrEngine` instance.
 *
 * # All three sealed classes are structurally identical
 *
 * They all expose `Idle` / `Initializing` / `Ready` / `Failed(...)`
 * subtypes. The mapper compares the runtime class's `simpleName`
 * against those four names case-insensitively. Unknown subtypes
 * default to `ENGINE_STATE_IDLE` — that is the conservative fallback
 * (capability-aware SDKs treat IDLE as "not yet ready").
 */
internal object HealthCheckEngineStateMapper {

    /**
     * Map a sealed-class engine state instance to its wire-stable
     * integer enum. Null and unknown subtypes map to
     * [HealthCheck.ENGINE_STATE_IDLE].
     */
    fun map(state: Any?): Int {
        val name = state?.let { it::class.java.simpleName } ?: return HealthCheck.ENGINE_STATE_IDLE
        return when {
            name.equals("Idle", ignoreCase = true) -> HealthCheck.ENGINE_STATE_IDLE
            name.equals("Initializing", ignoreCase = true) -> HealthCheck.ENGINE_STATE_INITIALIZING
            name.equals("Ready", ignoreCase = true) -> HealthCheck.ENGINE_STATE_READY
            name.equals("Failed", ignoreCase = true) -> HealthCheck.ENGINE_STATE_FAILED
            else -> HealthCheck.ENGINE_STATE_IDLE
        }
    }
}
