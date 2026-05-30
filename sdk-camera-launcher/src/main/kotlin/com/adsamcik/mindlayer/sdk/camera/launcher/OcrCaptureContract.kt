package com.adsamcik.mindlayer.sdk.camera.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.os.BundleCompat

/**
 * [ActivityResultContract] that launches the Mindlayer-owned OCR
 * capture activity and returns a structured result.
 *
 * # Usage
 *
 * ```kotlin
 * class MyFragment : Fragment() {
 *     private val ocrLauncher: ActivityResultLauncher<OcrCaptureRequest> =
 *         registerForActivityResult(OcrCaptureContract()) { result ->
 *             when (result) {
 *                 is OcrCaptureResult.Async ->
 *                     handleScannedText(result.result.text)
 *                 is OcrCaptureResult.Realtime ->
 *                     handleStructuredJson(result.finalJson)
 *                 OcrCaptureResult.Cancelled -> { /* user backed out */ }
 *                 is OcrCaptureResult.Error ->
 *                     showError(result.code, result.message)
 *             }
 *         }
 *
 *     private fun scanReceipt() {
 *         ocrLauncher.launch(
 *             OcrCaptureRequest(
 *                 mode = OcrCaptureMode.Async,
 *                 profileId = OcrProfileId.Receipt,
 *                 runLlmExtraction = true,
 *             )
 *         )
 *     }
 * }
 * ```
 *
 * # Wire stability
 *
 * The intent extras are an internal protocol between this contract and
 * [OcrCaptureActivity]. Do NOT key off [EXTRA_REQUEST] / [EXTRA_RESULT]
 * from outside this module. Both extras are versioned via
 * [OcrCaptureRequest.schemaVersion]; bumping requires coordinating both
 * sides.
 *
 * # Process death
 *
 * The system may kill the consumer process while the capture activity
 * is in front. On restoration, [parseResult] is still called with the
 * original request id by the framework — the request payload is held
 * in the saved instance state of [OcrCaptureActivity] and is replayed
 * from there, so consumers don't have to do anything special.
 */
class OcrCaptureContract : ActivityResultContract<OcrCaptureRequest, OcrCaptureResult>() {

    override fun createIntent(context: Context, input: OcrCaptureRequest): Intent =
        Intent(context, OcrCaptureActivity::class.java)
            .putExtra(EXTRA_REQUEST, input)
            .putExtra(EXTRA_SCHEMA_VERSION, input.schemaVersion)

    override fun parseResult(resultCode: Int, intent: Intent?): OcrCaptureResult {
        // The activity always sets RESULT_OK on a structured success/cancel/error
        // (with the result attached as an extra). RESULT_CANCELED is what the
        // framework gives us when the user kills the activity through the back
        // gesture without the activity setting a result; treat that as a clean
        // cancel.
        if (resultCode != Activity.RESULT_OK) return OcrCaptureResult.Cancelled
        val extras = intent?.extras ?: return OcrCaptureResult.Cancelled
        val parsed = BundleCompat.getParcelable(extras, EXTRA_RESULT, OcrCaptureResult::class.java)
        return parsed ?: OcrCaptureResult.Error(
            code = OcrCaptureResult.Error.CAMERA_INIT_FAILED,
            message = "Missing capture result extra",
        )
    }

    companion object {
        /** Intent extra key carrying the [OcrCaptureRequest] from contract → activity. */
        const val EXTRA_REQUEST: String = "com.adsamcik.mindlayer.sdk.camera.launcher.REQUEST"

        /** Intent extra key carrying the [OcrCaptureResult] from activity → contract. */
        const val EXTRA_RESULT: String = "com.adsamcik.mindlayer.sdk.camera.launcher.RESULT"

        /**
         * Intent extra key carrying [OcrCaptureRequest.schemaVersion] redundantly
         * so the activity can fast-fail on an incompatible launcher version
         * without unmarshalling the whole request. Wire-stable.
         */
        const val EXTRA_SCHEMA_VERSION: String = "com.adsamcik.mindlayer.sdk.camera.launcher.SCHEMA_VERSION"
    }
}

/**
 * Convenience type alias matching the JetBrains style: callers can
 * type `OcrCaptureLauncher` instead of the long generic.
 */
typealias OcrCaptureLauncher = ActivityResultLauncher<OcrCaptureRequest>
