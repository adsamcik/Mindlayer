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
        get() = null
        set(_) {}

    /**
     * Opaque JSON object passed to the service as `extraContextJson`.
     * Used for opt-in features such as thinking mode:
     * ```kotlin
     * openSession { extraContextJson = """{"thinking":{"enable":true}}""" }
     * ```
     * Default: `null` (no extra context).
     */
    var extraContextJson: String?
        get() = null
        set(_) {}
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
