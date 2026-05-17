package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Read-only snapshot of an OCR session's current state, polled via
 * [com.adsamcik.mindlayer.IMindlayerService.getOcrSessionState]. Useful for
 * dashboards, diagnostic UIs, and reconnect-after-process-death recovery.
 *
 * # Wire stability
 *
 * [schemaVersion] is the **first** field. Future fields go in [featureFlags]
 * (reserved bitfield) or `OcrSessionStateV2`.
 *
 * @property schemaVersion Wire-stable. Currently `1`.
 * @property sessionId The session id assigned at create time.
 * @property phase One of [PHASE_ACTIVE], [PHASE_FINALIZING], [PHASE_FINALIZED],
 *   [PHASE_CLOSED]. Unknown values (from a newer service) should be treated
 *   as "terminal-or-unknown".
 * @property framesAccepted Cumulative count of frames the engine has accepted.
 * @property framesDropped Cumulative count of frames dropped for backpressure
 *   ([OcrFrameAck.STATUS_DROPPED_BUSY]).
 * @property framesRejected Cumulative count of frames rejected on quality
 *   ([OcrFrameAck.STATUS_REJECTED_QUALITY]).
 * @property pendingQueueDepth Current intake queue depth at snapshot time.
 * @property streamAttached `true` if a caller has attached an event-pipe
 *   write end via `streamOcrEvents`.
 * @property createdAtMs Wall-clock time when the session was created
 *   (`SystemClock.elapsedRealtime`-equivalent service clock).
 * @property lastFrameAtMs Wall-clock time of the most recent accepted frame,
 *   or [createdAtMs] if no frames have been accepted yet. Used by the service
 *   to drive idle timeout.
 * @property featureFlags Reserved bitfield. v1 ignores all bits.
 */
@Parcelize
data class OcrSessionState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sessionId: String,
    val phase: Int,
    val framesAccepted: Int,
    val framesDropped: Int,
    val framesRejected: Int,
    val pendingQueueDepth: Int,
    val streamAttached: Boolean,
    val createdAtMs: Long,
    val lastFrameAtMs: Long,
    val featureFlags: Int = 0,
) : Parcelable {

    override fun toString(): String =
        "OcrSessionState(sessionId=$sessionId, phase=$phase, " +
            "accepted=$framesAccepted, dropped=$framesDropped, rejected=$framesRejected, " +
            "queue=$pendingQueueDepth, stream=$streamAttached)"

    companion object {
        /** Current parcelable schema version. Wire-stable. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Session is open and accepting frames. */
        const val PHASE_ACTIVE: Int = 1

        /** Caller invoked `finalizeOcrSession`; engine draining queued frames. */
        const val PHASE_FINALIZING: Int = 2

        /** Final result emitted; session no longer accepts frames. */
        const val PHASE_FINALIZED: Int = 3

        /** Session torn down (close, binder-death, idle timeout, max duration). */
        const val PHASE_CLOSED: Int = 4

        /** All currently-known phase values. */
        val ALL_PHASES: Set<Int> = setOf(
            PHASE_ACTIVE,
            PHASE_FINALIZING,
            PHASE_FINALIZED,
            PHASE_CLOSED,
        )
    }
}
