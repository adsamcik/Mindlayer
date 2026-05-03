package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides structured JSON output via two strategies:
 *
 * **TOOL_ROUTING** — routes through a synthetic tool call whose parameter
 * schema IS the desired output schema.  The model calls [TOOL_NAME] with
 * conforming arguments; the orchestrator extracts those arguments as the
 * response text.
 *
 * **PROMPT_AND_VALIDATE** — injects the JSON schema into the system prompt,
 * buffers the response, validates it, and retries up to N times if invalid.
 *
 * Clients specify strategy via `SessionConfig.extraContextJson`:
 * ```json
 * {
 *   "structured_output": {
 *     "schema": { "type": "object", "properties": { ... }, "required": [...] },
 *     "strategy": "tool_routing",
 *     "max_retries": 3
 *   }
 * }
 * ```
 */
object StructuredOutputHelper {

    private const val TAG = "StructuredOutputHelper"

    /** Synthetic tool name used by the TOOL_ROUTING strategy. */
    const val TOOL_NAME = "__structured_output"

    // ---- Config parsing -------------------------------------------------------

    /**
     * Parse the structured output configuration from the session's
     * `extraContextJson`, or return `null` if absent / malformed.
     */
    fun parseConfig(extraContextJson: String?): StructuredOutputConfig? {
        if (extraContextJson.isNullOrBlank()) return null
        return try {
            val root = Json.parseToJsonElement(extraContextJson).jsonObject
            val so = root["structured_output"]?.jsonObject ?: return null

            val schema = so["schema"]?.jsonObject ?: run {
                MindlayerLog.w(TAG, "structured_output missing 'schema'")
                return null
            }
            val strategyStr = so["strategy"]?.jsonPrimitive?.contentOrNull
                ?: "prompt_and_validate"
            val strategy = when (strategyStr.lowercase()) {
                "tool_routing" -> StructuredOutputStrategy.TOOL_ROUTING
                else -> StructuredOutputStrategy.PROMPT_AND_VALIDATE
            }
            val maxRetries = (so["max_retries"]?.jsonPrimitive?.intOrNull ?: 3)
                // F-034: cap retries so an unmatchable schema cannot run
                // an unbounded number of full inference round-trips. 5 is
                // the practical limit before we admit defeat.
                .coerceIn(0, 5)

            // v0.5: validation_depth. Wire values: "shallow" (default) or
            // "none" (caller opts out of server validation). Unknown
            // values fall back to "shallow" for backward compat.
            val depthStr = so["validation_depth"]?.jsonPrimitive?.contentOrNull
            val serverValidate = depthStr?.lowercase() != "none"

            StructuredOutputConfig(
                schema = schema,
                strategy = strategy,
                maxRetries = maxRetries,
                serverValidate = serverValidate,
            )
        } catch (t: Throwable) {
            MindlayerLog.e(TAG, "Failed to parse structured_output config: ${t.safeLabel()}")
            null
        }
    }

    // ---- TOOL_ROUTING helpers ------------------------------------------------

    /**
     * Build a synthetic [ToolProvider] whose parameters match the desired
     * output schema.  When `automaticToolCalling = false`, the model emits a
     * tool call whose arguments are the structured response.
     */
    fun buildSchemaToolDefinition(config: StructuredOutputConfig): ToolProvider {
        return tool(SchemaRoutingTool(buildSchemaToolJson(config)))
    }

    /**
     * F-072: serialize the same JSON description that
     * [buildSchemaToolDefinition] hands to LiteRT-LM, so [SessionManager]
     * can budget the token cost of the synthetic tool against the KV-cache
     * ceiling. Internal: the surface is the `ToolProvider`; only token
     * accounting needs the raw bytes.
     */
    internal fun buildSchemaToolJson(config: StructuredOutputConfig): String {
        return buildJsonObject {
            put("name", TOOL_NAME)
            put(
                "description",
                "Emit the structured response matching the required schema. " +
                    "Call this tool with the complete response object as arguments.",
            )
            put("parameters", config.schema)
        }.toString()
    }

    /**
     * Extract the structured result from a list of `(toolName, argsJson)`
     * pairs.  Returns the arguments JSON of the first [TOOL_NAME] call,
     * or `null` if none found.
     */
    fun extractStructuredResult(
        toolCalls: List<Pair<String, String>>,
    ): String? = toolCalls.firstOrNull { it.first == TOOL_NAME }?.second

    // ---- PROMPT_AND_VALIDATE helpers ----------------------------------------

    /**
     * Returns instruction text to append to the system prompt requiring
     * JSON output conforming to [config]'s schema.
     */
    fun buildSchemaPromptSuffix(config: StructuredOutputConfig): String = buildString {
        appendLine()
        appendLine("You MUST respond with valid JSON matching this schema:")
        appendLine(config.schema.toString())
        appendLine("Output ONLY the raw JSON object. Do NOT wrap it in markdown code fences.")
        appendLine("Do NOT include any text before or after the JSON.")
    }

    /**
     * Build a follow-up prompt used when a previous response failed
     * validation, asking the model to correct its output.
     */
    fun buildRetryPrompt(errors: List<String>, schema: JsonObject): String = buildString {
        appendLine("Your previous response was not valid JSON conforming to the schema.")
        appendLine("Errors:")
        for (error in errors) {
            appendLine("  - $error")
        }
        appendLine()
        appendLine("Please respond again with ONLY valid JSON matching this schema:")
        appendLine(schema.toString())
    }

    // ---- Validation ----------------------------------------------------------

    /**
     * Validate that [output] conforms to [schema].
     *
     * F-037: A pure-Kotlin subset JSON-Schema validator (draft-07-ish)
     * implemented inline as [SubsetSchemaValidator]. Covered keywords:
     * `type`, `required`, `enum`, `pattern`, `minLength`, `maxLength`,
     * `minimum`, `maximum`, `properties`, `additionalProperties`
     * (boolean OR schema), and `items` (single schema).
     *
     * Returns [ValidationResult.Valid] with the cleaned JSON (markdown
     * fences stripped) or [ValidationResult.Invalid] with up to 20
     * error messages.
     */
    fun validateJsonOutput(output: String, schema: JsonObject): ValidationResult {
        val cleaned = stripMarkdownFences(output.trim())
        val parsed = try {
            Json.parseToJsonElement(cleaned)
        } catch (_: Throwable) {
            return ValidationResult.Invalid(listOf("Not valid JSON"))
        }
        return when (val r = SubsetSchemaValidator.validate(parsed, schema)) {
            is ValidationResult.Valid -> ValidationResult.Valid(cleaned)
            is ValidationResult.Invalid -> ValidationResult.Invalid(r.errors.take(20))
        }
    }

    // ---- Internals -----------------------------------------------------------

    private fun stripMarkdownFences(input: String): String {
        var s = input
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

    /**
     * [OpenApiTool] that routes structured output through a synthetic tool
     * call.  [execute] is never invoked — the orchestrator intercepts the
     * tool call arguments.
     */
    private class SchemaRoutingTool(
        private val descriptionJson: String,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptionJson
        override fun execute(paramsJsonString: String): String =
            error(
                "SchemaRoutingTool.execute() should not be called — " +
                    "structured output is extracted from tool call arguments",
            )
    }

    // ---- F-037: native subset JSON-Schema validator -------------------------

    /**
     * Pure-Kotlin subset validator for JSON Schema. Replaces the previous
     * shallow top-level checker. We deliberately avoid the
     * `com.networknt:json-schema-validator` dep (the audit's first
     * recommendation) because its Jackson drag-in (~1.6 MB) and reflective
     * surface complicate R8 — given how narrow our schema needs are, a
     * native ~250 LoC implementation is preferable.
     *
     * Supported keywords:
     * - `type` — single string. One of: object, array, string, number,
     *   integer, boolean, null.
     * - `required` — array of property names that must be present (object).
     * - `enum` — array of valid values. Element equality is structural.
     * - `pattern` — regex (Java syntax). Compiled regexes are cached.
     * - `minLength`, `maxLength` — string length bounds.
     * - `minimum`, `maximum` — numeric bounds (inclusive).
     * - `properties` — per-key sub-schema; recursively validated.
     * - `additionalProperties` — boolean (allow/forbid) or schema (every
     *   non-`properties` key validates against the schema).
     * - `items` — single sub-schema applied to every array element.
     *
     * Unsupported by design: `oneOf`, `anyOf`, `allOf`, `format`, `$ref`,
     * `not`, `if/then/else`, `dependencies`. These return Valid with no
     * error but no enforcement either — explicitly out of scope until the
     * audit's "draft-07 strict" requirement extends.
     */
    internal object SubsetSchemaValidator {

        /**
         * Cap on the regex compile cache. Schemas are typically O(sessions),
         * so 256 entries comfortably covers a long-lived service. On
         * overflow we evict half the cache (cheap, no LRU machinery).
         */
        private const val PATTERN_CACHE_CAP = 256
        private const val MAX_SAFE_PATTERN_LEN = 256

        private val patternCache = ConcurrentHashMap<String, Regex>(PATTERN_CACHE_CAP)
        private val backreferencePattern = Regex("""\\[1-9]""")
        private val lookaroundPattern = Regex("""\(\?([=!]|<[=!])""")
        private val nestedQuantifierPattern =
            Regex("""\((?:[^()\\]|\\.)*(?:[+*]|\{\d+(?:,\d*)?})(?:[^()\\]|\\.)*\)(?:[+*]|\{\d+(?:,\d*)?})""")

        /**
         * Validate [json] against [schema]. Returns [ValidationResult.Valid]
         * (with [json] echoed) on success or [ValidationResult.Invalid] with
         * a list of error strings.
         */
        fun validate(json: JsonElement, schema: JsonObject): ValidationResult {
            val errors = mutableListOf<String>()
            check(json, schema, path = "$", errors = errors)
            return if (errors.isEmpty()) {
                ValidationResult.Valid(json.toString())
            } else {
                ValidationResult.Invalid(errors)
            }
        }

        private fun check(
            value: JsonElement,
            schema: JsonObject,
            path: String,
            errors: MutableList<String>,
        ) {
            // ── type ─────────────────────────────────────────────────────
            val typeStr = schema["type"]?.let {
                if (it is JsonPrimitive && it.isString) it.content else null
            }
            if (typeStr != null && !matchesType(value, typeStr)) {
                errors.add("$path: expected $typeStr, got ${describe(value)}")
                // Fall through anyway so other keywords can still emit a
                // hint; but skip type-specific checks below.
            }

            // ── enum ─────────────────────────────────────────────────────
            val enumElement = schema["enum"]
            if (enumElement is JsonArray) {
                if (enumElement.none { it == value }) {
                    errors.add("$path: value not in enum")
                }
            }

            when (value) {
                is JsonObject -> checkObject(value, schema, path, errors)
                is JsonArray -> checkArray(value, schema, path, errors)
                is JsonPrimitive -> checkPrimitive(value, schema, path, errors)
                JsonNull -> { /* type already validated */ }
            }
        }

        private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            // integer must be a whole number; both `42` and `42.0` accepted
            // if they round-trip exactly.
            "integer" -> value is JsonPrimitive && !value.isString && isInteger(value)
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "null" -> value is JsonNull
            else -> true // unknown type — don't reject
        }

        private fun isInteger(p: JsonPrimitive): Boolean {
            // Prefer longOrNull; fall back to doubleOrNull for `1.0` style.
            if (p.longOrNull != null) return true
            val d = p.doubleOrNull ?: return false
            return d.isFinite() && d == kotlin.math.floor(d)
        }

        private fun describe(v: JsonElement): String = when (v) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            JsonNull -> "null"
            is JsonPrimitive -> when {
                v.isString -> "string"
                v.booleanOrNull != null -> "boolean"
                v.longOrNull != null -> "integer"
                v.doubleOrNull != null -> "number"
                else -> "primitive"
            }
        }

        private fun checkObject(
            value: JsonObject,
            schema: JsonObject,
            path: String,
            errors: MutableList<String>,
        ) {
            // ── required ────────────────────────────────────────────────
            val required = schema["required"]
            if (required is JsonArray) {
                for (rField in required) {
                    val name = (rField as? JsonPrimitive)?.contentOrNull ?: continue
                    if (name !in value) {
                        errors.add("$path.$name: required field missing")
                    }
                }
            }

            // ── properties ──────────────────────────────────────────────
            val properties = schema["properties"] as? JsonObject
            if (properties != null) {
                for ((key, propSchema) in properties) {
                    val child = value[key] ?: continue
                    val childSchema = propSchema as? JsonObject ?: continue
                    check(child, childSchema, "$path.$key", errors)
                }
            }

            // ── additionalProperties ────────────────────────────────────
            val ap = schema["additionalProperties"]
            if (ap != null) {
                val knownKeys = properties?.keys ?: emptySet()
                when (ap) {
                    is JsonPrimitive -> {
                        if (ap.booleanOrNull == false) {
                            for (k in value.keys) {
                                if (k !in knownKeys) {
                                    errors.add("$path.$k: additional property not allowed")
                                }
                            }
                        }
                        // true (or non-bool primitive) = allow everything; no-op
                    }
                    is JsonObject -> {
                        for ((k, child) in value) {
                            if (k !in knownKeys) {
                                check(child, ap, "$path.$k", errors)
                            }
                        }
                    }
                    else -> { /* invalid schema shape — ignore */ }
                }
            }
        }

        private fun checkArray(
            value: JsonArray,
            schema: JsonObject,
            path: String,
            errors: MutableList<String>,
        ) {
            val items = schema["items"] as? JsonObject ?: return
            value.forEachIndexed { idx, element ->
                check(element, items, "$path[$idx]", errors)
            }
        }

        private fun checkPrimitive(
            value: JsonPrimitive,
            schema: JsonObject,
            path: String,
            errors: MutableList<String>,
        ) {
            // String constraints
            if (value.isString) {
                val s = value.content
                schema["minLength"]?.let { el ->
                    val min = (el as? JsonPrimitive)?.intOrNull
                    if (min != null && s.length < min) {
                        errors.add("$path: length ${s.length} < minLength $min")
                    }
                }
                schema["maxLength"]?.let { el ->
                    val max = (el as? JsonPrimitive)?.intOrNull
                    if (max != null && s.length > max) {
                        errors.add("$path: length ${s.length} > maxLength $max")
                    }
                }
                schema["pattern"]?.let { el ->
                    val pat = (el as? JsonPrimitive)?.contentOrNull
                    if (pat != null) {
                        val regex = compilePattern(pat)
                        if (regex == null) {
                            errors.add("$path: unsupported pattern")
                        } else if (!regex.containsMatchIn(s)) {
                            errors.add("$path: does not match pattern")
                        }
                    }
                }
            }

            // Numeric constraints
            val asDouble = if (!value.isString) value.doubleOrNull else null
            if (asDouble != null) {
                schema["minimum"]?.let { el ->
                    val min = (el as? JsonPrimitive)?.doubleOrNull
                    if (min != null && asDouble < min) {
                        errors.add("$path: $asDouble < minimum $min")
                    }
                }
                schema["maximum"]?.let { el ->
                    val max = (el as? JsonPrimitive)?.doubleOrNull
                    if (max != null && asDouble > max) {
                        errors.add("$path: $asDouble > maximum $max")
                    }
                }
            }
        }

        private fun compilePattern(pattern: String): Regex? {
            patternCache[pattern]?.let { return it }
            if (!isSafePattern(pattern)) {
                return null
            }
            val r = try {
                Regex(pattern)
            } catch (_: Throwable) {
                return null
            }
            if (patternCache.size >= PATTERN_CACHE_CAP) {
                // Cheap eviction: clear half the cache rather than LRU
                // bookkeeping. Schemas are stable per session so the
                // refill cost is bounded.
                val drop = patternCache.keys.take(patternCache.size / 2)
                drop.forEach { patternCache.remove(it) }
            }
            patternCache[pattern] = r
            return r
        }

        private fun isSafePattern(pattern: String): Boolean {
            if (pattern.length > MAX_SAFE_PATTERN_LEN) return false
            if (backreferencePattern.containsMatchIn(pattern)) return false
            if (lookaroundPattern.containsMatchIn(pattern)) return false
            if (nestedQuantifierPattern.containsMatchIn(pattern)) return false
            return true
        }

        /** Visible for testing: lets tests assert cache state. */
        internal fun cacheSizeForTest(): Int = patternCache.size

        /** Visible for testing: tests reset between cases. */
        internal fun resetCacheForTest() {
            patternCache.clear()
        }
    }
}

// ---- Public data types -------------------------------------------------------

enum class StructuredOutputStrategy {
    TOOL_ROUTING,
    PROMPT_AND_VALIDATE,
}

data class StructuredOutputConfig(
    val schema: JsonObject,
    val strategy: StructuredOutputStrategy,
    val maxRetries: Int = 3,
    /**
     * v0.5: server-side validation depth. When `false`, the orchestrator
     * skips server-side schema checks entirely and emits whatever the model
     * produced — the SDK opted out via `JsonValidationDepth.NONE` /
     * `CALLER_VALIDATES`. Defaults to `true` for backward compat.
     */
    val serverValidate: Boolean = true,
)

sealed class ValidationResult {
    data class Valid(val json: String) : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}
