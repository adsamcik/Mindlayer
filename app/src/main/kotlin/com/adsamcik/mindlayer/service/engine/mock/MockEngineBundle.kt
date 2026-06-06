package com.adsamcik.mindlayer.service.engine.mock

import com.adsamcik.mindlayer.service.engine.EmbeddingBackend
import com.adsamcik.mindlayer.service.engine.EmbeddingModelInfo
import com.adsamcik.mindlayer.service.engine.OcrLlmExtractor
import com.adsamcik.mindlayer.service.engine.PaddleOcrBackend

/**
 * Neutral holder for the DEBUG-only "CI mock engines" mode.
 *
 * The whole point of this type living in `src/main` is that production code
 * ([com.adsamcik.mindlayer.service.MindlayerMlService]) can reference it
 * unconditionally, while the *concrete* mock implementations stay in the
 * debug source set. Main code obtains a bundle (or `null`) from the
 * `mockEnginesOrNull(context)` seam:
 *
 *  - `app/src/debug/.../engine/mock/MockEngines.kt` returns a populated bundle
 *    when `BuildConfig.DEBUG` **and** the `debug.mindlayer.mock_engines` system
 *    property is `"1"`.
 *  - `app/src/release/.../engine/mock/MockEngines.kt` always returns `null`.
 *
 * Because the `Mock*` classes are physically absent from the release classpath,
 * release builds cannot enter mock mode even via reflection — mirroring the
 * `DebugAutoAccept` seam.
 *
 * # What this bundle carries (engine-mocks scope)
 *
 * This PR mocks only the **OCR**, **embeddings**, and **OCR→LLM structured
 * extraction** engines, all of which plug in through existing neutral
 * interfaces without touching the inference orchestrator or session manager:
 *
 *  - [embeddingBackendFactory] / [embeddingDefaultModel] — fed to
 *    [com.adsamcik.mindlayer.service.engine.EmbeddingEngine] and
 *    [com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator]. The engine's
 *    `isInitialized` fast-path lets the mock backend report `Ready` before any
 *    on-disk model discovery, and [embeddingDefaultModel] lights up
 *    `FEATURE_EMBEDDINGS` in `getCapabilities`.
 *  - [paddleOcrBackendFactory] — fed to
 *    [com.adsamcik.mindlayer.service.engine.PaddleOcrEngine]; the same
 *    fast-path flips `FEATURE_OCR_SESSION` / `FEATURE_OCR_IMAGE_ONESHOT`.
 *  - [ocrLlmExtractor] — replaces the production Gemma extractor so the
 *    single-image OCR→LLM extraction path returns plausible fields.
 *
 * The interactive LLM (chat / vision) mock is intentionally **out of scope**
 * here because it requires edits to the security-sensitive `createSession`
 * path; it ships as a focused follow-up.
 */
class MockEngineBundle(
    val embeddingBackendFactory: () -> EmbeddingBackend,
    val embeddingDefaultModel: EmbeddingModelInfo,
    val paddleOcrBackendFactory: () -> PaddleOcrBackend,
    val ocrLlmExtractor: OcrLlmExtractor,
)
