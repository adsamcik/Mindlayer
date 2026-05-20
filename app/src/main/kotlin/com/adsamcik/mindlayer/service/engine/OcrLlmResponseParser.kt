package com.adsamcik.mindlayer.service.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Pure-JVM parser for the schema-shaped JSON output emitted by the
 * Gemma OCR extractor.
 *
 * The on-the-wire shape produced by [OcrEvidencePromptBuilder] asks
 * the model to emit:
 *
 * ```json
 * {
 *   "total":           "24.95",
 *   "total_confidence":"high",
 *   "tax":             "1.95",
 *   "tax_confidence":  "medium",
 *   ...
 * }
 * ```
 *
 * This parser:
 *
 *  - Strips markdown fences (`​```json … ​```​`).
 *  - Tolerates leading / trailing whitespace.
 *  - Walks the top-level object and pairs each non-`*_confidence` key
 *    with its `<key>_confidence` sibling. Missing siblings default to
 *    [OcrFieldFusion.Confidence.MEDIUM].
 *  - Stringifies primitive values verbatim (numbers, booleans, strings
 *    all become a single String). Nested objects / arrays are
 *    re-serialised as compact JSON.
 *  - Returns [OcrExtractionResult.EMPTY] on parse failure — never
 *    throws.
 *
 * # Privacy
 *
 * Field values are user content. The parser does not log them.
 * Callers must continue treating extracted values as PII.
 *
 * # Why not [StructuredOutputHelper.validateJsonOutput]?
 *
 * `StructuredOutputHelper` validates a JSON document against a
 * caller-supplied JSON Schema. That is too strict for OCR — the
 * caller's schema describes the **end shape** (final structured
 * extraction), and any single frame's pass-through may be partial.
 * This parser is intentionally permissive: it surfaces whatever
 * key/value pairs the model emits and lets [OcrFieldFusion] do the
 * cross-frame agreement work.
 */
object OcrLlmResponseParser {

    /** Suffix the prompt asks the model to use for verbalized confidence. */
    const val CONFIDENCE_SUFFIX: String = "_confidence"

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse a raw model output into an [OcrExtractionResult].
     *
     * @param rawText the raw text body of the model's response. May
     *   include markdown fences or surrounding prose; the parser
     *   tolerates both and returns [OcrExtractionResult.EMPTY] if the
     *   body has no recognisable JSON object.
     * @return the parsed result. Always non-null. [OcrExtractionResult.rawJson]
     *   holds the fence-stripped JSON text when parsing succeeded.
     */
    fun parse(rawText: String): OcrExtractionResult {
        val stripped = stripMarkdownFences(rawText).trim()
        if (stripped.isEmpty()) return OcrExtractionResult.EMPTY

        val rootCandidate = extractTopLevelJsonObject(stripped) ?: return OcrExtractionResult.EMPTY

        val root = try {
            lenientJson.parseToJsonElement(rootCandidate)
        } catch (_: Throwable) {
            return OcrExtractionResult.EMPTY
        }
        if (root !is JsonObject) return OcrExtractionResult.EMPTY

        val fields = mutableListOf<OcrExtractedField>()
        for ((key, element) in root) {
            if (key.endsWith(CONFIDENCE_SUFFIX)) continue
            if (element is JsonNull) continue

            val confidenceElement = root["$key$CONFIDENCE_SUFFIX"]
            val confidence = parseConfidence(confidenceElement)
            val value = stringifyValue(element) ?: continue

            // Skip blank values so the fusion layer doesn't accumulate
            // empty-string evidence weights.
            if (value.isBlank()) continue

            fields.add(
                OcrExtractedField(
                    name = key,
                    value = value,
                    confidence = confidence,
                ),
            )
        }

        return OcrExtractionResult(fields = fields, rawJson = rootCandidate)
    }

    /**
     * Walk the input looking for the FIRST top-level JSON object —
     * brace-balanced, ignores braces inside strings.
     *
     * Tolerates leading prose like
     * `Here is the JSON: { ... }` because models sometimes ignore the
     * "no prose" instruction. Returns null if no balanced object is
     * found.
     */
    internal fun extractTopLevelJsonObject(input: String): String? {
        val start = input.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until input.length) {
            val c = input[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return input.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun stripMarkdownFences(input: String): String {
        var s = input.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline != -1) {
                s = s.substring(firstNewline + 1)
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length - 3)
            }
            s = s.trim()
        }
        return s
    }

    private fun parseConfidence(element: JsonElement?): OcrFieldFusion.Confidence {
        if (element == null || element is JsonNull) return OcrFieldFusion.Confidence.MEDIUM
        val raw = when (element) {
            is JsonPrimitive -> element.contentOrNull?.lowercase()?.trim()
            else -> null
        } ?: return OcrFieldFusion.Confidence.MEDIUM
        return when (raw) {
            "high", "h" -> OcrFieldFusion.Confidence.HIGH
            "low", "l" -> OcrFieldFusion.Confidence.LOW
            else -> OcrFieldFusion.Confidence.MEDIUM
        }
    }

    private fun stringifyValue(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> when {
            element.booleanOrNull != null -> element.boolean.toString()
            element.longOrNull != null -> element.long.toString()
            element.intOrNull != null -> element.int.toString()
            element.doubleOrNull != null -> element.double.toString()
            else -> element.contentOrNull
        }
        is JsonObject -> element.toString()
        is JsonArray -> element.toString()
        is JsonNull -> null
    }

    private val JsonPrimitive.long: Long get() = checkNotNull(longOrNull) { "expected long" }
    private val JsonPrimitive.int: Int get() = checkNotNull(intOrNull) { "expected int" }
    private val JsonPrimitive.double: Double get() = checkNotNull(doubleOrNull) { "expected double" }
}
