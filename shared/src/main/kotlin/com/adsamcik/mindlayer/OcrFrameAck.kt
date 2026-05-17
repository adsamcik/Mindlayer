package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Synchronous acknowledgement returned by
 * [com.adsamcik.mindlayer.IMindlayerService.pushOcrFrame].
 *
 * Carries the intake-stage outcome only — the OCR *result* flows out via the
 * pipe attached through
 * [com.adsamcik.mindlayer.IMindlayerService.streamOcrEvents]. This synchronous
 * ack lets the SDK exert immediate backpressure on its CameraX analyzer
 * (drop a frame at the source instead of staging SharedMemory it can't use).
 *
 * Tri-state design mirrors the v0.4 detailed-cancel pattern in [CancelResult].
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property frameId Echo of [OcrFrameMeta.frameId] for correlation.
 * @property status One of [STATUS_ACCEPTED], [STATUS_DROPPED_BUSY],
 *   [STATUS_REJECTED_QUALITY], [STATUS_REJECTED_FINALIZED]. Unknown
 *   values (from a newer service to an older SDK) should be treated
 *   as "not accepted" and observable via the async pipe events.
 * @property queueDepth Service-side pending frames after this push (after
 *   accept; before processing). Useful for adaptive throttling.
 * @property retryAfterMs Hint for [STATUS_DROPPED_BUSY] indicating how long
 *   the caller should wait before retrying. `0` for non-busy statuses.
 * @property featureFlags Reserved bitfield for future toggles. v1 ignores all bits.
 */
@Parcelize
data class OcrFrameAck(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val frameId: Long,
    val status: Int,
    val queueDepth: Int = 0,
    val retryAfterMs: Long = 0L,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrFrameAck(frameId=$frameId, status=$status, queue=$queueDepth, retry=${retryAfterMs}ms)"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Frame was accepted into the engine intake queue. */
        const val STATUS_ACCEPTED: Int = 1

        /** Intake queue saturated; caller should retry after [retryAfterMs]. */
        const val STATUS_DROPPED_BUSY: Int = 2

        /** Service-side presort rejected the frame on quality grounds. */
        const val STATUS_REJECTED_QUALITY: Int = 3

        /**
         * Session has been finalized — no further frames accepted. Caller must
         * either open a new session or honor the previous final result.
         */
        const val STATUS_REJECTED_FINALIZED: Int = 4

        /** All currently-known status values. */
        val ALL_STATUSES: Set<Int> = setOf(
            STATUS_ACCEPTED,
            STATUS_DROPPED_BUSY,
            STATUS_REJECTED_QUALITY,
            STATUS_REJECTED_FINALIZED,
        )
    }
}
