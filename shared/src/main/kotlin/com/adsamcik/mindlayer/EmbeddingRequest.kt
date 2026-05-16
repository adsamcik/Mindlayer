package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingRequest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val text: String,
    /** Client-supplied opaque tag echoed back in EmbeddingResult.tag. Useful for batch result correlation. */
    val tag: String? = null,
    /** Stable id from EmbeddingModelRegistry. null → service picks default. v1: only "embedding-gemma-300m-v1" valid. */
    val modelId: String? = null,
    /** L2-normalize before returning. Default true (callers do cosine = dot product). */
    val normalize: Boolean = true,
    /** Matryoshka truncation: 768/512/256/128. null → use model's nativeDim. */
    val outputDim: Int? = null,
    /** EmbeddingTask constant. Service prepends the matching prefix at tokenization. */
    val taskType: Int = EmbeddingTask.RETRIEVAL_DOCUMENT,
) : Parcelable {
    companion object { const val CURRENT_SCHEMA_VERSION: Int = 1 }
    override fun toString() =
        "EmbeddingRequest(text=<redacted:${text.length}>, tag=$tag, modelId=$modelId, normalize=$normalize, outputDim=$outputDim, taskType=$taskType, schemaVersion=$schemaVersion)"
}
