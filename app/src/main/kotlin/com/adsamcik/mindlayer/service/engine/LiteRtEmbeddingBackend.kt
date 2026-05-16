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
 * delegate wiring are therefore marked verify-on-device TODOs and the tokenizer
 * is a no-op stub. Phase D will add the Asset Pack; a follow-up will replace
 * [NoOpSentencePieceTokenizer] with a real tokenizer.
 *
 * GPU/NPU coexistence with LiteRT-LM is unverified. The backend chain is kept
 * local to this class so a process-wide accelerator mutex or MediaPipe fallback
 * remains a contained change after real-device validation.
 */
class LiteRtEmbeddingBackend(
    private val context: Context,
    private val memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
    private val tokenizerFactory: (EmbeddingModelInfo) -> SentencePieceTokenizer = {
        NoOpSentencePieceTokenizer
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
                // TODO(verifyOnDevice): create the base LiteRT Interpreter/CompiledModel
                // from model.modelPath and attach the selected delegate. Kept out of
                // Phase A execution because no embedding model/tokenizer is bundled.
                require(File(model.modelPath).isFile) { "Embedding model file missing" }
                require(File(model.tokenizerPath).isFile) { "Embedding tokenizer file missing" }
            }
            tokenizer = tokenizerFactory(model)
            loadedModel = model
            backendLabel = selectedBackend
            MindlayerLog.i(TAG, "Embedding backend initialized: model=${model.id}, backend=$selectedBackend")
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
        val model = loadedModel ?: throw IllegalStateException("Embedding backend is not initialized")
        val dim = outputDim ?: model.nativeDim
        require(dim in model.supportedDims) { "Unsupported embedding output dimension: $dim" }
        if (tokens.isEmpty()) return@withLock FloatArray(dim)
        // TODO(verifyOnDevice): invoke LiteRT and copy the f32 output tensor.
        throw UnsupportedOperationException("LiteRT embedding inference requires Phase D model validation")
    }

    override fun tokenize(text: String, maxTokens: Int): IntArray {
        val currentTokenizer = tokenizer
        return currentTokenizer.tokenize(text, maxTokens)
    }

    override suspend fun shutdown() = mutex.withLock {
        // TODO(verifyOnDevice): close native LiteRT Interpreter/Delegate handles here.
        loadedModel = null
        tokenizer = NoOpSentencePieceTokenizer
        backendLabel = "NONE"
    }

    private fun checkMemoryHeadroom(model: EmbeddingModelInfo) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val requiredBytes = model.sizeBytes + memoryHeadroomBytes
        if (memInfo.availMem < requiredBytes) {
            val availMb = memInfo.availMem / BYTES_PER_MB
            val requiredMb = requiredBytes / BYTES_PER_MB
            throw LowMemoryException(availMb = availMb, requiredMb = requiredMb)
        }
    }

    private fun resolveBackend(preferredBackend: String?): String {
        val preferred = preferredBackend?.uppercase()
        return when {
            preferred == "CPU" -> "CPU"
            preferred == "GPU" -> {
                MindlayerLog.w(TAG, "Embedding GPU delegate not packaged in Phase A; falling back to CPU")
                "CPU"
            }
            preferred == "NPU" && isNpuLikelySupported() -> "NPU"
            preferred == "NPU" -> "CPU"
            isNpuLikelySupported() -> "NPU"
            else -> "CPU"
        }
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
        override fun tokenize(text: String, maxTokens: Int): IntArray {
            MindlayerLog.w(TAG, "Embedding tokenizer stub active; returning zero tokens")
            return IntArray(0)
        }
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

