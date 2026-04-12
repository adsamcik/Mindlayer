package com.mindlayer.sdk

/**
 * Typed exception thrown by Mindlayer SDK one-shot convenience methods.
 *
 * @param message Human-readable error description from the service.
 * @param code Optional error code from the service for programmatic handling.
 * @param requestId The inference request ID, if available.
 */
class MindlayerException(
    message: String,
    val code: String? = null,
    val requestId: String? = null,
) : Exception(message)
