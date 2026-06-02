package com.adsamcik.mindlayer.sdk.vision

/**
 * Canonical prompt builders for Gemma 4 vision tasks.
 *
 * Each builder encodes one prompt shape from the Gemma vision documentation
 * (https://ai.google.dev/gemma/docs/capabilities/vision). The phrasings
 * follow the docs' two key best practices:
 *
 * 1. **Be specific**: name the task, name the labels, name the constraints.
 * 2. **Give constraints**: declare the output shape, the length budget,
 *    and what to do on ambiguity.
 *
 * Why a typed builder rather than freeform strings: a single source of
 * truth for prompt wording means a prompt-drift regression
 * (the model suddenly stops emitting `box_2d` JSON, for example) shows
 * up in one place — these builders are snapshot-tested for the *durable*
 * contract pieces (presence of `box_2d`, the `output only` JSON bias,
 * the label list), not exact wording.
 *
 * # Token-budget guidance
 *
 * Per the vision docs, Gemma 4 supports a token-budget knob that trades
 * resolution for speed (70/140/280/560/1120 visual tokens per image).
 * The Mindlayer SDK does **not** currently expose this knob — LiteRT-LM
 * 0.12.0 does not surface it through the Kotlin API. When it does, the
 * default per-task budget should be:
 *
 * - [detect] / [locate]: high (560 or 1120) — bounding-box accuracy
 *   depends on visual detail.
 * - [count]: high — counting also benefits from detail.
 * - [caption] / [describe]: medium (280) — descriptive prompts work
 *   well at moderate resolution and run faster.
 */
object VisionPrompts {

    // ---- Hard caps on prompt-shaping inputs ---------------------------------
    // These mirror the validation done by the calling helpers; documented
    // here so contributors editing prompts see the same caps.

    /** Maximum number of labels accepted by [detect]. */
    const val MAX_LABELS: Int = 32

    /** Maximum chars per label accepted by [detect]. */
    const val MAX_LABEL_LENGTH: Int = 64

    /** Maximum chars accepted by [locate] / [describe] / [count]. */
    const val MAX_FREEFORM_LENGTH: Int = 256

    /**
     * Canonical Gemma 4 object-detection prompt.
     *
     * The shape `"detect X, output only ```json"` is the exact form the
     * docs use (e.g. `"detect person and car, output only \`\`\`json"`)
     * and the form the model is most strongly trained on. Deviating from
     * this template tends to produce prose instead of the structured
     * `box_2d` array.
     *
     * @param labels object classes to detect; non-empty. Trimmed entries
     *   joined with `, ` (no Oxford comma).
     * @param maxObjects optional cap surfaced as an English clause; the
     *   parser also caps materialized output. Pass `null` for no cap.
     * @throws IllegalArgumentException if [labels] is empty, contains a
     *   blank entry, exceeds [MAX_LABELS], or contains a label longer
     *   than [MAX_LABEL_LENGTH].
     */
    fun detect(labels: List<String>, maxObjects: Int? = null): String {
        require(labels.isNotEmpty()) { "labels must not be empty" }
        require(labels.size <= MAX_LABELS) {
            "labels must have at most $MAX_LABELS entries (got ${labels.size})"
        }
        val cleaned = labels.map { it.trim() }
        require(cleaned.all { it.isNotEmpty() }) { "labels must not contain blank entries" }
        require(cleaned.all { it.length <= MAX_LABEL_LENGTH }) {
            "each label must be <= $MAX_LABEL_LENGTH chars"
        }
        val joined = cleaned.joinToString(", ")
        val cap = maxObjects?.let { " Return at most $it." } ?: ""
        return "detect $joined, output only ```json$cap"
    }

    /**
     * Locate the single best match for a free-form [description] in the
     * image and return one entry only, using the same `box_2d` JSON
     * format as [detect].
     *
     * @throws IllegalArgumentException if [description] is blank or
     *   exceeds [MAX_FREEFORM_LENGTH].
     */
    fun locate(description: String): String {
        val trimmed = description.trim()
        require(trimmed.isNotEmpty()) { "description must not be blank" }
        require(trimmed.length <= MAX_FREEFORM_LENGTH) {
            "description must be <= $MAX_FREEFORM_LENGTH chars (was ${trimmed.length})"
        }
        return "Find: $trimmed. Return exactly one entry, output only " +
            "```json with the shape [{\"box_2d\": [y1, x1, y2, x2], \"label\": \"...\"}]"
    }

    /** Caption prompt at one of the supported [style]s. */
    fun caption(style: CaptionStyle): String = when (style) {
        CaptionStyle.Short ->
            "Write a single short caption (12 words or fewer) for this image. No commentary."
        CaptionStyle.Descriptive ->
            "Write one descriptive paragraph (3 to 4 sentences) that captures the subject, " +
                "setting, and mood of this image."
        CaptionStyle.Hashtags ->
            "Suggest 6 to 10 relevant hashtags for this image, one per line, each prefixed " +
                "with `#`. No commentary."
    }

    /**
     * Describe the image at the requested level of [detail], optionally
     * focusing on a named aspect.
     *
     * @throws IllegalArgumentException if [focus] exceeds
     *   [MAX_FREEFORM_LENGTH].
     */
    fun describe(detail: DescribeDetail = DescribeDetail.Medium, focus: String? = null): String {
        val focusClause = focus
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.also {
                require(it.length <= MAX_FREEFORM_LENGTH) {
                    "focus must be <= $MAX_FREEFORM_LENGTH chars (was ${it.length})"
                }
            }
            ?.let { " Focus on $it." }
            .orEmpty()
        return when (detail) {
            DescribeDetail.Short ->
                "In two or three sentences, describe what is in this image.$focusClause"
            DescribeDetail.Medium ->
                "Describe what is in this image — subject, setting, and notable details — " +
                    "in one paragraph.$focusClause"
            DescribeDetail.Long ->
                "Describe this image in detail across multiple paragraphs: subject, setting, " +
                    "atmosphere, composition, and any visible text or notable objects.$focusClause"
        }
    }

    /**
     * Counting prompt.
     *
     * Per the Gemma vision docs ("Expecting Accurate Counts for Very
     * Dense Objects"), the model gives **approximate** counts for dense
     * scenes — surface that caveat in the response shape so callers
     * don't treat the number as exact.
     *
     * @throws IllegalArgumentException if [itemDescription] is blank or
     *   exceeds [MAX_FREEFORM_LENGTH].
     */
    fun count(itemDescription: String): String {
        val trimmed = itemDescription.trim()
        require(trimmed.isNotEmpty()) { "itemDescription must not be blank" }
        require(trimmed.length <= MAX_FREEFORM_LENGTH) {
            "itemDescription must be <= $MAX_FREEFORM_LENGTH chars (was ${trimmed.length})"
        }
        return "How many $trimmed are visible in this image? " +
            "Reply with a single integer on its own line, then on the next line " +
            "note whether the count is exact or approximate."
    }
}

/** Caption-style discriminator for [VisionPrompts.caption]. */
enum class CaptionStyle {
    /** One sentence, ≤ 12 words. */
    Short,

    /** 3–4 sentence descriptive paragraph. */
    Descriptive,

    /** 6–10 hashtags, one per line. */
    Hashtags,
}

/** Detail level for [VisionPrompts.describe]. */
enum class DescribeDetail {
    /** 2–3 sentences. */
    Short,

    /** One paragraph. */
    Medium,

    /** Multiple paragraphs. */
    Long,
}
