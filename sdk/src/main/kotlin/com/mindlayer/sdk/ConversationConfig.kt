package com.mindlayer.sdk

/**
 * Immutable configuration for a [Conversation].
 *
 * Created via [ConversationBuilder] DSL in [Mindlayer.conversation].
 */
class ConversationConfig internal constructor(
    internal val systemPrompt: String? = null,
    internal val maxTokens: Int = 4096,
    internal val temperature: Float = 0.7f,
    internal val topK: Int = 40,
    internal val topP: Float = 0.95f,
)

/**
 * DSL builder for [ConversationConfig].
 *
 * ```kotlin
 * val conv = mindlayer.conversation {
 *     systemPrompt("You are a coffee expert.")
 *     temperature(0.9f)
 * }
 * ```
 */
class ConversationBuilder {
    private var systemPrompt: String? = null
    private var maxTokens: Int = 4096
    private var temperature: Float = 0.7f
    private var topK: Int = 40
    private var topP: Float = 0.95f

    /** System instruction defining the model's behavior. */
    fun systemPrompt(prompt: String) { systemPrompt = prompt }

    /** Maximum context window in tokens. Default: 4096. */
    fun maxTokens(n: Int) {
        require(n in 128..8192) { "maxTokens must be 128-8192" }
        maxTokens = n
    }

    /** Sampling temperature. Default: 0.7. */
    fun temperature(t: Float) {
        require(t >= 0f) { "temperature must be >= 0" }
        temperature = t
    }

    /** Top-K sampling. Default: 40. */
    fun topK(k: Int) {
        require(k >= 1) { "topK must be >= 1" }
        topK = k
    }

    /** Top-P (nucleus) sampling. Default: 0.95. */
    fun topP(p: Float) {
        require(p > 0f && p <= 1f) { "topP must be in (0.0, 1.0]" }
        topP = p
    }

    internal fun build() = ConversationConfig(systemPrompt, maxTokens, temperature, topK, topP)
}
