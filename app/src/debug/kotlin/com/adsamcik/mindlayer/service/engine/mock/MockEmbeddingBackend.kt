package com.adsamcik.mindlayer.service.engine.mock

import com.adsamcik.mindlayer.service.engine.EmbeddingBackend
import com.adsamcik.mindlayer.service.engine.EmbeddingModelInfo
import java.util.Random
import kotlin.math.sqrt

/**
 * DEBUG-only mock [EmbeddingBackend] for the "CI mock engines" mode.
 *
 * Reports `isInitialized = true` and a fixed [MODEL] so
 * [com.adsamcik.mindlayer.service.engine.EmbeddingEngine.initializeLocked]
 * takes its `isInitialized` fast-path and reaches `Ready` **without** scanning
 * for an on-disk EmbeddingGemma model. That lets a consumer app's CI exercise
 * the full embed wire path (AIDL → coordinator → SHM / deferred blobs) on a
 * runner that has no ~250 MB model installed.
 *
 * # Determinism contract
 *
 * Vectors are a pure function of the token ids:
 *  - the **same** text always yields the **same** vector (cosine 1.0), so a
 *    consumer's "cache hit / idempotency" assertions hold;
 *  - **different** text yields a near-orthogonal vector (cosine well below
 *    0.99 — two independent 768-d Gaussian unit vectors have expected cosine
 *    ≈ 0), so "distinct inputs → distinct embeddings" assertions hold.
 *
 * Matryoshka truncation is honoured: a requested `outputDim < nativeDim` is the
 * renormalised prefix of the native vector, matching real EmbeddingGemma MRL
 * behaviour closely enough for integration tests.
 *
 * No real text is logged or persisted — the backend holds nothing across calls.
 */
internal class MockEmbeddingBackend : EmbeddingBackend {

    override val activeBackend: String = "MOCK"
    override val isInitialized: Boolean = true
    override val currentModel: EmbeddingModelInfo = MODEL

    override suspend fun initialize(model: EmbeddingModelInfo, preferredBackend: String?) {
        // No-op: the mock is always "loaded".
    }

    override suspend fun embed(tokens: IntArray, outputDim: Int?, normalize: Boolean): FloatArray {
        val dim = outputDim ?: MODEL.nativeDim
        // Generate the full native vector deterministically, then take the
        // requested Matryoshka prefix so that smaller dims are a consistent
        // truncation of the larger one.
        val seed = tokens.fold(SEED_BASIS) { acc, t -> acc * 1_000_003L + t }
        val rnd = Random(seed)
        val native = FloatArray(MODEL.nativeDim) { rnd.nextGaussian().toFloat() }
        val sliced = if (dim >= native.size) native else native.copyOf(dim)
        if (normalize) l2NormalizeInPlace(sliced)
        return sliced
    }

    override fun tokenize(text: String, maxTokens: Int): IntArray {
        // Lossless-ish, deterministic, content-sensitive: one token per UTF-8
        // byte (clamped to maxTokens). Distinct text → distinct token streams →
        // distinct vectors, with no real tokenizer asset required.
        val bytes = text.toByteArray(Charsets.UTF_8)
        val count = if (maxTokens in 1 until bytes.size) maxTokens else bytes.size
        return IntArray(count) { bytes[it].toInt() and 0xFF }
    }

    override suspend fun shutdown() {
        // No-op.
    }

    private fun l2NormalizeInPlace(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val norm = sqrt(sum)
        if (norm > 0.0) {
            val inv = (1.0 / norm).toFloat()
            for (i in v.indices) v[i] = v[i] * inv
        }
    }

    companion object {
        private const val SEED_BASIS = 1_125_899_906_842_597L

        /**
         * Fixed fake model the mock advertises. `nativeDim` and `supportedDims`
         * mirror EmbeddingGemma's published Matryoshka dimensions so
         * capability-aware SDKs see realistic values. This is also handed to
         * [com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator] as the
         * `defaultModelOverride`, so coordinator-level `outputDim` / `modelId`
         * validation agrees with what this backend accepts.
         */
        val MODEL: EmbeddingModelInfo = EmbeddingModelInfo(
            id = "mock-embeddinggemma",
            displayName = "Mock EmbeddingGemma (CI)",
            modelPath = "/dev/null/mock-embeddinggemma.tflite",
            tokenizerPath = "/dev/null/mock-embeddinggemma.tokenizer",
            sizeBytes = 0L,
            nativeDim = 768,
            supportedDims = listOf(768, 512, 256, 128),
            maxContextTokens = 2048,
            sha256 = null,
        )
    }
}
