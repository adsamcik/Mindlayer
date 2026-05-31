package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Tri-state outcome from `IMindlayerService.cancelInferenceV2(...)`.
 *
 * v0.4 successor to the legacy `cancelInference(...): void`. The legacy
 * method silently swallowed "no such request" cases by design (F-007
 * anti-enumeration) — callers couldn't distinguish "I cancelled it" from
 * "it was already done" from "typo in the requestId". This Parcelable
 * exposes the distinction **without** leaking cross-UID information:
 * [UNKNOWN] covers both "never existed" and "owned by another UID".
 *
 * Capability-gated via [ServiceCapabilities.FEATURE_DETAILED_CANCEL].
 *
 * @property schemaVersion Wire-stable parcelable schema version. Currently `1`.
 * @property outcome One of [CANCELLED], [ALREADY_FINISHED], [UNKNOWN].
 */
@Parcelize
data class CancelResult(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val outcome: Int,
) : Parcelable {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /**
         * Request was active and has been cancelled. Native generation has
         * been stopped; the inference flow's terminal frame will be
         * `InferenceEvent.Done(finishReason="cancelled")` or an
         * `InferenceEvent.Error` if the cancel raced with completion.
         */
        const val CANCELLED: Int = 1

        /**
         * Request was tracked by the service in the recent past but has
         * already terminated (success, error, or earlier cancel) before
         * this call arrived. Caller should observe the inference flow's
         * terminal frame for the actual finish reason.
         */
        const val ALREADY_FINISHED: Int = 2

        /**
         * No record of [requestId] for this caller. **Anti-enumeration**:
         * this single outcome covers both "we never saw this requestId"
         * and "the requestId belongs to another UID's request".
         * Differentiating the two would leak cross-UID activity.
         */
        const val UNKNOWN: Int = 0

        /** True when [outcome] is one of the defined constants. */
        fun isKnownOutcome(outcome: Int): Boolean =
            outcome == CANCELLED || outcome == ALREADY_FINISHED || outcome == UNKNOWN
    }
}
