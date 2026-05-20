package com.adsamcik.mindlayer.service.engine

import android.content.Context
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * A single [Mutex] serialises every public method. Inference calls
 * therefore queue per-engine; the higher-level [OcrSessionManager]
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

    private val mutex = Mutex()
    private val backend: PaddleOcrBackend by lazy(backendFactory)
    private val _state = MutableStateFlow<PaddleOcrEngineState>(PaddleOcrEngineState.Idle)

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
    suspend fun initialize(preferredBackend: String? = null): PaddleOcrModelInfo = mutex.withLock {
        initializeLocked(preferredBackend)
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
    ): OcrEngineOutput = mutex.withLock {
        require(width > 0 && height > 0) {
            "width and height must be positive (got $width x $height)"
        }
        require(yPlane.size == width * height) {
            "yPlane length ${yPlane.size} != width * height ${width * height}"
        }
        initializeLocked(preferredBackend = null)
        backend.recognise(yPlane, width, height, config)
    }

    /**
     * Release native resources without invalidating cached init
     * failure state. Subsequent calls re-init from scratch.
     */
    suspend fun unloadForMemoryPressure() = mutex.withLock {
        backend.shutdown()
        lastInitFailure = null
        lastInitThrowable = null
        _state.value = PaddleOcrEngineState.Idle
        MindlayerLog.i(TAG, "PaddleOCR backend unloaded for memory pressure")
    }

    /** Full shutdown — release native resources + reset state. */
    suspend fun shutdown() = mutex.withLock {
        backend.shutdown()
        lastInitFailure = null
        lastInitThrowable = null
        _state.value = PaddleOcrEngineState.Idle
    }

    private suspend fun initializeLocked(preferredBackend: String?): PaddleOcrModelInfo {
        if (backend.isInitialized) {
            _state.value = PaddleOcrEngineState.Ready
            return checkNotNull(backend.currentBundle)
        }
        lastInitFailure?.let { throw cachedInitException() }

        _state.value = PaddleOcrEngineState.Initializing
        return try {
            val bundle = PaddleOcrModelRegistry.getDefaultBundle(
                PaddleOcrModelRegistry.discoverBundles(context),
            ) ?: throw noPaddleOcrBundleFoundException()
            backend.initialize(bundle, preferredBackend)
            lastInitFailure = null
            lastInitThrowable = null
            _state.value = PaddleOcrEngineState.Ready
            bundle
        } catch (t: Throwable) {
            val failure = classifyInitFailure(t)
            // LowMemory is transient: mirror EmbeddingEngine and do not poison
            // the process-lifetime init cache. On-disk/native failures remain sticky.
            if (failure !is InitFailure.LowMemory) {
                lastInitFailure = failure
                lastInitThrowable = t
            }
            _state.value = PaddleOcrEngineState.Failed(failure)
            logRepository?.logInitFailureCategorized(failure)
            MindlayerLog.w(TAG, "PaddleOCR init failed: ${t.safeLabel()}", throwable = null)
            throw t
        }
    }

    private fun cachedInitException(): Throwable =
        lastInitThrowable ?: IllegalStateException("PaddleOCR initialization previously failed")

    private fun classifyInitFailure(t: Throwable): InitFailure = when (t) {
        is LowMemoryException -> InitFailure.LowMemory
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
            "No PaddleOCR bundle files found. Install the paddleocr_model AI Pack " +
                "or sideload PP-OCRv5 mobile artifacts to filesDir.",
        )

    private companion object {
        private const val TAG = "PaddleOcrEngine"
    }
}
