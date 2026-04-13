package com.mindlayer.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

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
    internal val expiration: Duration = 14.days,
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
    private var expiration: Duration = 14.days

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

    /**
     * Set conversation expiration duration. The conversation's session and
     * history will be automatically cleaned up after this duration of inactivity.
     * Default: 14 days.
     *
     * @param duration Expiration duration. Must be positive.
     */
    fun expiration(duration: Duration) {
        require(duration.isPositive()) { "expiration must be positive" }
        expiration = duration
    }

    /** Convenience: set expiration in days. */
    fun expirationDays(days: Int) {
        require(days > 0) { "expirationDays must be > 0" }
        expiration = days.days
    }

    internal fun build() = ConversationConfig(systemPrompt, maxTokens, temperature, topK, topP, expiration)
}
