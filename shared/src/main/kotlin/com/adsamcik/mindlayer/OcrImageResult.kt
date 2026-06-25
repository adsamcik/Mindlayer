package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Result of a one-shot [com.adsamcik.mindlayer.IMindlayerService.ocrImage]
 * call.
 *
 * Carries the recognized lines and, when
 * [OcrImageOptions.runLlmExtraction] was `true`, the structured fields
 * produced by the Gemma extraction pass.
 *
 * # Wire stability
 *
 * Per `docs/architecture/AIDL_STABILITY.md`: parcelables are wire-frozen once shipped.
 * [schemaVersion] is the **first** field and is currently `1`. Adding
 * fields is a wire break — use [featureFlags] for future single-bit
 * toggles or pack richer extensions into [extractionJson] (already opaque
 * to readers that only consume [extractionFields]).
 *
 * # Privacy
 *
 * [toString] reports counts and timings only — never line text, field
 * values, or the raw extraction JSON. Service-side `MindlayerLog` callers
 * MUST do the same and log only the result of `safeLabel()` on errors.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property lines Recognized text lines in detection order (top-to-bottom,
 *   left-to-right after de-skew). Capped by [OcrImageOptions.maxLines].
 *   May be empty when the image contains no text or all detections were
 *   rejected by the post-processor.
 * @property extractionFields Structured fields from the optional LLM pass.
 *   Always empty when [OcrImageOptions.runLlmExtraction] was `false`.
 *   May still be empty when LLM was requested but the model produced no
 *   parseable output — the call succeeds and [lines] remains populated.
 * @property extractionJson Verbatim model output (the schema-shaped JSON
 *   object) from the LLM pass. `null` when the LLM was not requested,
 *   when it produced no parseable output, or when the extractor opted out
 *   of surfacing the raw envelope.
 * @property backend Hardware backend label the OCR pass ran on
 *   (`"CPU"`, `"GPU"`, `"NPU"`). `"NONE"` if the backend was never
 *   initialised (rare — only on hard error before recognise was attempted).
 * @property ocrDurationMs Wall-clock time spent in the PaddleOCR
 *   detection + recognition + classifier passes.
 * @property llmDurationMs Wall-clock time spent in the LLM extraction pass.
 *   `0` when [OcrImageOptions.runLlmExtraction] was `false` or the pass
 *   short-circuited.
 * @property totalDurationMs Total wall-clock for the whole `ocrImage`
 *   call, including media staging, validation, Y-plane extraction, and
 *   result mapping. Always `>= ocrDurationMs + llmDurationMs`.
 * @property featureFlags Reserved bitfield. v1 ignores all bits.
 */
@Parcelize
data class OcrImageResult(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val lines: List<OcrImageLine> = emptyList(),
    val extractionFields: List<OcrImageExtractedField> = emptyList(),
    val extractionJson: String? = null,
    val backend: String = BACKEND_NONE,
    val ocrDurationMs: Long = 0L,
    val llmDurationMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrImageResult(lines=${lines.size}, extractionFields=${extractionFields.size}, " +
            "hasExtractionJson=${extractionJson != null}, backend=$backend, " +
            "ocr=${ocrDurationMs}ms, llm=${llmDurationMs}ms, total=${totalDurationMs}ms)"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Backend label when no backend was ever initialised. */
        const val BACKEND_NONE: String = "NONE"
    }
}
