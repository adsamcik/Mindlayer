package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.EmbeddingResult

/**
 * Transport that was actually used for a batch embedding call.
 *
 * The SDK picks transport automatically based on batch size, estimated
 * reply bytes, API level, and service-advertised caps — consumers do
 * not choose. Surfaced primarily for diagnostics and tests; production
 * code should rarely branch on this.
 *
 * See `docs/sdk/EMBEDDINGS_SDK_POLISH.md` § 3.3 for the selection rule.
 */
enum class EmbeddingTransport {
    /**
     * Inline binder transaction (reply parcel carries the vectors).
     * Picked for small batches that fit safely under the 1 MB binder
     * transaction limit.
     */
    Inline,

    /**
     * SharedMemory blob. Picked for mid-size batches on API 27+ when
     * the service advertises [com.adsamcik.mindlayer.ServiceCapabilities.maxEmbeddingBatchShm] > 0.
     */
    SharedMemory,

    /**
     * Durable deferred batch returned synchronously. Picked when SHM
     * is unavailable and the batch exceeds the inline cap — the SDK
     * submits a deferred batch under the hood and awaits its
     * completion before returning.
     */
    DeferredFallback,
}

/**
 * Result of a synchronous batch embedding call. Internal — exposed via
 * the canonical `embed { items(...) } → EmbeddingHandle.Batch` path.
 *
 * Wraps the underlying list of per-item [EmbeddingResult] (also
 * exposed via [List] delegation for `for`/`map`/`size` ergonomics),
 * the batch-level telemetry that legacy `embedBatch` / `embedBatchLarge`
 * paths discarded, and a record of which [EmbeddingTransport] the SDK
 * chose.
 *
 * **Sensitivity:** vectors are derivable to original text via
 * inversion attacks. Do NOT log this object's contents directly;
 * the typed result fields (`tag`, `tokenCount`, `backend`,
 * `durationMs`) are safe to log.
 */
class EmbeddingBatch internal constructor(
    val results: List<EmbeddingResult>,
    /** Transport actually used. See [EmbeddingTransport]. */
    val transport: EmbeddingTransport,
    /**
     * Aggregate ms across the batch as measured by the service
     * (wall-clock). 0 when the underlying transport did not surface
     * a batch-level total (legacy paths through SHM transfer drop
     * per-item `durationMs` and return 0 here — sum the per-item
     * `durationMs` if you need a precise per-item breakdown).
     */
    val totalDurationMs: Long,
    /**
     * Backend that produced the batch ("NPU" / "GPU" / "CPU"). All
     * items in a single batch share one backend in the current
     * service implementation.
     */
    val backend: String,
) : List<EmbeddingResult> by results {

    /** Convenience: just the L2-normalized vectors, in input order. */
    val vectors: List<FloatArray> get() = results.map { it.vector }

    override fun toString(): String =
        "EmbeddingBatch(size=${results.size}, transport=$transport, backend=$backend, totalDurationMs=$totalDurationMs)"
}
