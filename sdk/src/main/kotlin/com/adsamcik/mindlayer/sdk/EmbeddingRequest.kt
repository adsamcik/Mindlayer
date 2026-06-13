package com.adsamcik.mindlayer.sdk

import kotlin.time.Duration

/**
 * Declarative description of an embedding call, assembled through [Builder]
 * inside [Mindlayer.embed].
 *
 * `text(...)` and `items(...)` are mutually exclusive; the last call wins.
 * C1 lands the builder surface only; behavioural wiring lands in C2.
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

        fun deferred() {
            deferred = true
        }

        fun deadline(timeout: Duration) {
            deadline = timeout
        }

        internal var singleItem: EmbeddingItem? = null
        internal var items: List<EmbeddingItem>? = null
        internal var deferred: Boolean = false
        internal var deadline: Duration? = null
    }
}
