package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One structured field extracted by the optional LLM pass in
 * [com.adsamcik.mindlayer.IMindlayerService.ocrImage] when
 * [OcrImageOptions.runLlmExtraction] is `true`.
 *
 * Mirrors the in-process `OcrExtractedField` shape used by the multi-frame
 * session pipeline so callers that consume both APIs see consistent
 * semantics.
 *
 * # Wire stability
 *
 * Per `docs/architecture/AIDL_STABILITY.md`: parcelables are wire-frozen. [schemaVersion]
 * is the first field. Adding new fields is a wire break — use
 * [featureFlags] for future single-bit toggles.
 *
 * # Privacy
 *
 * [toString] redacts [value]; even structurally innocent strings can carry
 * PII (receipt totals, MRZ fields, names).
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property name Dot-separated JSON pointer relative to the schema root the
 *   caller supplied via [OcrImageOptions.extractionSchemaJson]
 *   (e.g. `"total"`, `"items[0].sku"`, `"shipping.address.zip"`).
 * @property value Extracted value, as the model produced it (trimmed by the
 *   extractor; opaque to the wire).
 * @property confidence One of [OcrImageLine.CONFIDENCE_LOW],
 *   [OcrImageLine.CONFIDENCE_MEDIUM], [OcrImageLine.CONFIDENCE_HIGH].
 *   Mirrors the verbalized confidence enum used by the recognition path.
 * @property featureFlags Reserved bitfield. v1 ignores all bits.
 */
@Parcelize
data class OcrImageExtractedField(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val name: String,
    val value: String,
    val confidence: Int = OcrImageLine.CONFIDENCE_LOW,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrImageExtractedField(name=$name, valueLen=${value.length}, conf=$confidence)"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
