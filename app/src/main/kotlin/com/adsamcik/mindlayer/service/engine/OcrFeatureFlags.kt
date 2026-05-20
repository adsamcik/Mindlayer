package com.adsamcik.mindlayer.service.engine

/**
 * Compile-time feature flags for the OCR subsystem.
 *
 * Mirrors EmbeddingFeatureFlags: capability discovery must not advertise an
 * unvalidated native inference stack just because the AIDL surface and AI Pack
 * scaffolding are present. Runtime probing would lazy-init LiteRT delegates and
 * allocate native memory, so readiness is represented as a compile-time pin.
 * Tests inject an override through OcrSessionManager(isProductionReady = ...).
 */
object OcrFeatureFlags {
    /**
     * OCR is not production-ready until real-device coexistence validation,
     * numeric OCR validation, and a first-party driver have all landed.
     *
     * When false, ServiceCapabilities.supportedFeatures must not contain
     * FEATURE_OCR_SESSION. Search this constant for the later one-line
     * production promotion.
     */
    const val IS_PRODUCTION_READY: Boolean = false
}
