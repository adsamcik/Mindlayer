package com.adsamcik.mindlayer

/**
 * Embedding task type constants. Match the EmbeddingGemma instruction-tuning
 * task prefixes (see https://huggingface.co/google/embeddinggemma-300m#prompt-instructions).
 * Service uses these to prepend the appropriate task prefix at tokenization.
 *
 * Append-only Int constants per `docs/AIDL_STABILITY.md`. The full
 * `EmbeddingRequest` parcelable lands in Phase B.
 */
object EmbeddingTask {
    const val RETRIEVAL_QUERY: Int = 0
    const val RETRIEVAL_DOCUMENT: Int = 1
    const val CLASSIFICATION: Int = 2
    const val CLUSTERING: Int = 3
    const val SIMILARITY: Int = 4
    const val CODE: Int = 5
    const val QUESTION_ANSWERING: Int = 6
    const val FACT_VERIFICATION: Int = 7

    /** True if [task] is a recognized task code. */
    fun isValid(task: Int): Boolean = task in 0..7

    /**
     * Map a task code to its instruction-tuning prefix, or empty string for unknown.
     * Per the EmbeddingGemma model card. Service prepends this BEFORE the
     * user text at tokenization time.
     */
    fun prefixFor(task: Int): String = when (task) {
        RETRIEVAL_QUERY        -> "task: search result | query: "
        RETRIEVAL_DOCUMENT     -> "title: none | text: "
        CLASSIFICATION         -> "task: classification | query: "
        CLUSTERING             -> "task: clustering | query: "
        SIMILARITY             -> "task: sentence similarity | query: "
        CODE                   -> "task: code retrieval | query: "
        QUESTION_ANSWERING     -> "task: question answering | query: "
        FACT_VERIFICATION      -> "task: fact checking | query: "
        else -> ""
    }
}
