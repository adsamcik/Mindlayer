package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.ImageTransfer

/**
 * F-072: KV-cache budget accounting.
 *
 * The previous code path clamped `effectiveMaxTokens` to the device-tier
 * ceiling **before** the system prompt, [SessionManager.TOOL_SAFETY_PREAMBLE],
 * structured-output schema suffix, and tool definitions were folded into the
 * `ConversationConfig`. On the ≤6 GB tier (2048-token cap) a moderate system
 * prompt + tool preamble + 1700-token user input would silently overflow the
 * native KV cache and either truncate the model output or crash native
 * generation, with no client-visible signal.
 *
 * This module reserves budget for the service-owned overhead at session
 * creation and refuses (a) sessions whose overhead alone meets or exceeds
 * the tier ceiling, and (b) inferences whose `reservedTokens + estimated
 * input tokens` would overflow.
 *
 * # Why these constants are conservative
 *
 * LiteRT-LM does not expose its tokenizer for offline use today. Until an
 * `Engine.estimateTokens(...)` surface lands we approximate token counts
 * with character-density heuristics. The constants below are deliberately
 * pessimistic so the budget check fails closed — a falsely-rejected request
 * is recoverable (caller shortens input or grows `maxTokens`); a falsely-
 * accepted overflow is a native crash.
 *
 * Replace these with a real tokenizer call when one becomes available; the
 * call sites all funnel through the helpers below so the swap is local.
 */

/**
 * Conservative upper bound on bytes-per-token for English natural language
 * mixed with JSON. English averages ~4 chars/token, JSON keys + structure
 * tend toward ~2.5 chars/token, so 3 is a defensible cap that overestimates
 * both. Underestimating would defeat the purpose of the check.
 */
const val CHARS_PER_TOKEN_ESTIMATE: Int = 3

/**
 * Token cost for a single image at Gemma 4 E2B's default vision-encoder
 * resolution (256×256 patches → 256 tokens). Multi-image and high-res
 * inputs are not exercised by the current orchestrator path
 * (litert-lm #1874 still blocks multi-image), so a single fixed cost is
 * sufficient until that issue lifts.
 *
 * Source: Gemma technical report § "Multimodal" — vision encoder produces
 * a fixed 256 soft-tokens per image at default crop.
 */
const val IMAGE_TOKENS_ESTIMATE: Int = 256

/**
 * Token cost per second of audio at Gemma 4's audio frontend default sample
 * rate. The audio encoder produces ~25 tokens/second; we round up via ceil
 * seconds so a 100 ms clip still costs at least one second of budget.
 *
 * Source: Gemma technical report § "Audio" — Universal Speech Model encoder
 * emits one token per 40 ms frame ≈ 25 tokens/second.
 */
const val AUDIO_TOKENS_PER_SECOND_ESTIMATE: Int = 25

/**
 * Fallback duration assumed for [AudioTransfer.durationMs] == null. We can't
 * trust the caller to tag duration honestly (and they can omit it entirely),
 * so the fallback floors a meaningful chunk of audio. 30 s × 25 tok/s = 750
 * tokens — large enough to deter "omit duration to bypass" patterns yet
 * small enough that legitimate short clips with missing metadata still pass.
 */
const val AUDIO_FALLBACK_DURATION_MS: Long = 30_000L

/**
 * Per-turn chat-template overhead — Gemma's `<start_of_turn>user`,
 * `<end_of_turn>`, and the trailing `<start_of_turn>model` for the response
 * frame. Counted on top of the input estimate so an empty-text request
 * still costs at least this many tokens.
 */
const val TURN_OVERHEAD_TOKENS: Int = 4

/**
 * F-072 typed exception. Thrown synchronously by [SessionManager.createSession]
 * when service-owned overhead alone meets or exceeds the session's effective
 * KV ceiling, and by [InferenceOrchestrator.infer] when overhead +
 * estimated input would overflow.
 *
 * The wire message body is built by [wireMessage] and surfaces via
 * [com.adsamcik.mindlayer.sdk.MindlayerException] with code
 * [com.adsamcik.mindlayer.shared.MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT].
 */
class ContextOverflowException(
    val reservedTokens: Int,
    val estimatedInputTokens: Int,
    val effectiveMaxTokens: Int,
) : IllegalStateException(
    buildWireMessage(reservedTokens, estimatedInputTokens, effectiveMaxTokens),
) {
    /**
     * Tokens still available for user input given current overhead.
     * Coerced to ≥0 so the SDK never sees a negative remaining budget
     * (the overhead-only case `reserved >= max` would otherwise produce
     * a negative number).
     */
    val remainingTokens: Int = (effectiveMaxTokens - reservedTokens).coerceAtLeast(0)

    /** Pre-built wire payload — the same string used for `IllegalStateException.message`. */
    val wireMessage: String = message ?: buildWireMessage(
        reservedTokens, estimatedInputTokens, effectiveMaxTokens,
    )

    companion object {
        fun buildWireMessage(
            reservedTokens: Int,
            estimatedInputTokens: Int,
            effectiveMaxTokens: Int,
        ): String {
            val remaining = (effectiveMaxTokens - reservedTokens).coerceAtLeast(0)
            return "input_exceeds_context " +
                "(reserved=$reservedTokens, " +
                "estimated_input=$estimatedInputTokens, " +
                "max=$effectiveMaxTokens, " +
                "remaining=$remaining)"
        }
    }
}

/**
 * Ceiling-divide [charLen] by [CHARS_PER_TOKEN_ESTIMATE]. Returns 0 for
 * non-positive inputs so empty strings cost nothing.
 */
internal fun estimateTokensForChars(charLen: Int): Int {
    if (charLen <= 0) return 0
    return (charLen + CHARS_PER_TOKEN_ESTIMATE - 1) / CHARS_PER_TOKEN_ESTIMATE
}

/**
 * Estimate the number of tokens consumed by audio of [durationMs] ms. When
 * [durationMs] is null we fall back to [AUDIO_FALLBACK_DURATION_MS] so
 * callers cannot bypass the budget check by omitting duration.
 */
internal fun estimateTokensForAudio(durationMs: Long?): Int {
    val ms = durationMs ?: AUDIO_FALLBACK_DURATION_MS
    if (ms <= 0L) return 0
    val seconds = ((ms + 999L) / 1000L).coerceAtLeast(0L).toInt()
    return seconds * AUDIO_TOKENS_PER_SECOND_ESTIMATE
}

/**
 * Aggregate input-token estimate for a single inference turn. Call BEFORE
 * the orchestrator's `Conversation.sendMessageAsync(...)` and combine with
 * [SessionManager.SessionHandle.reservedTokens] to decide whether the
 * request fits the KV-cache budget.
 *
 * Includes [TURN_OVERHEAD_TOKENS] so an empty-text request still costs at
 * least the chat-template envelope.
 */
internal fun estimateInputTokens(
    text: String?,
    image: ImageTransfer?,
    audio: AudioTransfer?,
): Int {
    var total = TURN_OVERHEAD_TOKENS
    total += estimateTokensForChars(text?.length ?: 0)
    if (image != null) total += IMAGE_TOKENS_ESTIMATE
    if (audio != null) total += estimateTokensForAudio(audio.durationMs)
    return total
}
