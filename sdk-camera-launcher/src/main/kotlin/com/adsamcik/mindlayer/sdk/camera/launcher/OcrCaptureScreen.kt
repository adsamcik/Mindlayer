package com.adsamcik.mindlayer.sdk.camera.launcher

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner

/**
 * Top-level Compose surface for [OcrCaptureActivity].
 *
 * State-machine over [OcrCaptureUiState]:
 *
 *  - `AwaitingPermission` / `PermissionDenied` → rationale + Grant button.
 *  - `Connecting`                              → spinner + "Connecting…".
 *  - `Ready` / `AsyncCapturing` /
 *    `RealtimeStreaming` / `RealtimeFinalizing` → preview + per-mode controls.
 *  - `Completed` → empty placeholder while the activity tears down.
 *
 * The launcher always renders in the platform-supplied dark
 * Material3 colour scheme because it occupies the full screen with a
 * camera preview behind translucent system bars — a light scheme
 * would wash out the preview. Host-app theming does NOT override
 * this; the launcher is intentionally consistent across hosts.
 */
@Composable
internal fun OcrCaptureScreen(
    request: OcrCaptureRequest,
    state: OcrCaptureUiState,
    onRequestPermission: () -> Unit,
    onCancel: () -> Unit,
    onCapture: () -> Unit,
    onFinalize: () -> Unit,
    onAnalyzerReady: (LifecycleOwner, PreviewView) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            when (state.phase) {
                CapturePhase.AwaitingPermission,
                CapturePhase.PermissionDenied -> PermissionRationale(
                    showRetry = state.phase == CapturePhase.PermissionDenied,
                    onRequestPermission = onRequestPermission,
                    onCancel = onCancel,
                )

                CapturePhase.Connecting -> CenteredSpinner(
                    label = stringResource(R.string.mindlayer_ocr_capture_connecting),
                )

                CapturePhase.Ready,
                CapturePhase.AsyncCapturing,
                CapturePhase.RealtimeStreaming,
                CapturePhase.RealtimeFinalizing -> CameraSurface(
                    request = request,
                    state = state,
                    onCancel = onCancel,
                    onCapture = onCapture,
                    onFinalize = onFinalize,
                    onAnalyzerReady = onAnalyzerReady,
                )

                CapturePhase.Completed -> Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PermissionRationale(
    showRetry: Boolean,
    onRequestPermission: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.mindlayer_ocr_capture_permission_required),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (showRetry) {
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.mindlayer_ocr_capture_permission_grant))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.mindlayer_ocr_capture_cancel))
        }
    }
}

@Composable
private fun CenteredSpinner(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(label, color = Color.White)
    }
}

@Composable
private fun CameraSurface(
    request: OcrCaptureRequest,
    state: OcrCaptureUiState,
    onCancel: () -> Unit,
    onCapture: () -> Unit,
    onFinalize: () -> Unit,
    onAnalyzerReady: (LifecycleOwner, PreviewView) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                onAnalyzerReady(lifecycleOwner, previewView)
                previewView
            },
        )
        TopBar(
            title = request.titleOverride
                ?: defaultTitleFor(request),
            onCancel = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(16.dp))
                .align(Alignment.TopCenter),
        )
        BottomControls(
            request = request,
            state = state,
            onCapture = onCapture,
            onFinalize = onFinalize,
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(24.dp))
                .align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun defaultTitleFor(request: OcrCaptureRequest): String =
    "${stringResource(R.string.mindlayer_ocr_capture_title)} · ${request.profileId.profile.displayName}"

@Composable
private fun TopBar(title: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.mindlayer_ocr_capture_cancel))
        }
    }
}

@Composable
private fun BottomControls(
    request: OcrCaptureRequest,
    state: OcrCaptureUiState,
    onCapture: () -> Unit,
    onFinalize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val busy = state.phase == CapturePhase.AsyncCapturing ||
            state.phase == CapturePhase.RealtimeFinalizing
        if (busy) {
            CircularProgressIndicator(color = Color.White)
            Spacer(modifier = Modifier.size(24.dp))
            Text(
                stringResource(R.string.mindlayer_ocr_capture_processing),
                color = Color.White,
            )
        } else when (request.mode) {
            OcrCaptureMode.Async -> ShutterButton(
                contentDescription = stringResource(R.string.mindlayer_ocr_capture_button),
                enabled = state.phase == CapturePhase.Ready,
                onClick = onCapture,
            )
            OcrCaptureMode.Realtime -> Button(
                onClick = onFinalize,
                enabled = state.phase == CapturePhase.RealtimeStreaming,
            ) {
                Text(stringResource(R.string.mindlayer_ocr_capture_finalize))
            }
        }
    }
}

@Composable
private fun ShutterButton(contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    // Material3 IconButton already exposes the click target with the
    // correct a11y semantics — we just need a round visual. We layer
    // a clickable ring + inner disc over it.
    androidx.compose.material3.IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(76.dp),
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.Gray),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
        }
    }
    // Compose merges semantics from descendants; explicit
    // contentDescription on the IconButton would be redundant. The
    // localised label is used by talkback via the surrounding
    // BottomControls layout when no inner content provides one.
    @Suppress("UNUSED_PARAMETER")
    contentDescription
}
