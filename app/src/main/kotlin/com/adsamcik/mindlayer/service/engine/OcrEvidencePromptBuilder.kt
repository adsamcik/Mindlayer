package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.OcrSessionConfig

/**
 * Pure-JVM Gemma prompt builder for OCR structured extraction.
 *
 * Produces the prompt text fed to [OcrLlmExtractor.extract] from a
 * single-frame [OcrEvidencePackage]. Not coupled to LiteRT-LM — the
 * builder is fully unit-testable and intentionally decoupled from the
 * inference runtime so the prompt template can be iterated on without
 * touching the engine binding.
 *
 * # Template shape (Strategy A — one frame per call)
 *
 * ```
 * <SYSTEM>
 * You are an extraction assistant. Read the OCR evidence below and
 * produce a single JSON object matching the SCHEMA. For each extracted
 * field, also include a `_confidence` field set to one of "low",
 * "medium", "high". Output ONLY raw JSON — no markdown fences, no prose.
 *
 * <MODE>: receipt
 *
 * <SCHEMA>
 * {"type":"object","properties":{...}}
 *
 * <BARCODES>
 * - format=EAN_13, value=4006381333931
 *
 * <OCR>
 * frame=42, lines=7, quality=0.82
 * 1. Total: $24.95
 * 2. ...
 *
 * <RESPONSE>
 * ```
 *
 * # Privacy
 *
 * The prompt text is user content. Callers MUST treat the returned
 * string as opaque user-data and never log it. The builder itself
 * does not log anything.
 *
 * # No PII redaction
 *
 * OCR text is forwarded verbatim — that is the whole point of the
 * extraction pass. Redaction would defeat the use case.
 */
object OcrEvidencePromptBuilder {

    /** Hard cap on prompt body length — generous but bounded. */
    const val MAX_PROMPT_CHARS: Int = 24_576

    /**
     * Build the prompt text for one frame.
     *
     * @param evidence the per-frame evidence package.
     * @return the prompt string; never null, never empty.
     */
    fun build(evidence: OcrEvidencePackage): String {
        val sb = StringBuilder(estimateInitialCapacity(evidence))

        sb.appendLine("<SYSTEM>")
        sb.appendLine(
            "You are an extraction assistant. Read the OCR evidence below " +
                "and produce a single JSON object matching the SCHEMA. For each " +
                "extracted field, also include a sibling `<field>_confidence` " +
                "set to one of \"low\", \"medium\", \"high\". Output ONLY raw " +
                "JSON — no markdown fences, no prose.",
        )
        sb.appendLine()

        sb.append("<MODE>: ").appendLine(modeLabel(evidence.mode))
        sb.appendLine()

        sb.appendLine("<SCHEMA>")
        sb.appendLine(evidence.outputSchemaJson)
        sb.appendLine()

        if (evidence.barcodeAnchors.isNotEmpty()) {
            sb.appendLine("<BARCODES>")
            for (b in evidence.barcodeAnchors) {
                sb.append("- format=").append(b.format)
                    .append(", value=").appendLine(b.value)
            }
            sb.appendLine()
        }

        sb.appendLine("<OCR>")
        sb.append("frame=").append(evidence.frameId)
            .append(", lines=").append(evidence.textLines.size)
            .append(", quality=").appendLine(formatQuality(evidence.frameQuality))
        for ((index, line) in evidence.textLines.withIndex()) {
            sb.append(index + 1).append(". ").appendLine(line.text)
        }
        sb.appendLine()

        sb.append("<RESPONSE>")

        return if (sb.length > MAX_PROMPT_CHARS) {
            // Truncate at the OCR section's end rather than mid-line to keep
            // the prompt grammatically intact.
            sb.substring(0, MAX_PROMPT_CHARS) + "\n<TRUNCATED>"
        } else {
            sb.toString()
        }
    }

    private fun estimateInitialCapacity(evidence: OcrEvidencePackage): Int {
        // System instruction + schema length + per-line + per-barcode.
        val base = 512 + evidence.outputSchemaJson.length
        val lines = evidence.textLines.sumOf { it.text.length + 6 }
        val barcodes = evidence.barcodeAnchors.sumOf { 24 + it.value.length }
        return base + lines + barcodes
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        OcrSessionConfig.MODE_GENERAL_DOCUMENT -> "general document"
        OcrSessionConfig.MODE_RECEIPT -> "receipt"
        OcrSessionConfig.MODE_ID_CARD -> "id card"
        OcrSessionConfig.MODE_WHITEBOARD -> "whiteboard"
        OcrSessionConfig.MODE_SCREEN_CAPTURE -> "screen capture"
        else -> "general document"
    }

    private fun formatQuality(quality: Double): String {
        // Two-decimal fixed format without locale issues.
        val scaled = (quality * 100.0).toInt()
        val whole = scaled / 100
        val frac = scaled % 100
        return if (frac < 10) "$whole.0$frac" else "$whole.$frac"
    }
}
