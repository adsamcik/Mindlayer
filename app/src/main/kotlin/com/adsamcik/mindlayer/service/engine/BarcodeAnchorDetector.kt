package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.EnumSet

/**
 * Pure-JVM barcode detector that runs alongside OCR recognition and
 * injects decoded barcode anchors into the OCR evidence package.
 *
 * # Design
 *
 * The detector consumes the SAME Y-plane the OCR engine consumes
 * (extracted by `MediaPart.extractYPlane()` in Phase 2 #1). Sharing
 * the luminance buffer avoids a duplicate decode / copy of the
 * incoming frame and keeps the dispatcher CPU budget bounded.
 *
 * ZXing's [PlanarYUVLuminanceSource] takes a packed Y-only byte
 * array directly, so no per-pixel conversion is needed. The
 * [HybridBinarizer] is the standard recommendation for natural-
 * lighting captures (vs. [com.google.zxing.common.GlobalHistogramBinarizer]
 * which is faster but biased for high-contrast prints).
 *
 * # Symbology coverage
 *
 * Defaults cover the symbologies most relevant to receipt /
 * product capture workflows:
 *   - Retail 1D: EAN-13, EAN-8, UPC-A, UPC-E, ITF
 *   - Logistics 1D: Code-128, Code-39
 *   - 2D: QR, Data Matrix, Aztec, PDF417
 *
 * `TRY_HARDER` is intentionally disabled: at multi-frame OCR
 * cadence the budget for each frame is tight, and the next frame
 * is the cheapest way to re-decode a missed barcode anyway.
 *
 * # Thread-safety
 *
 * ZXing's [MultiFormatReader] is NOT thread-safe; this class
 * synchronises around its [reader] field. The
 * [OcrRecognitionDispatcher] calls [decode] on a single coroutine
 * scope per session, so contention is low; the synchronisation
 * is defensive against future call-site changes.
 *
 * # Privacy
 *
 * Decoded payloads carry user-readable data (product GTIN, QR
 * URLs, etc.). The detector logs only the symbology name and the
 * payload length / hash-prefix via `safeLabel`, never the value.
 *
 * # Failure mode
 *
 * Per-frame decode is best-effort: if ZXing throws or returns no
 * results, the detector returns an empty list. Callers should
 * treat the absence of a detection as "no barcode this frame",
 * not as an error.
 */
class BarcodeAnchorDetector(
    formats: Set<BarcodeFormat> = DEFAULT_FORMATS,
) {

    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = EnumSet.copyOf(formats)
        setHints(hints)
    }

    /**
     * Decode all barcodes in the given Y-plane.
     *
     * @param yPlane row-major 8-bit greyscale Y data (length must be
     *   `width * height`, but the detector tolerates over-allocated
     *   buffers by clamping via the `dataWidth` / `dataHeight`
     *   arguments to [PlanarYUVLuminanceSource]).
     * @param width pixel width.
     * @param height pixel height.
     * @param frameId carried through to the resulting [BarcodeAnchor]s.
     * @return list of decoded anchors. Empty when no barcode found
     *   or when decoding failed for any reason.
     */
    fun decode(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        frameId: Long = 0L,
    ): List<BarcodeAnchor> {
        if (width <= 0 || height <= 0) return emptyList()
        if (yPlane.size < width * height) return emptyList()

        val source = PlanarYUVLuminanceSource(
            /* yuvData = */ yPlane,
            /* dataWidth = */ width,
            /* dataHeight = */ height,
            /* left = */ 0,
            /* top = */ 0,
            /* width = */ width,
            /* height = */ height,
            /* reverseHorizontal = */ false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result: Result = try {
            synchronized(reader) {
                try {
                    reader.decodeWithState(bitmap)
                } finally {
                    reader.reset()
                }
            }
        } catch (_: NotFoundException) {
            return emptyList()
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Barcode decode failed: ${t.safeLabel()}", throwable = null)
            return emptyList()
        }

        return listOf(
            BarcodeAnchor(
                format = result.barcodeFormat.name,
                value = result.text,
                boundingBox = quadFromResultPoints(result, width, height),
                frameId = frameId,
            ),
        )
    }

    /**
     * Convert ZXing's `ResultPoint[]` (1 to 4 points depending on
     * symbology) into the canonical 8-float normalised quadrilateral.
     *
     * Returns `null` when:
     *   - the result has fewer than 2 points (1D symbologies that
     *     locate only a single line are not worth representing as
     *     a quadrilateral);
     *   - or the frame dimensions are zero (defensive).
     */
    private fun quadFromResultPoints(
        result: Result,
        width: Int,
        height: Int,
    ): FloatArray? {
        val points = result.resultPoints ?: return null
        if (points.size < 2 || width <= 0 || height <= 0) return null
        val wf = width.toFloat()
        val hf = height.toFloat()
        val out = FloatArray(8)
        // For 4-point detections (QR / DataMatrix), use the actual
        // four corners. For 2/3-point detections (most 1D symbologies),
        // fall back to a tight axis-aligned bbox around the available
        // points so consumers still get a useful overlay rectangle.
        if (points.size >= 4) {
            for (i in 0 until 4) {
                out[i * 2] = points[i].x / wf
                out[i * 2 + 1] = points[i].y / hf
            }
        } else {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (p in points) {
                if (p == null) continue
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            if (!minX.isFinite() || !maxX.isFinite()) return null
            // Clockwise from top-left.
            out[0] = minX / wf; out[1] = minY / hf
            out[2] = maxX / wf; out[3] = minY / hf
            out[4] = maxX / wf; out[5] = maxY / hf
            out[6] = minX / wf; out[7] = maxY / hf
        }
        // Clamp to [0,1] in case ZXing returned slightly-outside points
        // (which can happen near frame edges with the HybridBinarizer).
        for (i in out.indices) {
            if (out[i] < 0f) out[i] = 0f
            if (out[i] > 1f) out[i] = 1f
        }
        return out
    }

    companion object {
        private const val TAG = "BarcodeAnchorDetector"

        /**
         * Symbologies enabled by default. Tuned for receipt + retail
         * + logistics capture; excludes obscure / high-false-positive
         * formats like CODABAR and RSS-Expanded.
         */
        val DEFAULT_FORMATS: Set<BarcodeFormat> = setOf(
            // 1D — retail
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E,
            BarcodeFormat.ITF,
            // 1D — logistics
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93,
            // 2D
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
            BarcodeFormat.PDF_417,
        )
    }
}
