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
)
