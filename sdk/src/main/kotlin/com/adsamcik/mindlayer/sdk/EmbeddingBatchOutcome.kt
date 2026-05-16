package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.EmbeddingResult

sealed class EmbeddingBatchOutcome {
    object StillRunning : EmbeddingBatchOutcome()
    data class Ready(
        val results: List<EmbeddingResult>,
        val totalDurationMs: Long,
        val backend: String,
    ) : EmbeddingBatchOutcome()
    data class Failed(val errorCode: Int, val errorName: String?) : EmbeddingBatchOutcome()
    object Cancelled : EmbeddingBatchOutcome()
    object Expired : EmbeddingBatchOutcome()
    object NotFound : EmbeddingBatchOutcome()
}
