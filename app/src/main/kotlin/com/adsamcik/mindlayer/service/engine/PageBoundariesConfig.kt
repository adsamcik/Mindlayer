package com.adsamcik.mindlayer.service.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * v0.9 multi-page realtime OCR — page-boundary detector configuration.
 *
 * Rides on [com.adsamcik.mindlayer.OcrSessionConfig.optionsJson] under the
 * `pageBoundaries` key, so the AIDL surface stays wire-frozen. See
 * `docs/ocr/OCR_API.md` ("Multi-page realtime (v0.9)") for the JSON schema.
 *
 * # Defaults
 *
 * When [enabled] is `false` (the default), the recognition dispatcher
 * skips ALL page-boundary work and behaves exactly like v0.8 — single
 * session-end `OCR_RESULT_FINALIZED`, no `OCR_PAGE_*` events.
 *
 * # Validation policy
 *
 * Out-of-range thresholds are clamped to the documented bounds and a
 * config that fails to parse degrades to [DISABLED] rather than throwing.
 * The product invariant is "don't reject a session over malformed
 * optional config" — the caller's session is more important than a
 * perfect knob value.
 *
 * # Threading
 *
 * Pure data — no Android imports, no JVM-thread state. Safe to share
 * across coroutines.
 *
 * @property enabled master switch. `false` → identical to v0.8 behaviour.
 * @property jaccardThreshold token-set Jaccard below this value counts as
 *   "different content" for one frame. Clamped to `0.0..1.0`.
 * @property spatialThreshold bbox-centroid shift between successive frames
 *   (in normalised 0..1 frame coordinates, summed across x+y axes) above
 *   this value counts as "different content". Clamped to `0.0..2.0`.
 * @property gyroThreshold per-frame gyro magnitude (rad/s) above this
 *   value counts as "different content". Clamped to `0.0..50.0`.
 * @property stabilityFrames boundary fires only after this many consecutive
 *   frames all signal "different content" — guards against single-frame
 *   glitches. Clamped to `1..30`.
 * @property llmExtractPerPage when true, run [OcrLlmExtractor] once per
 *   page at session-finalize and attach the result to that page's
 *   `OCR_PAGE_FINALIZED.fullJson`.
 * @property llmExtractFinal when true, run [OcrLlmExtractor] once on the
 *   aggregated page text at session-finalize and attach the result to
 *   `OCR_RESULT_FINALIZED.fullJson`. When false, the finalize event
 *   carries a `{pages: [...]}` rollup instead.
 */
data class PageBoundariesConfig(
    val enabled: Boolean = false,
    val jaccardThreshold: Double = DEFAULT_JACCARD_THRESHOLD,
    val spatialThreshold: Double = DEFAULT_SPATIAL_THRESHOLD,
    val gyroThreshold: Double = DEFAULT_GYRO_THRESHOLD,
    val stabilityFrames: Int = DEFAULT_STABILITY_FRAMES,
    val llmExtractPerPage: Boolean = false,
    val llmExtractFinal: Boolean = true,
) {

    override fun toString(): String =
        "PageBoundariesConfig(enabled=$enabled, jaccard=$jaccardThreshold, " +
            "spatial=$spatialThreshold, gyro=$gyroThreshold, " +
            "stabilityFrames=$stabilityFrames, " +
            "llmExtractPerPage=$llmExtractPerPage, llmExtractFinal=$llmExtractFinal)"

    companion object {
        const val DEFAULT_JACCARD_THRESHOLD: Double = 0.3
        const val DEFAULT_SPATIAL_THRESHOLD: Double = 0.5
        const val DEFAULT_GYRO_THRESHOLD: Double = 2.0
        const val DEFAULT_STABILITY_FRAMES: Int = 3

        const val MIN_JACCARD_THRESHOLD: Double = 0.0
        const val MAX_JACCARD_THRESHOLD: Double = 1.0
        const val MIN_SPATIAL_THRESHOLD: Double = 0.0
        const val MAX_SPATIAL_THRESHOLD: Double = 2.0
        const val MIN_GYRO_THRESHOLD: Double = 0.0
        const val MAX_GYRO_THRESHOLD: Double = 50.0
        const val MIN_STABILITY_FRAMES: Int = 1
        const val MAX_STABILITY_FRAMES: Int = 30

        private const val KEY_ROOT: String = "pageBoundaries"
        private const val KEY_ENABLED: String = "enabled"
        private const val KEY_JACCARD: String = "jaccardThreshold"
        private const val KEY_SPATIAL: String = "spatialThreshold"
        private const val KEY_GYRO: String = "gyroThreshold"
        private const val KEY_STABILITY: String = "stabilityFrames"
        private const val KEY_LLM_PER_PAGE: String = "llmExtractPerPage"
        private const val KEY_LLM_FINAL: String = "llmExtractFinal"

        /** The "feature off" config. Identical behaviour to v0.8. */
        val DISABLED: PageBoundariesConfig = PageBoundariesConfig(enabled = false)

        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parse a `PageBoundariesConfig` out of an opaque
         * `OcrSessionConfig.optionsJson` payload.
         *
         * Returns [DISABLED] when:
         *  - [optionsJson] is null / blank / not a JSON object;
         *  - the `pageBoundaries` block is missing, null, or not an object;
         *  - `pageBoundaries.enabled` is absent or false.
         *
         * Per-field type mismatches degrade silently to the field's default.
         * Out-of-range numeric values are clamped to the documented bounds.
         * Unknown keys inside `pageBoundaries` are ignored (forward-compat).
         */
        fun parse(optionsJson: String?): PageBoundariesConfig {
            if (optionsJson.isNullOrBlank()) return DISABLED
            val root = runCatching { lenientJson.parseToJsonElement(optionsJson) }
                .getOrNull() as? JsonObject
                ?: return DISABLED
            val block = root[KEY_ROOT] as? JsonObject ?: return DISABLED

            val enabled = (block[KEY_ENABLED] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!enabled) return DISABLED

            val jaccard = (block[KEY_JACCARD] as? JsonPrimitive)?.doubleOrNull
                ?.coerceIn(MIN_JACCARD_THRESHOLD, MAX_JACCARD_THRESHOLD)
                ?: DEFAULT_JACCARD_THRESHOLD
            val spatial = (block[KEY_SPATIAL] as? JsonPrimitive)?.doubleOrNull
                ?.coerceIn(MIN_SPATIAL_THRESHOLD, MAX_SPATIAL_THRESHOLD)
                ?: DEFAULT_SPATIAL_THRESHOLD
            val gyro = (block[KEY_GYRO] as? JsonPrimitive)?.doubleOrNull
                ?.coerceIn(MIN_GYRO_THRESHOLD, MAX_GYRO_THRESHOLD)
                ?: DEFAULT_GYRO_THRESHOLD
            val stability = (block[KEY_STABILITY] as? JsonPrimitive)?.intOrNull
                ?.coerceIn(MIN_STABILITY_FRAMES, MAX_STABILITY_FRAMES)
                ?: DEFAULT_STABILITY_FRAMES
            val perPage = (block[KEY_LLM_PER_PAGE] as? JsonPrimitive)?.booleanOrNull ?: false
            val final = (block[KEY_LLM_FINAL] as? JsonPrimitive)?.booleanOrNull ?: true

            return PageBoundariesConfig(
                enabled = true,
                jaccardThreshold = jaccard,
                spatialThreshold = spatial,
                gyroThreshold = gyro,
                stabilityFrames = stability,
                llmExtractPerPage = perPage,
                llmExtractFinal = final,
            )
        }
    }
}
