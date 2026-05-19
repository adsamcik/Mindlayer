package com.adsamcik.mindlayer.service.engine

/**
 * A single barcode detection result produced by [BarcodeAnchorDetector]
 * from a single OCR frame.
 *
 * Barcode anchors serve as high-confidence identity locks during
 * multi-frame OCR fusion: a decoded GTIN / SKU / QR payload is far
 * less ambiguous than reading "0123456789" off a smeared printed
 * line, so the structured-extraction stage ([com.adsamcik.mindlayer.service.engine.OcrRecognitionDispatcher])
 * can pin product identity early even when OCR text quality is
 * variable across frames.
 *
 * Surfaced via the existing OCR_FIELD_UPDATE wire path under the
 * synthetic field name `barcode[<index>]` so the SDK reader does
 * not need a new event type. The bounding box is encoded the same
 * way as line-level bbox (Phase 2 #7).
 *
 * @property format the symbology (`QR_CODE`, `EAN_13`, `CODE_128`,
 *   etc) — mirrors [com.google.zxing.BarcodeFormat.name] but is
 *   carried as a [String] so the wire protocol does not depend on
 *   ZXing being present on the SDK classpath.
 * @property value the decoded textual payload. For numeric formats
 *   (UPC, EAN, ITF) this is the digit string; for QR it is the
 *   embedded text. Privacy: carries user-readable data — treat
 *   like any other recognized text.
 * @property boundingBox the decoded barcode's quadrilateral in
 *   normalised 0..1 frame coordinates, clockwise from top-left, in
 *   the same `[x1,y1,x2,y2,x3,y3,x4,y4]` shape as
 *   [OcrTextLine.boundingBox]. May be `null` when ZXing returns a
 *   detection without `ResultPoint`s (some 1D symbologies do not
 *   provide all four corners).
 * @property frameId the frame this anchor came from. Used by the
 *   structured-extraction stage to weight more recent / higher-
 *   quality detections.
 */
data class BarcodeAnchor(
    val format: String,
    val value: String,
    val boundingBox: FloatArray? = null,
    val frameId: Long = 0L,
) {
    init {
        if (boundingBox != null) {
            require(boundingBox.size == 8) {
                "boundingBox must have 8 floats (quad), got ${boundingBox.size}"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BarcodeAnchor) return false
        if (format != other.format) return false
        if (value != other.value) return false
        if (frameId != other.frameId) return false
        return when {
            boundingBox == null && other.boundingBox == null -> true
            boundingBox == null || other.boundingBox == null -> false
            else -> boundingBox.contentEquals(other.boundingBox)
        }
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + frameId.hashCode()
        result = 31 * result + (boundingBox?.contentHashCode() ?: 0)
        return result
    }
}
