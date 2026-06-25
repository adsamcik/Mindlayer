package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Numeric limits the connected service enforces for the multi-frame OCR API,
 * fetched once via [com.adsamcik.mindlayer.IMindlayerService.getOcrLimits].
 *
 * Lives on a **separate parcelable** rather than as new fields on
 * [ServiceCapabilities] because the capability parcelable is wire-frozen per
 * `docs/architecture/AIDL_STABILITY.md` — adding fields would break old SDKs that decode
 * by positional layout.
 *
 * SDKs that pre-date [com.adsamcik.mindlayer.IMindlayerService.getOcrLimits]
 * catch `NoSuchMethodError` / `AbstractMethodError` and fall back to
 * [zeroBaseline] (which advertises "OCR not supported").
 *
 * # Wire stability
 *
 * [schemaVersion] is the **first** field. Future fields go in [featureFlags]
 * (reserved bitfield) or `OcrLimitsV2`.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property maxConcurrentOcrSessions Maximum simultaneous OCR sessions per
 *   caller UID. `0` = OCR refused for this caller / tier.
 * @property maxOcrFramesPerMinute Per-UID cap on `pushOcrFrame` calls per
 *   60-second sliding window. Enforced by a dedicated frame token bucket
 *   layered on top of the existing per-UID RPM bucket.
 * @property maxFramesPerOcrSession Hard cap on accepted frames per session.
 *   Reaching this triggers an implicit `finalizeOcrSession`.
 * @property maxOcrSessionDurationMs Hard wall-clock cap on session lifetime
 *   regardless of activity. Default Phase 1: 5 minutes (300_000).
 * @property ocrPerFrameDecodeBudgetTokens Maximum LLM decode tokens per frame
 *   in Strategy-A reset-per-frame KV mode. Enforced via brace-balance
 *   `cancelProcess()` in the inference orchestrator.
 * @property ocrSchemaJsonMaxLen Maximum length (chars) of
 *   [OcrSessionConfig.outputSchemaJson] and [OcrFrameMeta.regionJson].
 * @property featureFlags Reserved bitfield. v1 ignores all bits.
 */
@Parcelize
data class OcrLimits(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val maxConcurrentOcrSessions: Int,
    val maxOcrFramesPerMinute: Int,
    val maxFramesPerOcrSession: Int,
    val maxOcrSessionDurationMs: Long,
    val ocrPerFrameDecodeBudgetTokens: Int,
    val ocrSchemaJsonMaxLen: Int,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrLimits(maxSessions=$maxConcurrentOcrSessions, " +
            "framesPerMin=$maxOcrFramesPerMinute, framesPerSession=$maxFramesPerOcrSession, " +
            "durMs=$maxOcrSessionDurationMs, decodeTokens=$ocrPerFrameDecodeBudgetTokens, " +
            "schemaLen=$ocrSchemaJsonMaxLen)"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /**
         * Fallback advertised by SDKs whose service doesn't implement
         * [com.adsamcik.mindlayer.IMindlayerService.getOcrLimits] yet. All caps
         * are zero — effectively "OCR not supported".
         */
        @JvmStatic
        fun zeroBaseline(): OcrLimits = OcrLimits(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            maxConcurrentOcrSessions = 0,
            maxOcrFramesPerMinute = 0,
            maxFramesPerOcrSession = 0,
            maxOcrSessionDurationMs = 0L,
            ocrPerFrameDecodeBudgetTokens = 0,
            ocrSchemaJsonMaxLen = 0,
            featureFlags = 0,
        )
    }
}
