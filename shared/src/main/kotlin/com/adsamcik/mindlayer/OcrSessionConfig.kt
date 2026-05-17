package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration for a multi-frame OCR / parsing session.
 *
 * # Wire stability
 *
 * Per `docs/AIDL_STABILITY.md`: parcelables are wire-frozen once shipped.
 * [schemaVersion] is the **first** field and is currently `1`. Adding
 * fields to this parcelable later is a wire break — instead, use the
 * reserved [featureFlags] bitfield for future single-bit toggles, or
 * pack richer extensions into [optionsJson] (opaque JSON envelope,
 * parallel to [SessionConfig.extraContextJson]/[SessionConfig.toolsJson]).
 *
 * # Capability gating
 *
 * Surfaces via [com.adsamcik.mindlayer.IMindlayerService.createOcrSession];
 * gated by [ServiceCapabilities.FEATURE_OCR_SESSION]. Old SDKs that don't
 * advertise the flag see the method as `NoSuchMethodError` /
 * `AbstractMethodError` on the binder stub.
 *
 * @property schemaVersion Wire-stable. Currently `1`. Bumping requires a new method.
 * @property mode One of [MODE_GENERAL_DOCUMENT], [MODE_RECEIPT], [MODE_ID_CARD],
 *   [MODE_WHITEBOARD], [MODE_SCREEN_CAPTURE]. Unknown values are rejected with
 *   [com.adsamcik.mindlayer.shared.MindlayerErrorCode.OCR_SCHEMA_INVALID].
 * @property outputSchemaJson Caller-supplied JSON schema (string form) describing
 *   the structured output. Required. Maximum length capped by
 *   [OcrLimits.ocrSchemaJsonMaxLen] (default 16 KiB). Validated by
 *   [com.adsamcik.mindlayer.service.security.IpcInputValidator.validateOcrSessionConfig]
 *   before the engine sees it.
 * @property languageHints BCP-47 language tags hinting which OCR language pack
 *   to prefer. Empty list means "auto" / Latin default.
 * @property maxFrames Hard cap on accepted frames before service forces
 *   [com.adsamcik.mindlayer.IMindlayerService.finalizeOcrSession]. `0` means
 *   "use service default" (`maxFramesPerOcrSession`, advertised by
 *   [com.adsamcik.mindlayer.IMindlayerService.getOcrLimits]).
 * @property frameRateLimitFps Soft cap on caller's `pushOcrFrame` throughput.
 *   `0` means "use service default" (`maxOcrFramesPerMinute`).
 * @property optionsJson Opaque JSON envelope for forward-compatible extensions
 *   (presort thresholds, convergence knobs, etc). Parsed and bounded by the
 *   service; unknown keys are ignored, never logged. `null` = use profile defaults.
 * @property featureFlags Reserved bitfield for future toggles. v1 ignores all bits.
 */
@Parcelize
data class OcrSessionConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mode: Int,
    val outputSchemaJson: String,
    val languageHints: List<String> = emptyList(),
    val maxFrames: Int = 0,
    val frameRateLimitFps: Int = 0,
    val optionsJson: String? = null,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrSessionConfig(mode=$mode, maxFrames=$maxFrames, fps=$frameRateLimitFps, " +
            "languages=${languageHints.size}, " +
            "schemaJson=<redacted:${outputSchemaJson.length}>, " +
            "optionsJson=${if (optionsJson == null) "null" else "<redacted:${optionsJson.length}>"})"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** General document or scene-text OCR. Default profile. */
        const val MODE_GENERAL_DOCUMENT: Int = 1

        /** Receipt-shaped layout — small fonts, dense rows, optional totals. */
        const val MODE_RECEIPT: Int = 2

        /** ID-card / MRZ-bearing layout — perspective rectification + MRZ check. */
        const val MODE_ID_CARD: Int = 3

        /** Whiteboard photo — aggressive deskew + perspective correction. */
        const val MODE_WHITEBOARD: Int = 4

        /** Phone screenshot or screen-recording frame — no perspective transform. */
        const val MODE_SCREEN_CAPTURE: Int = 5

        /** All currently-known modes. The validator rejects unknown values. */
        val ALL_MODES: Set<Int> = setOf(
            MODE_GENERAL_DOCUMENT,
            MODE_RECEIPT,
            MODE_ID_CARD,
            MODE_WHITEBOARD,
            MODE_SCREEN_CAPTURE,
        )
    }
}
