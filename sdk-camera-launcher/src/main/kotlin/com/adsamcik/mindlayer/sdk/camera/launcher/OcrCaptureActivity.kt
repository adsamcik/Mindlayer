package com.adsamcik.mindlayer.sdk.camera.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Mindlayer-owned camera capture activity.
 *
 * Started indirectly via [OcrCaptureContract.createIntent]; not exported
 * (consumers MUST go through the contract). Owns:
 *
 *  1. The runtime `CAMERA` permission request — host apps don't need
 *     to declare it themselves; the manifest-merger picks it up from
 *     this module.
 *  2. The Mindlayer SDK connection lifecycle for the duration of the
 *     capture — connects on resume, disconnects on destroy.
 *  3. The CameraX preview + analysis pipeline.
 *  4. The Compose UI.
 *  5. The result handoff back to [OcrCaptureContract.parseResult].
 *
 * # Saved state
 *
 * The full [OcrCaptureRequest] is saved to instance state via
 * [onSaveInstanceState] / restored in [onCreate]. The activity does not
 * persist any pixel data; on process death the user re-points the
 * camera.
 *
 * # Result protocol
 *
 * - Setting a result via [completeWith] always uses [Activity.RESULT_OK]
 *   so the contract's [OcrCaptureContract.parseResult] can distinguish
 *   "user backed out" (`RESULT_CANCELED`, framework-set) from "the
 *   activity finished with an explicit outcome" (`RESULT_OK`, including
 *   structured cancel/error). The result variant lives in the intent
 *   extras — see [OcrCaptureContract.EXTRA_RESULT].
 *
 * # Testing
 *
 * Direct UI tests need an instrumented harness (real CameraX + real
 * preview surface). For unit-test coverage of the wire protocol see
 * [com.adsamcik.mindlayer.sdk.camera.launcher.OcrCaptureContractParcelTest]
 * which round-trips every request/result variant through a [android.os.Parcel].
 */
class OcrCaptureActivity : ComponentActivity() {

    private val viewModel: OcrCaptureViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                return OcrCaptureViewModel(application) as T
            }
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = restoreRequest(savedInstanceState) ?: run {
            // Pathological — somebody started the activity without going
            // through the contract. Fail closed with an explicit error.
            completeWith(
                OcrCaptureResult.Error(
                    code = OcrCaptureResult.Error.CAMERA_INIT_FAILED,
                    message = "OcrCaptureActivity launched without request extra",
                ),
            )
            return
        }
        viewModel.initialise(request) { result -> completeWith(result) }

        setContent {
            val state by viewModel.state.collectAsState()
            OcrCaptureScreen(
                request = request,
                state = state,
                onRequestPermission = ::requestCameraPermission,
                onCancel = { viewModel.onCancel() },
                onCapture = { viewModel.onCapture() },
                onFinalize = { viewModel.onFinalize() },
                onAnalyzerReady = { lifecycleOwner, previewView ->
                    viewModel.bindCamera(this@OcrCaptureActivity, lifecycleOwner, previewView)
                },
            )
        }

        // Kick off the permission request immediately if not granted —
        // the activity is single-purpose, so there's no UI value in
        // making the user tap a button to ask for the permission they
        // implicitly opted into by triggering the launcher.
        ensureCameraPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.activeRequest()?.let { outState.putParcelable(KEY_REQUEST, it) }
    }

    override fun onDestroy() {
        viewModel.shutdown()
        super.onDestroy()
    }

    private fun restoreRequest(savedInstanceState: Bundle?): OcrCaptureRequest? {
        val source = savedInstanceState
            ?: intent?.extras
            ?: return null
        val key = if (savedInstanceState != null) KEY_REQUEST else OcrCaptureContract.EXTRA_REQUEST
        return BundleCompat.getParcelable(source, key, OcrCaptureRequest::class.java)
    }

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onCameraPermissionResult(granted)
        if (!granted) requestCameraPermission()
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun completeWith(result: OcrCaptureResult) {
        val data = Intent().putExtra(OcrCaptureContract.EXTRA_RESULT, result)
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private companion object {
        const val KEY_REQUEST: String = "ocr_capture_request"
    }
}
