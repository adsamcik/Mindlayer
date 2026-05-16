package com.adsamcik.mindlayer.service.engine

/** Metadata for an installed embedding model. Mirrors [ModelInfo] semantics. */
data class EmbeddingModelInfo(
    val id: String,
    val displayName: String,
    val modelPath: String,
    val tokenizerPath: String,
    val sizeBytes: Long,
    val nativeDim: Int,
    val supportedDims: List<Int>,
    val maxContextTokens: Int,
    val sha256: String?,
)
