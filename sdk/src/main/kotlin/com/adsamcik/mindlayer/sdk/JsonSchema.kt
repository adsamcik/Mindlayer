package com.adsamcik.mindlayer.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A JSON Schema used to constrain model output ([InferenceRequest.Builder.outputJson],
 * [Mindlayer.extractJson]) or to describe tool arguments ([ToolSpec.parametersSchema]).
 *
 * The schema is held as a parsed [JsonObject] so it can be embedded in the
 * AIDL request parcel without a re-parse. Construct it from a literal string
 * with [parse] or wrap an already-built object with the primary constructor.
 */
data class JsonSchema(val json: JsonObject) {
    companion object {
        /** Parse a JSON Schema document from its textual form. */
        fun parse(schemaJson: String): JsonSchema =
            JsonSchema(Json.parseToJsonElement(schemaJson) as JsonObject)
    }
}
