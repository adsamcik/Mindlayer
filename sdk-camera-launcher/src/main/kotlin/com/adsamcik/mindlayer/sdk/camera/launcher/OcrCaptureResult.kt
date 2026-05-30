package com.adsamcik.mindlayer.sdk.camera.launcher

import android.os.Parcelable
import com.adsamcik.mindlayer.OcrImageResult
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.parcelize.Parcelize

/**
 * Result returned from [OcrCaptureContract].
 *
 * Sealed hierarchy so callers can `when` exhaustively over success,
 * cancellation, and error paths without inspecting an "ok" flag.
 *
 * # Privacy
 *
 * The success variants carry recognised text and structured fields. The
 * launcher never persists either; callers do whatever they like with
 * them. [toString] redacts string lengths only.
 */
sealed class OcrCaptureResult : Parcelable {

    /**
     * Successful single-image capture. The activity ran
     * [com.adsamcik.mindlayer.sdk.Mindlayer.ocrAsync] on the captured
     * frame and is returning the verbatim [OcrImageResult].
     *
     * @property result the underlying [com.adsamcik.mindlayer.OcrImageResult]
     *   from the AIDL call. Contains the recognised lines, optional
     *   bounding boxes, optional structured-extraction fields, and
     *   timing metadata.
     */
    @Parcelize
    data class Async(val result: OcrImageResult) : OcrCaptureResult() {
        override fun toString(): String = "OcrCaptureResult.Async(result=$result)"
    }

    /**
     * Successful multi-frame capture. The activity ran
     * [com.adsamcik.mindlayer.sdk.Mindlayer.ocrRealtime] and finalised
     * the session either on user tap of the "Done" button or on
     * convergence.
     *
     * @property finalJson the final fused JSON string from the session's
     *   `ResultFinalized` event. May be `null` when the session ended
     *   before the engine emitted a finalised result (rare — usually
     *   only when the capability flag is on but the engine wasn't
     *   actually wired yet at runtime).
     * @property framesPushed total number of frames the activity pushed
     *   to the session, including any rejected by the presort or service.
     *   Useful for diagnostics; do not surface as user-visible text.
     */
    @Parcelize
    data class Realtime(
        val finalJson: String?,
        val framesPushed: Int,
    ) : OcrCaptureResult() {
        override fun toString(): String =
            "OcrCaptureResult.Realtime(hasJson=${finalJson != null}, " +
                "json=${if (finalJson == null) "null" else "<redacted:${finalJson.length}>"}, " +
                "framesPushed=$framesPushed)"
    }

    /** User cancelled the capture (back gesture, system back, Cancel button). */
    @Parcelize
    data object Cancelled : OcrCaptureResult() {
        override fun toString(): String = "OcrCaptureResult.Cancelled"
    }

    /**
     * The capture failed before producing a result.
     *
     * @property code one of [MindlayerErrorCode]'s constants, plus a small
     *   set of launcher-local codes for CAMERA_PERMISSION_DENIED and
     *   CAMERA_INIT_FAILED. Callers may use [MindlayerErrorCode.codeName]
     *   to render a label.
     * @property message short human-readable label. Safe to log; never
     *   includes recognised text or stack traces.
     */
    @Parcelize
    data class Error(
        val code: Int,
        val message: String,
    ) : OcrCaptureResult() {
        override fun toString(): String =
            "OcrCaptureResult.Error(code=$code, message=$message)"

        companion object {
            // Launcher-local codes — outside the MindlayerErrorCode allocation
            // because they originate before the AIDL boundary is crossed.
            // Negative values are reserved by convention for SDK-side errors
            // that never round-trip through the service.

            /** User denied or revoked the runtime CAMERA permission. */
            const val CAMERA_PERMISSION_DENIED: Int = -1001

            /** CameraX failed to bind to the lifecycle (no compatible camera, hardware error). */
            const val CAMERA_INIT_FAILED: Int = -1002

            /** Mindlayer.connect() did not reach CONNECTED inside the timeout. */
            const val SERVICE_CONNECT_TIMEOUT: Int = -1003
        }
    }
}
