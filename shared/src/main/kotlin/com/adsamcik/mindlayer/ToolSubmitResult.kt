package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Tri-state outcome from `IMindlayerService.submitToolResultV2(...)`.
 *
 * v0.4 successor to `submitToolResult(...): void`, paralleling
 * [CancelResult] for the symmetric "is there an active request for this
 * caller?" check. Capability-gated via
 * [ServiceCapabilities.FEATURE_DETAILED_CANCEL].
 *
 * @property schemaVersion Wire-stable parcelable schema version. Currently `1`.
 * @property outcome One of [ACCEPTED], [NO_PENDING_CALL], [REQUEST_GONE].
 */
@Parcelize
data class ToolSubmitResult(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val outcome: Int,
) : Parcelable {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /**
         * The tool result was queued for the model. The inference flow
         * will resume and emit further [InferenceEvent] frames.
         */
        const val ACCEPTED: Int = 1

        /**
         * The request is active but no tool call is currently awaiting a
         * result with the given [callId]. Likely a stale `submitToolResult`
         * arriving after the model has already moved past that tool call,
         * or a typo in [callId]. The inference continues; this submission
         * is a no-op.
         */
        const val NO_PENDING_CALL: Int = 2

        /**
         * The request is no longer tracked by the service: it terminated
         * (success, error, cancel) or never existed for this caller.
         * **Anti-enumeration**: collapses "never existed" with "belongs to
         * another UID" the same way [CancelResult.UNKNOWN] does.
         */
        const val REQUEST_GONE: Int = 0

        fun isKnownOutcome(outcome: Int): Boolean =
            outcome == ACCEPTED || outcome == NO_PENDING_CALL || outcome == REQUEST_GONE
    }
}
