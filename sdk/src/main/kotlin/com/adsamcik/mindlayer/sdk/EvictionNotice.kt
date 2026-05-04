package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode

/**
 * Push notification emitted by the service when one of the caller's
 * sessions is involuntarily retired.
 *
 * Subscribe via [Mindlayer.evictionNotices]. The flow buffers up to
 * [Mindlayer.EVICTION_BUFFER] notices with `DROP_OLDEST` overflow, so
 * a slow consumer never causes the service-side dispatcher to block.
 *
 * @property sessionId the session that no longer exists.
 * @property reasonCode a [MindlayerErrorCode] integer. Most commonly
 *     [MindlayerErrorCode.SESSION_EVICTED] (caller-capacity eviction),
 *     [MindlayerErrorCode.SESSION_EXPIRED] (TTL exceeded), or
 *     [MindlayerErrorCode.MEMORY_PRESSURE] (device-wide eviction).
 */
data class EvictionNotice(
    val sessionId: String,
    val reasonCode: Int,
) {
    /** Human-readable code label, or `null` for codes the SDK doesn't recognise. */
    val codeName: String?
        get() = MindlayerErrorCode.nameOf(reasonCode)

    /**
     * Convenience predicate — `true` when the eviction was driven by the
     * device's memory budget rather than caller-specific capacity.
     */
    val isMemoryPressure: Boolean
        get() = reasonCode == MindlayerErrorCode.MEMORY_PRESSURE
}
