package com.adsamcik.mindlayer.service.engine

/**
 * Compile-time feature flags for the embedding subsystem.
 *
 * # Why
 *
 * The embedding service ships its IPC surface, AI-Pack scaffolding, deferred
 * lifecycle, SHM transport, and capability gating in Phase A — but the real
 * LiteRT inference path (see `LiteRtEmbeddingBackend.embed`) is intentionally
 * a fail-closed stub until Phase D bundles a real `embedding-gemma-300m-v1`
 * model + SentencePiece tokenizer + on-device verification. The earlier
 * embedding API audit + the May 2026 research report both flagged that
 * advertising `FEATURE_EMBEDDINGS` while every backend call would throw is a
 * client-facing correctness bug: capability-aware SDKs would attempt embed,
 * be told the feature exists, and then crash on every call.
 *
 * # How [IS_PRODUCTION_READY] is used
 *
 *  - `ServiceBinder.getCapabilities()` reads it via the coordinator and
 *    keeps `FEATURE_EMBEDDINGS` out of `supportedFeatures` (and all
 *    `maxEmbedding*` numeric caps at zero) while it is `false`.
 *  - The AIDL methods (`embed`, `embedBatch`, …) still exist on the binder
 *    and return `EMBEDDING_DISABLED` when called against an off-state
 *    service — they do not become unreachable, just unadvertised. This
 *    preserves the AIDL surface for clients pinning specific methods.
 *  - When Phase D lands, flip this to `true` in the same commit that wires
 *    `LiteRtEmbeddingBackend.embed` to a real `CompiledModel` + tokenizer.
 *
 * # Why a flag and not a runtime probe
 *
 * The backend interface intentionally lazy-inits the native runtime. Probing
 * "would this backend produce real vectors?" by actually calling it has
 * side effects (delegate init, native memory allocation) that a cheap
 * capability check must not trigger. The flag is a compile-time pin that
 * matches the underlying implementation; tests inject an override via the
 * `EmbeddingCoordinator(isProductionReady = …)` constructor parameter.
 */
object EmbeddingFeatureFlags {

    /**
     * Phase A: **false**. Phase D will flip this in the same commit that
     * replaces `LiteRtEmbeddingBackend.embed`'s `IllegalStateException` with
     * a real LiteRT `CompiledModel` + tokenizer pipeline.
     *
     * # Phase D status (this commit)
     *
     * Flipped to **true**:
     *   - `LiteRtEmbeddingBackend` now wires a real
     *     `com.google.ai.edge.litert.CompiledModel` via [LiteRtRunner]
     *     (production impl: `RealLiteRtRunner`).
     *   - Tokenizer defaults to [SentencePieceTokenizerFactory] which
     *     loads the Gemma `.spm.model` shipped in the
     *     `embeddinggemma_model` AI Pack.
     *   - The AI Pack distribution + release SHA validation are wired
     *     end-to-end (see `:app:validateReleaseEmbeddingSha256` +
     *     `:embeddinggemma_model:generateEmbeddingModelIntegrityManifest`).
     *
     * Still requires before shipping a release:
     *   - Real-device coexistence validation per
     *     `docs/LITERT_COEXISTENCE.md` (LiteRT-LM + LiteRT + LiteRT-OCR
     *     all in the `:ml` process).
     *   - The two `-PembeddingModelSha256` / `-PembeddingTokenizerSha256`
     *     Gradle props plumbed through CI and the release-signing flow.
     *   - A first-party product driver (per the GPT-5.5 adversarial
     *     review) — e.g. a dashboard "search history" feature that
     *     actually consumes the embedding API.
     *
     * When false:
     *   - `ServiceCapabilities.supportedFeatures` does not contain
     *     `FEATURE_EMBEDDINGS`.
     *   - All `maxEmbedding*` numeric caps advertised to clients are zero.
     *   - `embeddingModelIds` / `embeddingDims` are empty.
     *
     * Search this constant when promoting the embedding subsystem to
     * production.
     */
    const val IS_PRODUCTION_READY: Boolean = true
}
