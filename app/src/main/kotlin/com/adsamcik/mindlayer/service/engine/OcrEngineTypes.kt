package com.adsamcik.mindlayer.service.engine

/**
 * A single recognized text line in a frame's OCR result.
 *
 * Produced by [PaddleOcrBackend.recognize] and consumed by
 * [OcrSessionManager] (PR C3) when it builds the evidence package
 * passed to the structured-extraction LLM pass.
 *
 * @property text the recognized text (UTF-16 [String]).
 * @property confidence verbalized confidence — uses the same enum
 *   semantics as [OcrFieldFusion.Confidence] so the same fusion module
 *   consumes both OCR-stage scores and Gemma-stage scores without a
 *   translation step.
 * @property boundingBox quadrilateral in normalised 0..1 frame
 *   coordinates: `[x1, y1, x2, y2, x3, y3, x4, y4]` clockwise from
 *   top-left. Emitted only when [OcrEngineConfig.emitBoundingBoxes]
 *   is true on the engine config. Eight elements.
 * @property orientationDegrees post-classifier rotation that was
 *   applied to the cropped line patch before recognition (0, 90, 180,
 *   or 270). Zero when the bundle has no orientation classifier.
 */
data class OcrTextLine(
    val text: String,
    val confidence: OcrFieldFusion.Confidence,
    val boundingBox: FloatArray? = null,
    val orientationDegrees: Int = 0,
) {
    init {
        require(orientationDegrees in setOf(0, 90, 180, 270)) {
            "orientationDegrees $orientationDegrees not in {0, 90, 180, 270}"
        }
        if (boundingBox != null) {
            require(boundingBox.size == 8) {
                "boundingBox must have 8 floats (quad), got ${boundingBox.size}"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OcrTextLine) return false
        if (text != other.text) return false
        if (confidence != other.confidence) return false
        if (orientationDegrees != other.orientationDegrees) return false
        return when {
            boundingBox == null && other.boundingBox == null -> true
            boundingBox == null || other.boundingBox == null -> false
            else -> boundingBox.contentEquals(other.boundingBox)
        }
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + orientationDegrees
        result = 31 * result + (boundingBox?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        // Redact recognized text: even structurally-innocent strings can carry
        // PII (receipt totals, MRZ, names). Bounding-box geometry is fine to
        // surface in logs because it's pure layout information.
        "OcrTextLine(text=<redacted:${text.length}>, conf=$confidence, " +
            "rot=$orientationDegrees, hasBbox=${boundingBox != null})"
}

/**
 * Full output of a single-frame [PaddleOcrBackend.recognize] call.
 *
 * @property lines recognized text lines in detection order (typically
 *   reading order top-to-bottom, left-to-right after de-skew).
 * @property backend the runtime label that ran inference ("GPU", "CPU",
 *   "NPU"). Matches [PaddleOcrBackend.activeBackend].
 * @property detDurationMs detection-stage wall-clock latency.
 * @property recDurationMs recognition-stage wall-clock latency
 *   (summed across all detected lines).
 * @property clsDurationMs orientation classifier latency, or 0 when
 *   the bundle has no classifier or it was bypassed.
 * @property totalDurationMs wall-clock for the whole recognise call.
 */
data class OcrEngineOutput(
    val lines: List<OcrTextLine>,
    val backend: String,
    val detDurationMs: Long,
    val recDurationMs: Long,
    val clsDurationMs: Long,
    val totalDurationMs: Long,
) {
    override fun toString(): String =
        "OcrEngineOutput(lines=${lines.size}, backend=$backend, " +
            "det=${detDurationMs}ms, rec=${recDurationMs}ms, cls=${clsDurationMs}ms, " +
            "total=${totalDurationMs}ms)"
}

/**
 * Per-recognize-call configuration.
 *
 * Engine-wide knobs live on the bundle / backend; this struct is for
 * per-frame overrides driven by the caller's [com.adsamcik.mindlayer.OcrSessionConfig].
 *
 * @property emitBoundingBoxes when true, [OcrTextLine.boundingBox] is
 *   populated; otherwise null to save IPC payload size.
 *   Gated by [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_BOUNDING_BOXES].
 * @property maxLines hard cap on number of text lines returned. The
 *   detection head's output is truncated past this; lines with the
 *   lowest detection confidence drop first. `0` = no cap.
 * @property orientationDisabled when true, skip the orientation
 *   classifier even when the bundle has one. Used by
 *   [com.adsamcik.mindlayer.OcrSessionConfig.MODE_SCREEN_CAPTURE] where
 *   rotation is fixed.
 */
data class OcrEngineConfig(
    val emitBoundingBoxes: Boolean = false,
    val maxLines: Int = 0,
    val orientationDisabled: Boolean = false,
)
