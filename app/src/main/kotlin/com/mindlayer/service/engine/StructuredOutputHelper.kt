package com.mindlayer.service.engine

import android.util.Log
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
                Log.w(TAG, "structured_output missing 'schema'")
                return null
            }
            val strategyStr = so["strategy"]?.jsonPrimitive?.contentOrNull
                ?: "prompt_and_validate"
            val strategy = when (strategyStr.lowercase()) {
                "tool_routing" -> StructuredOutputStrategy.TOOL_ROUTING
                else -> StructuredOutputStrategy.PROMPT_AND_VALIDATE
            }
            val maxRetries = so["max_retries"]?.jsonPrimitive?.intOrNull ?: 3

            StructuredOutputConfig(
                schema = schema,
                strategy = strategy,
                maxRetries = maxRetries,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse structured_output config", t)
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
        val toolJson = buildJsonObject {
            put("name", TOOL_NAME)
            put(
                "description",
                "Emit the structured response matching the required schema. " +
                    "Call this tool with the complete response object as arguments.",
            )
            put("parameters", config.schema)
        }
        return tool(SchemaRoutingTool(toolJson.toString()))
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
     * Validate that [output] is well-formed JSON conforming to the basic
     * structure of [schema].  Checks:
     *  - Parseable as JSON
     *  - Required fields present (`"required"` array in schema)
     *  - Top-level property types (string, number, integer, boolean, array,
     *    object)
     *
     * Returns [ValidationResult.Valid] with the cleaned JSON (markdown fences
     * stripped) or [ValidationResult.Invalid] with error descriptions.
     */
    fun validateJsonOutput(output: String, schema: JsonObject): ValidationResult {
        val cleaned = stripMarkdownFences(output.trim())

        val parsed = try {
            Json.parseToJsonElement(cleaned)
        } catch (t: Throwable) {
            return ValidationResult.Invalid(listOf("Not valid JSON: ${t.message}"))
        }

        val errors = mutableListOf<String>()

        val schemaType = schema["type"]?.jsonPrimitive?.contentOrNull
        if (schemaType == "object" && parsed !is JsonObject) {
            errors.add("Expected JSON object but got ${parsed::class.simpleName}")
            return ValidationResult.Invalid(errors)
        }

        if (parsed is JsonObject) {
            // Check required fields
            val required = schema["required"]
            if (required is JsonArray) {
                for (field in required) {
                    val name = field.jsonPrimitive.content
                    if (name !in parsed) {
                        errors.add("Missing required field: $name")
                    }
                }
            }
            // Check top-level property types
            val properties = schema["properties"]?.jsonObject
            if (properties != null) {
                for ((key, propSchema) in properties) {
                    val value = parsed[key] ?: continue
                    val expectedType =
                        propSchema.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: continue
                    checkType(key, value, expectedType)?.let { errors.add(it) }
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid(cleaned)
        } else {
            ValidationResult.Invalid(errors)
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

    private fun checkType(
        key: String,
        value: JsonElement,
        expectedType: String,
    ): String? = when (expectedType) {
        "string" -> if (value !is JsonPrimitive || !value.isString) {
            "Field '$key': expected string"
        } else null

        "number" -> if (value !is JsonPrimitive || value.doubleOrNull == null) {
            "Field '$key': expected number"
        } else null

        "integer" -> if (value !is JsonPrimitive || value.longOrNull == null) {
            "Field '$key': expected integer"
        } else null

        "boolean" -> if (value !is JsonPrimitive || value.booleanOrNull == null) {
            "Field '$key': expected boolean"
        } else null

        "array" -> if (value !is JsonArray) {
            "Field '$key': expected array"
        } else null

        "object" -> if (value !is JsonObject) {
            "Field '$key': expected object"
        } else null

        else -> null
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
)

sealed class ValidationResult {
    data class Valid(val json: String) : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}
