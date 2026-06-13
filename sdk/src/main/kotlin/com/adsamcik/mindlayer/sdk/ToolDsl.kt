package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.3 typed builder DSL for tool (function-calling) definitions, parallel
 * to [JsonOutputBuilder].
 *
 * Replaces the opaque `tools(json: String)` escape hatch on
 * [SessionConfigBuilder] with a structured builder so the wire-format
 * shape is checked at construction time, not at session-create time:
 *
 * ```kotlin
 * mindlayer.openSession {
 *     systemPrompt = "You have access to tools"
 *     toolsJson = """[{"name":"get_weather","description":"Get current weather for a city","parameters":{"type":"object","required":["city"],"properties":{"city":{"type":"string"}}}}]"""
 * }
 * // or using the DSL builder (via SessionConfigBuilder):
 * val tools = ToolsBuilder().apply {
 *     tool("get_weather") {
 *         description("Get current weather for a city")
 *         parameters("""{
 *             "type": "object",
 *             "required": ["city"],
 *             "properties": { "city": {"type": "string"} }
 *         }""")
 *     }
 * }.buildJson()
 * mindlayer.openSession { toolsJson = tools }
 * ```
 *
 * Validation at builder time:
 * - Tool names must not be blank, must be ≤ 128 chars, and must not start
 *   with the reserved `__` prefix (which the service uses internally for
 *   the structured-output synthetic tool).
 * - Tools list must not exceed 64 entries (mirrors
 *   `IpcInputValidator.MAX_HISTORY_TURNS`-style limits).
 * - Each tool's `parameters` schema must parse as a JSON object.
 *
 * The wire envelope is identical to what `tools(json: String)` produces:
 * a JSON array of `{name, description?, parameters}` objects. The legacy
 * escape hatch stays for callers building tool catalogs from existing
 * registries.
 */
class ToolsBuilder internal constructor() {

    private val tools = mutableListOf<JsonObject>()

    /**
     * Register a tool with the given [name] and an optional configuration
     * block. Multiple [tool] calls accumulate.
     */
    fun tool(name: String, configure: ToolBuilder.() -> Unit = {}) {
        validateName(name)
        require(tools.size < MAX_TOOLS) {
            "too many tools (max $MAX_TOOLS)"
        }
        val builder = ToolBuilder(name).apply(configure)
        tools += builder.build()
    }

    internal fun build(): String =
        Json.encodeToString(JsonArray.serializer(), JsonArray(tools))

    private fun validateName(name: String) {
        require(name.isNotBlank()) { "tool name must not be blank" }
        require(name.length <= MAX_TOOL_NAME_LEN) {
            "tool name too long: ${name.length} > $MAX_TOOL_NAME_LEN"
        }
        require(!name.startsWith(RESERVED_PREFIX)) {
            "tool name '$name' uses reserved prefix '$RESERVED_PREFIX'"
        }
        require(tools.none { it["name"]?.let { v -> (v as? JsonPrimitive)?.content == name } == true }) {
            "duplicate tool name: $name"
        }
    }

    companion object {
        /** Max tool names per session. Matches the documented service limit. */
        const val MAX_TOOLS: Int = 64

        /** Max bytes in a tool name. Mirrors `IpcInputValidator.MAX_TOOL_NAME_LEN`. */
        const val MAX_TOOL_NAME_LEN: Int = 128

        /**
         * Reserved tool-name prefix. The service uses this for the
         * structured-output synthetic tool (`__schema_router`) and rejects
         * any caller-supplied tool that starts with it.
         */
        const val RESERVED_PREFIX: String = "__"
    }
}

/**
 * DSL for one tool definition. Used inside [ToolsBuilder.tool].
 */
class ToolBuilder internal constructor(private val name: String) {

    private var description: String? = null
    private var parametersSchema: JsonObject? = null

    /** Optional human-readable description of what the tool does. */
    fun description(text: String) {
        description = text
    }

    /**
     * JSON-Schema (draft-07 subset) for the tool's parameters. The string
     * must parse as a JSON object — typically a `{"type": "object",
     * "required": [...], "properties": {...}}` shape.
     */
    fun parameters(json: String) {
        require(json.isNotBlank()) { "tool '$name' parameters must not be blank" }
        val parsed = try {
            Json.parseToJsonElement(json)
        } catch (t: Throwable) {
            throw IllegalArgumentException(
                "tool '$name' parameters is not valid JSON",
                t,
            )
        }
        require(parsed is JsonObject) {
            "tool '$name' parameters must be a JSON object"
        }
        parametersSchema = parsed
    }

    /** Same as [parameters] but takes an already-parsed [JsonObject]. */
    fun parameters(obj: JsonObject) {
        parametersSchema = obj
    }

    internal fun build(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        description?.let { put("description", JsonPrimitive(it)) }
        // Parameters is required for typical tool-calling envelopes; if
        // the caller forgot, emit an empty schema rather than failing.
        // Server-side validation will catch a truly malformed schema.
        put(
            "parameters",
            parametersSchema ?: buildJsonObject { put("type", JsonPrimitive("object")) },
        )
    }
}
