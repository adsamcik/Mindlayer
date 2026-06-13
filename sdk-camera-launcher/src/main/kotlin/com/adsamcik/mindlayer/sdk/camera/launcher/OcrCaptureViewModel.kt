package com.adsamcik.mindlayer.sdk.camera.launcher

import android.app.Application
import android.content.Context
import android.util.Size
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.adsamcik.mindlayer.sdk.ConnectionState
import com.adsamcik.mindlayer.sdk.JsonSchema
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.MindlayerException
import com.adsamcik.mindlayer.sdk.OcrEvent
import com.adsamcik.mindlayer.sdk.OcrHandle
import com.adsamcik.mindlayer.sdk.camerax.OcrImageAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * UI-facing phases the capture activity moves through.
 *
 * Owned by [OcrCaptureViewModel] so they survive config changes
 * (rotation, dark-mode flip). The activity's Compose UI observes
 * [OcrCaptureViewModel.state] and renders accordingly.
 */
internal enum class CapturePhase {
    /** Initial state — waiting for the runtime CAMERA permission decision. */
    AwaitingPermission,

    /** Permission denied (initially or post-rationale). UI offers retry / Settings. */
    PermissionDenied,

    /** Permission granted; binding [Mindlayer] + CameraX. */
    Connecting,

    /** Camera bound; preview live; ready for capture / streaming. */
    Ready,

    /** Async mode: capture button pressed; encoding + ocr { } in flight. */
    AsyncCapturing,

    /** Realtime mode: session open, frames being pushed. */
    RealtimeStreaming,

    /** Realtime mode: user pressed Done; waiting for the final event. */
    RealtimeFinalizing,

    /** Terminal — the activity is being torn down with a structured result. */
    Completed,
}

/** Snapshot of UI-visible state. Drives the Compose layer in [OcrCaptureScreen]. */
internal data class OcrCaptureUiState(
    val phase: CapturePhase = CapturePhase.AwaitingPermission,
    val framesPushed: Int = 0,
    val errorMessage: String? = null,
)

/**
 * ViewModel for [OcrCaptureActivity]. Owns the Mindlayer SDK
 * connection, the CameraX lifecycle, and the captured-frame
 * dispatch logic.
 *
 * The ViewModel scope outlives configuration changes; the camera is
 * unbound/rebound on the new lifecycle owner. The Mindlayer
 * connection survives configuration changes — only a final
 * [shutdown] (from [OcrCaptureActivity.onDestroy]) closes it.
 *
 * # Threading
 *
 * - Public methods (`onCapture`, `onFinalize`, `bindCamera`) are
 *   expected on the main thread.
 * - Heavy work (Mindlayer.connect, OCR requests, session interaction)
 *   is dispatched on the IO dispatcher via the ViewModel scope.
 * - The CameraX analyzer runs on a dedicated single-threaded
 *   executor — required by `ImageAnalysis.Analyzer` contract.
 */
internal class OcrCaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(OcrCaptureUiState())
    val state: StateFlow<OcrCaptureUiState> = _state.asStateFlow()

    @Volatile private var request: OcrCaptureRequest? = null
    @Volatile private var completer: ((OcrCaptureResult) -> Unit)? = null
    @Volatile private var permissionResolved: Boolean = false
    @Volatile private var mindlayer: Mindlayer? = null
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var session: OcrHandle.MultiFrame? = null
    private var analyzer: OcrImageAnalyzer? = null
    private var realtimeEventJob: Job? = null
    @Volatile private var finalJson: String? = null

    /** Returns the original [OcrCaptureRequest] for instance-state persistence. */
    fun activeRequest(): OcrCaptureRequest? = request

    /** Called once from [OcrCaptureActivity.onCreate] (and re-create after process death). */
    fun initialise(request: OcrCaptureRequest, completer: (OcrCaptureResult) -> Unit) {
        if (this.request != null) {
            // Re-attaching after a config change — replace the
            // completer (which captured the previous Activity ref)
            // with the new one, but keep state/connection intact.
            this.completer = completer
            return
        }
        this.request = request
        this.completer = completer
    }

    /** Called from the permission launcher callback and from initial permission probe. */
    @MainThread
    fun onCameraPermissionResult(granted: Boolean) {
        if (permissionResolved && _state.value.phase != CapturePhase.AwaitingPermission &&
            _state.value.phase != CapturePhase.PermissionDenied
        ) {
            // Permission changed mid-session (user opened Settings). Ignore
            // — the CameraX binding is the source of truth.
            return
        }
        permissionResolved = true
        if (granted) {
            _state.update { it.copy(phase = CapturePhase.Connecting) }
        } else {
            _state.update { it.copy(phase = CapturePhase.PermissionDenied) }
        }
    }

    /**
     * Bind CameraX preview + analysis / capture use-cases. Called from
     * the Compose layer once the [PreviewView] surface is created. Safe
     * to call multiple times — only the first binding succeeds; later
     * calls are no-ops (handled internally by [ProcessCameraProvider]).
     */
    fun bindCamera(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val request = request ?: return
        if (_state.value.phase != CapturePhase.Connecting) return
        viewModelScope.launch {
            try {
                ensureMindlayerConnected(context)
                val provider = withContext(Dispatchers.Main) {
                    ProcessCameraProvider.getInstance(context).await()
                }
                cameraProvider = provider
                bindCameraOnMain(provider, lifecycleOwner, previewView, request)
                _state.update { it.copy(phase = CapturePhase.Ready) }
                if (request.mode == OcrCaptureMode.Realtime) {
                    startRealtimeSession()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(
                    code = OcrCaptureResult.Error.CAMERA_INIT_FAILED,
                    message = e.message ?: "Camera initialisation failed",
                )
            }
        }
    }

    private suspend fun ensureMindlayerConnected(context: Context) {
        if (mindlayer != null && mindlayer?.connectionState?.value == ConnectionState.CONNECTED) return
        val client = mindlayer ?: Mindlayer.connect(context.applicationContext).also { mindlayer = it }
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            client.connectionState.first { it == ConnectionState.CONNECTED }
            true
        }
        if (ok != true) {
            throw MindlayerException(
                message = "Timed out connecting to Mindlayer after ${CONNECT_TIMEOUT_MS}ms",
                code = OcrCaptureResult.Error.SERVICE_CONNECT_TIMEOUT,
            )
        }
    }

    private suspend fun bindCameraOnMain(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        request: OcrCaptureRequest,
    ) = withContext(Dispatchers.Main) {
        provider.unbindAll()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        when (request.mode) {
            OcrCaptureMode.Async -> {
                val capture = ImageCapture.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                imageCapture = capture
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            }
            OcrCaptureMode.Realtime -> {
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                // The analyzer is bound in startRealtimeSession() once
                // the session is open.
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                pendingAnalysisUseCase = analysis
            }
        }
    }

    private var pendingAnalysisUseCase: ImageAnalysis? = null

    private suspend fun startRealtimeSession() {
        val request = request ?: return
        val client = mindlayer ?: return
        val analysis = pendingAnalysisUseCase ?: return
        val profile = request.profileId.profile
        val opened = client.ocrSession {
            profile(profile)
            languageHints(request.languageHints)
            if (request.maxFrames > 0) maxFrames(request.maxFrames)
            request.extractionSchemaJson?.let { extractWithLlm(JsonSchema.parse(it)) }
        }
        session = opened
        analyzer = OcrImageAnalyzer(opened).also {
            analysis.setAnalyzer(analyzerExecutor, it)
        }
        _state.update { it.copy(phase = CapturePhase.RealtimeStreaming) }
        realtimeEventJob = viewModelScope.launch {
            try {
                opened.events.collect { event ->
                    when (event) {
                        is OcrEvent.FrameReceived,
                        is OcrEvent.FrameProcessing,
                        is OcrEvent.FrameProcessed -> {
                            _state.update { it.copy(framesPushed = it.framesPushed + 1) }
                        }
                        is OcrEvent.ResultFinalized -> {
                            finalJson = event.fullJson
                            completeRealtime()
                        }
                        is OcrEvent.Error -> {
                            fail(
                                code = mapOcrErrorCode(event.code),
                                message = event.message ?: "OCR stream error: ${event.code}",
                            )
                        }
                        else -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MindlayerException) {
                fail(code = e.code, message = e.message ?: "OCR stream failed")
            } catch (e: Exception) {
                fail(
                    code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.UNKNOWN,
                    message = e.message ?: "OCR stream failed",
                )
            }
        }
    }

    /** Map textual OCR stream error codes to numeric [MindlayerErrorCode]s for the result. */
    private fun mapOcrErrorCode(code: String): Int = when (code.lowercase()) {
        "ocr_idle_timeout" -> com.adsamcik.mindlayer.shared.MindlayerErrorCode.OCR_IDLE_TIMEOUT
        "ocr_max_duration" -> com.adsamcik.mindlayer.shared.MindlayerErrorCode.OCR_MAX_DURATION
        "ocr_session_finalized" -> com.adsamcik.mindlayer.shared.MindlayerErrorCode.OCR_SESSION_FINALIZED
        "low_memory", "memory_pressure" -> com.adsamcik.mindlayer.shared.MindlayerErrorCode.LOW_MEMORY
        "thermal_critical" -> com.adsamcik.mindlayer.shared.MindlayerErrorCode.THERMAL_CRITICAL
        else -> com.adsamcik.mindlayer.shared.MindlayerErrorCode.UNKNOWN
    }

    @MainThread
    fun onCapture() {
        val request = request ?: return
        if (request.mode != OcrCaptureMode.Async) return
        if (_state.value.phase != CapturePhase.Ready) return
        val capture = imageCapture ?: return
        val client = mindlayer ?: return
        _state.update { it.copy(phase = CapturePhase.AsyncCapturing) }
        viewModelScope.launch {
            try {
                val proxy = takePicture(capture)
                val bytes = try {
                    withContext(Dispatchers.IO) {
                        CaptureFrameUtils.encodeImageProxyToJpeg(proxy)
                    }
                } finally {
                    proxy.close()
                }
                val result = client.ocr {
                    image(bytes, "image/jpeg")
                    if (request.emitBoundingBoxes) emitBoundingBoxes()
                    if (request.runLlmExtraction) {
                        request.extractionSchemaJson?.let { extractWithLlm(JsonSchema.parse(it)) }
                    }
                    languageHints(request.languageHints)
                }.awaitResult()
                complete(
                    OcrCaptureResult.Async(
                        fullJson = result.fullJsonString(),
                        extractionJson = result.extractionJsonString(),
                        totalDurationMs = result.metrics.totalDurationMs ?: 0L,
                        ocrDurationMs = result.metrics.ocrDurationMs ?: 0L,
                        llmDurationMs = result.metrics.llmDurationMs ?: 0L,
                        backend = result.metrics.backend,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: MindlayerException) {
                fail(code = e.code, message = e.message ?: "ocr failed")
            } catch (e: ImageCaptureException) {
                fail(
                    code = OcrCaptureResult.Error.CAMERA_INIT_FAILED,
                    message = e.message ?: "Image capture failed",
                )
            } catch (e: Exception) {
                fail(
                    code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.UNKNOWN,
                    message = e.message ?: "Capture failed",
                )
            }
        }
    }

    private suspend fun takePicture(capture: ImageCapture): ImageProxy =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            capture.takePicture(
                analyzerExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (cont.isActive) cont.resumeWith(Result.success(image))
                        else image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(exception))
                    }
                },
            )
        }

    @MainThread
    fun onFinalize() {
        val request = request ?: return
        if (request.mode != OcrCaptureMode.Realtime) return
        if (_state.value.phase != CapturePhase.RealtimeStreaming) return
        _state.update { it.copy(phase = CapturePhase.RealtimeFinalizing) }
        viewModelScope.launch {
            try {
                val result = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) {
                    session?.finalize()
                }
                if (_state.value.phase == CapturePhase.RealtimeFinalizing) {
                    if (result != null) {
                        finalJson = result.fullJsonString()
                    }
                    completeRealtime()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MindlayerException) {
                fail(code = e.code, message = e.message ?: "finalize failed")
            } catch (e: Exception) {
                fail(
                    code = com.adsamcik.mindlayer.shared.MindlayerErrorCode.UNKNOWN,
                    message = e.message ?: "finalize failed",
                )
            }
        }
    }

    @MainThread
    fun onCancel() {
        complete(OcrCaptureResult.Cancelled)
    }

    /** Called from [OcrCaptureActivity.onDestroy]. Releases CameraX and the SDK. */
    fun shutdown() {
        realtimeEventJob?.cancel()
        realtimeEventJob = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        try { mindlayer?.disconnect() } catch (_: Exception) {}
        mindlayer = null
        analyzerExecutor.shutdown()
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
    }

    private fun completeRealtime() {
        val framesPushed = _state.value.framesPushed
        complete(
            OcrCaptureResult.Realtime(
                finalJson = finalJson,
                framesPushed = framesPushed,
            ),
        )
    }

    private fun complete(result: OcrCaptureResult) {
        if (_state.value.phase == CapturePhase.Completed) return
        _state.update { it.copy(phase = CapturePhase.Completed) }
        completer?.invoke(result)
    }

    private fun fail(code: Int, message: String) {
        if (_state.value.phase == CapturePhase.Completed) return
        _state.update { it.copy(errorMessage = message) }
        complete(OcrCaptureResult.Error(code = code, message = message))
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS: Long = 10_000L
        const val FINALIZE_TIMEOUT_MS: Long = 15_000L
    }
}
