package com.adsamcik.mindlayer.service.engine

interface EmbeddingBackend {
    /** Hardware backend label: "NPU", "GPU", "CPU", or "NONE" before init. */
    val activeBackend: String

    val isInitialized: Boolean

    /** The loaded model, or null when not yet initialised. */
    val currentModel: EmbeddingModelInfo?

    /**
     * Load [model] with the given backend preference chain.
     * Implementations MUST be idempotent: a second initialize call with the
     * same model is a no-op fast path.
     * Throws [LowMemoryException] when RAM is below model size + headroom.
     * Throws a typed exception (or [IllegalStateException] with a safeLabel)
     * on any other init failure — the engine layer catches and converts.
     */
    suspend fun initialize(
        model: EmbeddingModelInfo,
        preferredBackend: String? = null,
    )

    /**
     * Embed [tokens] and return [outputDim] dimensions (≤ model.nativeDim).
     * [outputDim] = null requests native dim. Returns f32 vector;
     * [normalize] = true applies L2 normalization.
     */
    suspend fun embed(
        tokens: IntArray,
        outputDim: Int?,
        normalize: Boolean,
    ): FloatArray

    /** Tokenize text into IDs. Returns up to [maxTokens] tokens (truncate). */
    fun tokenize(text: String, maxTokens: Int): IntArray

    /** Release native resources. Idempotent. */
    suspend fun shutdown()
}
