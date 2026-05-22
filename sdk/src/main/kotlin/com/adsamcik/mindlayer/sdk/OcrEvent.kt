package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrSessionState

/**
 * Events emitted from the service over the ``mindlayer.stream.ocr.v1``
 * pipe attached via [OcrSession.events].
 *
 * Mirrors the wire-stable [com.adsamcik.mindlayer.shared.StreamEventType]
 * constants 1-to-1 so the [TokenStreamReader] sibling for OCR can map
 * raw protocol events to this sealed hierarchy with no lossy translation.
 *
 * # Order guarantees
 *
 * The service emits events in causal order per session:
 *  - exactly one ``FrameReceived`` per ``pushOcrFrame`` call (modulo
 *    cancellation),
 *  - zero or more ``FrameRejectedQuality`` / ``FrameDroppedBusy``
 *    after that frame's intake decision,
 *  - if accepted: ``FrameProcessing`` -> 0..N ``FieldUpdate`` ->
 *    0..N ``FieldLocked`` -> ``FrameProcessed``,
 *  - any time: ``ResultSnapshot`` (the current fused output state)
 *    and ``ThrottleHint`` (advisory backpressure signal),
     *  - exactly one ``ResultFinalized`` per session, followed by terminal
     *    ``DONE`` and pipe close,
     *  - any time before ``DONE``: ``Error`` for terminal stream failures.
 *
 * # Privacy
 *
 * Field values in ``FieldUpdate`` / ``FieldLocked`` carry recognized
 * text — callers must apply the same redaction rules as
 * ``MindlayerLog`` if they log these. The [toString] implementation
 * uses ``<redacted:N>`` markers.
 */
sealed class OcrEvent {

    /** Service confirmed receipt of this frame's metadata. */
    data class FrameReceived(val frameId: Long) : OcrEvent()

    /** Service-side presort rejected the frame on quality grounds. */
    data class FrameRejectedQuality(val frameId: Long, val reason: String?) : OcrEvent()

    /** Intake queue saturated; caller should back off [retryAfterMs]. */
    data class FrameDroppedBusy(val frameId: Long, val retryAfterMs: Long) : OcrEvent()

    /** Recognition started on this frame. */
    data class FrameProcessing(val frameId: Long) : OcrEvent()

    /** Recognition completed. ``lineCount`` lines were extracted. */
    data class FrameProcessed(val frameId: Long, val lineCount: Int) : OcrEvent()

    /**
     * A field's best-value estimate changed.
     *
     * @property fieldName the JSON-Pointer-style field path
     *   (e.g. ``/total``, ``/items/0/price``).
     * @property topValue current best estimate.
     * @property confidence current fused confidence — verbalized:
     *   one of ``"low"`` / ``"medium"`` / ``"high"``.
     * @property consecutiveAgreement how many consecutive frames
     *   have agreed on ``topValue``; the field locks once this
     *   reaches the service's K-consecutive threshold.
     * @property boundingBox optional quadrilateral in normalised
     *   0..1 frame coordinates: ``[x1, y1, x2, y2, x3, y3, x4, y4]``
     *   clockwise from top-left. ``null`` when the service did not
     *   emit a bbox (e.g. older service, fused field without a
     *   single source line, or the engine config disabled bbox
     *   emission).
     */
    data class FieldUpdate(
        val fieldName: String,
        val topValue: String,
        val confidence: String,
        val consecutiveAgreement: Int,
        val boundingBox: FloatArray? = null,
    ) : OcrEvent() {
        override fun toString(): String =
            "FieldUpdate(field=$fieldName, value=<redacted:${topValue.length}>, " +
                "conf=$confidence, k=$consecutiveAgreement" +
                (if (boundingBox != null) ", bbox=quad" else "") +
                ")"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FieldUpdate) return false
            if (fieldName != other.fieldName) return false
            if (topValue != other.topValue) return false
            if (confidence != other.confidence) return false
            if (consecutiveAgreement != other.consecutiveAgreement) return false
            return when {
                boundingBox == null && other.boundingBox == null -> true
                boundingBox == null || other.boundingBox == null -> false
                else -> boundingBox.contentEquals(other.boundingBox)
            }
        }

        override fun hashCode(): Int {
            var result = fieldName.hashCode()
            result = 31 * result + topValue.hashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + consecutiveAgreement
            result = 31 * result + (boundingBox?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * A field's K-consecutive-agreement threshold was crossed; the
     * field's value is now locked. The service stops spending decode
     * tokens on this field until a frame contradicts it.
     *
     * @property boundingBox same semantics as [FieldUpdate.boundingBox].
     */
    data class FieldLocked(
        val fieldName: String,
        val topValue: String,
        val boundingBox: FloatArray? = null,
    ) : OcrEvent() {
        override fun toString(): String =
            "FieldLocked(field=$fieldName, value=<redacted:${topValue.length}>" +
                (if (boundingBox != null) ", bbox=quad" else "") +
                ")"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FieldLocked) return false
            if (fieldName != other.fieldName) return false
            if (topValue != other.topValue) return false
            return when {
                boundingBox == null && other.boundingBox == null -> true
                boundingBox == null || other.boundingBox == null -> false
                else -> boundingBox.contentEquals(other.boundingBox)
            }
        }

        override fun hashCode(): Int {
            var result = fieldName.hashCode()
            result = 31 * result + topValue.hashCode()
            result = 31 * result + (boundingBox?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Optional periodic snapshot of the full fused result.
     *
     * @property partialJson current fused result as a JSON string. May
     *   include nulls for fields not yet observed.
     */
    data class ResultSnapshot(val partialJson: String) : OcrEvent() {
        override fun toString(): String =
            "ResultSnapshot(<redacted:${partialJson.length}>)"
    }

    /**
     * Final result. After this event the service emits no more
     * events for the session and closes the pipe.
     *
     * @property fullJson final fused result as a JSON string matching
     *   the session's ``outputSchemaJson``.
     */
    data class ResultFinalized(val fullJson: String) : OcrEvent() {
        override fun toString(): String =
            "ResultFinalized(<redacted:${fullJson.length}>)"
    }

    /** Terminal OCR stream failure. The flow emits this, then fails. */
    data class Error(val code: String, val message: String?) : OcrEvent()

    /**
     * Advisory hint that the caller should slow down ``pushOcrFrame``.
     * Independent of [FrameDroppedBusy] — sent **before** the queue
     * saturates so a well-behaved SDK can adapt.
     */
    data class ThrottleHint(val recommendedIntervalMs: Long) : OcrEvent()
}

/**
 * Helper that lifts a raw [OcrFrameAck] from a synchronous
 * ``pushOcrFrame`` call into the [OcrEvent] hierarchy for SDK callers
 * that want a single observation stream.
 */
fun OcrFrameAck.toEvent(): OcrEvent? = when (status) {
    OcrFrameAck.STATUS_ACCEPTED -> OcrEvent.FrameReceived(frameId)
    OcrFrameAck.STATUS_DROPPED_BUSY -> OcrEvent.FrameDroppedBusy(frameId, retryAfterMs)
    OcrFrameAck.STATUS_REJECTED_QUALITY -> OcrEvent.FrameRejectedQuality(frameId, reason = null)
    // STATUS_REJECTED_FINALIZED maps to no event — callers should
    // observe ResultFinalized that the service already sent for the
    // session, and stop pushing.
    OcrFrameAck.STATUS_REJECTED_FINALIZED -> null
    else -> null
}

/** Status code helpers (for SDKs that want to handle [OcrFrameAck] inline). */
fun OcrFrameAck.isAccepted(): Boolean = status == OcrFrameAck.STATUS_ACCEPTED
fun OcrFrameAck.isDroppedBusy(): Boolean = status == OcrFrameAck.STATUS_DROPPED_BUSY
fun OcrFrameAck.isRejectedQuality(): Boolean = status == OcrFrameAck.STATUS_REJECTED_QUALITY
fun OcrFrameAck.isRejectedFinalized(): Boolean = status == OcrFrameAck.STATUS_REJECTED_FINALIZED
fun OcrFrameAck.isRejectedStreamNotAttached(): Boolean = status == OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED

/** Phase predicates on [OcrSessionState] for ergonomic SDK code. */
fun OcrSessionState.isActive(): Boolean = phase == OcrSessionState.PHASE_ACTIVE
fun OcrSessionState.isFinalizing(): Boolean = phase == OcrSessionState.PHASE_FINALIZING
fun OcrSessionState.isFinalized(): Boolean = phase == OcrSessionState.PHASE_FINALIZED
fun OcrSessionState.isClosed(): Boolean = phase == OcrSessionState.PHASE_CLOSED
fun OcrSessionState.isTerminal(): Boolean = phase >= OcrSessionState.PHASE_FINALIZED
