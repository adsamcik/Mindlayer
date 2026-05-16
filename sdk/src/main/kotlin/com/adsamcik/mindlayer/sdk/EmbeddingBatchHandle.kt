package com.adsamcik.mindlayer.sdk

/** Opaque handle to a deferred embedding batch. Carry through to fetch / cancel / ack. */
@ConsistentCopyVisibility
data class EmbeddingBatchHandle internal constructor(
    val requestId: String,
    val expiresAtMs: Long,
)
