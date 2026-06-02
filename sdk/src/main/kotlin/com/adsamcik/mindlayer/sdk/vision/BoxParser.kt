package com.adsamcik.mindlayer.sdk.vision

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Parses Gemma 4 vision detection output into typed [DetectedObject]s.
 *
 * Gemma 4 is trained to emit detection results as a JSON array of
 * `{"box_2d": [y1, x1, y2, x2], "label": "..."}` entries with coordinates
 * in a normalized 0..1000 grid. See
 * https://ai.google.dev/gemma/docs/capabilities/vision/image .
 *
 * # Tolerant input handling
 *
 * The output is *usually* wrapped in a ```json markdown fence, but the
 * instruction-tuned model occasionally drops the fence, mixes case, or
 * prepends a sentence of preamble ("Here are the objects: ..."). The
 * parser handles all three shapes:
 *
 * 1. ` ```json ... ``` ` (canonical, per the docs example)
 * 2. ` ``` ... ``` ` (fence without `json` language tag)
 * 3. Unfenced array (first `[` to matching last `]`)
 *
 * # Per-entry tolerance
 *
 * The parser silently skips individual malformed entries (missing fields,
 * out-of-range coords, inverted boxes) rather than failing the whole call,
 * so a detection with nine valid boxes and one malformed one still surfaces
 * the nine. A *top-level* JSON syntax error returns
 * [DetectionResult.ParseError].
 *
 * # Privacy
 *
 * The parser never returns raw model text or label content in error
 * messages — only structural metadata ("invalid JSON: unexpected EOF",
 * "expected JSON array, got object"). This keeps it safe for callers to
 * forward parse failures to logs.
 *
 * # Performance
 *
 * Output is hard-capped at [MAX_OBJECTS_PER_RESPONSE] entries. A
 * pathological response that contained a million entries would still
 * decode the JSON tree fully (kotlinx.serialization parses up-front), but
 * cap the materialized [DetectedObject] list — that's a deliberate
 * tradeoff: the JSON tree itself is bounded by the upstream token-budget
 * caps the service enforces, while the materialized list is what callers
 * iterate. 1024 is well above any realistic detection scene.
 */
internal object BoxParser {

    /** Hard cap on objects materialized from a single response. */
    private const val MAX_OBJECTS_PER_RESPONSE = 1024

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse [rawResponse] into a [DetectionResult]. See class-level KDoc for
     * the three accepted input shapes and the failure-branch semantics.
     */
    fun parse(rawResponse: String): DetectionResult {
        val payload = extractJsonArrayPayload(rawResponse) ?: return DetectionResult.NoStructuredOutput
        val element = try {
            json.parseToJsonElement(payload)
        } catch (e: SerializationException) {
            return DetectionResult.ParseError(
                message = "invalid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}",
            )
        }
        val array = element as? JsonArray
            ?: return DetectionResult.ParseError(message = "expected JSON array, got ${kindOf(element)}")

        val out = ArrayList<DetectedObject>(array.size.coerceAtMost(MAX_OBJECTS_PER_RESPONSE))
        for (entry in array) {
            if (out.size >= MAX_OBJECTS_PER_RESPONSE) break
            val obj = entry as? JsonObject ?: continue
            val detected = parseEntry(obj) ?: continue
            out += detected
        }
        return DetectionResult.Success(out.toList())
    }

    /**
     * Try to find a JSON array in [raw]:
     * 1. Strip ```json ... ``` (or ``` ... ```) fence if present.
     * 2. Otherwise trim outer prose to the first `[` and matching last `]`.
     *
     * Returns `null` when no plausible JSON-array payload is present.
     */
    internal fun extractJsonArrayPayload(raw: String): String? {
        if (raw.isBlank()) return null
        val fencedBody = FENCED_BLOCK.find(raw)?.groupValues?.get(1)?.trim()
        val candidate = fencedBody ?: raw
        val start = candidate.indexOf('[')
        val end = candidate.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1)
    }

    private fun parseEntry(obj: JsonObject): DetectedObject? {
        val labelRaw = (obj["label"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (labelRaw.isEmpty()) return null
        val box2dArr = obj["box_2d"] as? JsonArray ?: return null
        if (box2dArr.size != 4) return null
        val coords = ArrayList<Float>(4)
        for (e in box2dArr) {
            val f = (e as? JsonPrimitive)?.floatOrNull ?: return null
            coords += f
        }
        val box = VisionBoundingBox.fromBox2d(coords) ?: return null
        return DetectedObject(label = labelRaw, box = box)
    }

    private fun kindOf(element: kotlinx.serialization.json.JsonElement): String =
        when (element) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> if (element.isString) "string" else "primitive"
        }

    /**
     * Matches a fenced code block, optionally tagged with `json` (any case)
     * or any other language tag. We capture the body. `[\s\S]` is the
     * portable any-char-including-newline pattern.
     */
    private val FENCED_BLOCK = Regex("""```(?:[a-zA-Z0-9_+-]+)?\s*([\s\S]*?)```""")
}
