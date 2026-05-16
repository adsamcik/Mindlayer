package com.adsamcik.mindlayer.service.engine

import android.content.Context
import com.adsamcik.mindlayer.EmbeddingTask
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Objects
import kotlin.math.sqrt

sealed class EmbeddingEngineState {
    object Idle : EmbeddingEngineState()
    object Initializing : EmbeddingEngineState()
    object Ready : EmbeddingEngineState()
    data class Failed(val cause: InitFailure) : EmbeddingEngineState()
}

data class EmbeddingOutput(
    val vector: FloatArray,
    val dim: Int,
    val modelId: String,
    val tokenCount: Int,
    val truncated: Boolean,
    val backend: String,
    val durationMs: Long,
) {
    override fun toString() =
        "EmbeddingOutput(dim=$dim, model=$modelId, tokens=$tokenCount, truncated=$truncated, backend=$backend, durationMs=$durationMs)"

    override fun equals(other: Any?): Boolean = this === other || (other is EmbeddingOutput &&
        dim == other.dim && modelId == other.modelId && tokenCount == other.tokenCount &&
        truncated == other.truncated && backend == other.backend && durationMs == other.durationMs)

    override fun hashCode(): Int = Objects.hash(dim, modelId, tokenCount, truncated, backend, durationMs)
}

/** Owns lazy embedding model discovery, backend init, and single-writer inference. */
class EmbeddingEngine(
    private val context: Context,
    private val backendFactory: () -> EmbeddingBackend = { LiteRtEmbeddingBackend(context) },
    private val logRepository: LogRepository? = null,
) {

    private val mutex = Mutex()
    private val backend: EmbeddingBackend by lazy(backendFactory)
    private val _state = MutableStateFlow<EmbeddingEngineState>(EmbeddingEngineState.Idle)

    val state: StateFlow<EmbeddingEngineState> = _state.asStateFlow()

    @Volatile
    private var lastInitFailure: InitFailure? = null

    @Volatile
    private var lastInitThrowable: Throwable? = null

    suspend fun initialize(preferredBackend: String? = null): EmbeddingModelInfo = mutex.withLock {
        initializeLocked(preferredBackend)
    }

    suspend fun embed(
        text: String,
        task: Int = EmbeddingTask.RETRIEVAL_DOCUMENT,
        outputDim: Int? = null,
        normalize: Boolean = true,
    ): EmbeddingOutput = mutex.withLock {
        embedLocked(text, task, outputDim, normalize)
    }

    suspend fun embedBatch(
        texts: List<String>,
        task: Int = EmbeddingTask.RETRIEVAL_DOCUMENT,
        outputDim: Int? = null,
        normalize: Boolean = true,
    ): List<EmbeddingOutput> = mutex.withLock {
        texts.map { text -> embedLocked(text, task, outputDim, normalize) }
    }

    suspend fun unloadForMemoryPressure() = mutex.withLock {
        backend.shutdown()
        lastInitFailure = null
        lastInitThrowable = null
        _state.value = EmbeddingEngineState.Idle
        MindlayerLog.i(TAG, "Embedding backend unloaded for memory pressure")
    }

    suspend fun shutdown() = mutex.withLock {
        backend.shutdown()
        lastInitFailure = null
        lastInitThrowable = null
        _state.value = EmbeddingEngineState.Idle
    }

    private suspend fun embedLocked(
        text: String,
        task: Int,
        outputDim: Int?,
        normalize: Boolean,
    ): EmbeddingOutput {
        if (text.isEmpty()) throw IllegalArgumentException("Empty text not supported for embedding")
        val model = initializeLocked(preferredBackend = null)
        val dim = outputDim ?: model.nativeDim
        require(dim in model.supportedDims) { "Unsupported embedding output dimension: $dim" }

        val prefixedText = EmbeddingTask.prefixFor(task) + text
        val startedNs = System.nanoTime()
        val tokens = backend.tokenize(prefixedText, model.maxContextTokens)
        val rawVector = backend.embed(tokens, outputDim, normalize)
        val vector = if (normalize) normalized(rawVector) else rawVector
        val durationMs = (System.nanoTime() - startedNs) / 1_000_000L

        MindlayerLog.i(
            TAG,
            "Embedding complete: model=${model.id}, dim=${vector.size}, tokens=${tokens.size}, backend=${backend.activeBackend}, durationMs=$durationMs",
        )
        return EmbeddingOutput(
            vector = vector,
            dim = vector.size,
            modelId = model.id,
            tokenCount = tokens.size,
            truncated = tokens.size >= model.maxContextTokens,
            backend = backend.activeBackend,
            durationMs = durationMs,
        )
    }

    private suspend fun initializeLocked(preferredBackend: String?): EmbeddingModelInfo {
        if (backend.isInitialized) {
            _state.value = EmbeddingEngineState.Ready
            return checkNotNull(backend.currentModel)
        }
        lastInitFailure?.let { throw cachedInitException() }

        _state.value = EmbeddingEngineState.Initializing
        return try {
            val model = EmbeddingModelRegistry.getDefaultModel(
                EmbeddingModelRegistry.discoverModels(context),
            ) ?: throw noEmbeddingModelFoundException()
            backend.initialize(model, preferredBackend)
            lastInitFailure = null
            lastInitThrowable = null
            _state.value = EmbeddingEngineState.Ready
            model
        } catch (t: Throwable) {
            val failure = classifyInitFailure(t)
            lastInitFailure = failure
            lastInitThrowable = t
            _state.value = EmbeddingEngineState.Failed(failure)
            logRepository?.logInitFailureCategorized(failure)
            MindlayerLog.w(TAG, "Embedding init failed: ${t.safeLabel()}", throwable = null)
            throw t
        }
    }

    private fun cachedInitException(): Throwable =
        lastInitThrowable ?: IllegalStateException("Embedding initialization previously failed")

    private fun classifyInitFailure(t: Throwable): InitFailure = when (t) {
        is LowMemoryException -> InitFailure.LowMemory
        is SecurityException -> InitFailure.IntegrityMismatch
        is IllegalStateException -> if (t.message?.contains("No embedding model", ignoreCase = true) == true) {
            InitFailure.ModelMissing
        } else {
            InitFailure.NativeError(t.safeLabel())
        }
        else -> InitFailure.NativeError(t.safeLabel())
    }

    private fun noEmbeddingModelFoundException(): IllegalStateException =
        IllegalStateException("No embedding model files found. Phase D adds the embedding Asset Pack.")

    private fun normalized(vector: FloatArray): FloatArray {
        var sum = 0f
        for (value in vector) sum += value * value
        if (sum == 0f) return vector
        val norm = sqrt(sum.toDouble()).toFloat()
        return FloatArray(vector.size) { index -> vector[index] / norm }
    }

    private companion object {
        private const val TAG = "EmbeddingEngine"
    }
}
