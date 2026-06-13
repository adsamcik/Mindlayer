package com.adsamcik.mindlayer.sdk

import kotlinx.coroutines.flow.Flow

/**
 * One item in an embedding request batch.
 *
 * [tag] is an opaque caller correlation key echoed back on the matching
 * [EmbeddingResultItem]; [task] selects the embedding objective.
 *
 * [modelId] / [outputDim] / [normalize] mirror the per-item knobs that
 * the legacy `EmbeddingConfig` carried, so the canonical builder API is
 * a strict superset of the deprecated entry points.
 */
data class EmbeddingItem(
    val text: String,
    val tag: String? = null,
    val task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
    val modelId: String? = null,
    val outputDim: Int? = null,
    val normalize: Boolean = true,
)

/**
 * Value wrapper around an embedding vector so sealed result types get correct
 * structural `equals`/`hashCode` (a bare [FloatArray] uses reference identity).
 */
@JvmInline
value class EmbeddingVector(val data: FloatArray)

/**
 * One result in an embedding batch, correlated to its input by [tag].
 *
 * Carries the same telemetry as the legacy [com.adsamcik.mindlayer.EmbeddingResult]
 * parcelable so the canonical `embed { } → EmbeddingHandle.Batch` path is a
 * strict superset of the deprecated `embedBatch` / `embedBatchLarge` shapes:
 * [dim], [modelId], [tokenCount], [truncated], [backend], and [durationMs]
 * are all surfaced for diagnostics. Fields are nullable because some legacy
 * call sites (e.g. SHM transfer with batch-only metadata) historically did
 * not carry per-item telemetry.
 */
data class EmbeddingResultItem(
    val tag: String?,
    val vector: EmbeddingVector,
    val dim: Int? = null,
    val modelId: String? = null,
    val tokenCount: Int? = null,
    val truncated: Boolean? = null,
    val backend: String? = null,
    val durationMs: Long? = null,
)

/** Caller-facing embedding event stream type. Behaviour lands in C2. */
sealed interface EmbeddingEvent {
    /** Terminal success frame carrying the computed results. */
    data class Completed(val items: List<EmbeddingResultItem>) : EmbeddingEvent

    /** Terminal error frame. [code] is a [com.adsamcik.mindlayer.shared.MindlayerErrorCode] integer. */
    data class Error(val code: Int, val message: String) : EmbeddingEvent
}

/**
 * Cold handle for an embedding call. The returned subtype depends on the
 * request shape (single text, batch, or deferred).
 *
 * Behavioural wiring lands in C2; in C1 this is the public type skeleton.
 */
sealed interface EmbeddingHandle {
    val events: Flow<EmbeddingEvent>

    /** Single-text embedding. */
    interface Single : EmbeddingHandle {
        suspend fun awaitVector(): FloatArray
    }

    /** Inline batch embedding. */
    interface Batch : EmbeddingHandle {
        suspend fun awaitVectors(): List<EmbeddingResultItem>
    }

    /** Deferred (out-of-band) batch embedding with explicit acknowledgement. */
    interface Deferred : EmbeddingHandle, AutoCloseable {
        val requestId: String
        suspend fun awaitReady(): List<EmbeddingResultItem>
        suspend fun acknowledge()
    }
}
