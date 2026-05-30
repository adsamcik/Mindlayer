package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single recognized text line from a one-shot
 * [com.adsamcik.mindlayer.IMindlayerService.ocrImage] call.
 *
 * Shape parallels the in-process `OcrTextLine` in `:app`'s engine layer so
 * the same line representation flows through both APIs (multi-frame
 * sessions and single-image one-shot).
 *
 * # Wire stability
 *
 * Per `docs/AIDL_STABILITY.md`: parcelables are wire-frozen. [schemaVersion]
 * is the first field. Adding new fields is a wire break — use
 * [featureFlags] for future single-bit toggles.
 *
 * # Privacy
 *
 * [toString] redacts [text]; it intentionally surfaces only the length so
 * dashboard / diagnostics rendering of a line list does not leak content
 * into logs.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property text Recognized text (UTF-16 [String]).
 * @property confidence One of [CONFIDENCE_LOW], [CONFIDENCE_MEDIUM],
 *   [CONFIDENCE_HIGH]. Mirrors the verbalized scale used by the in-process
 *   `OcrFieldFusion.Confidence` enum; unknown values map to `LOW` on the
 *   SDK side for forward-compatibility.
 * @property boundingBox Quadrilateral in normalised 0..1 frame coordinates:
 *   `[x1, y1, x2, y2, x3, y3, x4, y4]`, clockwise from top-left.
 *   Populated only when the caller set
 *   [OcrImageOptions.emitBoundingBoxes] = `true`. Eight elements when
 *   present.
 * @property orientationDegrees Rotation the orientation classifier applied
 *   to the cropped line patch before recognition (`0`, `90`, `180`, or
 *   `270`). `0` when the bundle has no classifier or
 *   [OcrImageOptions.orientationDisabled] = `true`.
 * @property featureFlags Reserved bitfield. v1 ignores all bits.
 */
@Parcelize
data class OcrImageLine(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val text: String,
    val confidence: Int = CONFIDENCE_LOW,
    val boundingBox: FloatArray? = null,
    val orientationDegrees: Int = 0,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OcrImageLine) return false
        return schemaVersion == other.schemaVersion &&
            text == other.text &&
            confidence == other.confidence &&
            orientationDegrees == other.orientationDegrees &&
            featureFlags == other.featureFlags &&
            when {
                boundingBox == null && other.boundingBox == null -> true
                boundingBox == null || other.boundingBox == null -> false
                else -> boundingBox.contentEquals(other.boundingBox)
            }
    }

    override fun hashCode(): Int {
        var r = schemaVersion
        r = 31 * r + text.hashCode()
        r = 31 * r + confidence
        r = 31 * r + orientationDegrees
        r = 31 * r + featureFlags
        r = 31 * r + (boundingBox?.contentHashCode() ?: 0)
        return r
    }

    override fun toString(): String =
        "OcrImageLine(textLen=${text.length}, conf=$confidence, " +
            "rot=$orientationDegrees, hasBbox=${boundingBox != null})"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Lowest verbalized confidence (or unknown for forward-compat). */
        const val CONFIDENCE_LOW: Int = 1

        /** Mid verbalized confidence. */
        const val CONFIDENCE_MEDIUM: Int = 2

        /** Highest verbalized confidence. */
        const val CONFIDENCE_HIGH: Int = 3
    }
}
