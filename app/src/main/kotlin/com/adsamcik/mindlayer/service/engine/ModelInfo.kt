package com.adsamcik.mindlayer.service.engine

/**
 * Describes an available LLM model on the device.
 */
data class ModelInfo(
    /** Unique identifier derived from filename without extension. e.g. "gemma-4-E2B-it" */
    val id: String,
    /** Human-readable display name. e.g. "Gemma 4 E2B Instruct" */
    val displayName: String,
    /** Absolute path to the .litertlm file on disk. */
    val path: String,
    /** File size in bytes. */
    val sizeBytes: Long,
    /** Verified SHA-256 when integrity metadata was available. */
    val sha256: String? = null,
    /** Whether this is the default/recommended model. */
    val isDefault: Boolean = false,
    /**
     * Whether the model supports image inputs (multimodal vision encoder).
     *
     * When `true`, [EngineManager] passes a non-null `visionBackend` and
     * positive [maxImagesPerTurn] to [com.google.ai.edge.litertlm.EngineConfig]
     * so the LiteRT-LM engine actually loads its vision executor. **If
     * vision support is mis-declared, the native engine SIGSEGVs the
     * first time an image content part is enqueued** — see LiteRT-LM
     * issues #1874 + #1686. Default `false` is safe: text-only inference
     * works without a vision executor.
     *
     * Derived in [ModelRegistry.deriveSupportsVision] from the model id;
     * any model whose id starts with `gemma-4-` is treated as multimodal.
     */
    val supportsVision: Boolean = false,
    /**
     * Max image content parts the model can accept per inference turn.
     * Only meaningful when [supportsVision] is `true`. Forwarded to
     * `EngineConfig.maxNumImages`; LiteRT-LM defaults `max_num_images`
     * to `0` when this field is null, which prevents the vision executor
     * from running even after it loads (issue #1686). 1 is the right
     * floor for single-image chat callers like Starlit Coffee.
     */
    val maxImagesPerTurn: Int = 0,
)
