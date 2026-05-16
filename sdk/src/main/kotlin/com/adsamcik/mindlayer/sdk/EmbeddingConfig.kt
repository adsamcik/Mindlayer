package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.EmbeddingRequest

/**
 * User-facing embedding request configuration.
 *
 * [text] is sensitive input and is redacted from [toString] output.
 */
data class EmbeddingConfig(
    val text: String,
    val task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
    val normalize: Boolean = true,
    /** 768 / 512 / 256 / 128. null → service default (native dim). */
    val outputDim: Int? = null,
    /** Service-side embedding model. null → service picks default. */
    val modelId: String? = null,
    /** Echoed back in [com.adsamcik.mindlayer.EmbeddingResult.tag] for batch correlation. */
    val tag: String? = null,
) {
    override fun toString(): String =
        "EmbeddingConfig(text=<redacted:${text.length}>, task=${task.javaClass.simpleName}, normalize=$normalize, outputDim=$outputDim, modelId=$modelId, tag=$tag)"
}

enum class EmbeddingModel(val id: String) {
    EmbeddingGemma300m("embedding-gemma-300m-v1"),
    ;

    companion object {
        /** Default per the spike synthesis. */
        @JvmStatic val Default: EmbeddingModel = EmbeddingGemma300m
    }
}

sealed class EmbeddingTask(internal val code: Int) {
    object RetrievalQuery : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_QUERY)
    object RetrievalDocument : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.RETRIEVAL_DOCUMENT)
    object Classification : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.CLASSIFICATION)
    object Clustering : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.CLUSTERING)
    object Similarity : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.SIMILARITY)
    object Code : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.CODE)
    object QuestionAnswering : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.QUESTION_ANSWERING)
    object FactVerification : EmbeddingTask(com.adsamcik.mindlayer.EmbeddingTask.FACT_VERIFICATION)
}

internal fun EmbeddingConfig.toAidlRequest(): EmbeddingRequest = EmbeddingRequest(
    text = text,
    tag = tag,
    modelId = modelId,
    normalize = normalize,
    outputDim = outputDim,
    taskType = task.code,
)
