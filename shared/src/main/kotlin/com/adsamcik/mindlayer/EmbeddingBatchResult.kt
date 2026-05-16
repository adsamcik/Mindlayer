package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingBatchResult(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val results: List<EmbeddingResult>,
    /** Aggregate ms across the batch (wall-clock on the service). */
    val totalDurationMs: Long,
    /** Per-batch backend (single backend per batch in v1). */
    val backend: String,
) : Parcelable {
    companion object { const val CURRENT_SCHEMA_VERSION: Int = 1 }
}
