package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Per-frame metadata accompanying a [MediaPart] payload pushed via
 * [com.adsamcik.mindlayer.IMindlayerService.pushOcrFrame].
 *
 * Travels alongside the [MediaPart] so that the [MediaPart] contract stays
 * unchanged (per `docs/architecture/AIDL_STABILITY.md` parcelables are wire-frozen).
 *
 * # Wire stability
 *
 * [schemaVersion] is the **first** field and is currently `1`. Future
 * extensions use [featureFlags] (reserved bitfield) or the opaque [extraJson]
 * envelope. Adding fields to this parcelable is a wire break.
 *
 * # frameId monotonicity
 *
 * The caller supplies a monotonically-increasing [frameId] per OCR session
 * (`session.openTimeUnixMs` is a reasonable seed). The service rejects
 * non-monotonic IDs as
 * [com.adsamcik.mindlayer.shared.MindlayerErrorCode.OCR_SCHEMA_INVALID];
 * this prevents replay attacks and out-of-order frame staging.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property frameId Caller-monotonic id, scoped to the OCR session.
 * @property captureTimeMs Wall-clock time when the frame was captured
 *   on the caller's clock. Used for latency telemetry; the service does
 *   not validate it as authoritative.
 * @property rotationDegrees `0`, `90`, `180`, or `270`. Other values are rejected.
 * @property regionJson Optional ROI rectangle list as JSON array, e.g.
 *   `[{"x":0.1,"y":0.2,"w":0.8,"h":0.5}]` (normalized 0–1 coordinates).
 *   Bounded by [OcrLimits.ocrSchemaJsonMaxLen]. `null` = OCR the full frame.
 * @property qualityHint SDK-side presort hint so the service can skip
 *   redundant work. One of [QUALITY_UNKNOWN], [QUALITY_GOOD], [QUALITY_BLURRY],
 *   [QUALITY_TOO_DARK], [QUALITY_DUPLICATE]. Service treats it as advisory.
 * @property extraJson Opaque JSON envelope for forward-compatible per-frame data
 *   (gyro magnitude, frame-quality score breakdown, etc.). Bounded; parsed by
 *   the service; unknown keys ignored.
 * @property featureFlags Reserved bitfield for future toggles. v1 ignores all bits.
 */
@Parcelize
data class OcrFrameMeta(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val frameId: Long,
    val captureTimeMs: Long,
    val rotationDegrees: Int = 0,
    val regionJson: String? = null,
    val qualityHint: Int = QUALITY_UNKNOWN,
    val extraJson: String? = null,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrFrameMeta(frameId=$frameId, rot=$rotationDegrees, qHint=$qualityHint, " +
            "region=${if (regionJson == null) "null" else "<redacted:${regionJson.length}>"})"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Quality not measured / unknown. Default. */
        const val QUALITY_UNKNOWN: Int = 0

        /** Frame passed all SDK-side presort checks. */
        const val QUALITY_GOOD: Int = 1

        /** Frame failed blur threshold; caller submits anyway for fallback. */
        const val QUALITY_BLURRY: Int = 2

        /** Frame is underexposed / shadow-clipped. */
        const val QUALITY_TOO_DARK: Int = 3

        /** Frame is near-duplicate of the most-recently-processed frame. */
        const val QUALITY_DUPLICATE: Int = 4

        /** All currently-known quality hints. */
        val ALL_QUALITY_HINTS: Set<Int> = setOf(
            QUALITY_UNKNOWN,
            QUALITY_GOOD,
            QUALITY_BLURRY,
            QUALITY_TOO_DARK,
            QUALITY_DUPLICATE,
        )

        /** Allowed rotation values per CameraX `ImageInfo.rotationDegrees`. */
        val ALLOWED_ROTATIONS: Set<Int> = setOf(0, 90, 180, 270)
    }
}
