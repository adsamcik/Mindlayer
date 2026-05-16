package com.adsamcik.mindlayer;

/**
 * v0.4 push-notification callback for service-side session evictions.
 *
 * Registered via {@link IMindlayerService#subscribeEvictionNotices}, fired
 * on each event that retires a session out from under the caller (memory
 * pressure eviction, expiration, OOM kill, explicit revoke). The reasonCode
 * is a {@link com.adsamcik.mindlayer.shared.MindlayerErrorCode} integer
 * (typically SESSION_EVICTED, SESSION_EXPIRED, or MEMORY_PRESSURE).
 *
 * All methods are {@code oneway} ? the service does not block on slow
 * client-side handlers. The client should marshal the notification to
 * its own coroutine context and return immediately.
 */
interface IClientCallback {
    /**
     * Called once per session that the service evicted while this callback
     * was registered. Idempotent on the service side ? duplicate callbacks
     * for the same (sessionId, reasonCode) pair are not guaranteed but also
     * not prevented.
     *
     * @param sessionId the session that was evicted.
     * @param reasonCode {@link com.adsamcik.mindlayer.shared.MindlayerErrorCode}.
     */
    oneway void onSessionEvicted(String sessionId, int reasonCode);

    oneway void onDeferredInferenceComplete(String requestId, int statusCode);

    // v0.7: deferred-embedding completion notification ? mirrors onDeferredInferenceComplete.
    oneway void onEmbeddingBatchComplete(String requestId);
}

