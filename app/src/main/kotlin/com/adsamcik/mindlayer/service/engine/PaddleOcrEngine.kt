package com.adsamcik.mindlayer.service.engine

import android.content.Context
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
import com.adsamcik.mindlayer.service.modeldelivery.ModelFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Lifecycle states for [PaddleOcrEngine]. Mirrors
 * [EmbeddingEngineState] semantics so dashboards can render OCR
 * engine state with the same UI components.
 */
sealed class PaddleOcrEngineState {
    object Idle : PaddleOcrEngineState()
    object Initializing : PaddleOcrEngineState()
    object Ready : PaddleOcrEngineState()
    data class Failed(val cause: InitFailure) : PaddleOcrEngineState()
}

/**
 * Owns lazy PaddleOCR bundle discovery, backend init, and
 * single-writer inference.
 *
 * Mirrors [EmbeddingEngine] in shape so the rest of the service
 * (orchestrator, lifecycle, memory-pressure responder) can treat it
 * uniformly. Differences:
 *
 *  - Discovers a **bundle** (4 files) via [PaddleOcrModelRegistry]
 *    instead of a single model + tokenizer.
 *  - Inference takes a Y-plane + width + height (single frame)
 *    instead of a tokenized text string.
 *  - No batch entry — multi-frame fusion lives at a higher layer in
 *    [OcrSessionManager] (PR C3).
 *
 * # Threading model
 *
 * A single adaptive scheduler serialises every public method. Inference calls
 * queue shortest-frame-first once admitted; the higher-level [OcrSessionManager]
 * enforces single-flight per session and surfaces backpressure as
 * `OcrFrameAck.STATUS_DROPPED_BUSY` when the queue would grow.
 *
 * # Memory pressure
 *
 * [unloadForMemoryPressure] tears down the native backend without
 * losing the engine handle. Subsequent [recognise] calls re-init
 * lazily. This is the symmetric hook that [MemoryBudget] already
 * uses for [EmbeddingEngine].
 */
class PaddleOcrEngine(
    private val context: Context,
    private val logRepository: LogRepository? = null,
    private val backendFactory: () -> PaddleOcrBackend = {
        LiteRtPaddleOcrBackend(context, logRepository = logRepository)
    },
) {

    private val backend: PaddleOcrBackend by lazy(backendFactory)
    private val _state = MutableStateFlow<PaddleOcrEngineState>(PaddleOcrEngineState.Idle)
    private val scheduler = AdaptiveWorkloadScheduler(
        loadedAffinity = {
            if (_state.value is PaddleOcrEngineState.Ready) OCR_AFFINITY else null
        },
    )

    val state: StateFlow<PaddleOcrEngineState> = _state.asStateFlow()

    @Volatile
    private var lastInitFailure: InitFailure? = null

    @Volatile
    private var lastInitThrowable: Throwable? = null

    /**
     * Eagerly initialise the backend and return the resolved bundle.
     * Used by the service to advertise [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_SESSION]
     * only after the engine is confirmed ready (PR C3).
     */
    suspend fun initialize(preferredBackend: String? = null): PaddleOcrModelInfo =
        scheduler.run(lifecycleRequest()) { initializeLocked(preferredBackend) }

    /**
     * Retry a sticky native/backend initialization failure.
     *
     * The reset and fresh initialization are one mutex-atomic operation. A
     * concurrent retry that arrives after the first succeeds observes the
     * initialized fast path instead of tearing the backend down again.
     */
    suspend fun retryInitialize(preferredBackend: String? = null): PaddleOcrModelInfo =
        scheduler.run(lifecycleRequest()) {
        try {
            ModelDeliveryFileLock.withLockSuspending(context.filesDir, ModelFamily.OCR) {
                ModelDeliveryFileLock.requireAvailable(
                    context.filesDir,
                    ModelFamily.OCR,
                    lockHeld = true,
                )
                if (backend.isInitialized) {
                    _state.value = PaddleOcrEngineState.Ready
                    return@withLockSuspending checkNotNull(backend.currentBundle)
                }
                try {
                    backend.shutdown()
                } finally {
                    lastInitFailure = null
                    lastInitThrowable = null
                    _state.value = PaddleOcrEngineState.Idle
                }
                initializeWithDeliveryLockHeld(preferredBackend)
            }
        } catch (cancellation: CancellationException) {
            _state.value = PaddleOcrEngineState.Idle
            throw cancellation
        }
        }

    /**
     * Recognise a single Y-plane frame.
     *
     * Lazily initialises the backend on first call. Mutex-serialised
     * — concurrent recognise calls queue behind each other.
     */
    suspend fun recognise(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig = OcrEngineConfig(),
    ): OcrEngineOutput = scheduler.run(
        AdaptiveWorkloadScheduler.Request(
            affinityKey = OCR_AFFINITY,
            estimatedCost = safePixelCost(width, height),
        ),
    ) {
        ModelDeliveryFileLock.requireAvailable(context.filesDir, ModelFamily.OCR)
        require(width > 0 && height > 0) {
            "width and height must be positive (got $width x $height)"
        }
        require(yPlane.size == width * height) {
            "yPlane length ${yPlane.size} != width * height ${width * height}"
        }
        initializeLocked(preferredBackend = null)
        backend.recognise(yPlane, width, height, config)
    }

    /** Release native resources and reset initialization state. */
    suspend fun unloadForMemoryPressure() = scheduler.run(lifecycleRequest()) {
        backend.shutdown()
        lastInitFailure = null
        lastInitThrowable = null
        _state.value = PaddleOcrEngineState.Idle
        MindlayerLog.i(TAG, "PaddleOCR backend unloaded for memory pressure")
    }

    /** Full shutdown — release native resources + reset state. */
    suspend fun shutdown() = scheduler.run(lifecycleRequest()) {
        backend.shutdown()
        lastInitFailure = null
        lastInitThrowable = null
        _state.value = PaddleOcrEngineState.Idle
    }

    private suspend fun initializeLocked(preferredBackend: String?): PaddleOcrModelInfo {
        return try {
            ModelDeliveryFileLock.withLockSuspending(context.filesDir, ModelFamily.OCR) {
                initializeWithDeliveryLockHeld(preferredBackend)
            }
        } catch (cancellation: CancellationException) {
            _state.value = PaddleOcrEngineState.Idle
            throw cancellation
        }
    }

    private suspend fun initializeWithDeliveryLockHeld(preferredBackend: String?): PaddleOcrModelInfo {
        ModelDeliveryFileLock.requireAvailable(
            context.filesDir,
            ModelFamily.OCR,
            lockHeld = true,
        )
        if (backend.isInitialized) {
            _state.value = PaddleOcrEngineState.Ready
            return checkNotNull(backend.currentBundle)
        }
        lastInitFailure?.let { throw cachedInitException() }

        _state.value = PaddleOcrEngineState.Initializing
        return try {
            val bundle = PaddleOcrModelRegistry.getDefaultBundle(
                PaddleOcrModelRegistry.discoverBundles(context, deliveryLockHeld = true),
            ) ?: throw noPaddleOcrBundleFoundException()
            backend.initialize(bundle, preferredBackend)
            LiteRtAcceleratorResolver.latestDecision("ocr")?.let { decision ->
                logRepository?.logBackendDecision(
                    featureName = "ocr",
                    backend = decision.backend,
                    reason = decision.reason,
                    attempted = decision.attempted,
                )
            }
            lastInitFailure = null
            lastInitThrowable = null
            _state.value = PaddleOcrEngineState.Ready
            bundle
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val failure = classifyInitFailure(t)
            // ModelMissing and IntegrityMismatch can be remediated by a
            // completed on-demand delivery without restarting `:ml`.
            // Native backend errors remain sticky until an explicit unload.
            if (failure is InitFailure.NativeError || failure is InitFailure.BackendUnavailable) {
                lastInitFailure = failure
                lastInitThrowable = t
            }
            _state.value = PaddleOcrEngineState.Failed(failure)
            logRepository?.logInitFailureCategorized(failure, featureName = "ocr")
            MindlayerLog.w(TAG, "PaddleOCR init failed: ${t.safeLabel()}", diagnosticThrowable = t)
            throw t
        }
    }

    private fun cachedInitException(): Throwable =
        lastInitThrowable ?: IllegalStateException("PaddleOCR initialization previously failed")

    private fun classifyInitFailure(t: Throwable): InitFailure = when (t) {
        is LowMemoryException -> InitFailure.LowMemory
        is IOException -> InitFailure.ModelMissing
        is SecurityException -> InitFailure.IntegrityMismatch
        is IllegalStateException -> if (t.message?.contains("No PaddleOCR bundle", ignoreCase = true) == true) {
            InitFailure.ModelMissing
        } else {
            InitFailure.NativeError(t.safeLabel())
        }
        else -> InitFailure.NativeError(t.safeLabel())
    }

    private fun noPaddleOcrBundleFoundException(): IllegalStateException =
        IllegalStateException(
            "No PaddleOCR bundle files found. Download PaddleOCR from the Models screen " +
                "or sideload PP-OCRv5 mobile artifacts for development.",
        )

    private fun lifecycleRequest() = AdaptiveWorkloadScheduler.Request(
        affinityKey = OCR_AFFINITY,
        priority = AdaptiveWorkloadScheduler.MAX_PRIORITY,
        estimatedCost = 1,
    )

    private fun safePixelCost(width: Int, height: Int): Int =
        (width.toLong() * height.toLong())
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

    private companion object {
        private const val TAG = "PaddleOcrEngine"
        private const val OCR_AFFINITY = "paddleocr-ppocrv5-mobile"
    }
}
