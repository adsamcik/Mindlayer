package com.adsamcik.mindlayer.sdk.vision

/**
 * Tri-state result of a Gemma vision detection call.
 *
 * The three states distinguish the *legitimate* outcomes of asking an
 * instruction-tuned vision model for structured output:
 *
 * - [Success]: the model produced parseable detections. May be empty
 *   (Gemma returned `[]` because no requested object was present).
 * - [NoStructuredOutput]: the model responded with prose, a refusal, or
 *   any other text that did not include a parseable JSON array. Typically
 *   happens when the prompt is ambiguous or the image is irrelevant to
 *   the requested labels.
 * - [ParseError]: a JSON array *was* found but the JSON itself is
 *   malformed at the top level (truncated, syntax error). [parsed] holds
 *   any well-formed entries that were recovered before the error, which
 *   is sometimes empty but lets callers degrade gracefully.
 *
 * The ergonomic helpers
 * [Mindlayer.detectObjects][com.adsamcik.mindlayer.sdk.detectObjects] /
 * [Mindlayer.locateObject][com.adsamcik.mindlayer.sdk.locateObject]
 * flatten this to a plain `List<DetectedObject>` (empty on either failure
 * branch) for one-line call sites. Advanced callers that need to detect
 * "model didn't comply" vs "no objects present" — e.g. a QA dashboard
 * watching for prompt-drift regressions — should use the `…Result`
 * variants and pattern-match on this type instead.
 */
sealed interface DetectionResult {
    /** Detections recovered from this response. Empty on either failure branch. */
    val objects: List<DetectedObject>

    /** Model produced a parseable JSON array (possibly with zero entries). */
    data class Success(override val objects: List<DetectedObject>) : DetectionResult

    /**
     * Model responded with non-JSON text (prose, refusal, etc.). [objects]
     * is always empty.
     */
    data object NoStructuredOutput : DetectionResult {
        override val objects: List<DetectedObject> = emptyList()
    }

    /**
     * A JSON array was found but failed to parse at the top level.
     *
     * @property message short, content-free diagnostic (e.g.
     *   `"invalid JSON: unexpected EOF"`). Safe to log — never includes
     *   model output or labels.
     * @property parsed any well-formed entries recovered before the error.
     */
    data class ParseError(
        val message: String,
        val parsed: List<DetectedObject> = emptyList(),
    ) : DetectionResult {
        override val objects: List<DetectedObject> get() = parsed
    }
}
