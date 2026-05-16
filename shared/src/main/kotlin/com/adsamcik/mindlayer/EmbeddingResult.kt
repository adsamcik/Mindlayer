package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Objects

@Parcelize
data class EmbeddingResult(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val tag: String? = null,
    val vector: FloatArray,
    val dim: Int,
    val modelId: String,
    val tokenCount: Int,
    val truncated: Boolean,
    val backend: String,
    val durationMs: Long,
) : Parcelable {
    companion object { const val CURRENT_SCHEMA_VERSION: Int = 1 }
    override fun toString() =
        "EmbeddingResult(dim=$dim, tag=$tag, modelId=$modelId, tokenCount=$tokenCount, truncated=$truncated, backend=$backend, durationMs=$durationMs, schemaVersion=$schemaVersion)"

    override fun equals(other: Any?): Boolean = this === other || (other is EmbeddingResult &&
        schemaVersion == other.schemaVersion &&
        tag == other.tag &&
        dim == other.dim &&
        modelId == other.modelId &&
        tokenCount == other.tokenCount &&
        truncated == other.truncated &&
        backend == other.backend &&
        durationMs == other.durationMs)

    override fun hashCode(): Int = Objects.hash(schemaVersion, tag, dim, modelId, tokenCount, truncated, backend, durationMs)
}
