package com.adsamcik.mindlayer.service.engine

/**
 * Metadata for an installed PaddleOCR PP-OCRv5 mobile model set.
 *
 * Unlike [EmbeddingModelInfo] which carries a single ``.tflite`` + tokenizer
 * pair, PaddleOCR ships as a **bundle** of four files:
 *
 *  - ``detectionPath`` — text-line detection model
 *  - ``recognitionPath`` — text recognition model
 *  - ``classifierPath`` — orientation classifier (optional in some releases;
 *    when missing, the engine skips the rotation step)
 *  - ``dictionaryPath`` — character dictionary consumed by the rec head
 *
 * Mirrors [ModelInfo] / [EmbeddingModelInfo] semantics so the same UI /
 * diagnostics surfaces can render OCR models without special-casing.
 *
 * @property id stable identifier built from the detection model's stem
 *   (e.g. ``"paddleocr-ppocrv5-mobile"``).
 * @property displayName human-readable name shown in diagnostics.
 * @property detectionPath absolute path to the det ``.tflite``.
 * @property recognitionPath absolute path to the rec ``.tflite``.
 * @property classifierPath absolute path to the cls ``.tflite``, or null
 *   when the bundle ships without an orientation classifier.
 * @property dictionaryPath absolute path to the dictionary ``.txt``.
 * @property totalSizeBytes sum of the four file sizes — used for memory
 *   budgeting decisions.
 * @property detSha256 / recSha256 / clsSha256 / dictSha256 lowercase
 *   SHA-256 of each artifact when integrity verification ran; null when
 *   integrity verification was skipped (debuggable builds only).
 */
data class PaddleOcrModelInfo(
    val id: String,
    val displayName: String,
    val detectionPath: String,
    val recognitionPath: String,
    val classifierPath: String?,
    val dictionaryPath: String,
    val totalSizeBytes: Long,
    val detSha256: String?,
    val recSha256: String?,
    val clsSha256: String?,
    val dictSha256: String?,
) {
    /**
     * True when the orientation classifier shipped with this bundle. The
     * engine pipeline skips the rotation step when this is false.
     */
    val hasOrientationClassifier: Boolean get() = classifierPath != null
}
