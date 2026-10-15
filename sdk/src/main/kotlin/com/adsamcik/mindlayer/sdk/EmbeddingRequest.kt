package com.adsamcik.mindlayer.sdk

import kotlin.time.Duration

/**
 * Declarative description of an embedding call, assembled through [Builder]
 * inside [Mindlayer.embed].
 *
 * `text(...)` and `items(...)` are mutually exclusive; the last call wins.
 * Explicit [Builder.deferred] requests return a durable result handle; other
 * requests return completed single/batch handles.
 */
class EmbeddingRequest private constructor() {

    @MindlayerDsl
    class Builder {
        /** Single text. Mutually exclusive with [items]. */
        fun text(
            text: String,
            tag: String? = null,
            task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
            modelId: String? = null,
            outputDim: Int? = null,
            normalize: Boolean = true,
        ) {
            singleItem = EmbeddingItem(text, tag, task, modelId, outputDim, normalize)
            items = null
        }

        /** Many texts. Direct list pass-through — no per-item allocations. */
        fun items(items: List<EmbeddingItem>) {
            this.items = items
            singleItem = null
        }

        /** Submit without waiting for computation; explicitly acknowledge or close the returned handle. */
        fun deferred() {
            deferred = true
        }

        /**
         * Total budget from [Mindlayer.embed] invocation to obtaining the result,
         * including connection/submission and time before [EmbeddingHandle.Deferred.awaitReady].
         * Expiry requests cancellation and disposal. [Duration.INFINITE] keeps the service's default budget.
         */
        fun deadline(timeout: Duration) {
            require(timeout > Duration.ZERO) { "Embedding deadline must be positive" }
            deadline = timeout
        }

        internal var singleItem: EmbeddingItem? = null
        internal var items: List<EmbeddingItem>? = null
        internal var deferred: Boolean = false
        internal var deadline: Duration? = null
    }
}
