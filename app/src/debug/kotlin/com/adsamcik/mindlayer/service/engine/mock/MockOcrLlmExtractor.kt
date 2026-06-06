package com.adsamcik.mindlayer.service.engine.mock

import com.adsamcik.mindlayer.service.engine.OcrEvidencePackage
import com.adsamcik.mindlayer.service.engine.OcrExtractedField
import com.adsamcik.mindlayer.service.engine.OcrExtractionResult
import com.adsamcik.mindlayer.service.engine.OcrFieldFusion
import com.adsamcik.mindlayer.service.engine.OcrLlmExtractor

/**
 * DEBUG-only mock [OcrLlmExtractor] for the "CI mock engines" mode.
 *
 * Replaces the production Gemma structured extractor so the single-image
 * OCR→LLM extraction path returns a small, deterministic, plausible result on
 * a runner that has no Gemma model. Every field is `[mock]`-tagged so consumers
 * never mistake mock output for a real extraction. Returns
 * [OcrExtractionResult.EMPTY] when the frame had no recognised lines, matching
 * the real extractor's "nothing extractable" contract.
 */
internal class MockOcrLlmExtractor : OcrLlmExtractor {

    override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult {
        if (evidence.textLines.isEmpty()) return OcrExtractionResult.EMPTY
        val fields = listOf(
            OcrExtractedField(
                name = "mock_status",
                value = "[mock] ok",
                confidence = OcrFieldFusion.Confidence.HIGH,
            ),
            OcrExtractedField(
                name = "mock_line_count",
                value = evidence.textLines.size.toString(),
                confidence = OcrFieldFusion.Confidence.MEDIUM,
            ),
        )
        return OcrExtractionResult(
            fields = fields,
            rawJson = "{\"mock_status\":\"[mock] ok\"}",
        )
    }
}
