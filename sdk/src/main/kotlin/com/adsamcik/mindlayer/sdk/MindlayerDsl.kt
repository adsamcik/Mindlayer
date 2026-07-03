package com.adsamcik.mindlayer.sdk

/**
 * DSL marker for the Mindlayer builder/scope hierarchy.
 *
 * Applying `@MindlayerDsl` to every builder and scope type stops an inner
 * builder from implicitly seeing the receiver of an enclosing builder — e.g.
 * it prevents `withSession { infer { ... } }` from leaking the outer
 * [SessionScope] members into the inner [InferenceRequest.Builder].
 */
@DslMarker
annotation class MindlayerDsl

/**
 * Mutable per-call session configuration applied to an ephemeral or named
 * session before an inference request runs.
 *
 * Behavioural wiring lands in C2; in C1 this is a public skeleton.
 */
@MindlayerDsl
interface SessionScope {
    var systemPrompt: String?
    var maxTokens: Int?
    var historyPolicy: HistoryPolicy

    /**
     * Raw JSON array of tool definitions for a tool-enabled session.
     * Mirrors [SessionConfigBuilder.tools] (the raw-JSON overload).
     * Callers serialize their tool specs to the engine's tools JSON
     * and set this field; it is passed through to [SessionConfigBuilder.toolsJsonRaw]
     * at session creation time.
     *
     * Default: `null` (no tools configured).
     */
    var toolsJson: String?

    /**
     * Opaque JSON object passed to the service as `extraContextJson`.
     * Used for opt-in features such as thinking mode:
     * ```kotlin
     * openSession { extraContextJson = """{"thinking":{"enable":true}}""" }
     * ```
     * Prefer the typed [jsonOutput] helper over hand-building a
     * `structured_output` envelope string here.
     *
     * Default: `null` (no extra context).
     */
    var extraContextJson: String?

    /**
     * Request schema-constrained structured JSON output for this session.
     *
     * Configure the [JsonOutputBuilder] and this merges the resulting
     * `{"structured_output":{schema,strategy,max_retries,validation_depth}}`
     * envelope into [extraContextJson] — the exact wire contract the Mindlayer
     * service's structured-output engine consumes (single-sourced through
     * [JsonOutputBuilder.build], the same one the internal
     * [SessionConfigBuilder.jsonOutput] emits). Other keys already present on
     * [extraContextJson] are preserved; only a `structured_output` key is
     * replaced. This lets apps enum-constrain a session without hand-building
     * the envelope string.
     *
     * ```kotlin
     * mindlayer.openSession {
     *     systemPrompt = "You classify support tickets."
     *     jsonOutput {
     *         schema("""{"type":"object","properties":{"severity":{"enum":["low","high"]}},"required":["severity"]}""")
     *         strategy(JsonOutputStrategy.PromptAndValidate)
     *         maxRetries(3)
     *         validationDepth(JsonValidationDepth.SHALLOW)
     *     }
     * }
     * ```
     *
     * Works the same inside `infer { ephemeralSession { jsonOutput { … } } }`.
     * If the connected service predates structured output the envelope is a
     * graceful no-op. For security-critical schemas, re-validate the response
     * locally — see [JsonValidationDepth].
     */
    fun jsonOutput(block: JsonOutputBuilder.() -> Unit) {
        val envelope = JsonOutputBuilder().apply(block).build()
        extraContextJson = mergeExtraContext(extraContextJson, envelope)
    }
}

/**
 * Mutable sampler configuration for an inference request.
 *
 * Behavioural wiring lands in C2; in C1 this is a public skeleton.
 */
@MindlayerDsl
interface SamplerScope {
    var topK: Int?
    var topP: Float?
    var temperature: Float?
    var seed: Int?
}
