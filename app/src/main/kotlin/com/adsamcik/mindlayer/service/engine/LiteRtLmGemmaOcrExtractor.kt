package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.withTimeout

/**
 * LiteRT-LM Gemma-backed [OcrLlmExtractor] — Phase 3 #4.
 *
 * Replaces [NoOpOcrLlmExtractor] as the production default once the
 * service `MindlayerMlService` is constructed. Each call opens a
 * **fresh one-shot conversation** (Strategy A reset-per-frame KV),
 * sends the prompt built by [OcrEvidencePromptBuilder], reads the
 * response, and closes the conversation. No KV state survives between
 * calls; cross-frame agreement happens downstream in [OcrFieldFusion].
 *
 * # Failure tolerance
 *
 * - If [engineProvider] returns `null` (engine not ready / not
 *   installed), [extract] returns [OcrExtractionResult.EMPTY] without
 *   raising — the dispatcher's per-line + per-barcode fusion path
 *   keeps emitting events.
 * - Any [Throwable] from the conversation lifecycle is logged via
 *   `safeLabel()` only (no prompt fragments) and the call returns
 *   [OcrExtractionResult.EMPTY].
 * - The 30-second timeout in [extractionTimeoutMs] hard-caps a frame's
 *   wall-clock — `withTimeout` cancels the native generation cleanly
 *   so a hung Gemma call does not stall the dispatcher.
 *
 * # Privacy
 *
 * - The prompt text, the raw response text, and every parsed field
 *   value are user content. None of them are logged.
 * - Only the symbology of the prompt and the safeLabel of any
 *   failure ever reaches the log surface.
 *
 * # API surface uses `Any?` to keep unit tests JDK-17 compatible
 *
 * The LiteRT-LM `Engine` class (and friends — `Conversation`,
 * `ConversationConfig`, `SamplerConfig`, ...) is compiled to JVM
 * class-file v65 (JDK 21). Pure-JVM unit tests running on JDK 17
 * fail to load any of those classes with
 * `UnsupportedClassVersionError`. To keep this extractor's tests
 * purely-JVM:
 *
 *   1. This file imports **zero** LiteRT-LM symbols. Its class init
 *      therefore never triggers a transitive class load of v65
 *      bytecode.
 *   2. The public API types the engine as `Any?`; the cast to
 *      LiteRT-LM `Engine` happens inside the production
 *      conversation-runner — which lives in
 *      `LiteRtLmGemmaOcrExtractorProduction.kt` and is only loaded
 *      when that file is referenced (e.g. from
 *      `MindlayerMlService.onCreate`).
 *   3. Tests construct `LiteRtLmGemmaOcrExtractor` directly with a
 *      controllable lambda and never import the production helper.
 *
 * Production wiring sites use
 * [LiteRtLmGemmaOcrExtractorProduction.create] which assembles the
 * extractor with the real LiteRT-LM conversation runner.
 *
 * @param engineProvider returns the loaded LiteRT-LM `Engine` (typed
 *   as `Any?` for unit-test compatibility) when one is available;
 *   `null` when the engine has not been initialised yet, has been
 *   unloaded for memory pressure, or has settled into a `Failed`
 *   state.
 * @param conversationRunner the per-frame "open conversation -> send
 *   prompt -> receive response text -> close" lambda. Tests pass a
 *   controllable fake; production-mode callers pass the runner
 *   produced by
 *   [LiteRtLmGemmaOcrExtractorProduction.defaultConversationRunner].
 * @param extractionTimeoutMs hard wall-clock cap per frame. Phase 3
 *   target budget is 30 s/frame (per the OCR design); the default
 *   matches.
 */
class LiteRtLmGemmaOcrExtractor(
    private val engineProvider: () -> Any?,
    private val conversationRunner: suspend (engine: Any, prompt: String) -> String?,
    private val extractionTimeoutMs: Long = DEFAULT_EXTRACTION_TIMEOUT_MS,
) : OcrLlmExtractor {

    override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult {
        val engine = engineProvider() ?: run {
            // Common steady-state case on devices without the model
            // bundle. Quiet — keeping this as `i` would noise up logs
            // on every frame.
            return OcrExtractionResult.EMPTY
        }

        val prompt = try {
            OcrEvidencePromptBuilder.build(evidence)
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "Prompt build failed: ${t.safeLabel()}",
                sessionId = evidence.sessionId,
                throwable = null,
            )
            return OcrExtractionResult.EMPTY
        }

        val rawText = try {
            withTimeout(extractionTimeoutMs) {
                conversationRunner(engine, prompt)
            }
        } catch (t: Throwable) {
            // Includes TimeoutCancellationException + every native
            // LiteRT-LM throwable. NEVER include the prompt or the
            // partial response in the log — only the safeLabel of the
            // throwable.
            MindlayerLog.w(
                TAG,
                "Gemma OCR extraction failed: ${t.safeLabel()}",
                sessionId = evidence.sessionId,
                throwable = null,
            )
            return OcrExtractionResult.EMPTY
        } ?: return OcrExtractionResult.EMPTY

        return OcrLlmResponseParser.parse(rawText)
    }

    companion object {
        const val TAG: String = "LiteRtLmGemmaOcrExtractor"

        /**
         * Default 30-second wall-clock cap. The OCR design accepts ~30 s
         * per frame because the dispatcher pipelines frames behind the
         * camera's throttle and the engine itself is single-writer.
         */
        const val DEFAULT_EXTRACTION_TIMEOUT_MS: Long = 30_000L
    }
}
