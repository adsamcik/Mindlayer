package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Pure-function helpers for validating and parsing client-supplied
 * [SessionConfig] payloads. Extracted from [SessionManager] so the parsing
 * logic can be unit-tested without an engine/context, and so [SessionManager]
 * focuses on lifecycle rather than payload shape.
 *
 * All members are stateless. Errors that are client-visible (e.g. reserved
 * tool name) are thrown as [IllegalArgumentException]. Errors that are
 * silently tolerated (malformed `extraContextJson`) return defaults — see
 * each method's KDoc for the policy.
 */
internal object SessionConfigValidator {

    private const val TAG = "SessionConfigValidator"

    /**
     * Validate top-level [SessionConfig] byte budgets and structural limits.
     *
     * Delegates to [IpcInputValidator] so the AIDL-boundary path and the
     * in-process path see identical limits. Throws
     * [IllegalArgumentException] for out-of-range values — callers translate
     * this into a [SecurityException] at the AIDL boundary.
     */
    fun validateSessionConfig(config: SessionConfig) {
        IpcInputValidator.validateSessionConfig(config)
    }

    /**
     * Container for both the [ToolProvider] instances and the declared
     * tool-name set. The name set is consumed by `SessionHandle.allowedToolNames`
     * so the orchestrator can drop any tool call the model fabricates outside
     * of this allowlist (F-036).
     */
    data class ParsedTools(
        val providers: List<ToolProvider>,
        val names: Set<String>,
    )

    /**
     * Parse a JSON array of OpenAPI-style tool descriptions into [ToolProvider]
     * instances and capture the declared names.
     *
     * Each element is treated as an independent tool description JSON string.
     * In manual mode (`automaticToolCalling = false`) the `execute()` method
     * is never invoked by the framework — the model emits tool calls that
     * the client handles externally.
     *
     * Throws [IllegalArgumentException] when a tool name uses the reserved
     * `__` prefix (F-066 / L9). Returns `null` for any other parse error so
     * the session is still created without tools (logged as a warning).
     */
    fun parseToolDefinitions(toolsJson: String?): ParsedTools? {
        if (toolsJson.isNullOrBlank()) return null

        return try {
            val array = Json.parseToJsonElement(toolsJson) as JsonArray
            if (array.isEmpty()) return null

            val names = mutableSetOf<String>()
            val providers = array.map { element ->
                val obj = element.jsonObject
                val nameElement = obj["name"]
                val name: String? = if (nameElement is JsonPrimitive && nameElement.isString) {
                    nameElement.content
                } else {
                    null
                }
                val reservedPrefix = IpcInputValidator.RESERVED_TOOL_PREFIX
                require(name == null || !name.startsWith(reservedPrefix)) {
                    "tool name '$name' uses reserved prefix"
                }
                if (name != null) names.add(name)
                val description = obj.toString()
                tool(JsonDefinedTool(description))
            }
            MindlayerLog.d(TAG, "Parsed ${providers.size} tool definition(s)")
            ParsedTools(providers = providers, names = names)
        } catch (e: IllegalArgumentException) {
            // L9: reserved-name rejections must surface to the client so the
            // session is rejected, not silently created without tools.
            throw e
        } catch (t: Throwable) {
            MindlayerLog.e(TAG, "Failed to parse toolsJson: ${t.safeLabel()}")
            null
        }
    }

    /**
     * v0.5: parse the `extraContextJson.token_batch` opt-in flag.
     * **Fail-open**: any parse error / non-object / missing key returns
     * `false` so existing callers with malformed `extraContextJson` don't
     * regress.
     */
    fun parseTokenBatchOptIn(extraContextJson: String?): Boolean {
        if (extraContextJson.isNullOrBlank()) return false
        return try {
            val element = Json.parseToJsonElement(extraContextJson)
            val obj = element as? JsonObject ?: return false
            val flag = obj["token_batch"] as? JsonPrimitive ?: return false
            flag.booleanOrNull == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * v1.1: parse the `extraContextJson.thinking.enable` opt-in flag for
     * Gemma 4 thinking mode. Accepts the canonical nested form:
     *
     * ```json
     * { "thinking": { "enable": true } }
     * ```
     *
     * The bare-boolean shorthand `{ "thinking": true }` is also honoured
     * for caller convenience (mirrors how `token_batch` is wired).
     *
     * **Fail-open**: any parse error / non-object / missing key returns
     * `false` so existing callers with malformed extraContextJson don't
     * regress (matches [parseTokenBatchOptIn]).
     */
    fun parseThinkingOptIn(extraContextJson: String?): Boolean {
        if (extraContextJson.isNullOrBlank()) return false
        return try {
            val element = Json.parseToJsonElement(extraContextJson)
            val obj = element as? JsonObject ?: return false
            val node = obj["thinking"] ?: return false
            when (node) {
                is JsonPrimitive -> node.booleanOrNull == true
                is JsonObject -> {
                    val flag = node["enable"] as? JsonPrimitive ?: return false
                    flag.booleanOrNull == true
                }
                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Lightweight [OpenApiTool] backed by a pre-serialised JSON description.
     * [execute] is never called in manual mode — the model's tool-call output
     * is forwarded to the client, which returns results via AIDL.
     */
    private class JsonDefinedTool(
        private val descriptionJson: String,
    ) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptionJson
        override fun execute(paramsJsonString: String): String =
            error("Manual tool mode — execute() should not be called by the framework")
    }
}
