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
     * OCR is production-ready as of v0.9.
     *
     * Gates ServiceCapabilities.FEATURE_OCR_SESSION and
     * ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT — both flip together with
     * this constant. Historical driver results live in `docs/archive/`; those
     * include skipped inference and error-handling scenarios, so their pass
     * totals do not qualify the current native runtime. See
     * `docs/project/ROADMAP.md` for remaining native/model qualification and
     * `samples/ocr-driver/` for the driver (`ValidationRunner.runAll()`).
     *
     * Flipping back to `false` is the documented hot-fix path if a regression
     * is discovered on real hardware — both feature flags disappear from the
     * capability set immediately and capability-aware SDKs degrade gracefully.
     */
    const val IS_PRODUCTION_READY: Boolean = true
}
