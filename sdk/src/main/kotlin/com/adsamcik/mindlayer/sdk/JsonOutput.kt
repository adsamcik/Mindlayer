package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Strategy selector for [JsonOutputBuilder].
 *
 * - [PromptAndValidate] — schema text is appended to the system prompt; the
 *   response is parsed and validated against the schema and retried on
 *   failure. Works alongside multimodal inputs (images/audio).
 * - [ToolRouting] — the model is forced to call a single synthetic tool whose
 *   parameter schema IS the response schema. Slightly stricter output but
 *   cannot be combined with caller-provided tools in the same session.
 */
enum class JsonOutputStrategy(internal val wire: String) {
    PromptAndValidate("prompt_and_validate"),
    ToolRouting("tool_routing"),
}

/**
 * DSL builder for requesting structured JSON output from the model.
 *
 * Produced via [SessionConfigBuilder.jsonOutput]:
 *
 * ```kotlin
 * mindlayer.createSession {
 *     systemPrompt("You extract fields.")
 *     jsonOutput {
 *         schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
 *         strategy(JsonOutputStrategy.PromptAndValidate)
 *         maxRetries(2)
 *     }
 * }
 * ```
 *
 * The builder serialises to the `structured_output` envelope consumed by the
 * Mindlayer service's structured-output engine. If the connected service is
 * older than the structured-output feature, the envelope is ignored and the
 * session behaves as if `jsonOutput` was not called (graceful no-op).
 *
 * At least one of [schema] overloads must be called before the session is
 * built; otherwise [build] throws.
 */
class JsonOutputBuilder internal constructor() {

    private var schemaElement: JsonElement? = null
    private var strategy: JsonOutputStrategy = JsonOutputStrategy.PromptAndValidate
    private var maxRetries: Int = 3

    /**
     * JSON Schema (draft-07 subset) describing the desired response shape.
     * The string must parse as a JSON object (e.g. `{"type":"object", ...}`).
     *
     * @throws IllegalArgumentException if the string is blank or not a JSON object.
     */
    fun schema(json: String) {
        require(json.isNotBlank()) { "schema must not be blank" }
        val parsed = try {
            Json.parseToJsonElement(json)
        } catch (t: Throwable) {
            throw IllegalArgumentException("schema is not valid JSON", t)
        }
        require(parsed is JsonObject) { "schema must be a JSON object" }
        schemaElement = parsed
    }

    /**
     * JSON Schema as an already-parsed [JsonObject]. Useful when building
     * the schema programmatically with `buildJsonObject { ... }`.
     */
    fun schema(obj: JsonObject) {
        schemaElement = obj
    }

    /** Output strategy. Defaults to [JsonOutputStrategy.PromptAndValidate]. */
    fun strategy(s: JsonOutputStrategy) {
        strategy = s
    }

    /**
     * Maximum number of validation-retry cycles for
     * [JsonOutputStrategy.PromptAndValidate]. Ignored by
     * [JsonOutputStrategy.ToolRouting]. Must be >= 0. Default: 3.
     */
    fun maxRetries(n: Int) {
        require(n >= 0) { "maxRetries must be >= 0, got $n" }
        maxRetries = n
    }

    internal fun build(): JsonObject {
        val schema = schemaElement
            ?: throw IllegalStateException(
                "jsonOutput { ... } requires schema(...) to be set",
            )
        return buildJsonObject {
            put(
                "structured_output",
                buildJsonObject {
                    put("schema", schema)
                    put("strategy", JsonPrimitive(strategy.wire))
                    put("max_retries", JsonPrimitive(maxRetries))
                },
            )
        }
    }
}

/**
 * Merge [addition] into [base], letting keys in [addition] win on conflict.
 * If [base] is null/blank/not-an-object, [addition] is returned as-is.
 */
internal fun mergeExtraContext(base: String?, addition: JsonObject): String {
    val baseObj: JsonObject? = if (base.isNullOrBlank()) {
        null
    } else {
        try {
            val parsed = Json.parseToJsonElement(base)
            if (parsed is JsonObject) parsed else null
        } catch (_: Throwable) {
            null
        }
    }
    if (baseObj == null) return Json.encodeToString(JsonObject.serializer(), addition)
    val merged = buildJsonObject {
        baseObj.forEach { (k, v) -> if (k !in addition) put(k, v) }
        addition.forEach { (k, v) -> put(k, v) }
    }
    return Json.encodeToString(JsonObject.serializer(), merged)
}
