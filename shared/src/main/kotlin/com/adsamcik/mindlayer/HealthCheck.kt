package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Lightweight liveness probe response — Phase 3 #8.
 *
 * Returned by [IMindlayerService.ping]. Designed for low-overhead
 * round-trip "is the service alive and roughly what state is it in?"
 * checks. Distinct from:
 *
 *  - [ServiceCapabilities] (returned by `getCapabilities()`) which
 *    is a one-time wire-handshake describing what features the
 *    service supports.
 *  - [ServiceStatus] (returned by `getStatus()`) which is a richer
 *    dashboard-shaped snapshot with memory pressure, thermal band,
 *    backend, etc. — costs more to compute and produce.
 *
 * `ping()` deliberately:
 *  - bypasses the allowlist gate (a co-signed peer in pending-
 *    approval can still confirm the service is alive);
 *  - charges zero rate-limit cost;
 *  - returns in O(1) — only fields that are already in-memory.
 *
 * Use this for connectivity checks, watchdog probes, and clock-skew
 * detection. Do **not** use it as a trigger for inference decisions —
 * the engine-state fields here are advisory and may race with an
 * eviction event.
 *
 * # Wire stability
 *
 * Per `docs/AIDL_STABILITY.md`: parcelables are wire-frozen once
 * shipped. [schemaVersion] is the first field and is currently `1`.
 * Adding fields later is a wire break — use [extensionsJson] for
 * forward-compatible extensions.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property serverTimestampMs Server-side wall-clock at the moment
 *   the response was prepared. Use against the caller's clock to
 *   detect skew. Milliseconds since epoch.
 * @property serviceUptimeMs How long the service process has been
 *   running, in milliseconds. Resets to zero on every service
 *   restart (OOM-kill, manual stop, system reclaim, dashboard
 *   stop action).
 * @property apiVersion Mirror of [ServiceCapabilities.apiVersion].
 *   Lets a caller verify it's talking to the version it expects
 *   without paying for a full capability handshake.
 * @property llmEngineState One of [ENGINE_STATE_IDLE],
 *   [ENGINE_STATE_INITIALIZING], [ENGINE_STATE_READY],
 *   [ENGINE_STATE_FAILED]. Tracks the LiteRT-LM Gemma engine.
 * @property embeddingEngineState Same enum as [llmEngineState],
 *   for the LiteRT EmbeddingGemma backend.
 * @property ocrEngineState Same enum, for the PaddleOCR engine.
 * @property extensionsJson Reserved forward-compatibility envelope.
 *   `null` in v1. Unknown keys are tolerated; the parser MUST NOT
 *   reject on unknown fields.
 */
@Parcelize
data class HealthCheck(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val serverTimestampMs: Long,
    val serviceUptimeMs: Long,
    val apiVersion: Int,
    val llmEngineState: Int = ENGINE_STATE_IDLE,
    val embeddingEngineState: Int = ENGINE_STATE_IDLE,
    val ocrEngineState: Int = ENGINE_STATE_IDLE,
    val extensionsJson: String? = null,
) : Parcelable {

    /** True when every wired engine is in the [ENGINE_STATE_READY] state. */
    val allEnginesReady: Boolean
        get() = llmEngineState == ENGINE_STATE_READY &&
            embeddingEngineState == ENGINE_STATE_READY &&
            ocrEngineState == ENGINE_STATE_READY

    /** True when at least one engine is in the [ENGINE_STATE_READY] state. */
    val anyEngineReady: Boolean
        get() = llmEngineState == ENGINE_STATE_READY ||
            embeddingEngineState == ENGINE_STATE_READY ||
            ocrEngineState == ENGINE_STATE_READY

    override fun toString(): String =
        "HealthCheck(serverTs=$serverTimestampMs, uptimeMs=$serviceUptimeMs, " +
            "api=$apiVersion, llm=$llmEngineState, emb=$embeddingEngineState, " +
            "ocr=$ocrEngineState, allReady=$allEnginesReady)"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Engine not loaded, not initialising. Default state on cold boot. */
        const val ENGINE_STATE_IDLE: Int = 0

        /** Engine init in progress. Caller should retry the underlying API later. */
        const val ENGINE_STATE_INITIALIZING: Int = 1

        /** Engine loaded + ready to serve requests. */
        const val ENGINE_STATE_READY: Int = 2

        /**
         * Engine init failed (no bundle / integrity mismatch / native error /
         * low memory). The engine is NOT going to recover without external
         * action (reinstall, free memory, restart service). Capability-aware
         * SDKs should NOT issue requests against this engine.
         */
        const val ENGINE_STATE_FAILED: Int = 3
    }
}
