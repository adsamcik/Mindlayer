package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Options for a one-shot single-image OCR call via
 * [com.adsamcik.mindlayer.IMindlayerService.ocrImage].
 *
 * # Why a separate parcelable from [OcrSessionConfig]
 *
 * Single-image OCR is the natural shape for callers that have a single
 * captured image (gallery picker, sharesheet target, "scan this receipt"
 * one-shot, screenshot text-extraction). The session pipeline carries a lot
 * of multi-frame machinery — frame intake queue, presort, cross-frame
 * fusion, IDLE / MAX_DURATION timers, finalization drain, event pipe — that
 * adds latency and code surface for callers that just want
 * `bytes → recognized lines`.
 *
 * This config exposes only knobs that make sense for a single call. Many of
 * them mirror per-frame knobs from the session API ([OcrSessionConfig] +
 * [OcrFrameMeta]) so callers porting from one API to the other have a
 * predictable mapping.
 *
 * # Wire stability
 *
 * Per `docs/architecture/AIDL_STABILITY.md`: parcelables are wire-frozen once shipped.
 * [schemaVersion] is the **first** field and is currently `1`. Adding fields
 * is a wire break — instead, use [featureFlags] for single-bit toggles or
 * pack richer extensions into [optionsJson].
 *
 * # Capability gating
 *
 * Surfaces via [com.adsamcik.mindlayer.IMindlayerService.ocrImage]; gated
 * by [ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT]. SDKs that don't
 * advertise the flag see the method as
 * `NoSuchMethodError` / `AbstractMethodError` on the binder stub.
 *
 * # Threading
 *
 * The service serialises every recognise call through the per-engine mutex
 * inside `PaddleOcrEngine` — concurrent `ocrImage` invocations therefore
 * queue rather than race. Callers needing high-throughput continuous
 * scanning should use the [OcrSessionConfig] session API which buffers
 * frame intake and applies presort.
 *
 * @property schemaVersion Wire-stable. Currently `1`. Bumping requires a new method.
 * @property emitBoundingBoxes When `true`, each [OcrImageLine] in the response
 *   carries an 8-float quadrilateral in normalised (0..1) frame coordinates.
 *   Defaults to `false` to keep payloads small.
 * @property maxLines Hard cap on lines in the response. `0` means "service
 *   default" (currently 64). Clamped to a safe upper bound by the validator.
 * @property orientationDisabled When `true`, skip the orientation classifier
 *   step even when the bundle has one. Use when the input is known to be
 *   axis-aligned (e.g. screenshot pipelines) — saves ~50ms per call.
 * @property languageHints BCP-47 language tags hinting which OCR pack to
 *   prefer. PP-OCRv5 mobile is multilingual today so this is advisory; the
 *   value is forwarded to the optional LLM extraction stage when enabled.
 * @property runLlmExtraction When `true`, run the structured-extraction
 *   Gemma pass on the recognized lines and populate
 *   [OcrImageResult.extractionFields] / [OcrImageResult.extractionJson].
 *   Requires [extractionSchemaJson]. Adds the LLM decode latency (typically
 *   2-5s) to the call. Defaults to `false` — most callers just want the raw
 *   text.
 * @property extractionSchemaJson JSON schema describing the structured output
 *   shape. **Required** when [runLlmExtraction] is `true`; ignored otherwise.
 *   Same shape as [OcrSessionConfig.outputSchemaJson]. Capped by
 *   [OcrLimits.ocrSchemaJsonMaxLen]. Validated server-side before the engine
 *   sees it.
 * @property extractionDecodeBudgetTokens Hard token cap on the LLM decode
 *   pass. `0` means "service default"
 *   ([OcrLimits.ocrPerFrameDecodeBudgetTokens]). Only meaningful when
 *   [runLlmExtraction] is `true`.
 * @property optionsJson Opaque JSON envelope for forward-compatible
 *   extensions. Parsed and bounded server-side; unknown keys are ignored,
 *   never logged. `null` = use defaults.
 * @property featureFlags Reserved bitfield. v1 ignores all bits.
 */
@Parcelize
data class OcrImageOptions(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val emitBoundingBoxes: Boolean = false,
    val maxLines: Int = 0,
    val orientationDisabled: Boolean = false,
    val languageHints: List<String> = emptyList(),
    val runLlmExtraction: Boolean = false,
    val extractionSchemaJson: String? = null,
    val extractionDecodeBudgetTokens: Int = 0,
    val optionsJson: String? = null,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrImageOptions(bbox=$emitBoundingBoxes, maxLines=$maxLines, " +
            "orientationDisabled=$orientationDisabled, langs=${languageHints.size}, " +
            "runLlm=$runLlmExtraction, " +
            "schemaJson=${if (extractionSchemaJson == null) "null" else "<redacted:${extractionSchemaJson.length}>"}, " +
            "decodeBudget=$extractionDecodeBudgetTokens, " +
            "optionsJson=${if (optionsJson == null) "null" else "<redacted:${optionsJson.length}>"})"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
