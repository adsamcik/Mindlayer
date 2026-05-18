package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase A LiteRT embedding backend scaffold.
 *
 * This class owns the lifecycle seam for the base `com.google.ai.edge.litert`
 * runtime, but Phase A deliberately ships without a bundled EmbeddingGemma
 * model or production SentencePiece binding. Native interpreter creation and
 * delegate wiring are therefore marked `TODO(verifyOnDevice)` and the
 * tokenizer is a no-op stub. Phase D will add the Asset Pack; a follow-up
 * will replace [NoOpSentencePieceTokenizer] with a real tokenizer binding.
 *
 * # What this PR ships
 *
 *  - The [EmbeddingBackend] interface contract
 *  - Lifecycle (initialize / shutdown) with the same threading + idempotence
 *    rules as [LiteRtPaddleOcrBackend]
 *  - Memory headroom enforcement at init time (model + working buffer)
 *  - Backend resolution chain (NPU → GPU → CPU) honouring caller preference
 *    as best-effort intent — the actual delegate label is overwritten at the
 *    `verifyOnDevice` point when native init succeeds.
 *  - `embed` implementation that **fails closed** with a precise
 *    [IllegalStateException] until a real-device follow-up wires the LiteRT
 *    [`CompiledModel`] / `Interpreter` path against a bundled model.
 *
 * # What is deferred
 *
 *  - Native LiteRT Interpreter / CompiledModel creation from `model.modelPath`
 *    + delegate attach (`GpuDelegate` for GPU, `QnnAccelerator` for NPU,
 *    XNNPACK / default for CPU).
 *  - f32 output-tensor copy and optional L2 normalisation post-process.
 *  - SentencePiece tokenizer binding (likely a small JNI wrapper or pure-Kotlin
 *    SentencePiece port — picked at the time the real `.spm.model` is bundled).
 *  - Real-device GPU/NPU coexistence with the LiteRT-LM (Gemma) runtime —
 *    same coexistence story as [LiteRtPaddleOcrBackend].
 *
 * Each deferred piece is marked with a `TODO(verifyOnDevice)` so a future
 * PR can grep for them and pick them up.
 */
class LiteRtEmbeddingBackend(
    private val context: Context,
    private val memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
    private val tokenizerFactory: (EmbeddingModelInfo) -> SentencePieceTokenizer = {
        NoOpSentencePieceTokenizer
    },
    private val availableMemoryProvider: () -> Long = {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            Long.MAX_VALUE
        } else {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        }
    },
) : EmbeddingBackend {

    private val mutex = Mutex()

    @Volatile
    private var loadedModel: EmbeddingModelInfo? = null

    @Volatile
    private var tokenizer: SentencePieceTokenizer = NoOpSentencePieceTokenizer

    @Volatile
    private var backendLabel: String = "NONE"

    override val activeBackend: String
        get() = backendLabel

    override val isInitialized: Boolean
        get() = loadedModel != null

    override val currentModel: EmbeddingModelInfo?
        get() = loadedModel

    override suspend fun initialize(
        model: EmbeddingModelInfo,
        preferredBackend: String?,
    ): Unit = mutex.withLock {
        loadedModel?.let { current ->
            if (current.id == model.id && current.modelPath == model.modelPath) return
        }

        checkMemoryHeadroom(model)
        val selectedBackend = resolveBackend(preferredBackend)
        try {
            withContext(Dispatchers.IO) {
                verifyArtifactFilesExist(model)
                // TODO(verifyOnDevice): create the base LiteRT
                // Interpreter/CompiledModel from model.modelPath and attach
                // the selected delegate. Kept out of execution here because
                // no embedding model/tokenizer is bundled yet; the bundle
                // lands via the embeddinggemma_model AI Pack once release
                // -PembeddingModelSha256 / -PembeddingTokenizerSha256 are
                // wired into CI alongside the real artifact upload.
            }
            tokenizer = tokenizerFactory(model)
            loadedModel = model
            backendLabel = selectedBackend
            if (tokenizer === NoOpSentencePieceTokenizer) {
                // One-time WARN at init time avoids per-call log spam from
                // the tokenize() path. Diagnostic surface is enough to
                // explain why downstream embed() calls will fail closed.
                MindlayerLog.w(
                    TAG,
                    "Embedding tokenizer stub active: real SentencePiece binding " +
                        "lands with the embeddinggemma_model artifact upload.",
                )
            }
            MindlayerLog.i(
                TAG,
                "Embedding backend ready: model=${model.id}, backend=$selectedBackend",
            )
        } catch (t: Throwable) {
            loadedModel = null
            tokenizer = NoOpSentencePieceTokenizer
            backendLabel = "NONE"
            MindlayerLog.w(TAG, "Embedding backend init failed: ${t.safeLabel()}", throwable = null)
            throw t
        }
    }

    override suspend fun embed(
        tokens: IntArray,
        outputDim: Int?,
        normalize: Boolean,
    ): FloatArray = mutex.withLock {
        val model = loadedModel ?: throw IllegalStateException(
            "Embedding backend not initialised; call initialize() first.",
        )
        val dim = outputDim ?: model.nativeDim
        require(dim in model.supportedDims) { "Unsupported embedding output dimension: $dim" }
        if (tokens.isEmpty()) return@withLock FloatArray(dim)
        // TODO(verifyOnDevice): invoke LiteRT and copy the f32 output
        // tensor, then optionally L2-normalise. Until that lands, fail
        // closed with a precise message so EmbeddingCoordinator (and the
        // ServiceBinder layer above it) can route through a typed
        // EMBEDDING_MODEL_UNAVAILABLE error to clients while debug builds
        // surface the stub state during development.
        throw IllegalStateException(
            "LiteRT embed() pipeline not yet wired — Phase A ships the scaffold only. " +
                "The Interpreter / CompiledModel + delegate wiring lands in a follow-up " +
                "after the embeddinggemma_model conversion artifacts are uploaded " +
                "(see plan.md § Embeddings pre-release gaps and the " +
                "TODO(verifyOnDevice) markers in this file).",
        )
    }

    override fun tokenize(text: String, maxTokens: Int): IntArray {
        val currentTokenizer = tokenizer
        return currentTokenizer.tokenize(text, maxTokens)
    }

    override suspend fun shutdown(): Unit = mutex.withLock {
        if (loadedModel == null) return
        try {
            withContext(Dispatchers.IO) {
                // TODO(verifyOnDevice): close native LiteRT Interpreter /
                // Delegate handles. Contract is idempotent so calling
                // shutdown on an unloaded backend is a no-op (handled by
                // the early return above).
            }
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Embedding shutdown error: ${t.safeLabel()}", throwable = null)
        } finally {
            loadedModel = null
            tokenizer = NoOpSentencePieceTokenizer
            backendLabel = "NONE"
        }
    }

    private fun checkMemoryHeadroom(model: EmbeddingModelInfo) {
        val available = availableMemoryProvider()
        val required = model.sizeBytes + memoryHeadroomBytes
        if (available < required) {
            throw LowMemoryException(
                availMb = available / BYTES_PER_MB,
                requiredMb = required / BYTES_PER_MB,
            )
        }
    }

    private fun verifyArtifactFilesExist(model: EmbeddingModelInfo) {
        require(File(model.modelPath).isFile) { "Embedding model file missing: ${model.modelPath}" }
        require(File(model.tokenizerPath).isFile) {
            "Embedding tokenizer file missing: ${model.tokenizerPath}"
        }
    }

    /**
     * Best-effort intent label for the chosen backend. The actual delegate
     * selection at the `verifyOnDevice` point may fall back to CPU when
     * GPU/NPU init fails on a given device. Mirrors [LiteRtPaddleOcrBackend]'s
     * resolveBackend so diagnostics across the two stacks read consistently.
     */
    private fun resolveBackend(preferredBackend: String?): String =
        when (preferredBackend?.uppercase()) {
            "CPU" -> "CPU"
            "GPU" -> "GPU"
            "NPU" -> if (isNpuLikelySupported()) "NPU" else "CPU"
            null -> when {
                isNpuLikelySupported() -> "NPU"
                else -> "CPU"
            }
            else -> "CPU"
        }

    private fun isNpuLikelySupported(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        @Suppress("InlinedApi")
        val soc = Build.SOC_MODEL.orEmpty().lowercase()
        if (soc !in QUALCOMM_NPU_SOCS && soc !in MEDIATEK_NPU_SOCS) return false
        val libDir = File(context.applicationInfo.nativeLibraryDir ?: return false)
        val libs = libDir.list().orEmpty()
        return libs.any { it.contains("HtpV", ignoreCase = true) || it.startsWith("libQnn") }
    }

    interface SentencePieceTokenizer {
        fun tokenize(text: String, maxTokens: Int): IntArray
    }

    object NoOpSentencePieceTokenizer : SentencePieceTokenizer {
        override fun tokenize(text: String, maxTokens: Int): IntArray = IntArray(0)
    }

    private companion object {
        private const val TAG = "LiteRtEmbeddingBackend"
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val MEMORY_HEADROOM_BYTES = 256L * BYTES_PER_MB
        private val QUALCOMM_NPU_SOCS = setOf(
            "sm8450", "sm8475", "sm8550", "sm8650", "sm8750", "sm8850",
        )
        private val MEDIATEK_NPU_SOCS = setOf(
            "mt6878", "mt6897", "mt6983", "mt6985", "mt6989", "mt6990", "mt6991",
        )
    }
}

