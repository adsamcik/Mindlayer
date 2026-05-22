package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Typed snapshot of service health for programmatic consumers (the dashboard
 * UI, external monitoring). v0.4 successor to the legacy
 * [String]-returning `IMindlayerService.getDiagnostics()` — which stays
 * alive for human-readable bug reports.
 *
 * # Why not return everything as Parcelables?
 *
 * The legacy `String getDiagnostics()` is a JSON dump that includes a
 * paginated tail of recent log entries (potentially several KB). Returning
 * all of that as a strongly-typed Parcelable would be either lossy
 * (truncated to fit the 1 MB Binder limit) or layout-fragile
 * (deeply-nested `List<LogEntry>`). The right split, per the v0.4 plan:
 *
 * - **`getDiagnosticsTyped()`** (this Parcelable) — small, structured,
 *   programmatically consumable. The dashboard polls this every 2 s.
 * - **`getDiagnostics(): String`** — full JSON dump for "paste into a bug
 *   report" workflows. Stays as-is.
 *
 * # Per-caller scoping
 *
 * For external callers, [callerSessionCount], [recentInferenceCount], and
 * [recentErrorCount] are scoped to the caller's UID — same anti-enumeration
 * property as `getStatus`. Self-UID dashboard sees aggregate counts.
 *
 * @property schemaVersion Wire-stable parcelable schema version. `2`.
 * @property capturedAtMs Wall-clock millis when the snapshot was taken.
 * @property service Re-export of [ServiceStatus] — the same struct
 *   returned by [IMindlayerService.getStatus]. Provided here so dashboard
 *   polling fetches both via one round-trip.
 * @property engine Re-export of [EngineInfo].
 * @property callerSessionCount How many sessions are currently owned by
 *   the calling UID (or all of them, for self-UID dashboard).
 * @property recentInferenceCount How many inferences started in the last
 *   5 minutes for the calling UID (or globally for self-UID).
 * @property recentErrorCount How many errors fired in the last 5 minutes
 *   for the calling UID (or globally for self-UID).
 * @property recentlyCompletedTrackedCount Size of the service's
 *   recently-completed cache (used by `cancelInferenceV2` to distinguish
 *   ALREADY_FINISHED from UNKNOWN). Useful for diagnosing memory pressure
 *   on the cache.
 */
@Parcelize
data class DiagnosticsSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val capturedAtMs: Long,
    val service: ServiceStatus,
    val engine: EngineInfo,
    val callerSessionCount: Int,
    val recentInferenceCount: Int,
    val recentErrorCount: Int,
    val recentlyCompletedTrackedCount: Int,
    val ocr: OcrDiagnostics = OcrDiagnostics(),
    val embedding: EmbeddingDiagnostics = EmbeddingDiagnostics(),
    val acceleratorDecisions: Map<String, AcceleratorDecisionSnapshot> = emptyMap(),
    val deferredCounters: Map<String, DeferredCounters> = mapOf(
        "chat" to DeferredCounters(),
        "embedding" to DeferredCounters(),
    ),
) : Parcelable {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

@Parcelize
data class OcrDiagnostics(
    val currentBackend: String? = null,
    val sessionsActive: Int = 0,
    val sessionsFinalized: Int = 0,
    val framesProcessed: Int = 0,
    val framesRejected: Int = 0,
    val framesDropped: Int = 0,
    val lastFinalizedAt: Long? = null,
    val lastBackendReadyAt: Long? = null,
) : Parcelable

@Parcelize
data class EmbeddingDiagnostics(
    val modelLoaded: Boolean = false,
    val modelId: String? = null,
    val currentBackend: String? = null,
    val batchesProcessed: Int = 0,
    val vectorsGenerated: Int = 0,
    val lastBackendReadyAt: Long? = null,
) : Parcelable

@Parcelize
data class AcceleratorDecisionSnapshot(
    val feature: String,
    val backend: String,
    val reason: String,
    val attempted: List<AcceleratorAttemptSnapshot> = emptyList(),
    val timestampMs: Long,
) : Parcelable

@Parcelize
data class AcceleratorAttemptSnapshot(
    val backend: String,
    val reason: String,
) : Parcelable

@Parcelize
data class DeferredCounters(
    val submitted: Int = 0,
    val completed: Int = 0,
    val fetched: Int = 0,
    val cancelled: Int = 0,
    val expired: Int = 0,
) : Parcelable
