package com.adsamcik.mindlayer.service.engine

/**
 * Pluggable structured-extraction seam used by [OcrRecognitionDispatcher].
 *
 * # Strategy A — reset-per-frame KV
 *
 * Each call MUST treat the supplied [evidence] as a fresh, self-contained
 * prompt. The dispatcher does NOT carry KV state across frames; cross-frame
 * agreement is handled downstream by [OcrFieldFusion].
 *
 * # Implementations
 *
 * - [NoOpOcrLlmExtractor] — default; returns an empty result. Used
 *   while the LiteRT-LM Gemma binding is being wired in a follow-up
 *   patch. The dispatcher ships with this default so the OCR pipeline
 *   keeps emitting per-line `ocr_field_update` events without LLM
 *   structured extraction.
 *
 * - A future `LiteRtLmGemmaOcrExtractor` will adapt the existing
 *   LiteRT-LM `Engine` / `Conversation` surface and inject the
 *   schema as a JSON-Schema-constrained one-shot inference. That
 *   landing requires Gemma prompt-iteration + golden-test validation
 *   and is intentionally NOT part of this Phase 2 #4 ship.
 *
 * # Privacy
 *
 * Implementations MUST:
 *  - Treat extracted field values as user content (no log lines, no
 *    crash-reporter, no `MindlayerLog.d/i/w/e(..., msg = result.text)`).
 *  - Never persist the prompt or the model output to `filesDir`,
 *    `cacheDir`, or external storage.
 *  - Log only `safeLabel()` on failure, never `Throwable.message` from
 *    native LiteRT-LM errors (those can embed prompt fragments).
 *
 * # Failure tolerance
 *
 * Implementations SHOULD return an empty [OcrExtractionResult] when the
 * model errors out, rather than throwing. The dispatcher already catches
 * any throw and silently skips the extraction emission for that frame —
 * but returning an empty result is cleaner and lets the dispatcher's
 * `OcrEvent.FrameProcessed` event still fire with `lineCount` reflecting
 * the OCR-stage output.
 */
interface OcrLlmExtractor {

    /**
     * Run structured extraction for one frame.
     *
     * @param evidence single-frame evidence package.
     * @return extracted fields with verbalized per-field confidence.
     *   Empty list means "this frame did not yield any new structured
     *   field observations" (the dispatcher still emits per-line
     *   `ocr_field_update` events from the raw OCR text).
     */
    suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult
}

/**
 * One extracted field from a single-frame structured-extraction pass.
 *
 * @property name dot-separated JSON pointer relative to the root of the
 *   caller's [com.adsamcik.mindlayer.OcrSessionConfig.outputSchemaJson]
 *   schema (e.g. `"total"`, `"items[0].sku"`, `"shipping.address.zip"`).
 *   The dispatcher uses this verbatim as the [OcrFieldFusion] field key.
 * @property value the extracted value, as the model wrote it (already
 *   trimmed by the extractor; the dispatcher treats it as opaque).
 * @property confidence the model's verbalized per-field confidence.
 *   Hops through [OcrFieldFusion.FieldObservation] to drive K-consecutive
 *   lock logic and per-value evidence weighting.
 */
data class OcrExtractedField(
    val name: String,
    val value: String,
    val confidence: OcrFieldFusion.Confidence,
) {
    init {
        require(name.isNotBlank()) { "field name must not be blank" }
    }

    override fun toString(): String =
        // Redact value — even structurally innocent strings can carry PII.
        "OcrExtractedField(name=$name, valueLen=${value.length}, conf=$confidence)"
}

/**
 * Result of a single [OcrLlmExtractor.extract] call.
 *
 * @property fields the structured fields extracted from this frame; may
 *   be empty if the model refused, timed out, or saw nothing extractable.
 * @property rawJson optional verbatim model output (the schema-shaped
 *   JSON object). Held so [OcrRecognitionDispatcher.finalize] can fall
 *   back to it when fusion produced no winners. May be `null` to opt out
 *   of the raw-JSON path entirely.
 */
data class OcrExtractionResult(
    val fields: List<OcrExtractedField>,
    val rawJson: String? = null,
) {
    companion object {
        val EMPTY = OcrExtractionResult(fields = emptyList(), rawJson = null)
    }

    override fun toString(): String =
        "OcrExtractionResult(fields=${fields.size}, hasRawJson=${rawJson != null})"
}

/**
 * Default no-op extractor. Returns [OcrExtractionResult.EMPTY] every call.
 *
 * Shipping this as the default keeps the dispatcher's Phase 2 #4 wire
 * surface (registration, evidence package construction, fusion
 * integration, finalize JSON) testable and exercised without requiring
 * the live LiteRT-LM Gemma binding to be in place.
 */
class NoOpOcrLlmExtractor : OcrLlmExtractor {
    override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult =
        OcrExtractionResult.EMPTY
}
