package com.adsamcik.mindlayer.service.engine

/**
 * Single-frame evidence handed to an [OcrLlmExtractor].
 *
 * Bundles everything the LLM needs to do structured extraction on one
 * frame: the OCR-recognized text lines (with optional bounding-box
 * geometry), the decoded barcode anchors (high-confidence GTINs / QR
 * payloads), the caller's schema, the [com.adsamcik.mindlayer.OcrSessionConfig.mode]
 * hint, and frame-level metadata for fusion weighting.
 *
 * # Immutable
 *
 * All collections are wrapped as defensively-immutable views by the
 * dispatcher before being handed to the extractor. The extractor MUST
 * NOT mutate them.
 *
 * # No raw image
 *
 * The Y-plane never reaches the LLM extractor. Per the Strategy-A
 * Gemma-text-only pipeline, the OCR pass digests the pixels and the
 * extractor sees only [textLines] + [barcodeAnchors] + the schema.
 *
 * @property sessionId opaque session id (forwarded for log tagging
 *   only; the extractor must not persist it).
 * @property frameId monotonic per-session frame id; propagates into
 *   [OcrFieldFusion.FieldObservation.frameId].
 * @property frameIndex zero-based position of this frame within the
 *   per-session accepted-frame sequence. Lets the extractor reason
 *   about "first frame" vs "Nth frame" prompts.
 * @property mode one of the `OcrSessionConfig.MODE_*` constants.
 * @property outputSchemaJson the caller's schema text.
 * @property textLines OCR-recognized lines for this frame.
 * @property barcodeAnchors decoded barcodes for this frame, if any.
 * @property frameQuality 0..1 frame-quality score; propagates into
 *   the [OcrFieldFusion.FieldObservation] weight so blurry frames
 *   contribute less even at the same confidence.
 */
data class OcrEvidencePackage(
    val sessionId: String,
    val frameId: Long,
    val frameIndex: Int,
    val mode: Int,
    val outputSchemaJson: String,
    val textLines: List<OcrTextLine>,
    val barcodeAnchors: List<BarcodeAnchor>,
    val frameQuality: Double,
) {
    init {
        require(frameId >= 0L) { "frameId $frameId must be non-negative" }
        require(frameIndex >= 0) { "frameIndex $frameIndex must be non-negative" }
        require(frameQuality in 0.0..1.0) {
            "frameQuality $frameQuality must be in 0..1"
        }
    }

    override fun toString(): String =
        "OcrEvidencePackage(frameId=$frameId, frameIndex=$frameIndex, mode=$mode, " +
            "schemaLen=${outputSchemaJson.length}, lines=${textLines.size}, " +
            "barcodes=${barcodeAnchors.size}, quality=$frameQuality)"
}
