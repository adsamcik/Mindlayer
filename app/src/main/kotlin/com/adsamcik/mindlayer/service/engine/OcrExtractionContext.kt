package com.adsamcik.mindlayer.service.engine

/**
 * Per-OCR-session extraction context.
 *
 * Captures the caller's [com.adsamcik.mindlayer.OcrSessionConfig.outputSchemaJson]
 * and [com.adsamcik.mindlayer.OcrSessionConfig.mode] so the recognition
 * dispatcher can run a structured-extraction pass (Phase 2 #4) after each
 * frame's OCR recognition completes.
 *
 * Held by [OcrRecognitionDispatcher] keyed by sessionId. Registered when
 * the session is created in [OcrSessionManager.createSession] and removed
 * when the session is closed.
 *
 * @property mode One of the `OcrSessionConfig.MODE_*` constants (general
 *   document, receipt, ID card, whiteboard, screen capture). Used by the
 *   prompt builder to select a mode-specific system instruction.
 * @property outputSchemaJson Caller-supplied JSON schema describing the
 *   structured output. Stored verbatim — the schema text is opaque to the
 *   dispatcher and is forwarded to the LLM extractor.
 */
data class OcrExtractionContext(
    val mode: Int,
    val outputSchemaJson: String,
) {
    override fun toString(): String =
        "OcrExtractionContext(mode=$mode, schemaLen=${outputSchemaJson.length})"
}
