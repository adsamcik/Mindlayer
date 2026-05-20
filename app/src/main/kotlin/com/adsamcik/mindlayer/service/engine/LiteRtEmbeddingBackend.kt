package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * Thin abstraction over the LiteRT [com.google.ai.edge.litert.CompiledModel]
 * pipeline used by [LiteRtEmbeddingBackend]. Exists for two reasons:
 *
 *  1. **Testability.** `CompiledModel` and its `TensorBuffer` companions
 *     load `liblitert.so` at class-init time. Robolectric / pure-JVM
 *     tests can't satisfy that link and throw `UnsatisfiedLinkError`
 *     the moment MockK touches the class. Routing the backend through
 *     a Kotlin-only interface lets tests inject a stub runner without
 *     triggering the native loader.
 *
 *  2. **Isolation of the native surface.** The embedding backend only
 *     uses a small slice of the LiteRT API (create / run / close,
 *     populate two int32 inputs, read one float32 output). Keeping the
 *     surface narrow means future LiteRT API churn only touches
 *     [RealLiteRtRunner], not the engine logic.
 */
internal interface LiteRtRunner {
    /**
     * Run embedding inference on a pre-padded `[seq_len]` input. Returns
     * the model's native-dim float32 output vector (no truncation, no
     * normalization — those are owned by the caller).
     */
    fun runEmbedding(inputIds: IntArray, attentionMask: IntArray): FloatArray

    /** Release native handles. Idempotent. */
    fun close()
}

/** Production factory signature: `(modelPath, acceleratorLabel) -> LiteRtRunner`. */
internal typealias LiteRtRunnerFactory = (String, String) -> LiteRtRunner

/**
 * Production LiteRT embedding backend for EmbeddingGemma-300M.
 *
 * # Pipeline
 *
 *  Tokens (from SentencePiece) → padded int32 `input_ids` + `attention_mask`
 *  → LiteRT `CompiledModel.run` → float32 `sentence_embedding` (native dim
 *  768) → optional Matryoshka truncate → optional L2-renormalize.
 *
 * # Lifecycle
 *
 *  - [initialize] creates a [LiteRtRunner] for the supplied
 *    [EmbeddingModelInfo.modelPath] with the resolved accelerator
 *    (NPU → GPU → CPU per [resolveBackend]), and a SentencePiece tokenizer
 *    from `tokenizerFactory` (defaults to [SentencePieceTokenizerFactory]
 *    which loads the Gemma `.spm.model` bundled in the same AI Pack).
 *  - [embed] is single-writer (mutex) over the [LiteRtRunner] because
 *    `CompiledModel.run` is not documented thread-safe and serialising
 *    is cheaper than per-call delegate handle juggling. Concurrent
 *    embed callers on the coordinator are throttled by the engine's
 *    own [EmbeddingEngine.mutex] one layer above, so this mutex is
 *    defense in depth.
 *  - [shutdown] closes the runner; idempotent.
 *
 * # Coexistence with chat (LiteRT-LM) + OCR (LiteRT) backends
 *
 * The three LiteRT runtimes share a single `:ml` process. As of
 * LiteRT 2.1.5 + LiteRT-LM 0.11.0 the explicit coexistence story is
 * unproven on real devices — see `docs/LITERT_COEXISTENCE.md` for the
 * validation checklist that must be run before this backend is enabled
 * in production. [EmbeddingFeatureFlags.IS_PRODUCTION_READY] gates the
 * AIDL surface on top of the checklist's completion.
 */
class LiteRtEmbeddingBackend internal constructor(
    private val context: Context,
    private val memoryHeadroomBytes: Long,
    private val tokenizerFactory: (EmbeddingModelInfo) -> SentencePieceTokenizer,
    private val availableMemoryProvider: () -> Long,
    private val runnerFactory: LiteRtRunnerFactory,
) : EmbeddingBackend {

    /** Production constructor: real CompiledModel + SentencePiece + system memory probe. */
    constructor(
        context: Context,
        memoryHeadroomBytes: Long = MEMORY_HEADROOM_BYTES,
    ) : this(
        context = context,
        memoryHeadroomBytes = memoryHeadroomBytes,
        tokenizerFactory = { model -> SentencePieceTokenizerFactory.load(model) },
        availableMemoryProvider = {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
                Long.MAX_VALUE
            } else {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                info.availMem
            }
        },
        runnerFactory = { modelPath, acceleratorLabel ->
            RealLiteRtRunner.create(modelPath, acceleratorLabel)
        },
    )

    private val mutex = Mutex()

    @Volatile
    private var loadedModel: EmbeddingModelInfo? = null

    @Volatile
    private var tokenizer: SentencePieceTokenizer = NoOpSentencePieceTokenizer

    @Volatile
    private var runner: LiteRtRunner? = null

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
            val newRunner = withContext(Dispatchers.IO) {
                verifyArtifactFilesExist(model)
                runnerFactory(model.modelPath, selectedBackend)
            }
            runner = newRunner
            tokenizer = tokenizerFactory(model)
            loadedModel = model
            backendLabel = selectedBackend
            if (tokenizer === NoOpSentencePieceTokenizer) {
                // One-time WARN at init time avoids per-call log spam from
                // the tokenize() path. The default factory now loads a real
                // SentencePiece tokenizer, so this warning fires only when
                // a test (or other injection point) supplies the No-Op
                // fallback — useful diagnostic, not a production state.
                MindlayerLog.w(
                    TAG,
                    "Embedding tokenizer stub active (test injection?); embed() will return zeros.",
                )
            }
            MindlayerLog.i(
                TAG,
                "Embedding backend ready: model=${model.id}, backend=$selectedBackend",
            )
        } catch (t: Throwable) {
            runner?.runCatching { close() }
            runner = null
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
        val activeRunner = runner ?: throw IllegalStateException(
            "Embedding backend not initialised; call initialize() first.",
        )
        val dim = outputDim ?: model.nativeDim
        require(dim in model.supportedDims) { "Unsupported embedding output dimension: $dim" }
        if (tokens.isEmpty()) return@withLock FloatArray(dim)

        return@withLock withContext(Dispatchers.IO) {
            runInference(model, activeRunner, tokens, dim, normalize)
        }
    }

    private fun runInference(
        model: EmbeddingModelInfo,
        activeRunner: LiteRtRunner,
        tokens: IntArray,
        dim: Int,
        normalize: Boolean,
    ): FloatArray {
        // EmbeddingGemma's exported .tflite expects two int32 inputs of
        // shape [1, seq_len] where seq_len is the value the model was
        // exported with (256 / 512 / 1024 / 2048). We pad to seq_len with
        // 0 (pad token) and mark padded positions as attention_mask = 0.
        val seqLen = model.maxContextTokens
        val effectiveLen = tokens.size.coerceAtMost(seqLen)
        val inputIds = IntArray(seqLen)
        val attentionMask = IntArray(seqLen)
        for (i in 0 until effectiveLen) {
            inputIds[i] = tokens[i]
            attentionMask[i] = 1
        }

        val raw = activeRunner.runEmbedding(inputIds, attentionMask)
        val truncated = if (dim < raw.size) raw.copyOfRange(0, dim) else raw
        return if (normalize) l2Normalize(truncated) else truncated
    }

    override fun tokenize(text: String, maxTokens: Int): IntArray {
        val currentTokenizer = tokenizer
        return currentTokenizer.tokenize(text, maxTokens)
    }

    override suspend fun shutdown(): Unit = mutex.withLock {
        if (loadedModel == null) return
        try {
            withContext(Dispatchers.IO) {
                runner?.runCatching { close() }
            }
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Embedding shutdown error: ${t.safeLabel()}", throwable = null)
        } finally {
            runner = null
            loadedModel = null
            tokenizer = NoOpSentencePieceTokenizer
            backendLabel = "NONE"
        }
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += x.toDouble() * x.toDouble()
        if (sumSq <= 0.0) return v
        val norm = sqrt(sumSq).toFloat()
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
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
     * selection in the underlying [LiteRtRunner] may fall back to CPU when
     * GPU/NPU init fails on a given device. Mirrors
     * [LiteRtPaddleOcrBackend]'s resolveBackend so diagnostics across
     * the two stacks read consistently.
     */
    private fun resolveBackend(preferredBackend: String?): String =
        LiteRtAcceleratorResolver.resolveBackend(
            requested = preferredBackend,
            featureName = "embeddings",
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        ).backend

    interface SentencePieceTokenizer {
        fun tokenize(text: String, maxTokens: Int): IntArray
    }

    object NoOpSentencePieceTokenizer : SentencePieceTokenizer {
        override fun tokenize(text: String, maxTokens: Int): IntArray = IntArray(0)
    }

    companion object {
        private const val TAG = "LiteRtEmbeddingBackend"
        private const val BYTES_PER_MB = 1024L * 1024L
        const val MEMORY_HEADROOM_BYTES = 256L * BYTES_PER_MB
        /**
         * Test-only constructor. Production code uses the public no-arg
         * `(Context)` constructor; tests inject all four collaborators so
         * neither LiteRT native libs nor the real SentencePiece file are
         * touched.
         */
        internal fun forTesting(
            context: Context,
            memoryHeadroomBytes: Long = 0L,
            tokenizerFactory: (EmbeddingModelInfo) -> SentencePieceTokenizer = { NoOpSentencePieceTokenizer },
            availableMemoryProvider: () -> Long = { Long.MAX_VALUE },
            runnerFactory: LiteRtRunnerFactory,
        ): LiteRtEmbeddingBackend = LiteRtEmbeddingBackend(
            context = context,
            memoryHeadroomBytes = memoryHeadroomBytes,
            tokenizerFactory = tokenizerFactory,
            availableMemoryProvider = availableMemoryProvider,
            runnerFactory = runnerFactory,
        )
    }
}
