package com.adsamcik.mindlayer

import android.os.ParcelFileDescriptor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingBatchTransfer(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Read-only PFD over a SharedMemory region. Layout: [int32 count][int32 dim] then count*dim*f32. */
    val pfd: ParcelFileDescriptor,
    val count: Int,
    val dim: Int,
    val modelId: String,
    /** Per-vector metadata: tag, tokenCount, truncated, durationMs — vectors carried in SHM. */
    val perItemMetadata: List<EmbeddingItemMetadata>,
    val totalDurationMs: Long,
    val backend: String,
) : Parcelable {
    companion object { const val CURRENT_SCHEMA_VERSION: Int = 1 }
}
